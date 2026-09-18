package tree

import db.ConnectionProfile
import db.FolderRow
import engine.model.DbObjectMeta
import engine.model.ObjectKind
import engine.model.ObjectSearch
import engine.model.SQL_OBJECT_KINDS
import engine.model.SchemaMeta
import engine.model.SchemaObjects
import engine.model.displayNoun

/** 左侧树行类别。M2：连接行之下支持 schema / 对象组 / 对象。 */
enum class TreeRowKind {
    FOLDER, CONNECTION, SCHEMA, OBJECT_GROUP, DB_OBJECT, PLACEHOLDER,
    /** Redis key 层级命名空间（前缀节点，如 `a:b` 中的 `a`）。 */
    KEY_NAMESPACE,
    /** Redis 等「命名空间即过滤器」后端的过滤条（DB / 类型 / pattern）。 */
    FILTER,
    /** 分页「继续扫描」行（Redis `SCAN` 游标续页）。 */
    LOAD_MORE,
}

/** 连接运行状态（供状态点/连接入口展示；树层只做展示判断，不持有连接）。 */
enum class ConnUiStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

enum class PlaceholderKind { NONE, LOADING, ERROR, INFO }

/**
 * 扁平化树行：供 LazyColumn 单列渲染，缩进由 depth 决定。
 * 各 kind 使用的字段见属性注释。
 */
data class TreeRowInfo(
    val key: String,
    val kind: TreeRowKind,
    val depth: Int,
    val name: String,
    /** FOLDER/CONNECTION 行：所属 folderId（根级连接为 null）。 */
    val folderId: String? = null,
    /** CONNECTION 行。 */
    val profile: ConnectionProfile? = null,
    /** FOLDER/CONNECTION/SCHEMA 行：是否展开（折叠时显示计数徽章）。 */
    val expanded: Boolean = false,
    /** FOLDER 收起显连接数 / CONNECTION 收起显 schema 数 / SCHEMA 收起显对象数。 */
    val childCount: Int = 0,
    /** CONNECTION 行：连接运行状态。 */
    val connStatus: ConnUiStatus? = null,
    /** CONNECTION(ERROR)/PLACEHOLDER(ERROR) 行：错误/说明文字。 */
    val message: String? = null,
    /** SCHEMA 行。 */
    val schema: SchemaMeta? = null,
    /** OBJECT_GROUP 行：该组的对象类型（label 取 [ObjectKind.displayNoun]）。 */
    val groupKind: ObjectKind? = null,
    /** DB_OBJECT 行。 */
    val dbObject: DbObjectMeta? = null,
    /** PLACEHOLDER 行：LOADING 显示 spinner，ERROR 红字，INFO 灰字。 */
    val placeholderKind: PlaceholderKind = PlaceholderKind.NONE,
    /** FILTER 行（Redis 过滤条）。 */
    val filter: TreeFilterInfo? = null,
    /** 是否可展开/收起（FILTER/LOAD_MORE/强制展开的键组 = false）。 */
    val expandable: Boolean = true,
)

/** 「命名空间即过滤器」后端的过滤条状态：DB 下拉 / 类型下拉 / key pattern。 */
data class TreeFilterInfo(
    val dbOptions: List<String>,
    val db: String,
    val pattern: String,
    val type: String?,
)

/** 连接运行时只读视图：由 app 层 ConnectionsState 实现（compose 快照状态在其内部被读）。 */
interface ConnectionRuntimeView {
    fun statusOf(profileId: String): ConnUiStatus
    fun statusMessageOf(profileId: String): String?
    fun schemasOf(profileId: String): List<SchemaMeta>?
    fun schemasLoadingOf(profileId: String): Boolean
    fun objectsOf(profileId: String, schemaKey: String): SchemaObjects?
    fun objectsLoadingOf(profileId: String, schemaKey: String): Boolean

    /**
     * 搜索用库列表：[schemasOf] 的离线扩展——已连接取实时元数据；未连接/连接中/失败时
     * 回落本地 `meta_cache`（可能没有 → null）。默认退回实时，普通树不受影响。
     */
    fun searchSchemasOf(profileId: String): List<SchemaMeta>? = schemasOf(profileId)

