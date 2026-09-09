package app.core

import jdbc.QueryExecutor
import jdbc.QueryResult
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.sql.Connection

/**
 * 结果集导出 CSV（UTF-8）。规则：header=列名；NULL → 空串；
 * 含逗号/引号/换行/回车 → 双引号包裹并转义内部引号。不依赖三方库。
 */
object CsvExport {

    fun toCsv(result: QueryResult): String {
        val sb = StringBuilder(result.rows.size * 64)
        sb.append(result.columns.joinToString(",") { escape(it.name) }).append('\n')
        result.rows.forEach { row ->
            sb.append(row.joinToString(",") { escape(it) }).append('\n')
        }
        return sb.toString()
    }

    private fun escape(v: String?): String {
        val s = v ?: return ""
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s
    }

    /** 写文件；父目录不存在自动创建。 */
    fun write(file: File, result: QueryResult) {
        file.parentFile?.mkdirs()
        BufferedWriter(FileWriter(file, Charsets.UTF_8)).use { w ->
            w.write(toCsv(result))
        }
    }

    /**
     * 全量导出：重跑 [sql] 并把全部结果行流式写入 CSV，不受 QueryExecutor.MAX_ROWS 限制。
     * 阻塞调用，需在 IO 线程执行；返回导出行数（不含表头）。
     * 复用 QueryExecutor.cellToString 保证与结果表相同的单元格转换。
     */
    fun exportAll(file: File, conn: Connection, sql: String): Long {
        file.parentFile?.mkdirs()
        conn.createStatement().use { st ->
            // 全量导出不做 30s 截断（用户明确要全量）
            runCatching { st.queryTimeout = 0 }
            st.executeQuery(sql).use { rs ->
                val meta = rs.metaData
                val count = meta.columnCount
                val header = (1..count).joinToString(",") {
                    escape(meta.getColumnLabel(it).ifBlank { meta.getColumnName(it) })
                }
                var written = 0L
                BufferedWriter(FileWriter(file, Charsets.UTF_8)).use { w ->
                    w.write(header)
                    w.newLine()
                    val sb = StringBuilder(256)
                    while (rs.next()) {
                        sb.setLength(0)
                        for (i in 1..count) {
                            if (i > 1) sb.append(',')
                            sb.append(escape(QueryExecutor.cellToString(rs.getObject(i))))
                        }
                        w.write(sb.toString())
                        w.newLine()
                        written++
                    }
                    w.flush()
                }
                return written
            }
        }
    }
}
