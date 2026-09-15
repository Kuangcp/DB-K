package jdbc

import db.DbType
import engine.model.ColumnMeta
import engine.model.SchemaMeta
import org.tinylog.Logger
import java.sql.Connection

/**
 * Oracle：`schema == 用户`，可用 `ALTER SESSION SET CURRENT_SCHEMA` 切换执行作用域。
 * 驱动 `ojdbc` 受 license 限制不在内置 classpath，由 [ExternalDrivers] 从 `<dataDir>/drivers` 加载。
 *
 * 方言差异：
 * - 标识符默认双引号（大写语义），继承 [DbDialect] 默认实现；
 * - 无 `LIMIT`，预览用 `FETCH FIRST n ROWS ONLY`（12c+）；
 * - schema = 用户（`all_users` 过滤 Oracle 维护账号），`ALL_TAB_COLUMNS` 探测列；
 * - DDL 优先 `DBMS_METADATA.GET_DDL`，失败回落列元数据重建。
 */
object OracleDialect : GenericDialect(DbType.ORACLE, "oracle.jdbc.OracleDriver") {

    /** Oracle 只有 row prefetch（无对外 REF CURSOR），按 fetchSize 批量预取。 */
    override val cursorStrategy: CursorStrategy get() = CursorStrategy.PREFETCH

    /** 12c+ 的 `ORACLE_MAINTAINED` 可干净过滤系统账号；旧库回报错后回落全量 + 名称过滤。 */
    private val usersSql = "SELECT username FROM all_users WHERE oracle_maintained = 'N' ORDER BY username"
    private val allUsersSql = "SELECT username FROM all_users ORDER BY username"

    private val columnsSql = """
        SELECT column_name, data_type, nullable, column_id
        FROM all_tab_columns
        WHERE owner = ? AND table_name = ?
        ORDER BY column_id
    """.trimIndent()

    override fun loadSchemas(conn: Connection): List<SchemaMeta> {
        val users = runCatching { queryFirstStrings(conn, usersSql) }
            .recoverCatching { queryFirstStrings(conn, allUsersSql) }
            .onFailure { Logger.warn(it, "oracle loadSchemas failed") }
            .getOrDefault(emptyList())
            .filter { !it.isSystemSchemaName() }
        if (users.isNotEmpty()) return users.map { SchemaMeta(catalog = null, schema = it) }
        return super.loadSchemas(conn)
    }

    override fun sessionContextSql(schema: SchemaMeta): String? =
        schema.schema?.let { "ALTER SESSION SET CURRENT_SCHEMA = ${quoteIdent(it)}" }

    override fun loadColumns(conn: Connection, schema: SchemaMeta?, table: String): List<ColumnMeta> {
        val owner = schema?.schema
        if (owner != null) {
            val cols = runCatching {
                conn.prepareStatement(columnsSql).use { ps ->
                    ps.setString(1, owner)
                    ps.setString(2, table)
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                val name = rs.getString(1) ?: continue
                                add(
                                    ColumnMeta(
                                        name = name,
                                        typeName = rs.getString(2),
                                        nullable = rs.getString(3)?.equals("Y", ignoreCase = true) ?: true,
                                        ordinal = runCatching { rs.getInt(4) }.getOrDefault(0),
                                    ),
                                )
                            }
                        }
                    }
                }
            }.onFailure { Logger.warn(it, "oracle loadColumns failed {}.{}", owner, table) }
                .getOrDefault(emptyList())
            if (cols.isNotEmpty()) return markPrimaryKeys(conn, schema, table, cols)
        }
        return queryTableColumns(conn, schema, table)
    }

    override fun tableDdl(conn: Connection, schema: SchemaMeta?, name: String): String? {
        val owner = schema?.schema
        if (owner != null) {
            for (type in listOf("TABLE", "VIEW")) {
                val ddl = runCatching {
                    conn.prepareStatement("SELECT DBMS_METADATA.GET_DDL(?, ?, ?) FROM dual").use { ps ->
                        ps.setString(1, type)
                        ps.setString(2, name)
                        ps.setString(3, owner)
                        ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                    }
                }.onFailure { Logger.warn(it, "oracle tableDdl {} failed for {}.{}", type, owner, name) }
                    .getOrNull()
                if (!ddl.isNullOrBlank()) return ddl
            }
        }
        return super.tableDdl(conn, schema, name)
    }

    override fun previewSelect(schema: SchemaMeta?, name: String): String {
        val prefix = schema?.schema?.let { quoteIdent(it) + "." } ?: ""
        return "SELECT * FROM $prefix${quoteIdent(name)} FETCH FIRST 100 ROWS ONLY"
    }

    /** Oracle 12c+ 的 OFFSET/FETCH 分页（无需 ORDER BY）。 */
    override fun paginate(baseSql: String, offset: Long, limit: Int): String =
        "$baseSql OFFSET $offset ROWS FETCH NEXT $limit ROWS ONLY"

    private fun queryFirstStrings(conn: Connection, sql: String): List<String> =
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                buildList { while (rs.next()) rs.getString(1)?.let { add(it) } }
            }
        }
}
