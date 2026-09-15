package app.core.export

import engine.model.QueryColumn
import java.io.Writer

/**
 * JSON 流式写出：`[ {..}, {..} ]`，逐行增量写，不构造整棵对象树。
 * 数值/布尔按列类型输出裸字面量，其余为字符串；重名列自动加 `#2` 后缀避免键覆盖。
 */
class JsonSink(private val out: Writer, private val pretty: Boolean = true) : RowSink {

    private var columns: List<QueryColumn> = emptyList()
    private var keys: List<String> = emptyList()
    private var count = 0L

    override fun begin(columns: List<QueryColumn>) {
        this.columns = columns
        this.keys = disambiguate(columns.map { it.name })
        out.write("[")
    }

    override fun row(values: List<String?>) {
        if (count > 0) out.write(",")
        if (pretty) out.write("\n  ")
        out.write("{")
        for (i in columns.indices) {
            if (i > 0) out.write(", ")
            out.write(ExportText.jsonString(keys[i]))
            out.write(": ")
            out.write(ExportText.jsonValue(values.getOrNull(i), columns[i].sqlType))
        }
        out.write("}")
        count++
    }

    override fun finish(): Long {
        if (count > 0 && pretty) out.write("\n")
        out.write("]\n")
        out.flush()
        return count
    }

    override fun close() {
        runCatching { out.close() }
    }

    private fun disambiguate(names: List<String>): List<String> {
        val seen = mutableMapOf<String, Int>()
        return names.map { raw ->
            val name = raw.ifBlank { "column" }
            val n = (seen[name] ?: 0) + 1
            seen[name] = n
            if (n == 1) name else "$name#$n"
        }
    }
}