    /** 搜索用对象清单：同 [searchSchemasOf]，未连接时取本地缓存。 */
    fun searchObjectsOf(profileId: String, schemaKey: String): SchemaObjects? = objectsOf(profileId, schemaKey)

    /** 该连接支持的对象组顺序（非 SQL 后端可覆写，如 Redis 仅「键」）。 */
    fun objectGroupsOf(profileId: String): List<ObjectKind> = SQL_OBJECT_KINDS

    /** 命名空间是否作为「过滤器」而非树层级（Redis DB）。 */
    fun flatNamespaceOf(profileId: String): Boolean = false

    /** 当前查看的命名空间（过滤器模式；SQL 后端为 null）。 */
    fun activeNamespaceOf(profileId: String): SchemaMeta? = null

    /** 当前对象搜索条件（pattern / 类型）；SQL 后端为默认全量。 */
    fun objectSearchOf(profileId: String): ObjectSearch = ObjectSearch()

    /** 对象组是否还有下一页（「继续扫描」）。 */
    fun objectsHasMoreOf(profileId: String, schemaKey: String, kind: ObjectKind): Boolean = false

    /** 某类型组正文是否正在懒加载（P6；非懒加载实现默认 false）。 */
    fun groupObjectsLoadingOf(profileId: String, schemaKey: String, kind: ObjectKind): Boolean = false
}

/** 空实现：未接线的运行时（测试/回退用）。 */
object NoRuntime : ConnectionRuntimeView {
    override fun statusOf(profileId: String) = ConnUiStatus.DISCONNECTED
    override fun statusMessageOf(profileId: String): String? = null
    override fun schemasOf(profileId: String): List<SchemaMeta>? = null
    override fun schemasLoadingOf(profileId: String) = false
    override fun objectsOf(profileId: String, schemaKey: String): SchemaObjects? = null
    override fun objectsLoadingOf(profileId: String, schemaKey: String) = false
}

/** schema 行 key（含 profile 维度，供展开集合与对象缓存寻址）。 */
fun schemaRowKey(profileId: String, schema: SchemaMeta): String = "s:$profileId:${schema.key}"

fun connectionRowKey(profileId: String): String = "c:$profileId"

/**
 * 依据 folders + connections + 运行时状态生成扁平行。
 * 顺序：文件夹（展开后带其下连接）→ 根级（未分组）连接；
 * 连接展开 → schema 行（按加载状态/内容继续展开）；
 * schema 展开 → 各非空对象组 header + 对象行。
 */
fun buildTreeRows(
    folders: List<FolderRow>,
    connections: List<ConnectionProfile>,
    expandedFolderIds: Set<String>,
    expandedConnectionIds: Set<String>,
    expandedSchemaKeys: Set<String>,
    expandedGroupKeys: Set<String>,
    runtime: ConnectionRuntimeView,
    /** 非空时进入搜索模式：只保留命中节点及其祖先，全部强制展开（见 [buildTreeRowsSearch]）。 */
    search: String = "",
    /** 搜索范围：null = 全部数据源；否则只搜该 profile（方案 A 的数据源级范围）。 */
    searchScopeProfileId: String? = null,
    /** Redis key 层级命名空间展开态（会话级，不持久化）。 */
    expandedKeyNamespaceKeys: Set<String> = emptySet(),
): List<TreeRowInfo> {
    val query = search.trim()
    if (query.isNotEmpty()) {
        return buildTreeRowsSearch(folders, connections, runtime, query, searchScopeProfileId, expandedKeyNamespaceKeys)
    }
    return buildTreeRowsNormal(
        folders, connections, expandedFolderIds, expandedConnectionIds,
        expandedSchemaKeys, expandedGroupKeys, expandedKeyNamespaceKeys, runtime,
    )
}

