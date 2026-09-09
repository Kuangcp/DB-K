package jdbc.model

/**
 * 树中“库”节点：JDBC 中 catalog/schema 语义因库而异（PG 多 schema 单库、
 * MySQL 多 catalog、SQLite 伪 schema main、H2 多 schema），此处合一表示。
 */
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
enum class ObjectKind {
    TABLE, VIEW, TRIGGER, MATERIALIZED_VIEW, SEQUENCE,
    ROUTINE, AGGREGATE, OPERATOR, TYPE, OPERATOR_CLASS, OPERATOR_FAMILY,
}

/** 双击/菜单可 SQL 预览的对象类型（SELECT 语义成立的：表、视图、物化视图）。 */
val PREVIEWABLE_KINDS = setOf(ObjectKind.TABLE, ObjectKind.VIEW, ObjectKind.MATERIALIZED_VIEW)

fun ObjectKind.isPreviewable(): Boolean = this in PREVIEWABLE_KINDS

/** 对象中文名词（右键“复制 XX 名”文案用）。 */
val ObjectKind.displayNoun: String
    get() = when (this) {
        ObjectKind.TABLE -> "表"
        ObjectKind.VIEW -> "视图"
        ObjectKind.MATERIALIZED_VIEW -> "物化视图"
        ObjectKind.TRIGGER -> "触发器"
        ObjectKind.SEQUENCE -> "序列"
        ObjectKind.ROUTINE -> "函数与过程"
        ObjectKind.AGGREGATE -> "聚合"
        ObjectKind.OPERATOR -> "操作符"
        ObjectKind.TYPE -> "类型"
        ObjectKind.OPERATOR_CLASS -> "操作符类"
        ObjectKind.OPERATOR_FAMILY -> "操作符族"
    }

/** 表 / 视图 / 序列……对象节点。TRIGGER 时 tableName 为其所属表名。 */
data class DbObjectMeta(
    val name: String,
    val kind: ObjectKind,
    /** TRIGGER 时其所属表名。 */
    val tableName: String? = null,
)

/**
 * 单个 schema 下按类型分组拆好的对象清单。
 * 内部以 Map<ObjectKind, List<DbObjectMeta>> 表达（各库只填自己支持的类型），
 * 另提供 tables/views/triggers 便捷读法供预览与补全等既有调用使用。
 */
data class SchemaObjects(
    val objects: Map<ObjectKind, List<DbObjectMeta>> = emptyMap(),
) {
    val tables: List<String> get() = namesOf(ObjectKind.TABLE)
    val views: List<String> get() = namesOf(ObjectKind.VIEW)
    val materializedViews: List<String> get() = namesOf(ObjectKind.MATERIALIZED_VIEW)
    val triggers: List<DbObjectMeta> get() = objects[ObjectKind.TRIGGER].orEmpty()

    val isEmpty: Boolean get() = objects.values.none { it.isNotEmpty() }

    /** 该 schema 全部对象数量（schema 收起时的徽章数）。 */
    val total: Int get() = objects.values.sumOf { it.size }

    fun forKind(kind: ObjectKind): List<DbObjectMeta> = objects[kind].orEmpty()

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
