package app.core

import app.core.export.CsvSink
import engine.model.QueryResult
import java.io.StringWriter

/**
 * 结果集导出 CSV（UTF-8）。规则：header=列名；NULL → 空串；
 * 含逗号/引号/换行/回车 → 双引号包裹并转义内部引号。
 * 实际写出复用 N8 的流式 [CsvSink]；大结果导出见 `app.core.export.ResultExport`。
 */
object CsvExport {

    fun toCsv(result: QueryResult): String {
        val w = StringWriter()
        CsvSink(w).use { sink ->
            sink.begin(result.columns)
            result.rows.forEach { sink.row(it) }
            sink.finish()
        }
        return w.toString()
    }
}
