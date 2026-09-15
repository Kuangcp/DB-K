package jdbc

import db.ConnectionProfile
import db.DbType
import engine.model.DbObjectMeta
import engine.model.ObjectKind
import engine.model.SchemaMeta
import engine.model.SchemaObjects
import java.sql.Connection

/**
 * SQLite：单 schema（main），对象列表直接查 sqlite_master
 * （JDBC getTables 在 sqlite-jdbc 上行为不稳定）。
 */
object SQLiteDialect : GenericDialect(DbType.SQLITE, "org.sqlite.JDBC") {

    /** 本地单文件无网络空闲问题，跳过用前校验。 */
    override fun healthFor(profile: ConnectionProfile): ConnectionHealth? = null

    /** 单文件无库/schema 可切。 */
    override val supportsTargetSwitch: Boolean get() = false


    override fun loadSchemas(conn: Connection): List<SchemaMeta> =
        listOf(SchemaMeta(catalog = null, schema = "main"))

    /** 精确 DDL：sqlite_master.sql 即建表/建视图原始语句（保留用户书写格式）。 */
    override fun tableDdl(conn: Connection, schema: SchemaMeta?, name: String): String? {
        val sql = runCatching {
            conn.prepareStatement(
                "SELECT sql FROM sqlite_master WHERE name = ? AND type IN ('table','view') LIMIT 1"
            ).use { ps ->
                ps.setString(1, name)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }.getOrNull()
        return sql ?: super.tableDdl(conn, schema, name)
    }

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
