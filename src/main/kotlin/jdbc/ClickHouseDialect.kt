package jdbc

import db.DbType
import engine.model.ColumnMeta
import engine.model.SchemaMeta
import engine.model.SchemaObjects
import org.tinylog.Logger
import java.sql.Connection

/**
 * ClickHouse：数据库 = catalog（形态同 MySQL）。探测走 system 库：
 * system.databases 列库，system.tables 自带 engine 列可区分表/物化视图。
 * 标识符引用用反引号（双引号在默认设置下不是标识符引号）。
 */
object ClickHouseDialect : GenericDialect(DbType.CLICKHOUSE, "com.clickhouse.jdbc.ClickHouseDriver") {

    /** CH 走 HTTP 响应流，fetchSize 控制批大小。 */
    override val cursorStrategy: CursorStrategy get() = CursorStrategy.PREFETCH

    /** 引擎名（大写）判定为“视图”。 */
    private val viewEngines = setOf("VIEW", "MATERIALIZEDVIEW", "LIVEVIEW", "WINDOWVIEW")

    override fun quoteIdent(name: String): String = "`" + name.replace("`", "``") + "`"

    /** CH 的库即 catalog：USE 切过去。 */
    override fun sessionContextSql(schema: SchemaMeta): String? =
        schema.catalog?.let { "USE ${quoteIdent(it)}" }

    override fun loadSchemas(conn: Connection): List<SchemaMeta> {
        val out = mutableListOf<SchemaMeta>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT name FROM system.databases ORDER BY name").use { rs ->
                while (rs.next()) {
                    rs.getString(1)?.let {
                        if (!it.isSystemSchemaName()) out += SchemaMeta(catalog = it, schema = null)
                    }
                }
            }
        }
        return out
    }

    override fun loadObjects(conn: Connection, schema: SchemaMeta): SchemaObjects {
        val catalog = schema.catalog ?: return SchemaObjects.EMPTY
        val tables = mutableListOf<String>()
        val views = mutableListOf<String>()
        conn.prepareStatement(
            "SELECT name, engine FROM system.tables " +
                "WHERE database = ? AND is_temporary = 0 ORDER BY name"
        ).use { ps ->
            ps.setString(1, catalog)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val name = rs.getString(1) ?: continue
                    val engine = rs.getString(2)?.uppercase().orEmpty()
                    if (engine in viewEngines) views += name else tables += name
                }
            }
        }
        return SchemaObjects.simple(tables.sorted(), views.sorted())
    }

    /** 精确 DDL：SHOW CREATE TABLE（CH 返回单列 statement）。 */
    override fun tableDdl(conn: Connection, schema: SchemaMeta?, name: String): String? {
        val db = schema?.catalog
        val qualified = if (db != null) "${quoteIdent(db)}.${quoteIdent(name)}" else quoteIdent(name)
        val ddl = runCatching {
            conn.createStatement().use { st ->
                st.executeQuery("SHOW CREATE TABLE $qualified").use { rs ->
                    if (rs.next()) rs.getString(1) else null
                }
            }
        }.onFailure { Logger.warn(it, "clickhouse tableDdl failed {}", name) }.getOrNull()
        return ddl ?: super.tableDdl(conn, schema, name)
    }

    /**
     * ClickHouse 列探测走 system.columns（快且稳）；失败/为空回落通用实现。
     * 库优先用 [SchemaMeta.catalog]；`Nullable(...)` 类型据此识别可空。
     */
    override fun loadColumns(conn: Connection, schema: SchemaMeta?, table: String): List<ColumnMeta> {
        val db = schema?.catalog
        val sql = buildString {
            append("SELECT name, type, position FROM system.columns WHERE ")
            if (db != null) append("database = ? AND ")
            append("table = ? ORDER BY position")
        }
        val cols = runCatching {
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                if (db != null) ps.setString(idx++, db)
                ps.setString(idx, table)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<ColumnMeta>()
                    while (rs.next()) {
                        val type = rs.getString(2)
                        out += ColumnMeta(
                            name = rs.getString(1) ?: continue,
                            typeName = type,
                            nullable = type?.startsWith("Nullable", ignoreCase = true) == true,
                            ordinal = rs.getInt(3),
                        )
                    }
                    out
                }
            }
        }.onFailure { Logger.warn(it, "clickhouse loadColumns failed {}", table) }
            .getOrDefault(emptyList())
        return if (cols.isNotEmpty()) markPrimaryKeys(conn, schema, table, cols) else queryTableColumns(conn, schema, table)
    }
}
