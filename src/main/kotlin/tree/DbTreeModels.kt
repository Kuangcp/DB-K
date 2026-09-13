package tree

import db.ConnectionProfile
import db.FolderRow
import engine.model.DbObjectMeta
import engine.model.ObjectKind
import engine.model.SchemaMeta
import engine.model.SchemaObjects

/** 左侧树行类别。M2：连接行之下支持 schema / 对象组 / 对象。 */
enum class TreeRowKind {
    FOLDER, CONNECTION, SCHEMA, OBJECT_GROUP, DB_OBJECT, PLACEHOLDER,
}

/** 连接运行状态（供状态点/连接入口展示；树层只做展示判断，不持有连接）。 */
enum class ConnUiStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/** 对象组（schema 下的展示分组）。条目顺序 = 展示顺序；kind 与该组对象类型一一对应。
 * 各库只产出自己支持的类型（Map 里没有的类型整组不显示）。 */
enum class ObjectGroupKind(val label: String, val kind: ObjectKind) {
    TABLES("表", ObjectKind.TABLE),
    MATERIALIZED_VIEWS("物化视图", ObjectKind.MATERIALIZED_VIEW),
    VIEWS("视图", ObjectKind.VIEW),
    TRIGGERS("触发器", ObjectKind.TRIGGER),
    SEQUENCES("序列", ObjectKind.SEQUENCE),
    ROUTINES("函数与过程", ObjectKind.ROUTINE),
    AGGREGATES("聚合", ObjectKind.AGGREGATE),
    OPERATORS("操作符", ObjectKind.OPERATOR),
    TYPES("类型", ObjectKind.TYPE),
    OPERATOR_CLASSES("操作符类", ObjectKind.OPERATOR_CLASS),
    OPERATOR_FAMILIES("操作符族", ObjectKind.OPERATOR_FAMILY),
}

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
    /** OBJECT_GROUP 行。 */
    val groupKind: ObjectGroupKind? = null,
    /** DB_OBJECT 行。 */
    val dbObject: DbObjectMeta? = null,
    /** PLACEHOLDER 行：LOADING 显示 spinner，ERROR 红字，INFO 灰字。 */
    val placeholderKind: PlaceholderKind = PlaceholderKind.NONE,
)

/** 连接运行时只读视图：由 app 层 ConnectionsState 实现（compose 快照状态在其内部被读）。 */
interface ConnectionRuntimeView {
    fun statusOf(profileId: String): ConnUiStatus
    fun statusMessageOf(profileId: String): String?
    fun schemasOf(profileId: String): List<SchemaMeta>?
    fun schemasLoadingOf(profileId: String): Boolean
    fun objectsOf(profileId: String, schemaKey: String): SchemaObjects?
    fun objectsLoadingOf(profileId: String, schemaKey: String): Boolean

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
            children.forEach { conn -> appendConnection(out, conn, 1, expandedConnectionIds, expandedSchemaKeys, expandedGroupKeys, runtime) }
        }
    }
    byFolder[null].orEmpty().sortedBy { it.sortOrder }.forEach { conn ->
        appendConnection(out, conn, 0, expandedConnectionIds, expandedSchemaKeys, expandedGroupKeys, runtime)
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
    runtime: ConnectionRuntimeView,
) {
    val expanded = conn.id in expandedConnectionIds
    val status = runtime.statusOf(conn.id)
    val schemas = runtime.schemasOf(conn.id)
    out += TreeRowInfo(
        key = connectionRowKey(conn.id),
        kind = TreeRowKind.CONNECTION,
        depth = depth,
        name = conn.name,
        folderId = conn.folderId,
        profile = conn,
        expanded = expanded,
        childCount = schemas?.size ?: 0,
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
                else -> schemas.forEach { schema ->
                    appendSchema(out, conn, schema, depth + 1, expandedSchemaKeys, expandedGroupKeys, runtime)
                }
            }
        }
    }
}

private fun appendSchema(
    out: MutableList<TreeRowInfo>,
    conn: ConnectionProfile,
    schema: SchemaMeta,
    depth: Int,
    expandedSchemaKeys: Set<String>,
    expandedGroupKeys: Set<String>,
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
        else -> {
            ObjectGroupKind.entries.forEach { group ->
                val kind = group.kind
                val count = objects.countOf(kind)
                if (count <= 0) return@forEach
                val groupKey = "$rowKey:g:${group.name}"
                val groupExpanded = groupKey in expandedGroupKeys
                out += TreeRowInfo(
                    key = groupKey,
                    kind = TreeRowKind.OBJECT_GROUP,
                    depth = depth + 1,
                    name = group.label,
                    profile = conn,
                    schema = schema,
                    groupKind = group,
                    expanded = groupExpanded,
                    childCount = count,
                )
                // 组折叠：对象行只在组展开时列出（大库例程上千条也不拖垮渲染）
                if (groupExpanded) {
                    if (!objects.isLoaded(kind)) {
                        // P6 懒加载：正文未拉取时给占位；上层在展开时触发 ensureGroupObjects
                        out += if (runtime.groupObjectsLoadingOf(conn.id, schema.key, kind)) {
                            loadingPlaceholder(depth + 2, "$groupKey:load", "正在加载${group.label}…")
                        } else {
                            infoPlaceholder(depth + 2, "$groupKey:load", "尚未加载")
                        }
                    } else {
                        objects.forKind(kind).forEach { obj ->
                            out += TreeRowInfo(
                                key = "$groupKey:o:${obj.name}",
                                kind = TreeRowKind.DB_OBJECT,
                                depth = depth + 2,
                                name = obj.name,
                                profile = conn,
                                schema = schema,
                                dbObject = obj,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun loadingPlaceholder(depth: Int, key: String, text: String) = TreeRowInfo(
    key = key, kind = TreeRowKind.PLACEHOLDER, depth = depth, name = text,
    placeholderKind = PlaceholderKind.LOADING,
)

private fun infoPlaceholder(depth: Int, key: String, text: String) = TreeRowInfo(
    key = key, kind = TreeRowKind.PLACEHOLDER, depth = depth, name = text,
    placeholderKind = PlaceholderKind.INFO,
)
