package jdbc

import db.DbType
import jdbc.model.DbObjectMeta
import jdbc.model.ObjectKind
import jdbc.model.SchemaMeta
import jdbc.model.SchemaObjects
import java.sql.Connection

/**
 * SQLite：单 schema（main），对象列表直接查 sqlite_master
 * （JDBC getTables 在 sqlite-jdbc 上行为不稳定）。
 */
object SQLiteDialect : GenericDialect(DbType.SQLITE, "org.sqlite.JDBC") {

    /** 单文件无库/schema 可切。 */
    override val supportsTargetSwitch: Boolean get() = false


    override fun loadSchemas(conn: Connection): List<SchemaMeta> =
        listOf(SchemaMeta(catalog = null, schema = "main"))

    override fun loadObjects(conn: Connection, schema: SchemaMeta): SchemaObjects {
        val tables = mutableListOf<String>()
        val views = mutableListOf<String>()
        val triggers = mutableListOf<DbObjectMeta>()
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT name, type FROM sqlite_master WHERE type IN ('table','view') " +
                    "AND name NOT LIKE 'sqlite_%' ORDER BY name"
            ).use { rs ->
                while (rs.next()) {
                    val name = rs.getString(1) ?: continue
                    when (rs.getString(2)) {
                        "table" -> tables += name
                        "view" -> views += name
                    }
                }
            }
            runCatching {
                st.executeQuery(
                    "SELECT name, tbl_name FROM sqlite_master WHERE type = 'trigger' " +
                        "AND name NOT LIKE 'sqlite_%' ORDER BY name"
                ).use { rs ->
                    while (rs.next()) {
                        val name = rs.getString(1) ?: continue
                        triggers += DbObjectMeta(name, ObjectKind.TRIGGER, rs.getString(2))
                    }
                }
            }
        }
        return SchemaObjects.simple(tables.sorted(), views.sorted(), triggers.sortedBy { it.name })
    }
}
