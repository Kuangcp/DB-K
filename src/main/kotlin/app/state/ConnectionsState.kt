package app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import db.ColumnCache
import db.ConnectionProfile
import db.MetaCache
import engine.DataSourceSession
import engine.model.ColumnMeta
import engine.model.ObjectKind
import engine.model.SchemaMeta
import engine.model.SchemaObjects
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
 * 每条连接一个 LiveConnection（单线程串行 JDBC），所有探测/执行经 Dispatchers.IO。
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
        val live: DataSourceSession = LiveConnection(profile)
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

    /** 元数据获取唯一入口：缓存命中(含过期) → 入内存；未命中 → 查库整体预取回写。 */
    private suspend fun loadMetadata(rt: ConnRuntime) {
        rt.schemasLoading = true
        rt.statusMessage = null
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
            withContext(Dispatchers.IO) { metaCache.save(rt.profile, schemas, objects) }
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
        runtime(profile).reset()
    }

    /** 档案被编辑（URL 可能变）：断开并丢弃运行缓存；指纹失配的旧缓存行自动作废。 */
    fun invalidate(profileId: String) {
        columns.invalidate(profileId)
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
        val live = rt.metaLive ?: LiveConnection(profile).also { rt.metaLive = it }
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
            val live = rt.metaLive ?: LiveConnection(profile).also { rt.metaLive = it }
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
        val lazy = live.capabilities.lazyObjectGroups
        schemas.forEach { s ->
            runCatching {
                // 懒加载方言：只取组计数 + 关系类核心组（补全/首屏用）；重目录组展开时再拉。
                if (lazy) {
                    val counts = live.loadObjectCounts(s)
                    val core = live.loadCoreObjects(s)
                    SchemaObjects(objects = core.objects, counts = counts)
                } else {
                    live.loadObjects(s)
                }
            }
                .onSuccess { objects[s.key] = it }
                .onFailure { t ->
                    Logger.error(t, "load objects failed {} {}", rt.profile.name, s.displayName)
                    failed += s.displayName
                }
        }
        val warn = if (failed.isEmpty()) null else "对象加载失败：${failed.joinToString("、").take(60)}"
        return Triple(schemas, objects, warn)
    }

    companion object {
        private fun friendlyMessage(t: Throwable?): String = friendlySqlError(t)

        /** 按组懒加载标记的键（schemaKey + 类型）。 */
        private fun groupLoadingKey(schemaKey: String, kind: ObjectKind): String =
            schemaKey + "\u0000" + kind.name
    }
}
