package engine.model

import kotlinx.serialization.Serializable
import i18n.Str

/**
 * 树中“库”节点：JDBC 中 catalog/schema 语义因库而异（PG 多 schema 单库、
 * MySQL 多 catalog、SQLite 伪 schema main、H2 多 schema），此处合一表示。
 */
@Serializable
data class SchemaMeta(
    val catalog: String?,
    val schema: String?,
) {
    /** 展示名：优先 schema；SQLite 显示 main。 */
    val displayName: String get() = schema ?: catalog ?: "default"

    /** 唯一键（含 null 区隔），供 UI 展开集合与对象缓存使用。 */
    val key: String get() = (catalog ?: "") + "\u0000" + (schema ?: "")
}

/**
 * 对象类型（对象组粒度）。各库只产出自己支持的类型：
 * 通用库 = 表/视图/触发器；PG 细分出物化视图/序列/例程等（见 PostgresDialect）。
 */
@Serializable
enum class ObjectKind {
    TABLE, VIEW, TRIGGER, MATERIALIZED_VIEW, SEQUENCE,
    ROUTINE, AGGREGATE, OPERATOR, TYPE, OPERATOR_CLASS, OPERATOR_FAMILY,
    /** 非 SQL 后端（如 Redis）的通用键对象。 */
    KEY,
    /** Elasticsearch 索引。 */
    INDEX,
    /** Elasticsearch 别名。 */
    ALIAS,
}

/** SQL 后端的对象组顺序（表 / 视图 / …）；非 SQL 后端自行提供 [engine.DataSourceSession.objectGroups]。 */
val SQL_OBJECT_KINDS: List<ObjectKind> = listOf(
    ObjectKind.TABLE, ObjectKind.MATERIALIZED_VIEW, ObjectKind.VIEW, ObjectKind.TRIGGER,
    ObjectKind.SEQUENCE, ObjectKind.ROUTINE, ObjectKind.AGGREGATE, ObjectKind.OPERATOR,
    ObjectKind.TYPE, ObjectKind.OPERATOR_CLASS, ObjectKind.OPERATOR_FAMILY,
)

/** 双击/菜单可预览的对象类型：SQL = 表/视图/物化视图；非 SQL = Redis 键（[ObjectKind.KEY]）、ES 索引/别名。 */
val PREVIEWABLE_KINDS = setOf(
    ObjectKind.TABLE, ObjectKind.VIEW, ObjectKind.MATERIALIZED_VIEW, ObjectKind.KEY,
    ObjectKind.INDEX, ObjectKind.ALIAS,
)

fun ObjectKind.isPreviewable(): Boolean = this in PREVIEWABLE_KINDS

/** 对象单数名词 key（“复制 XX 名”/DDL 标题等短语用，由 UI 层翻译）。 */
val ObjectKind.nounKey: Str
    get() = when (this) {
        ObjectKind.TABLE -> Str.NounTable
        ObjectKind.VIEW -> Str.NounView
        ObjectKind.MATERIALIZED_VIEW -> Str.NounMaterializedView
        ObjectKind.TRIGGER -> Str.NounTrigger
        ObjectKind.SEQUENCE -> Str.NounSequence
        ObjectKind.ROUTINE -> Str.NounRoutine
        ObjectKind.AGGREGATE -> Str.NounAggregate
        ObjectKind.OPERATOR -> Str.NounOperator
        ObjectKind.TYPE -> Str.NounType
        ObjectKind.OPERATOR_CLASS -> Str.NounOperatorClass
        ObjectKind.OPERATOR_FAMILY -> Str.NounOperatorFamily
        ObjectKind.KEY -> Str.NounKey
        ObjectKind.INDEX -> Str.NounIndex
        ObjectKind.ALIAS -> Str.NounAlias
    }

/** 对象组标题 key（树分组行；英文用复数）。 */
val ObjectKind.groupKey: Str
    get() = when (this) {
        ObjectKind.TABLE -> Str.GroupTable
        ObjectKind.VIEW -> Str.GroupView
        ObjectKind.MATERIALIZED_VIEW -> Str.GroupMaterializedView
        ObjectKind.TRIGGER -> Str.GroupTrigger
        ObjectKind.SEQUENCE -> Str.GroupSequence
        ObjectKind.ROUTINE -> Str.GroupRoutine
        ObjectKind.AGGREGATE -> Str.GroupAggregate
        ObjectKind.OPERATOR -> Str.GroupOperator
        ObjectKind.TYPE -> Str.GroupType
        ObjectKind.OPERATOR_CLASS -> Str.GroupOperatorClass
        ObjectKind.OPERATOR_FAMILY -> Str.GroupOperatorFamily
        ObjectKind.KEY -> Str.GroupKey
        ObjectKind.INDEX -> Str.GroupIndex
        ObjectKind.ALIAS -> Str.GroupAlias
    }

/**
 * 单列元数据（编辑器列补全 / 结果编辑定位用）。
 * [typeName] 为驱动给出的类型名，可能为 null；[ordinal] 用于稳定排序。
 * [primaryKey]：该列属于行定位键（首选主键；无主键时回落到最少的唯一索引）。
 * 结果单元格编辑时用作 WHERE 定位；取不到键信息时为 false（则该结果只读）。
 */
