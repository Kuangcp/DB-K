package jdbc

import db.DbType
import jdbc.model.ColumnMeta
import jdbc.model.DbObjectMeta
import jdbc.model.ObjectKind
import jdbc.model.SchemaMeta
import jdbc.model.SchemaObjects
import org.tinylog.Logger
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

    /**
     * MySQL/MariaDB 列探测走 information_schema.COLUMNS（快且稳定）；失败/为空回落通用实现。
     * 表所属库优先用 [SchemaMeta.catalog]（MySQL 用 catalog 表达库）。
     */
    override fun loadColumns(conn: Connection, schema: SchemaMeta?, table: String): List<ColumnMeta> {
        val db = schema?.catalog ?: schema?.schema
        val sql = buildString {
            append("SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, ORDINAL_POSITION ")
            append("FROM information_schema.COLUMNS WHERE ")
            if (db != null) append("TABLE_SCHEMA = ? AND ")
            append("TABLE_NAME = ? ORDER BY ORDINAL_POSITION")
        }
        val cols = runCatching {
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                if (db != null) ps.setString(idx++, db)
                ps.setString(idx, table)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<ColumnMeta>()
                    while (rs.next()) {
                        out += ColumnMeta(
                            name = rs.getString(1) ?: continue,
                            typeName = rs.getString(2),
                            nullable = rs.getString(3)?.equals("YES", ignoreCase = true) ?: true,
                            ordinal = rs.getInt(4),
                        )
                    }
                    out
                }
            }
        }.onFailure { Logger.warn(it, "mysql loadColumns failed {}", table) }
            .getOrDefault(emptyList())
        return if (cols.isNotEmpty()) markPrimaryKeys(conn, schema, table, cols) else queryTableColumns(conn, schema, table)
    }

    /**
     * 精确 DDL：SHOW CREATE TABLE（表返回 Create Table、视图返回 Create View，取第 2 列）。
     * 权限不足/对象不存在时回落通用重建。
     */
    override fun tableDdl(conn: Connection, schema: SchemaMeta?, name: String): String? {
        val db = schema?.catalog ?: schema?.schema
        val qualified = if (db != null) "${quoteIdent(db)}.${quoteIdent(name)}" else quoteIdent(name)
        val ddl = runCatching {
            conn.createStatement().use { st ->
                st.executeQuery("SHOW CREATE TABLE $qualified").use { rs ->
                    if (rs.next()) rs.getString(2) else null
                }
            }
        }.onFailure { Logger.warn(it, "mysql tableDdl failed {}", name) }.getOrNull()
        return ddl ?: super.tableDdl(conn, schema, name)
    }

    /** MySQL/MariaDB 的库即 catalog：执行前 USE 一下，保证与树里看到的库一致。 */
    override fun sessionContextSql(schema: jdbc.model.SchemaMeta): String? =
        schema.catalog?.let { "USE ${quoteIdent(it)}" }
}

/** MySQL。 */
object MySqlDialect : MySqlLikeDialect(DbType.MYSQL, "com.mysql.cj.jdbc.Driver")

/** MariaDB 与 MySQL 的 catalog 语义一致，只换驱动。 */
object MariaDbDialect : MySqlLikeDialect(DbType.MARIADB, "org.mariadb.jdbc.Driver")
