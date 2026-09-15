package app.core.export

import engine.model.QueryResult
import jdbc.LiveConnection
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * N8 导出编排：把两种数据来源（内存结果 / 游标流式重跑）接到统一的 [RowSink] 写出器。
 * 全部为阻塞调用，须在 `Dispatchers.IO` 执行；不依赖 compose。
 */
object ResultExport {

    /** 路径 A：写出已在内存中的结果（≤ `QueryExecutor.MAX_ROWS` 行）。返回写入行数。 */
    fun writeCached(
        file: File,
        format: ExportFormat,
        result: QueryResult,
        options: ExportOptions,
        quoteIdent: (String) -> String = ::defaultQuoteIdent,
    ): Long {
        file.parentFile?.mkdirs()
        return openSink(file, format, options, quoteIdent).use { sink ->
            sink.begin(result.columns)
            for (row in result.rows) sink.row(row)
            sink.finish()
        }
    }

    /**
     * 路径 B：重跑 [sql] 并按方言游标流式导出（不受 1000 行限制，内存常量级）。
     * [live] 必须已连接；[sessionContextSql] 非空时先切换会话目标。返回写入行数。
     */
    fun writeStreamed(
        file: File,
        format: ExportFormat,
        live: LiveConnection,
        sql: String,
        sessionContextSql: String?,
        options: ExportOptions,
        quoteIdent: (String) -> String = ::defaultQuoteIdent,
    ): Long {
        file.parentFile?.mkdirs()
        return openSink(file, format, options, quoteIdent).use { sink ->
            live.streamQuery(sql, sessionContextSql, onMeta = sink::begin, onRow = sink::row)
            sink.finish()
        }
    }

    private fun openSink(
        file: File,
        format: ExportFormat,
        options: ExportOptions,
        quoteIdent: (String) -> String,
    ): RowSink = when (format) {
        ExportFormat.EXCEL -> XlsxSink(FileOutputStream(file))
        ExportFormat.CSV -> CsvSink(textWriter(file))
        ExportFormat.JSON -> JsonSink(textWriter(file), pretty = options.prettyJson)
        ExportFormat.SQL_INSERT -> SqlInsertSink(
            out = textWriter(file),
            tableName = options.tableName.ifBlank { "exported_data" },
            batchSize = options.batchSize,
            quoteIdent = quoteIdent,
        )
    }

    private fun textWriter(file: File): BufferedWriter =
        BufferedWriter(OutputStreamWriter(FileOutputStream(file), StandardCharsets.UTF_8))
}
