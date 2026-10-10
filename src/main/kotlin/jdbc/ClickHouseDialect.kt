package jdbc

import db.ConnectionProfile
import db.DbType
import engine.model.ColumnMeta
import engine.model.DbObjectMeta
import engine.model.ObjectKind
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

    /** CH 驱动对 `isValid` 支持不一，探活显式用 `SELECT 1`。 */
    override fun healthFor(profile: ConnectionProfile): ConnectionHealth =
        ConnectionHealth(validationQuery = "SELECT 1")

    /** CH 走 HTTP 响应流，fetchSize 控制批大小。 */
    override val cursorStrategy: CursorStrategy get() = CursorStrategy.PREFETCH

    /**
     * CH 驱动把 `setMaxRows` 转成服务端 setting `max_result_rows`，默认 `result_overflow_mode=throw`，
     * 超限直接抛异常（不是截断）；改用 `break` 又会连同子查询一起截断（聚合会算错）。
     * 所以不下推，改为在执行前给 plain SELECT 注入 `LIMIT`（[injectsRowLimit]）。
     */
    override val supportsRowLimit: Boolean get() = false

    /** CK 用 `LIMIT` 注入限行（能被查询优化器真正下推，与手写 LIMIT 等效）。 */
    override val injectsRowLimit: Boolean get() = true

    /** 支持对象组懒加载：组展开才拉正文，首次连接只取计数 + 表/视图名。 */
    override val lazyObjectGroups: Boolean get() = true

    /** 引擎名（大写）判定为“视图”。 */
    private val viewEngines = setOf("VIEW", "MATERIALIZEDVIEW", "LIVEVIEW", "WINDOWVIEW")

    override fun quoteIdent(name: String): String = "`" + name.replace("`", "``") + "`"

    /** CH 的库即 catalog：USE 切过去。 */
    override fun sessionContextSql(schema: SchemaMeta): String? =
        schema.catalog?.let { "USE ${quoteIdent(it)}" }

    override fun sessionZoneSql(): String = "SELECT timezone()"

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

    override fun loadObjectCounts(conn: Connection, schema: SchemaMeta): Map<ObjectKind, Int> {
        val catalog = schema.catalog ?: return emptyMap()
        val counts = mutableMapOf<ObjectKind, Int>()
        conn.prepareStatement(
            "SELECT engine, count() AS cnt FROM system.tables " +
                "WHERE database = ? AND is_temporary = 0 GROUP BY engine"
        ).use { ps ->
            ps.setString(1, catalog)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val engine = rs.getString(1)?.uppercase().orEmpty()
                    val cnt = rs.getInt(2)
                    val kind = if (engine in viewEngines) ObjectKind.VIEW else ObjectKind.TABLE
                    counts[kind] = (counts[kind] ?: 0) + cnt
                }
            }
        }
        return counts
    }

    /** 核心组（表/视图名），等价于 [loadObjects] 但显式覆写供 lazyObjectGroups 管线使用。 */
    override fun loadCoreObjects(conn: Connection, schema: SchemaMeta): SchemaObjects =
        loadObjects(conn, schema)

    override fun loadObjectsForKind(conn: Connection, schema: SchemaMeta, kind: ObjectKind): List<DbObjectMeta> {
        val catalog = schema.catalog ?: return emptyList()
        val wantViews = kind == ObjectKind.VIEW
        conn.prepareStatement(
            "SELECT name FROM system.tables " +
                "WHERE database = ? AND is_temporary = 0 ORDER BY name"
        ).use { ps ->
            ps.setString(1, catalog)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<DbObjectMeta>()
                while (rs.next()) {
                    val name = rs.getString(1) ?: continue
                    out += DbObjectMeta(name, kind)
                }
                return out
            }
        }
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
