package app.core.export

import engine.model.QueryColumn
import java.io.Writer

/** 默认标识符引用（ANSI 双引号）；MySQL/MariaDB 由调用方传入反引号实现。 */
fun defaultQuoteIdent(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

/**
 * SQL INSERT 流式写出：每 [batchSize] 行合并成一条多值 INSERT，减少语句数；
 * 值用标准 SQL 单引号转义，NULL / 数值 / 布尔不加引号。
 */
class SqlInsertSink(
    private val out: Writer,
    private val tableName: String,
    private val batchSize: Int = 100,
    private val quoteIdent: (String) -> String = ::defaultQuoteIdent,
) : RowSink {

    private var columns: List<QueryColumn> = emptyList()
    private var pendingInBatch = 0
    private var count = 0L

    override fun begin(columns: List<QueryColumn>) {
        this.columns = columns
    }

    override fun row(values: List<String?>) {
        if (pendingInBatch == 0) {
            if (count > 0) out.write(";\n")
            out.write("INSERT INTO ")
            out.write(tableName)
            out.write(" (")
            out.write(columns.joinToString(", ") { quoteIdent(it.name) })
            out.write(") VALUES\n")
        } else {
            out.write(",\n")
        }
        out.write("  (")
        for (i in columns.indices) {
            if (i > 0) out.write(", ")
            out.write(ExportText.sqlLiteral(values.getOrNull(i), columns[i].sqlType))
        }
        out.write(")")
        count++
        pendingInBatch = if (pendingInBatch + 1 >= batchSize.coerceAtLeast(1)) 0 else pendingInBatch + 1
    }

    override fun finish(): Long {
        if (count > 0) out.write(";\n")
        out.flush()
        return count
    }

    override fun close() {
        runCatching { out.close() }
    }
}