private fun buildTreeRowsNormal(
    folders: List<FolderRow>,
    connections: List<ConnectionProfile>,
    expandedFolderIds: Set<String>,
    expandedConnectionIds: Set<String>,
    expandedSchemaKeys: Set<String>,
    expandedGroupKeys: Set<String>,
    expandedKeyNamespaceKeys: Set<String>,
    runtime: ConnectionRuntimeView,
): List<TreeRowInfo> {
    val out = mutableListOf<TreeRowInfo>()
    val byFolder = connections.groupBy { it.folderId }

    folders.sortedBy { it.sortOrder }.forEach { folder ->
        val children = byFolder[folder.id].orEmpty().sortedBy { it.sortOrder }
        val expanded = folder.id in expandedFolderIds
        out += TreeRowInfo(
            key = "f:${folder.id}",
            kind = TreeRowKind.FOLDER,
            depth = 0,
            name = folder.name,
            folderId = folder.id,
            expanded = expanded,
            childCount = children.size,
        )
        if (expanded) {
            children.forEach { conn -> appendConnection(out, conn, 1, expandedConnectionIds, expandedSchemaKeys, expandedGroupKeys, expandedKeyNamespaceKeys, runtime) }
        }
    }
    byFolder[null].orEmpty().sortedBy { it.sortOrder }.forEach { conn ->
        appendConnection(out, conn, 0, expandedConnectionIds, expandedSchemaKeys, expandedGroupKeys, expandedKeyNamespaceKeys, runtime)
    }
    return out
}

private fun appendConnection(
    out: MutableList<TreeRowInfo>,
    conn: ConnectionProfile,
    depth: Int,
    expandedConnectionIds: Set<String>,
    expandedSchemaKeys: Set<String>,
    expandedGroupKeys: Set<String>,
    expandedKeyNamespaceKeys: Set<String>,
    runtime: ConnectionRuntimeView,
) {
    val expanded = conn.id in expandedConnectionIds
    val status = runtime.statusOf(conn.id)
    val schemas = runtime.schemasOf(conn.id)
    val flat = runtime.flatNamespaceOf(conn.id)
    val activeNs = if (flat) runtime.activeNamespaceOf(conn.id) else null
    out += TreeRowInfo(
        key = connectionRowKey(conn.id),
        kind = TreeRowKind.CONNECTION,
        depth = depth,
        name = conn.name,
        folderId = conn.folderId,
        profile = conn,
        expanded = expanded,
        childCount = if (flat) {
            activeNs?.let { ns -> runtime.objectsOf(conn.id, ns.key)?.totalOf(ObjectKind.KEY) } ?: 0
        } else {
            schemas?.size ?: 0
        },
        connStatus = status,
        message = runtime.statusMessageOf(conn.id),
    )
    if (!expanded) return

    when (status) {
        ConnUiStatus.CONNECTING -> out += loadingPlaceholder(depth + 1, "p:${conn.id}:connecting", "正在连接…")
        ConnUiStatus.DISCONNECTED -> out += infoPlaceholder(depth + 1, "p:${conn.id}:disconnected", "未连接")
        ConnUiStatus.ERROR -> out += TreeRowInfo(
            key = "p:${conn.id}:error",
            kind = TreeRowKind.PLACEHOLDER,
            depth = depth + 1,
            name = runtime.statusMessageOf(conn.id) ?: "连接失败",
            placeholderKind = PlaceholderKind.ERROR,
        )
        ConnUiStatus.CONNECTED -> {
            when {
                schemas == null && runtime.schemasLoadingOf(conn.id) ->
                    out += loadingPlaceholder(depth + 1, "p:${conn.id}:schemas", "正在加载库列表…")
                schemas == null ->
                    out += infoPlaceholder(depth + 1, "p:${conn.id}:schemas", "尚未加载库列表")
                schemas.isEmpty() ->
                    out += infoPlaceholder(depth + 1, "p:${conn.id}:empty", "该连接下没有可见的库")
                // 命名空间即过滤器（Redis）：不铺 DB 行，只渲染当前 DB 的过滤条 + 键列表
                flat -> appendFlatNamespace(out, conn, schemas, depth + 1, expandedGroupKeys, expandedKeyNamespaceKeys, runtime)
                else -> schemas.forEach { schema ->
                    appendSchema(out, conn, schema, depth + 1, expandedSchemaKeys, expandedGroupKeys, expandedKeyNamespaceKeys, runtime)
                }
            }
        }
    }
}

