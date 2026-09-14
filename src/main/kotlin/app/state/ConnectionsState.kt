package app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.settings.RedisPrefs
import app.settings.RedisUiState
import db.ColumnCache
import db.ConnectionProfile
import db.MetaCache
import engine.DataSourceSession
import engine.model.ColumnMeta
import engine.model.ObjectKind
import engine.model.ObjectSearch
import engine.model.SchemaMeta
import engine.model.SchemaObjects
import engine.model.SQL_OBJECT_KINDS
import jdbc.LiveConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tinylog.Logger
import tree.ConnUiStatus
import tree.ConnectionRuntimeView
import java.sql.SQLException

/** 把异常压成一行可读短消息（供状态栏/弹层展示）。 */
internal fun friendlySqlError(t: Throwable?): String {
    val cause = t ?: return "未知错误"
    val root = generateSequence(cause) { it.cause }.last()
    return when (root) {
        is SQLException -> {
            val detail = root.message ?: "数据库错误"
            val short = detail.lineSequence().first().take(180)
            if (root.errorCode != 0) "错误码 ${root.errorCode}: $short" else short
        }
        else -> (root.message ?: root.javaClass.simpleName).take(180)
    }
}

/**
 * 连接运行时状态：profileId -> ConnRuntime。
 * 每条连接一个 `DataSourceSession`（按协议由 `SessionFactory` 创建：JDBC = `LiveConnection`
 * 单线程串行，Redis = `RedisSession`），所有探测/执行经 Dispatchers.IO。
 * UI 可观察状态全部是 compose snapshot（status/schemas/objects…），
 * 方法为 suspend，由 UI 协程调用（内部切 IO 后再写回 snapshot 状态）。
 *
 * 目录元数据（库列表 + 各库对象）统一走 [MetaCache] 磁盘缓存：
 * - 连接只负责 JDBC 建连；库/对象默认读缓存入内存 → 编辑器补全与树展开立即可用、零目标库查询；
 * - 缓存未命中（首连/URL 变更）才查库，并把“全部 schema 对象”整体预取回写缓存；
 * - 缓存过期：先用缓存展示，后台静默重取一次（受 TTL 约束，不对数据库造成周期性压力）；
 * - 右键「刷新元数据缓存」= 主动整体重取 + 回写。
 */
