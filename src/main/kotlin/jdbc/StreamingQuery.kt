package jdbc

import engine.model.QueryColumn
import org.tinylog.Logger
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement

/**
 * 全量结果**流式**读取（N8 大数据量导出）：不走 [QueryExecutor] 的 1000 行截断，
 * 按 [CursorStrategy] 配置语句后逐行回调，内存占用与结果行数无关。
 *
 * 阻塞调用，必须跑在连接所属的单线程执行器上（经 [LiveConnection.streamQuery] 调用）。
 * 详见 `doc/EXPORT.md` §2 游标支持矩阵。
 */
object StreamingQuery {

    /**
     * 执行 [sql] 并逐行读取。[onMeta] 在首行前回调一次（列元数据），[onRow] 每行回调一次。
     * 返回读取的数据行数（不含表头）。[onRow] 收到的是**复用的可变列表**，调用方需即时消费。
     */
    fun stream(
        conn: Connection,
        sql: String,
        strategy: CursorStrategy,
        fetchSize: Int,
        onMeta: (List<QueryColumn>) -> Unit,
        onRow: (List<String?>) -> Unit,
    ): Long {
        // PostgreSQL 服务端 portal 要求 autoCommit=false；其余策略不改事务状态。
        val useTransaction = strategy == CursorStrategy.TRANSACTION_PORTAL
        val originalAutoCommit = runCatching { conn.autoCommit }.getOrDefault(true)
        if (useTransaction && originalAutoCommit) conn.autoCommit = false
        try {
            // 前向只读：MySQL 流式与多数驱动的服务端游标都要求该组合。
            conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY).use { st ->
                // 全量导出不做 30s 截断（用户明确要全量）
                runCatching { st.queryTimeout = 0 }
                configure(st, strategy, fetchSize)
                st.executeQuery(sql).use { rs ->
                    val columns = QueryExecutor.columnMetas(rs.metaData)
                    onMeta(columns)
                    val count = columns.size
                    var written = 0L
                    val row = ArrayList<String?>(count)
                    while (rs.next()) {
                        row.clear()
                        for (i in 1..count) row.add(QueryExecutor.cellToString(rs.getObject(i)))
                        onRow(row)
                        written++
                    }
                    Logger.info("streamed {} rows ({} cursor): {}", written, strategy, sql.substringBefore('\n').take(80))
                    return written
                }
            }
        } finally {
            if (useTransaction) {
                // 只读导出：回滚事务并恢复原 autoCommit，不留下未结束的事务
                runCatching { conn.rollback() }
                if (originalAutoCommit) runCatching { conn.autoCommit = true }
            }
        }
    }

    private fun configure(st: Statement, strategy: CursorStrategy, fetchSize: Int) {
        runCatching {
            when (strategy) {
                CursorStrategy.NONE,
                CursorStrategy.TRANSACTION_PORTAL,
                CursorStrategy.PREFETCH,
                -> st.fetchSize = fetchSize

                // MySQL / MariaDB 约定：Integer.MIN_VALUE = 逐行流式结果集
                CursorStrategy.MYSQL_STREAM -> st.fetchSize = Integer.MIN_VALUE
            }
        }.onFailure { Logger.warn(it, "setFetchSize failed for {}", strategy) }
    }
}