/** 「命名空间即过滤器」：过滤条（DB / 类型 / pattern）+ 当前 DB 的键列表（恒展开）。 */
private fun appendFlatNamespace(
    out: MutableList<TreeRowInfo>,
    conn: ConnectionProfile,
    schemas: List<SchemaMeta>,
    depth: Int,
    expandedGroupKeys: Set<String>,
    expandedKeyNamespaceKeys: Set<String>,
    runtime: ConnectionRuntimeView,
) {
    val active = runtime.activeNamespaceOf(conn.id)
    if (active == null) {
        out += infoPlaceholder(depth, "p:${conn.id}:ns", "尚未选择库")
        return
    }
    val search = runtime.objectSearchOf(conn.id)
    out += TreeRowInfo(
        key = "f:${conn.id}:filter",
        kind = TreeRowKind.FILTER,
        depth = depth,
        name = "",
        profile = conn,
        expandable = false,
        filter = TreeFilterInfo(
            dbOptions = schemas.map { it.displayName },
            db = active.displayName,
            pattern = search.pattern,
            type = search.type,
        ),
    )
    if (runtime.objectsOf(conn.id, active.key) == null) {
        out += infoPlaceholder(
            depth + 1,
            "p:${conn.id}:keys",
            runtime.statusMessageOf(conn.id) ?: "尚未加载键",
        )
    } else {
        appendGroups(out, conn, active, depth, expandedGroupKeys, expandedKeyNamespaceKeys, runtime, lockedExpanded = true)
    }
}

private fun appendSchema(
    out: MutableList<TreeRowInfo>,
    conn: ConnectionProfile,
    schema: SchemaMeta,
    depth: Int,
    expandedSchemaKeys: Set<String>,
    expandedGroupKeys: Set<String>,
    expandedKeyNamespaceKeys: Set<String>,
    runtime: ConnectionRuntimeView,
) {
    val rowKey = schemaRowKey(conn.id, schema)
    val expanded = rowKey in expandedSchemaKeys
    val objects = runtime.objectsOf(conn.id, schema.key)
    val total = objects?.total ?: 0
    out += TreeRowInfo(
        key = rowKey,
        kind = TreeRowKind.SCHEMA,
        depth = depth,
        name = schema.displayName,
        profile = conn,
        schema = schema,
        expanded = expanded,
        childCount = total,
    )
    if (!expanded) return

    when {
        objects == null && runtime.objectsLoadingOf(conn.id, schema.key) ->
            out += loadingPlaceholder(depth + 1, "$rowKey:load", "正在加载对象…")
        objects == null ->
            out += infoPlaceholder(depth + 1, "$rowKey:load", "尚未加载")
        objects.isEmpty ->
            out += infoPlaceholder(depth + 1, "$rowKey:empty", "（空 schema）")
        else -> appendGroups(out, conn, schema, depth + 1, expandedGroupKeys, expandedKeyNamespaceKeys, runtime, lockedExpanded = false)
    }
}

/**
 * 渲染某命名空间下的对象组与对象（P6 懒加载）。
 * [lockedExpanded] = true 时组恒展开、不可收起（Redis 只有一个键组，省一次点击），末尾给「继续扫描」。
 */
