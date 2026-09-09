package jdbc

import db.ConnectionProfile
import db.DbType
import jdbc.model.DbObjectMeta
import jdbc.model.ObjectKind
import jdbc.model.SchemaMeta
import jdbc.model.SchemaObjects
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * PostgreSQL：单 catalog（当前库）、多 schema。
 * 直接用 pg_catalog 查询，比 JDBC getTables 对分区表/物化视图/触发器更可控。
 */
object PostgresDialect : GenericDialect(DbType.POSTGRES, "org.postgresql.Driver") {

    private val schemasSql = """
        SELECT schema_name FROM information_schema.schemata
        WHERE schema_name NOT LIKE 'pg\_%' AND schema_name <> 'information_schema'
        ORDER BY 1
    """.trimIndent()

    private val objectsSql = """
        SELECT c.relname, c.relkind
        FROM pg_catalog.pg_class c
        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = ? AND c.relkind IN ('r','v','m','p','f')
        ORDER BY c.relname
    """.trimIndent()

    private val triggersSql = """
        SELECT t.tgname, c.relname
        FROM pg_catalog.pg_trigger t
        JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid
        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = ? AND NOT t.tgisinternal
        ORDER BY t.tgname
    """.trimIndent()

    override fun loadSchemas(conn: Connection): List<SchemaMeta> =
        queryStrings(conn, schemasSql) { it.getString(1) }
            .map { SchemaMeta(catalog = null, schema = it) }

    override fun loadObjects(conn: Connection, schema: SchemaMeta): SchemaObjects {
        val tables = mutableListOf<String>()
        val views = mutableListOf<String>()
        val triggers = mutableListOf<DbObjectMeta>()
        val schemaName = schema.schema ?: "public"
        conn.prepareStatement(objectsSql).use { ps ->
            ps.setString(1, schemaName)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val name = rs.getString(1) ?: continue
                    when (rs.getString(2)) {
                        "r", "p", "f" -> tables += name
                        "v", "m" -> views += name
                    }
                }
            }
        }
        runCatching {
            conn.prepareStatement(triggersSql).use { ps ->
                ps.setString(1, schemaName)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val name = rs.getString(1) ?: continue
                        triggers += DbObjectMeta(name, ObjectKind.TRIGGER, rs.getString(2))
                    }
                }
            }
        }
        return SchemaObjects(tables.sorted(), views.sorted(), triggers.sortedBy { it.name })
    }

    private fun queryStrings(conn: Connection, sql: String, extract: (ResultSet) -> String?): List<String> {
        conn.prepareStatement(sql).use { ps ->
            ps.executeQuery().use { rs ->
                val out = mutableListOf<String>()
                while (rs.next()) extract(rs)?.let { out += it }
                return out
            }
        }
    }
}