@Serializable
data class ColumnMeta(
    val name: String,
    val typeName: String? = null,
    val nullable: Boolean = true,
    val ordinal: Int = 0,
    val primaryKey: Boolean = false,
)

/** 表 / 视图 / 序列……对象节点。TRIGGER 时 tableName 为其所属表名。 */
@Serializable
data class DbObjectMeta(
    val name: String,
    val kind: ObjectKind,
    /** TRIGGER 时其所属表名。 */
    val tableName: String? = null,
    /** 后端自定义提示（如 Redis key 类型），供预览/展示用。 */
    val detail: String? = null,
    /** 剩余 TTL 秒（Redis 等；null / <0 = 无过期或未知）。 */
    val ttlSeconds: Long? = null,
)

/**
 * 对象搜索条件（非 SQL 后端用；SQL 后端忽略）。
 * @param pattern  glob 模式（Redis `SCAN MATCH`；`*` 为全部）
 * @param type     类型过滤（Redis key 类型；null = 全部）
 * @param cursor   分页游标（null = 首屏；由上次结果的 [ObjectSearchResult.nextCursor] 回传）
 */
data class ObjectSearch(
    val pattern: String = "*",
    val type: String? = null,
    val cursor: String? = null,
)

/** 一次对象搜索的结果（非 SQL 后端分页）。 */
data class ObjectSearchResult(
    val objects: List<DbObjectMeta>,
    /** 继续翻页的游标；null = 已扫完。 */
    val nextCursor: String? = null,
    /** 本次是否把游标范围内全部扫完（`SCAN` 返回 0）。 */
    val finished: Boolean = true,
)

/**
 * 单个 schema 下的对象清单。支持**按组增量加载**（大库优化，见 P6）：
 * - [objects] 仅含已加载正文的组；
 * - [counts] 记录已知组计数（含尚未加载正文的组），供组行徒章与懒加载决策；
 * - [isLoaded] / [countOf] 是树的读取入口：未加载但有计数 → 可展开触发加载。
 * 全量加载的方言 [counts] 可为空，[countOf] 自动回落到 [objects] 实际大小。
 */
@Serializable
data class SchemaObjects(
    val objects: Map<ObjectKind, List<DbObjectMeta>> = emptyMap(),
    /** 组计数（不拉正文）；缺失视为未知，回落已加载组大小。 */
    val counts: Map<ObjectKind, Int> = emptyMap(),
) {
    val tables: List<String> get() = namesOf(ObjectKind.TABLE)
    val views: List<String> get() = namesOf(ObjectKind.VIEW)
    val materializedViews: List<String> get() = namesOf(ObjectKind.MATERIALIZED_VIEW)
    val triggers: List<DbObjectMeta> get() = objects[ObjectKind.TRIGGER].orEmpty()

    val isEmpty: Boolean get() = knownKinds.all { countOf(it) == 0 }

    /** 该 schema 全部对象数量（schema 收起时的徽章数）。 */
    val total: Int get() = knownKinds.sumOf { countOf(it) }

    fun forKind(kind: ObjectKind): List<DbObjectMeta> = objects[kind].orEmpty()

    /** 展示计数：已加载组取实际条数（最可信），否则取 [counts]。 */
    fun countOf(kind: ObjectKind): Int = objects[kind]?.size ?: counts[kind] ?: 0

    /** 权威总数：优先 [counts]（分页加载时正文可能只有一页，但总数仍取自 DBSIZE 等计数）。 */
    fun totalOf(kind: ObjectKind): Int = counts[kind] ?: objects[kind]?.size ?: 0

    /** 该组正文是否已加载（false 且 [countOf] > 0 表示可懒加载）。 */
    fun isLoaded(kind: ObjectKind): Boolean = objects.containsKey(kind)

    private val knownKinds: Set<ObjectKind> get() = objects.keys + counts.keys

    private fun namesOf(kind: ObjectKind): List<String> = objects[kind].orEmpty().map { it.name }

    companion object {
        val EMPTY = SchemaObjects()

        /** 简易构造：表/视图（名字）/触发器 + 可选额外类型组。空列表自动忽略。 */
        fun simple(
            tables: List<String>,
            views: List<String>,
            triggers: List<DbObjectMeta> = emptyList(),
            extra: Map<ObjectKind, List<DbObjectMeta>> = emptyMap(),
        ): SchemaObjects {
            val m = linkedMapOf<ObjectKind, List<DbObjectMeta>>()
            if (tables.isNotEmpty()) {
                m[ObjectKind.TABLE] = tables.map { DbObjectMeta(it, ObjectKind.TABLE) }
            }
            if (views.isNotEmpty()) {
                m[ObjectKind.VIEW] = views.map { DbObjectMeta(it, ObjectKind.VIEW) }
            }
            if (triggers.isNotEmpty()) {
                m[ObjectKind.TRIGGER] = triggers
            }
            m.putAll(extra.filterValues { it.isNotEmpty() })
            return SchemaObjects(m)
        }
    }
}