private fun appendGroups(
    out: MutableList<TreeRowInfo>,
    conn: ConnectionProfile,
    schema: SchemaMeta,
    depth: Int,
    expandedGroupKeys: Set<String>,
    expandedKeyNamespaceKeys: Set<String>,
    runtime: ConnectionRuntimeView,
    lockedExpanded: Boolean,
) {
    val objects = runtime.objectsOf(conn.id, schema.key) ?: return
    val schemaKey = schemaRowKey(conn.id, schema)
    val search = runtime.objectSearchOf(conn.id)
    val filtered = search.pattern != "*" || search.type != null
    runtime.objectGroupsOf(conn.id).forEach { kind ->
        // 分页模式：无过滤时用权威总数（DBSIZE）；有过滤时用已加载的匹配数（避免“标题 N 条却一条不列”）
        val count = if (lockedExpanded && !filtered) objects.totalOf(kind) else objects.countOf(kind)
        if (count <= 0) {
            if (lockedExpanded && objects.isLoaded(kind)) {
                out += infoPlaceholder(
                    depth,
                    "$schemaKey:g:${kind.name}:none",
                    if (filtered) "无匹配的键" else "（无键）",
                )
            }
            return@forEach
        }
        val groupKey = "$schemaKey:g:${kind.name}"
        val groupExpanded = lockedExpanded || groupKey in expandedGroupKeys
        out += TreeRowInfo(
            key = groupKey,
            kind = TreeRowKind.OBJECT_GROUP,
            depth = depth,
            name = kind.displayNoun,
            profile = conn,
            schema = schema,
            groupKind = kind,
            expanded = groupExpanded,
            childCount = count,
            expandable = !lockedExpanded,
        )
        if (!groupExpanded) return@forEach
        // 组折叠：对象行只在组展开时列出（大库例程上千条也不拖垮渲染）
        when {
            !objects.isLoaded(kind) && runtime.groupObjectsLoadingOf(conn.id, schema.key, kind) ->
                // P6 懒加载：正文未拉取时给占位；上层在展开时触发 ensureGroupObjects
                out += loadingPlaceholder(depth + 1, "$groupKey:load", "正在加载${kind.displayNoun}…")
            !objects.isLoaded(kind) ->
                out += infoPlaceholder(depth + 1, "$groupKey:load", "尚未加载")
            else -> {
                val items = objects.forKind(kind)
                if (kind == ObjectKind.KEY && conn.keySeparator.isNotBlank()) {
                    appendKeyTree(out, conn, schema, items, conn.keySeparator, depth, groupKey, expandedKeyNamespaceKeys)
                } else {
                    items.forEach { obj ->
                        out += TreeRowInfo(
                            key = "$groupKey:o:${obj.name}",
                            kind = TreeRowKind.DB_OBJECT,
                            depth = depth + 1,
                            name = obj.name,
                            profile = conn,
                            schema = schema,
                            dbObject = obj,
                        )
                    }
                }
                if (lockedExpanded && runtime.objectsHasMoreOf(conn.id, schema.key, kind)) {
                    out += TreeRowInfo(
                        key = "$groupKey:more",
                        kind = TreeRowKind.LOAD_MORE,
                        depth = depth + 1,
                        name = "继续扫描…",
                        profile = conn,
                        schema = schema,
                        groupKind = kind,
                    )
                }
            }
        }
    }
}

/**
 * Redis key 层级：按 [separator] 前缀树渲染 KEY 组。
 * 叶子行显示最后一段（如 `a:b:c` 展开到 `c`），但 `dbObject.name` 仍是完整 key（预览/复制不变）；
 * 既是前缀又是真实 key 的节点，展开后把真实 key 作为首个叶子列在 children 之前。
 */
private fun appendKeyTree(
    out: MutableList<TreeRowInfo>,
    conn: ConnectionProfile,
    schema: SchemaMeta,
    items: List<DbObjectMeta>,
    separator: String,
    depth: Int,
    groupKey: String,
    expandedKeyNamespaceKeys: Set<String>,
) {
    fun emit(node: KeyTreeNode, rowDepth: Int) {
        val hasChildren = node.children.isNotEmpty()
        if (!hasChildren) {
            val obj = node.key ?: DbObjectMeta(node.path, ObjectKind.KEY)
            out += TreeRowInfo(
                key = "$groupKey:o:${obj.name}",
                kind = TreeRowKind.DB_OBJECT,
                depth = rowDepth,
                name = node.segment,
                profile = conn,
                schema = schema,
                dbObject = obj,
            )
            return
        }
        val nsKey = "$groupKey:n:${node.path}"
        val expanded = nsKey in expandedKeyNamespaceKeys
        out += TreeRowInfo(
            key = nsKey,
            kind = TreeRowKind.KEY_NAMESPACE,
            depth = rowDepth,
            name = node.segment,
            profile = conn,
            schema = schema,
            expanded = expanded,
            childCount = node.descendantKeyCount(),
        )
        if (!expanded) return
        // 该前缀本身也是真实 key：作为叶子排在 children 之前
        node.key?.let { meta ->
            out += TreeRowInfo(
                key = "$groupKey:o:${meta.name}",
                kind = TreeRowKind.DB_OBJECT,
                depth = rowDepth + 1,
                name = node.segment,
                profile = conn,
                schema = schema,
                dbObject = meta,
            )
        }
        node.children.forEach { emit(it, rowDepth + 1) }
    }
    buildKeyTree(items, separator).forEach { emit(it, depth + 1) }
}

private fun loadingPlaceholder(depth: Int, key: String, text: String) = TreeRowInfo(
    key = key, kind = TreeRowKind.PLACEHOLDER, depth = depth, name = text,
    placeholderKind = PlaceholderKind.LOADING,
)