class ConnectionsState(
    private val metaCache: MetaCache = MetaCache(),
    private val columnCache: ColumnCache = ColumnCache(),
) : ConnectionRuntimeView {

    private class ConnRuntime(val profile: ConnectionProfile) {
        val live: DataSourceSession = SessionFactory.create(profile)
        /** 编辑器列补全专用的独立元数据连接（懒建；不排队在执行线程后，也不受 sessionContextSql 影响）。 */
        var metaLive: DataSourceSession? = null
        // 仅 ConnectionsState（外层）修改；对外只经 ConnectionRuntimeView 只读暴露
        var status by mutableStateOf(ConnUiStatus.DISCONNECTED)
        var statusMessage by mutableStateOf<String?>(null)
        var schemas by mutableStateOf<List<SchemaMeta>?>(null)
        var schemasLoading by mutableStateOf(false)
        val objects = mutableStateMapOf<String, SchemaObjects>()
        val objectsLoading = mutableStateMapOf<String, Boolean>()
        /** 按组懒加载中的标记（key = schemaKey + 组类型）；值 true 时树出「正在加载」。 */
        val groupLoading = mutableStateMapOf<String, Boolean>()
        /** 后台静默重取进行中标记（防重复）。非 UI 展示，普通字段即可。 */
        var backgroundRefreshing = false

        fun reset() {
            live.close()
            metaLive?.close()
            metaLive = null
            status = ConnUiStatus.DISCONNECTED
            statusMessage = null
            schemas = null
            schemasLoading = false
            objects.clear()
            objectsLoading.clear()
            groupLoading.clear()
            backgroundRefreshing = false
        }
    }

    private val runtimes = mutableMapOf<String, ConnRuntime>()

    /** 「命名空间即过滤器」后端（Redis）：profileId → 当前查看的 DB。 */
    private val activeNamespaces = mutableStateMapOf<String, SchemaMeta>()

    /** profileId → 当前 key 搜索条件（pattern / 类型；不含游标）。 */
    private val objectSearches = mutableStateMapOf<String, ObjectSearch>()

    /** profileId → 键组分页状态（瞬态，不落盘）。 */
    private val keyPages = mutableStateMapOf<String, KeyPageState>()

    private data class KeyPageState(val schemaKey: String, val nextCursor: String?, val hasMore: Boolean)

    /** 列元数据会话缓存（快照状态；补全读取，异步回填；带磁盘缓存，离线可用）。 */
    val columns = ColumnCatalog(
        loader = { profile, schema, table -> fetchColumns(profile, schema, table) },
        store = columnCache,
    )

    private fun runtime(profile: ConnectionProfile): ConnRuntime =
        runtimes.getOrPut(profile.id) { ConnRuntime(profile) }

    // ---------- ConnectionRuntimeView（UI 读） ----------

    override fun statusOf(profileId: String): ConnUiStatus =
        runtimes[profileId]?.status ?: ConnUiStatus.DISCONNECTED

    override fun statusMessageOf(profileId: String): String? = runtimes[profileId]?.statusMessage

    override fun schemasOf(profileId: String): List<SchemaMeta>? = runtimes[profileId]?.schemas

    override fun schemasLoadingOf(profileId: String): Boolean =
        runtimes[profileId]?.schemasLoading == true

    override fun objectsOf(profileId: String, schemaKey: String): SchemaObjects? =
        runtimes[profileId]?.objects?.get(schemaKey)

    override fun objectsLoadingOf(profileId: String, schemaKey: String): Boolean =
        runtimes[profileId]?.objectsLoading?.get(schemaKey) == true

    override fun groupObjectsLoadingOf(profileId: String, schemaKey: String, kind: ObjectKind): Boolean =
        runtimes[profileId]?.groupLoading?.get(groupLoadingKey(schemaKey, kind)) == true

    override fun objectGroupsOf(profileId: String): List<ObjectKind> =
        runtimes[profileId]?.live?.objectGroups() ?: SQL_OBJECT_KINDS

    /** 命名空间作为过滤器（Redis DB）：树不铺 DB 行。 */
    override fun flatNamespaceOf(profileId: String): Boolean =
        runtimes[profileId]?.live?.capabilities?.namespaceAsFilter == true

    /** 当前查看的命名空间（过滤器模式）。 */
    override fun activeNamespaceOf(profileId: String): SchemaMeta? = activeNamespaces[profileId]

    /** 当前 key 搜索条件（pattern / 类型）。 */
    override fun objectSearchOf(profileId: String): ObjectSearch = objectSearches[profileId] ?: ObjectSearch()

    /** 键组是否还有下一页（「继续扫描」）。 */
    override fun objectsHasMoreOf(profileId: String, schemaKey: String, kind: ObjectKind): Boolean =
        kind == ObjectKind.KEY && keyPages[profileId]?.let { it.schemaKey == schemaKey && it.hasMore } == true

    // ---------- 连接 / 元数据（suspend；UI 协程调用，内部切 IO） ----------

    /**
     * 展开连接：未连接则连接（只建 JDBC 连接，不查目录元数据）；已连接则确保元数据就绪。
     * 元数据流程见类注释：缓存命中/过期/未命中三种路径，成功后 status=CONNECTED 且
     * schemas 就绪、对象已整体入缓存（编辑器补全与树展开不依赖额外预取触发）。
     * 幂等，可并发调（CONNECTING / schemasLoading 期间调用直接返回）。
     */
    suspend fun ensureConnectionReady(profile: ConnectionProfile) {
        val rt = runtime(profile)
        if (rt.status == ConnUiStatus.CONNECTING) return
        if (rt.status != ConnUiStatus.CONNECTED) {
            rt.status = ConnUiStatus.CONNECTING
            rt.statusMessage = null
            rt.schemasLoading = false
            val opened = withContext(Dispatchers.IO) { runCatching { rt.live.open() } }
            opened.exceptionOrNull()?.let { Logger.error(it, "connect failed {}", profile.name) }
            if (opened.isFailure) {
                rt.status = ConnUiStatus.ERROR
                rt.statusMessage = friendlyMessage(opened.exceptionOrNull())
                return
            }
            rt.status = ConnUiStatus.CONNECTED
        }
        if (rt.schemas == null && !rt.schemasLoading) {
            loadMetadata(rt)
        }
    }

    /** 元数据获取唯一入口：缓存命中(含过期) → 入内存；未命中 → 查库整体预取回写。
     *  「命名空间即过滤器」后端（Redis）**不走磁盘缓存**（key 列表瞬时性强、缓存易误导），每次都从服务端取当前 DB 首页。 */
    private suspend fun loadMetadata(rt: ConnRuntime) {
        rt.schemasLoading = true
        rt.statusMessage = null
        if (rt.live.capabilities.namespaceAsFilter) {
            val fetched = fetchFreshMetadata(rt)
            rt.schemasLoading = false
            fetched.onFailure { t ->
                Logger.error(t, "load metadata failed {}", rt.profile.name)
                rt.status = ConnUiStatus.ERROR
                rt.statusMessage = friendlyMessage(t)
            }
            return
        }
        val cached = withContext(Dispatchers.IO) { metaCache.load(rt.profile) }
        if (cached != null) {
            applyMetadata(rt, cached.schemas, cached.objects)
            rt.schemasLoading = false
            if (cached.stale) {
                Logger.info("meta cache stale for {}; silent background refresh", rt.profile.name)
                backgroundRefresh(rt)
            }
            return
        }
        val fetched = fetchFreshMetadata(rt)
        rt.schemasLoading = false
        fetched.onFailure { t ->
            Logger.error(t, "load metadata failed {}", rt.profile.name)
            rt.status = ConnUiStatus.ERROR
            rt.statusMessage = friendlyMessage(t)
        }
    }

    /**
     * 显式刷新（右键数据源 → 刷新元数据缓存）：清内存后整体重取并回写磁盘缓存。
     * @return 是否成功；失败时状态置 ERROR（含原因），可再次连接重试。
     */
    suspend fun refreshMetadata(profile: ConnectionProfile): Boolean {
        val rt = runtimes[profile.id] ?: return false
        if (rt.status != ConnUiStatus.CONNECTED) return false
        columns.invalidate(profile.id)
        rt.schemas = null
        rt.objects.clear()
        rt.objectsLoading.clear()
        rt.groupLoading.clear()
        rt.schemasLoading = true
        val result = fetchFreshMetadata(rt)
        rt.schemasLoading = false
        result.onFailure { t ->
            Logger.error(t, "refresh metadata failed {}", profile.name)
            rt.status = ConnUiStatus.ERROR
            rt.statusMessage = friendlyMessage(t)
        }
        return result.isSuccess
    }

    /** 查库全量元数据并回写缓存（整体预取：编辑器补全依赖全部 schema 对象）。 */
    private suspend fun fetchFreshMetadata(rt: ConnRuntime): Result<Unit> {
        val outcome = withContext(Dispatchers.IO) {
            runCatching { loadAllMetaFromDb(rt) }
        }
        outcome.onSuccess { (schemas, objects, warn) ->
            applyMetadata(rt, schemas, objects)
            if (warn != null) rt.statusMessage = warn
            if (!rt.live.capabilities.namespaceAsFilter) {
                withContext(Dispatchers.IO) { metaCache.save(rt.profile, schemas, objects) }
            }
        }
        return outcome.map { }
    }

    /**
     * 缓存过期后的后台静默刷新：不闪占位符、不打断浏览——成功才整体替换 + 回写。
     * 频率受 TTL 约束（过期连接时最多一次）；失败仅记日志，沿用旧缓存。
     */
    private suspend fun backgroundRefresh(rt: ConnRuntime) {
        if (rt.backgroundRefreshing) return
        rt.backgroundRefreshing = true
        try {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { loadAllMetaFromDb(rt) }
            }
            outcome.onSuccess { (schemas, objects, warn) ->
                applyMetadata(rt, schemas, objects)
                if (warn != null) rt.statusMessage = warn
                withContext(Dispatchers.IO) { metaCache.save(rt.profile, schemas, objects) }
            }.onFailure {
                Logger.error(it, "background meta refresh failed {}", rt.profile.name)
            }
        } finally {
            rt.backgroundRefreshing = false
        }
    }

    /**
     * 展开 schema：确保其对象已加载（幂等）。缓存命中时对象已在内存；个别 schema 因
     * 刷新/预取失败未入缓存时，这里兜底查库。
     * schema 展开错误直接置 statusMessage（连接级显示），对象失败不打断其它 schema。
     */
    suspend fun ensureSchemaObjects(profile: ConnectionProfile, schema: SchemaMeta) {
        val rt = runtime(profile)
        val key = schema.key
        if (rt.objects.containsKey(key) || rt.objectsLoading[key] == true) return
        rt.objectsLoading[key] = true
        val result = withContext(Dispatchers.IO) {
            runCatching { rt.live.loadObjects(schema) }
        }
        rt.objectsLoading[key] = false
        result.onSuccess { rt.objects[key] = it }
            .onFailure {
                Logger.error(it, "load objects failed {} {} {}", rt.profile.name, schema.displayName)
                rt.statusMessage = friendlyMessage(it)
            }
    }

    /**
     * 懒加载方言：展开某类型组时确保该组正文已加载（幂等）。
     * 非懒加载方言或已加载组无操作；成功后把该组并入 schema 的 [SchemaObjects] 并增量回写磁盘缓存。
     */
    suspend fun ensureGroupObjects(profile: ConnectionProfile, schema: SchemaMeta, kind: ObjectKind) {
        val rt = runtime(profile)
        val key = schema.key
        val current = rt.objects[key] ?: return
        val loadingKey = groupLoadingKey(key, kind)
        if (current.isLoaded(kind) || rt.groupLoading[loadingKey] == true) return
        rt.groupLoading[loadingKey] = true
        val result = withContext(Dispatchers.IO) {
            runCatching { rt.live.loadObjectsForKind(schema, kind) }
        }
        rt.groupLoading.remove(loadingKey)
        result.onSuccess { items ->
            // 写回时重读当前快照：期间可能已并发加载了别的组（同一 schema 多组先后展开）
            val base = rt.objects[key] ?: current
            rt.objects[key] = SchemaObjects(
                objects = base.objects + (kind to items),
                counts = base.counts,
            )
            val schemas = rt.schemas
            if (schemas != null) {
                withContext(Dispatchers.IO) { metaCache.save(rt.profile, schemas, rt.objects.toMap()) }
            }
        }.onFailure {
            Logger.error(it, "load group failed {} {} {}", rt.profile.name, schema.displayName, kind)
            rt.statusMessage = friendlyMessage(it)
        }
    }

    /** 主动断开：只放连接与会话内存，磁盘缓存保留（下次连接/离线仍可用）。 */
    fun disconnect(profile: ConnectionProfile) {
        columns.evict(profile.id)
        clearFlatState(profile.id)
        runtime(profile).reset()
    }

    /** 档案被编辑（URL 可能变）：断开并丢弃运行缓存；指纹失配的旧缓存行自动作废。 */
    fun invalidate(profileId: String) {
        columns.invalidate(profileId)
        clearFlatState(profileId)
        runtimes.remove(profileId)?.let { rt ->
            rt.live.close()
            rt.metaLive?.close()
        }
    }

    /** 供查询执行引擎取通用会话句柄（连接必须在 CONNECTED 才非空）。 */
    fun sessionOf(profileId: String): DataSourceSession? = runtimes[profileId]?.live

    /** JDBC 专属路径（全量流式 CSV 导出等）需要具体实现时使用。 */
    fun jdbcConnection(profileId: String): LiveConnection? = runtimes[profileId]?.live as? LiveConnection

    /** 向某连接的当前执行语句发起取消（UI 取消按钮 / Esc）。 */
    fun cancelCurrentQuery(profileId: String): Boolean =
        runtimes[profileId]?.live?.cancel() ?: false

    /** 档案被删除：丢运行时并清磁盘缓存行。 */
    fun forget(profileId: String) {
        invalidate(profileId)
        metaCache.delete(profileId)
        RedisPrefs.clear(profileId)
    }

    /** 应用退出清理。 */
    fun disposeAll() {
        columns.clear()
        runtimes.values.forEach { it.live.close(); it.metaLive?.close() }
        runtimes.clear()
    }

    // ---------- 列元数据（编辑器补全） ----------

    /**
     * 探测单表列（阻塞部分经 IO）。用独立的「元数据连接」：不排队在执行线程后，
     * 也不受执行前 sessionContextSql 切换 schema 的影响（schema 全部显式传入）。
     * 失败直接抛出，由 [ColumnCatalog.ensure] 吞掉并回落。
     */
    suspend fun fetchColumns(
        profile: ConnectionProfile,
        schema: SchemaMeta?,
        table: String,
    ): List<ColumnMeta> = withContext(Dispatchers.IO) {
        val rt = runtime(profile)
        val live = rt.metaLive ?: SessionFactory.create(profile).also { rt.metaLive = it }
        if (!live.isOpen) live.open()
        live.loadColumns(schema, table)
    }

    /**
     * 取对象定义 DDL（Ctrl+Q 浮窗）。同样走独立元数据连接（不占执行连接、不受 sessionContextSql 影响）。
     * 失败不抛：以 [Result.failure] 返回可读原因（供浮窗展示）。
     */
    suspend fun fetchDdl(
        profile: ConnectionProfile,
        schema: SchemaMeta?,
        name: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val rt = runtime(profile)
            val live = rt.metaLive ?: SessionFactory.create(profile).also { rt.metaLive = it }
            if (!live.isOpen) live.open()
            live.objectDdl(schema, name)
        }.fold(
            onSuccess = { ddl ->
                if (ddl.isNullOrBlank()) {
                    Result.failure(IllegalStateException("未获取到定义（对象可能不存在，或当前账号无权限）"))
                } else {
                    Result.success(ddl)
                }
            },
            onFailure = { t ->
                Logger.warn(t, "fetchDdl failed {} {}", profile.name, name)
                Result.failure(IllegalStateException(friendlySqlError(t), t))
            },
        )
    }

    // ---------- 命名空间过滤器（Redis DB / 对象搜索） ----------

    /** 切换当前查看的 DB（只保留该 DB 的对象缓存，重新拉计数 + 首页键）。 */
    suspend fun setActiveDb(profile: ConnectionProfile, ns: SchemaMeta) {
        val rt = runtime(profile)
        if (!rt.live.capabilities.namespaceAsFilter) return
        activeNamespaces[profile.id] = ns
        // 只查看一个 DB：清掉其它 DB 的对象缓存与分页游标
        rt.objects.keys.toList().forEach { if (it != ns.key) rt.objects.remove(it) }
        rt.groupLoading.clear()
        keyPages.remove(profile.id)
        val result = if (rt.status == ConnUiStatus.CONNECTED) {
            withContext(Dispatchers.IO) { runCatching { loadFlatNamespace(profile.id, rt.live, ns) } }
        } else {
            null
        }
        result?.onSuccess { rt.objects[ns.key] = it }
            ?.onFailure { rt.statusMessage = friendlyMessage(it) }
        persistRedisState(profile.id)
    }

    /** 设置 key pattern / 类型过滤（调用方防抖）并从首页重扫。 */
    suspend fun setObjectFilter(profile: ConnectionProfile, pattern: String, type: String?) {
        val rt = runtime(profile)
        if (!rt.live.capabilities.namespaceAsFilter) return
        objectSearches[profile.id] = ObjectSearch(pattern = pattern.ifBlank { "*" }, type = type)
        persistRedisState(profile.id)
        if (rt.status != ConnUiStatus.CONNECTED) return
        val ns = activeNamespaces[profile.id] ?: return
        keyPages.remove(profile.id)
        val result = withContext(Dispatchers.IO) { runCatching { loadFlatNamespace(profile.id, rt.live, ns) } }
        result.onSuccess { rt.objects[ns.key] = it }
            .onFailure { rt.statusMessage = friendlyMessage(it) }
    }

    /** 「继续扫描」：按游标拉下一页键并追加（去重不变）。 */
    suspend fun loadMoreObjects(profile: ConnectionProfile) {
        val rt = runtime(profile)
        if (!rt.live.capabilities.namespaceAsFilter || rt.status != ConnUiStatus.CONNECTED) return
        val ns = activeNamespaces[profile.id] ?: return
        val cursor = keyPages[profile.id]?.takeIf { it.schemaKey == ns.key }?.nextCursor ?: return
        val search = (objectSearches[profile.id] ?: ObjectSearch()).copy(cursor = cursor)
        val result = withContext(Dispatchers.IO) {
            runCatching { rt.live.searchObjects(ns, ObjectKind.KEY, search) }
        }
        result.onSuccess { page ->
            val base = rt.objects[ns.key]
            val existing = base?.forKind(ObjectKind.KEY).orEmpty()
            val seen = existing.mapTo(HashSet()) { it.name }
            val merged = existing + page.objects.filter { seen.add(it.name) }
            rt.objects[ns.key] = SchemaObjects(
                objects = mapOf(ObjectKind.KEY to merged),
                counts = base?.counts ?: emptyMap(),
            )
            keyPages[profile.id] = KeyPageState(ns.key, page.nextCursor, !page.finished)
        }.onFailure { rt.statusMessage = friendlyMessage(it) }
    }

    // ---------- 内部 ----------

    /** 把新元数据整体替换进运行时（对象加载标记一并复位）。 */
    private fun applyMetadata(
        rt: ConnRuntime,
        schemas: List<SchemaMeta>,
        objects: Map<String, SchemaObjects>,
    ) {
        rt.schemas = schemas
        rt.objects.clear()
        rt.objects.putAll(objects)
        rt.objectsLoading.clear()
        rt.groupLoading.clear()
    }

    /**
     * 阻塞读库全量元数据（调用方负责切 IO）：
     * 库列表失败抛异常；单个 schema 的对象失败只记消息不打断（对齐旧的 ensureSchemaObjects 语义）。
     * @return schemas、各库对象（schema.key → SchemaObjects）、警告消息（可能有对象未取到）。
     */
    private fun loadAllMetaFromDb(
        rt: ConnRuntime,
    ): Triple<List<SchemaMeta>, Map<String, SchemaObjects>, String?> {
        val live = rt.live
        val schemas = live.loadNamespaces()
        val objects = linkedMapOf<String, SchemaObjects>()
        val failed = mutableListOf<String>()
        val reasons = mutableListOf<String>()
        val lazy = live.capabilities.lazyObjectGroups
        val flat = live.capabilities.namespaceAsFilter
        // 「命名空间即过滤器」：只加载当前 DB（先取 B/S 计数 + 首页键）
        val active = if (flat) pickActiveNamespace(rt.profile, schemas) else null
        if (active != null) activeNamespaces[rt.profile.id] = active
        schemas.forEach { s ->
            if (flat && s.key != active?.key) return@forEach
            runCatching {
                when {
                    flat -> loadFlatNamespace(rt.profile.id, live, s)
                    // 懒加载方言：只取组计数 + 关系类核心组（补全/首屏用）；重目录组展开时再拉。
                    lazy -> {
                        val counts = live.loadObjectCounts(s)
                        val core = live.loadCoreObjects(s)
                        SchemaObjects(objects = core.objects, counts = counts)
                    }
                    else -> live.loadObjects(s)
                }
            }
                .onSuccess { objects[s.key] = it }
                .onFailure { t ->
                    Logger.error(t, "load objects failed {} {}", rt.profile.name, s.displayName)
                    failed += s.displayName
                    reasons += friendlyMessage(t)
                }
        }
        val warn = if (failed.isEmpty()) {
            null
        } else {
            // 带上根因（如 ES 403 缺权限），否则树/状态栏只有一句“加载失败”无从排查
            "对象加载失败：${failed.joinToString("、").take(40)}：${reasons.first().take(160)}"
        }
        return Triple(schemas, objects, warn)
    }

    /**
     * 选定「当前查看的 DB」：优先持久化偏好，其次连接档案的 `database`，最后 db0。
     * 同时惰性初始化该连接的 key 搜索条件（从持久化偏好读）。
     */
    private fun pickActiveNamespace(profile: ConnectionProfile, schemas: List<SchemaMeta>): SchemaMeta? {
        if (schemas.isEmpty()) return null
        val prefs = RedisPrefs.load(profile.id)
        if (objectSearches[profile.id] == null) {
            objectSearches[profile.id] = ObjectSearch(
                pattern = prefs.pattern.ifBlank { "*" },
                type = prefs.type,
            )
        }
        val want = prefs.db ?: ("db" + profile.database.trim().ifEmpty { "0" })
        return schemas.firstOrNull { it.displayName.equals(want, ignoreCase = true) } ?: schemas.first()
    }

    /** 拉当前 DB 的计数 + 首页键，并记录分页游标（只拉一页，其余由「继续扫描」拉）。 */
    private fun loadFlatNamespace(profileId: String, live: DataSourceSession, ns: SchemaMeta): SchemaObjects {
        val counts = live.loadObjectCounts(ns)
        val search = (objectSearches[profileId] ?: ObjectSearch()).copy(cursor = null)
        val page = live.searchObjects(ns, ObjectKind.KEY, search)
        keyPages[profileId] = KeyPageState(ns.key, page.nextCursor, !page.finished)
        return SchemaObjects(objects = mapOf(ObjectKind.KEY to page.objects), counts = counts)
    }

    /** 把当前 DB / pattern / 类型写入 `<dataDir>/redis.properties`（小文件 IO，切 IO 线程）。 */
    private suspend fun persistRedisState(profileId: String) {
        val state = RedisUiState(
            db = activeNamespaces[profileId]?.displayName,
            pattern = objectSearches[profileId]?.pattern ?: "*",
            type = objectSearches[profileId]?.type,
        )
        withContext(Dispatchers.IO) { RedisPrefs.save(profileId, state) }
    }

    /** 清理「命名空间过滤器」态的瞬态字段（断开/失效时）。 */
    private fun clearFlatState(profileId: String) {
        activeNamespaces.remove(profileId)
        keyPages.remove(profileId)
        objectSearches.remove(profileId)
    }

    companion object {
        private fun friendlyMessage(t: Throwable?): String = friendlySqlError(t)

        /** 按组懒加载标记的键（schemaKey + 类型）。 */
        private fun groupLoadingKey(schemaKey: String, kind: ObjectKind): String =
            schemaKey + "\u0000" + kind.name
    }
}
