package jdbc

import engine.model.QueryColumn
import engine.model.QueryResult
import org.tinylog.Logger
import java.sql.Connection
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Statement
import java.sql.Types

/** SQL 执行引擎（jdbc 层，不依赖 compose）：单语句执行，结果集最大 MAX_ROWS 行。 */
object QueryExecutor {

    const val MAX_ROWS = 1000
    private const val QUERY_TIMEOUT_SECONDS = 30

    /** 可产生结果集的语句首关键字。 */
    private val QUERY_HEADS = setOf("select", "with", "show", "explain", "desc", "describe", "pragma", "table", "values")

    /** 首个标识符（关键字）词。 */
    private val LEADING_KEYWORD = Regex("^[A-Za-z]+")

    /**
     * 判断一条 SQL 是否产生结果集（决定走 executeQuery 还是 executeUpdate）。
     * 取关键字时必须按“任意空白”切词——SQL 常写成 `SELECT\n    a, b`，若按空格切会得到
     * `"select\n"` 而误判为非查询（ClickHouse 等驱动对 SELECT 走 executeUpdate 不报错，
     * 只返回 0，表现为“语句执行成功（非查询，未产生结果集）”）。
     * 另外跳过前导注释与左括号（如 `-- 注释\nselect`、`(select 1) union all …`）。
     */
    fun isQueryLike(sql: String): Boolean {
        var s = sql.trimStart()
        var stripped = true
        while (stripped) {
            stripped = false
            when {
                s.startsWith("--") -> { s = s.substringAfter('\n', "").trimStart(); stripped = true }
                s.startsWith("/*") -> { s = s.substringAfter("*/", "").trimStart(); stripped = true }
                s.startsWith("(") -> { s = s.substring(1).trimStart(); stripped = true }
            }
        }
        val head = LEADING_KEYWORD.find(s)?.value?.lowercase() ?: return false
        return head in QUERY_HEADS
    }

    /**
     * 执行单条 SQL。阻塞调用；调用方负责放到工作线程、保证连接可用。
     * 结果集在连接关闭前整体读出（上限 MAX_ROWS），随后释放语句。
     * [registerStatement]：可选——语句建立后回调一次、finally 中再回调 null，
     * 供 LiveConnection 登记当前执行语句以支持外部 cancel（Statement.cancel）。
     */
    fun execute(conn: Connection, sql: String, registerStatement: ((Statement?) -> Unit)? = null): QueryResult {
        val started = System.currentTimeMillis()
        val stmt = conn.createStatement()
        try {
            stmt.queryTimeout = QUERY_TIMEOUT_SECONDS
            registerStatement?.invoke(stmt)
            return if (isQueryLike(sql)) {
                stmt.executeQuery(sql).use { rs -> readResultSet(sql, rs, started) }
            } else {
                val affected = stmt.executeUpdate(sql)
                QueryResult(sql, emptyList(), emptyList(), affectedRows = affected, durationMs = System.currentTimeMillis() - started)
            }
        } finally {
            registerStatement?.invoke(null)
            runCatching { stmt.close() }
        }
    }

    /**
     * 执行会话上下文前导（目标库/schema 切换，如 USE `db` / SET search_path TO …）；
     * null/空白 = 无需切换。失败会抛异常（由调用方按执行错误展示）。
     */
    fun applyContext(conn: Connection, contextSql: String?) {
        if (contextSql.isNullOrBlank()) return
        conn.createStatement().use { st ->
            st.queryTimeout = QUERY_TIMEOUT_SECONDS
            st.execute(contextSql)
        }
    }

    private fun readResultSet(sql: String, rs: ResultSet, started: Long): QueryResult {
        val meta = rs.metaData
        val count = meta.columnCount
        val columns = (1..count).map { readColumnMeta(meta, it) }
        val rows = ArrayList<List<String?>>(minOf(MAX_ROWS, 256))
        var truncated = false
        while (rs.next()) {
            if (rows.size >= MAX_ROWS) {
                truncated = true
                Logger.info("query truncated at {} rows: {}", MAX_ROWS, sql.substringBefore('\n').take(80))
                break
            }
            val row = ArrayList<String?>(count)
            for (i in 1..count) {
                row.add(readCell(rs, i))
            }
            rows.add(row)
        }
        return QueryResult(sql, columns, rows, durationMs = System.currentTimeMillis() - started, truncated = truncated)
    }

    private fun readCell(rs: ResultSet, i: Int): String? = cellToString(rs.getObject(i))

    /**
     * 读单列元数据；各取值独立 runCatching，个别驱动不支持的 API 不影响整体（降级为不可编辑）。
     * 注意：部分驱动对表达式列的 `getTableName`/`getColumnName` 可能给出别名而非空——
     * 编辑判定在 app 层还会用 `ColumnCatalog` 的已知列名二次校验，双保险。
     */
    private fun readColumnMeta(meta: ResultSetMetaData, i: Int): QueryColumn {
        val label = runCatching { meta.getColumnLabel(i) }.getOrNull().orEmpty()
        val baseName = runCatching { meta.getColumnName(i) }.getOrNull().orEmpty()
        fun textOf(read: (Int) -> String): String? =
            runCatching { read(i) }.getOrNull()?.takeIf { it.isNotBlank() }
        val tableName = textOf { meta.getTableName(it) }
        // SQLite(xerial) 的 getCatalogName 返回的是表名（非 catalog），与表名相同则视为无 catalog，
        // 否则会给 UPDATE 拼出 `"users"."users"` 这种错误限定名。
        val catalogName = textOf { meta.getCatalogName(it) }
        return QueryColumn(
            name = label.ifBlank { baseName },
            catalog = catalogName?.takeUnless { it.equals(tableName, ignoreCase = true) },
            schema = textOf { meta.getSchemaName(it) },
            table = tableName,
            baseColumn = baseName.takeIf { it.isNotBlank() },
            sqlType = runCatching { meta.getColumnType(i) }.getOrDefault(Types.OTHER),
            nullable = runCatching { meta.isNullable(i) != ResultSetMetaData.columnNoNulls }.getOrDefault(true),
            autoIncrement = runCatching { meta.isAutoIncrement(i) }.getOrDefault(false),
            readOnly = runCatching { meta.isReadOnly(i) }.getOrDefault(false),
        )
    }

    /** 单元格值 → 展示/导出字符串（与 CSV 全量导出共用同一转换）。 */
    fun cellToString(v: Any?): String? = when (v) {
        null -> null
        is ByteArray -> "[${v.size} bytes]"
        is Boolean -> if (v) "true" else "false"
        else -> v.toString()
    }
}