private fun infoPlaceholder(depth: Int, key: String, text: String) = TreeRowInfo(
    key = key, kind = TreeRowKind.PLACEHOLDER, depth = depth, name = text,
    placeholderKind = PlaceholderKind.INFO,
)

// ---------------- 搜索模式 ----------------

/** 大小写不敏感子串命中（query 已 trim）。 */
private fun nameHas(name: String, query: String): Boolean = name.contains(query, ignoreCase = true)

/** 对象是否自身命中（名称；触发器也只看触发器名，不看所属表，保证高亮与命中一致）。 */
private fun objectHas(obj: DbObjectMeta, query: String): Boolean = nameHas(obj.name, query)

/**
 * 行名称是否自身命中搜索——供结果计数/跳转/高亮使用。
 * 必须与 [buildTreeRowsSearch] 的「自身命中」判定保持一致：
 * 仅因后代命中而被保留的祖先行（如库行）不算命中。
 */
internal fun treeRowSelfMatches(row: TreeRowInfo, query: String): Boolean = when (row.kind) {
    TreeRowKind.FOLDER, TreeRowKind.CONNECTION, TreeRowKind.SCHEMA -> nameHas(row.name, query)
    TreeRowKind.DB_OBJECT -> row.dbObject?.let { objectHas(it, query) } == true
    else -> false
}

/** 某 schema 下命中的对象（按组）；已连接取实时、未连接取本地缓存。未加载组无正文，不参与搜索。 */
private fun matchingObjects(
    runtime: ConnectionRuntimeView,
    profileId: String,
    schema: SchemaMeta,
    query: String,
): Map<ObjectKind, List<DbObjectMeta>> {
    val objects = runtime.searchObjectsOf(profileId, schema.key) ?: return emptyMap()
    val out = linkedMapOf<ObjectKind, List<DbObjectMeta>>()
    objects.objects.forEach { (kind, list) ->
        val hit = list.filter { objectHas(it, query) }
        if (hit.isNotEmpty()) out[kind] = hit
    }
    return out
}

/** 该连接是否有任何命中（自身名 / 库名 / 对象名）——文件夹可见性判定用。离线走缓存。 */
private fun connectionHasMatch(
    runtime: ConnectionRuntimeView,
    conn: ConnectionProfile,
    query: String,
): Boolean {
    if (nameHas(conn.name, query)) return true
    if (runtime.flatNamespaceOf(conn.id)) return false
    return runtime.searchSchemasOf(conn.id).orEmpty().any { s ->
        nameHas(s.displayName, query) || matchingObjects(runtime, conn.id, s, query).isNotEmpty()
    }
}

/**
 * 搜索模式扁平行：只保留自身命中的节点与承载它们的祖先链，全部强制展开。
 * 容器行（文件夹/连接/库/组）在搜索模式下不可折叠（`expandable = false`），
 * 避免“点了箭头但命中内容不移除”的错觉；表/视图双击预览仍然可用。
 *
 * 「命名空间即过滤器」后端（Redis）的键由过滤条走服务端 `SCAN MATCH`，这里只按连接名命中。
 */
