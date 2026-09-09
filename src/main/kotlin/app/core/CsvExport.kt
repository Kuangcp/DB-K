package app.core

import jdbc.QueryResult
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

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
}
