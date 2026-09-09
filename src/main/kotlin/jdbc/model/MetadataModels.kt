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

/** 表 / 视图（触发器单独归类展示）。 */
enum class ObjectKind { TABLE, VIEW, TRIGGER }

data class DbObjectMeta(
    val name: String,
    val kind: ObjectKind,
    /** TRIGGER 时其所属表名。 */
    val tableName: String? = null,
)

/** 单个 schema 下按展示分组拆好的对象清单。 */
data class SchemaObjects(
    val tables: List<String>,
    val views: List<String>,
    val triggers: List<DbObjectMeta>,
) {
    val isEmpty: Boolean get() = tables.isEmpty() && views.isEmpty() && triggers.isEmpty()

    fun forKind(kind: ObjectKind): List<DbObjectMeta> = when (kind) {
        ObjectKind.TABLE -> tables.map { DbObjectMeta(it, ObjectKind.TABLE) }
        ObjectKind.VIEW -> views.map { DbObjectMeta(it, ObjectKind.VIEW) }
        ObjectKind.TRIGGER -> triggers
    }

    companion object {
        val EMPTY = SchemaObjects(emptyList(), emptyList(), emptyList())
    }
}
