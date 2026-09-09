package jdbc

import org.tinylog.Logger
import java.sql.Connection
import java.sql.ResultSet

/** 查询结果：结果集模式（列+行）或更新模式（影响行数）。行内值为字符串化的单元格。 */
data class QueryColumn(val name: String)

data class QueryResult(
    val sql: String,
    val columns: List<QueryColumn>,
    val rows: List<List<String?>>,
    val affectedRows: Int? = null,
    val truncated: Boolean = false,
    val durationMs: Long = 0,
) {
    val isQuery: Boolean get() = affectedRows == null
    val rowCount: Int get() = rows.size
}

/** SQL 执行引擎（jdbc 层，不依赖 compose）：单语句执行，结果集最大 MAX_ROWS 行。 */
object QueryExecutor {

    const val MAX_ROWS = 1000
    private const val QUERY_TIMEOUT_SECONDS = 30

    /** 判断一条 SQL 是否产生结果集（决定走 executeQuery 还是 executeUpdate）。 */
    fun isQueryLike(sql: String): Boolean {
        val first = sql.trimStart().substringBefore(';').trim()
        val head = first.lowercase().substringBefore(' ').substringBefore('(')
        return head in setOf("select", "with", "show", "explain", "desc", "describe", "pragma", "table")
    }

    /**
     * 执行单条 SQL。阻塞调用；调用方负责放到工作线程、保证连接可用。
     * 结果集在连接关闭前整体读出（上限 MAX_ROWS），随后释放语句。
     */
    fun execute(conn: Connection, sql: String): QueryResult {
        val started = System.currentTimeMillis()
        val stmt = conn.createStatement()
        try {
            stmt.queryTimeout = QUERY_TIMEOUT_SECONDS
            return if (isQueryLike(sql)) {
                stmt.executeQuery(sql).use { rs -> readResultSet(sql, rs, started) }
            } else {
                val affected = stmt.executeUpdate(sql)
                QueryResult(sql, emptyList(), emptyList(), affectedRows = affected, durationMs = System.currentTimeMillis() - started)
            }
        } finally {
            runCatching { stmt.close() }
        }
    }

    private fun readResultSet(sql: String, rs: ResultSet, started: Long): QueryResult {
        val meta = rs.metaData
        val count = meta.columnCount
        val columns = (1..count).map { QueryColumn(meta.getColumnLabel(it).ifBlank { meta.getColumnName(it) }) }
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

    private fun readCell(rs: ResultSet, i: Int): String? {
        val v = rs.getObject(i) ?: return null
        return when (v) {
            is ByteArray -> "[${v.size} bytes]"
            is Boolean -> if (v) "true" else "false"
            else -> v.toString()
        }
    }
}
