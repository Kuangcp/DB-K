package app.core.export

import engine.model.QueryColumn
import java.io.Writer

/** CSV 流式写出（UTF-8）：表头 + 每行，规则与既有 `CsvExport` 一致。 */
class CsvSink(private val out: Writer) : RowSink {

    private var count = 0L

    override fun begin(columns: List<QueryColumn>) {
        out.write(columns.joinToString(",") { ExportText.csvField(it.name) })
        out.write("\n")
    }

    override fun row(values: List<String?>) {
        out.write(values.joinToString(",") { ExportText.csvField(it) })
        out.write("\n")
        count++
    }

    override fun finish(): Long {
        out.flush()
        return count
    }

    override fun close() {
        runCatching { out.close() }
    }
}
