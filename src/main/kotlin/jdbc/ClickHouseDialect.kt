package jdbc

import db.DbType
import jdbc.model.SchemaMeta
import jdbc.model.SchemaObjects
import java.sql.Connection

/**
 * ClickHouse：数据库 = catalog（形态同 MySQL）。探测走 system 库：
 * system.databases 列库，system.tables 自带 engine 列可区分表/物化视图。
 * 标识符引用用反引号（双引号在默认设置下不是标识符引号）。
 */
object ClickHouseDialect : GenericDialect(DbType.CLICKHOUSE, "com.clickhouse.jdbc.ClickHouseDriver") {

    /** 引擎名（大写）判定为“视图”。 */
    private val viewEngines = setOf("VIEW", "MATERIALIZEDVIEW", "LIVEVIEW", "WINDOWVIEW")

    override fun quoteIdent(name: String): String = "`" + name.replace("`", "``") + "`"

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
}
