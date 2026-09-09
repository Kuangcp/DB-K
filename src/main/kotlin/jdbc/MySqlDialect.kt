package jdbc

import db.DbType
import jdbc.model.DbObjectMeta
import jdbc.model.ObjectKind
import jdbc.model.SchemaMeta
import jdbc.model.SchemaObjects
import java.sql.Connection

/**
 * MySQL / MariaDB 共用的 catalog 语义基类；标识符引用用反引号。
 */
open class MySqlLikeDialect(
    dbType: DbType,
    driverClass: String,
) : GenericDialect(dbType, driverClass) {

    private val systemSchemas = """(
        SELECT 'INFORMATION_SCHEMA' UNION ALL SELECT 'PERFORMANCE_SCHEMA'
        UNION ALL SELECT 'MYSQL' UNION ALL SELECT 'SYS'
    )"""

    override fun quoteIdent(name: String): String = "`" + name.replace("`", "``") + "`"

    override fun loadSchemas(conn: Connection): List<SchemaMeta> {
        val sql = "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA " +
            "WHERE SCHEMA_NAME NOT IN $systemSchemas ORDER BY 1"
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                val out = mutableListOf<SchemaMeta>()
                while (rs.next()) rs.getString(1)?.let { out += SchemaMeta(catalog = it, schema = null) }
                return out
            }
        }
    }

    override fun loadObjects(conn: Connection, schema: SchemaMeta): SchemaObjects {
        val catalog = schema.catalog ?: return SchemaObjects.EMPTY
        val tables = mutableListOf<String>()
        val views = mutableListOf<String>()
        conn.prepareStatement(
            "SELECT TABLE_NAME, TABLE_TYPE FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE IN ('BASE TABLE','VIEW') ORDER BY TABLE_NAME"
        ).use { ps ->
            ps.setString(1, catalog)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val name = rs.getString(1) ?: continue
                    when (rs.getString(2)) {
                        "BASE TABLE" -> tables += name
                        "VIEW" -> views += name
                    }
                }
            }
        }
        val triggers = mutableListOf<DbObjectMeta>()
        runCatching {
            conn.prepareStatement(
                "SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE FROM information_schema.TRIGGERS " +
                    "WHERE TRIGGER_SCHEMA = ? ORDER BY TRIGGER_NAME"
            ).use { ps ->
                ps.setString(1, catalog)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val name = rs.getString(1) ?: continue
                        triggers += DbObjectMeta(name, ObjectKind.TRIGGER, rs.getString(2))
                    }
                }
            }
        }
        return SchemaObjects.simple(tables.sorted(), views.sorted(), triggers.sortedBy { it.name })
    }

    /** MySQL/MariaDB 的库即 catalog：执行前 USE 一下，保证与树里看到的库一致。 */
    override fun sessionContextSql(schema: jdbc.model.SchemaMeta): String? =
        schema.catalog?.let { "USE ${quoteIdent(it)}" }
}

/** MySQL。 */
object MySqlDialect : MySqlLikeDialect(DbType.MYSQL, "com.mysql.cj.jdbc.Driver")

/** MariaDB 与 MySQL 的 catalog 语义一致，只换驱动。 */
object MariaDbDialect : MySqlLikeDialect(DbType.MARIADB, "org.mariadb.jdbc.Driver")