private fun buildTreeRowsSearch(
    folders: List<FolderRow>,
    connections: List<ConnectionProfile>,
    runtime: ConnectionRuntimeView,
    query: String,
    scopeProfileId: String?,
    expandedKeyNamespaceKeys: Set<String>,
): List<TreeRowInfo> {
    val out = mutableListOf<TreeRowInfo>()
    val byFolder = connections.groupBy { it.folderId }
    fun inScope(id: String): Boolean = scopeProfileId == null || scopeProfileId == id

    fun appendConnection(conn: ConnectionProfile, depth: Int) {
        val schemas = runtime.searchSchemasOf(conn.id).orEmpty()
        val self = nameHas(conn.name, query)
        if (runtime.flatNamespaceOf(conn.id)) {
            if (!self) return
            out += searchConnectionRow(conn, depth, expanded = false, childCount = 0, runtime = runtime)
            appendFlatNamespace(out, conn, schemas, depth + 1, emptySet(), expandedKeyNamespaceKeys, runtime)
            return
        }
        val schemaHits = schemas.mapNotNull { s ->
            val hit = matchingObjects(runtime, conn.id, s, query)
            if (hit.isEmpty() && !nameHas(s.displayName, query)) null else s to hit
        }
        if (!self && schemaHits.isEmpty()) return
        out += searchConnectionRow(
            conn, depth,
            expanded = schemaHits.isNotEmpty(), childCount = schemaHits.size, runtime = runtime,
        )
        val live = runtime.statusOf(conn.id) == ConnUiStatus.CONNECTED
        schemaHits.forEach { (schema, hit) ->
            val rowKey = schemaRowKey(conn.id, schema)
            val known = runtime.objectGroupsOf(conn.id)
            // 组顺序：先按后端标准顺序，再补缓存里存在但当前会话不认识的后端专属组（如离线 ES 的 INDEX/ALIAS）
            val groups = known.filter { hit.containsKey(it) } + hit.keys.filterNot { it in known }
            out += TreeRowInfo(
                key = rowKey,
                kind = TreeRowKind.SCHEMA,
                depth = depth + 1,
                name = schema.displayName,
                profile = conn,
                schema = schema,
                expanded = groups.isNotEmpty(),
                childCount = groups.sumOf { hit[it]?.size ?: 0 },
                expandable = false,
            )
            groups.forEach { kind ->
                val objs = hit[kind].orEmpty()
                val groupKey = "$rowKey:g:${kind.name}"
                out += TreeRowInfo(
                    key = groupKey,
                    kind = TreeRowKind.OBJECT_GROUP,
                    depth = depth + 2,
                    name = kind.displayNoun,
                    profile = conn,
                    schema = schema,
                    groupKind = kind,
                    expanded = true,
                    childCount = objs.size,
                    expandable = false,
                )
                objs.forEach { obj ->
                    out += TreeRowInfo(
                        key = "$groupKey:o:${obj.name}",
                        kind = TreeRowKind.DB_OBJECT,
                        depth = depth + 3,
                        name = obj.name,
                        profile = conn,
                        schema = schema,
                        dbObject = obj,
                    )
                }
            }
            // 离线数据源：缓存里的懒加载组只有计数没正文，提示还有多少对象需要连接后才能搜
            if (!live) {
                val cached = runtime.searchObjectsOf(conn.id, schema.key)
                if (cached != null) {
                    val pending = (cached.objects.keys + cached.counts.keys).distinct()
                        .filter { !cached.isLoaded(it) && cached.countOf(it) > 0 }
                    if (pending.isNotEmpty()) {
                        val n = pending.sumOf { cached.countOf(it) }
                        out += infoPlaceholder(depth + 2, "$rowKey:pending", "另有 $n 个对象未缓存（连接后可搜索）")
                    }
                }
            }
        }
    }

    folders.sortedBy { it.sortOrder }.forEach { folder ->
        val children = byFolder[folder.id].orEmpty().sortedBy { it.sortOrder }
            .filter { inScope(it.id) && connectionHasMatch(runtime, it, query) }
        if (!nameHas(folder.name, query) && children.isEmpty()) return@forEach
        out += TreeRowInfo(
            key = "f:${folder.id}",
            kind = TreeRowKind.FOLDER,
            depth = 0,
            name = folder.name,
            folderId = folder.id,
            expanded = children.isNotEmpty(),
            childCount = children.size,
            expandable = false,
        )
        children.forEach { appendConnection(it, 1) }
    }
    byFolder[null].orEmpty().sortedBy { it.sortOrder }
        .filter { inScope(it.id) && connectionHasMatch(runtime, it, query) }
        .forEach { appendConnection(it, 0) }
    return out
}

private fun searchConnectionRow(
    conn: ConnectionProfile,
    depth: Int,
    expanded: Boolean,
    childCount: Int,
    runtime: ConnectionRuntimeView,
): TreeRowInfo = TreeRowInfo(
    key = connectionRowKey(conn.id),
    kind = TreeRowKind.CONNECTION,
    depth = depth,
    name = conn.name,
    folderId = conn.folderId,
    profile = conn,
    expanded = expanded,
    childCount = childCount,
    connStatus = runtime.statusOf(conn.id),
    message = runtime.statusMessageOf(conn.id),
    expandable = false,
)
