package jdbc

import db.DbType
import engine.model.SchemaMeta
import org.tinylog.Logger
import java.sql.Connection

/**
 * SQL Server：单库多 schema（连接 profile 的 database 决定库；schema 为 dbo 等）。
 * 驱动 `mssql-jdbc` 不在内置 classpath，由 [ExternalDrivers] 从 `<dataDir>/drivers` 加载。
 *
 * 方言差异：
 * - 标识符用 `[]`（内部 `]` 翻倍）；
 * - 无 `LIMIT`，预览用 `SELECT TOP n`；
 * - 无会话级 schema 切换（默认 schema 由 `ALTER USER … WITH DEFAULT_SCHEMA` 决定），
 *   因此控制台执行目标不可切换；
 * - 对象/列探测先用通用 JDBC 元数据兜底（树/查询能跑通），后续按需走 sys 视图覆写。
 */
object SqlServerDialect : GenericDialect(DbType.SQLSERVER, "com.microsoft.sqlserver.jdbc.SQLServerDriver") {

    /** adaptive buffering 下 fetchSize 即批大小（doc/EXPORT.md §2）。 */
    override val cursorStrategy: CursorStrategy get() = CursorStrategy.PREFETCH

    private val schemasSql = """
        SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA
        WHERE SCHEMA_NAME NOT IN ('sys','INFORMATION_SCHEMA','guest')
        ORDER BY 1
    """.trimIndent()

    override fun quoteIdent(name: String): String = "[" + name.replace("]", "]]") + "]"

    /** SQL Server 无会话级 schema 切换，控制台不提供执行目标。 */
    override val supportsTargetSwitch: Boolean get() = false

    override fun loadSchemas(conn: Connection): List<SchemaMeta> {
        val userSchemas = runCatching {
            conn.createStatement().use { st ->
                st.executeQuery(schemasSql).use { rs ->
                    buildList { while (rs.next()) rs.getString(1)?.let { add(it) } }
                }
            }
        }.onFailure { Logger.warn(it, "sqlserver loadSchemas failed") }.getOrDefault(emptyList())
            .filter { !it.isSystemSchemaName() }
        if (userSchemas.isNotEmpty()) return userSchemas.map { SchemaMeta(catalog = null, schema = it) }
        return super.loadSchemas(conn)
    }

    override fun previewSelect(schema: SchemaMeta?, name: String): String {
        val prefix = schema?.schema?.let { quoteIdent(it) + "." } ?: ""
        return "SELECT TOP 100 * FROM $prefix${quoteIdent(name)}"
    }

    /** OFFSET/FETCH 要求 ORDER BY；原查询没有时补一个无意义的稳定排序。 */
    override fun paginate(baseSql: String, offset: Long, limit: Int): String {
        val orderBy = if (Regex("(?i)\\border\\s+by\\b").containsMatchIn(baseSql)) "" else " ORDER BY (SELECT NULL)"
        return "$baseSql$orderBy OFFSET $offset ROWS FETCH NEXT $limit ROWS ONLY"
    }
}
