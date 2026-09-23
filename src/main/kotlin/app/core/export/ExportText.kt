package app.core.export

import java.sql.Types

/**
 * 各导出格式的字段转义 / 类型判定（纯函数，可单测）。
 * 输入是结果网格里已字符串化的单元格值（`null` = SQL NULL）。
 */
object ExportText {

    /** 数值型 JDBC 类型：JSON / SQL INSERT 输出裸数字而非带引号字符串。 */
    private val NUMERIC_TYPES = setOf(
        Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
        Types.FLOAT, Types.REAL, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL,
    )

    fun isNumericType(sqlType: Int): Boolean = sqlType in NUMERIC_TYPES

    fun isBooleanType(sqlType: Int): Boolean = sqlType == Types.BOOLEAN || sqlType == Types.BIT

    /** CSV 字段：含逗号/引号/换行/回车 → 双引号包裹并双写内部引号；null → 空串。 */
    fun csvField(v: String?): String {
        val s = v ?: return ""
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else {
            s
        }
    }

    /**
     * 单行 CSV（含表头行）：列名行 + 数据行，各字段走 [csvField]。
     * 用于结果区右键「复制本行 → CSV」，方便直接粘贴到表格软件。
     */
    fun csvHeaderAndRow(columns: List<String>, row: List<String?>): String =
        columns.joinToString(",") { csvField(it) } + "\n" +
            row.joinToString(",") { csvField(it) }

    /**
     * TSV 字段：NULL → 空串；其余**原样**（B 方案：不做转义/引号包裹）。
     * 值内含 Tab/换行时按原义带出（分享文本友好；粘回表格可能串列，属已知取舍）。
     */
    fun tsvField(v: String?): String = v ?: ""

    /**
     * 表头行 + 数据行：行内 Tab 分隔，行间 `\n`，无尾随换行。
     * 用于结果区「复制本行 → TSV（含表头）」与「复制全部（TSV，含表头）」。
     * 单列多行（如 PG `EXPLAIN`）即“表头 + 每行一条”。
     */
    fun tsvHeaderAndRows(columns: List<String>, rows: List<List<String?>>): String {
        val header = columns.joinToString("\t") { tsvField(it) }
        if (rows.isEmpty()) return header
        return buildString {
            append(header)
            for (r in rows) {
                append('\n')
                append(r.joinToString("\t") { tsvField(it) })
            }
        }
    }

    /** 单行 TSV（含表头行）。 */
    fun tsvHeaderAndRow(columns: List<String>, row: List<String?>): String =
        tsvHeaderAndRows(columns, listOf(row))

    /** 多行 TSV（**无表头**）：行内 Tab、行间 `\n`。用于结果区多选 Ctrl+C。 */
    fun tsvRows(rows: List<List<String?>>): String =
        rows.joinToString("\n") { r -> r.joinToString("\t") { tsvField(it) } }

    /** 表头行 + 数据行 CSV：行内逗号、行间 `\n`，各字段走 [csvField]。用于多选「复制选区 → CSV」。 */
    fun csvHeaderAndRows(columns: List<String>, rows: List<List<String?>>): String {
        val header = columns.joinToString(",") { csvField(it) }
        if (rows.isEmpty()) return header
        return buildString {
            append(header)
            for (r in rows) {
                append('\n')
                append(r.joinToString(",") { csvField(it) })
            }
        }
    }

    /** SQL INSERT / JSON 里的字符串字面量转义（标准 SQL：单引号双写）。 */
    fun sqlString(v: String): String = "'" + v.replace("'", "''") + "'"

    /**
     * SQL INSERT 的值：NULL → `NULL`；数值型且可解析 → 裸数字；布尔型 → `TRUE`/`FALSE`；
     * 其余 → 单引号字面量。
     */
    fun sqlLiteral(v: String?, sqlType: Int): String = when {
        v == null -> "NULL"
        isNumericType(sqlType) && v.trim().toBigDecimalOrNull() != null -> v.trim()
        isBooleanType(sqlType) && v.equals("true", ignoreCase = true) -> "TRUE"
        isBooleanType(sqlType) && v.equals("false", ignoreCase = true) -> "FALSE"
        else -> sqlString(v)
    }

    /** JSON 标量：NULL → `null`；数值型且可解析 → 裸数字；布尔型 → `true`/`false`；其余 → JSON 字符串。 */
    fun jsonValue(v: String?, sqlType: Int): String = when {
        v == null -> "null"
        isNumericType(sqlType) && v.trim().toBigDecimalOrNull() != null -> v.trim()
        isBooleanType(sqlType) && v.equals("true", ignoreCase = true) -> "true"
        isBooleanType(sqlType) && v.equals("false", ignoreCase = true) -> "false"
        else -> jsonString(v)
    }

    /** JSON 字符串字面量（含双引号）：转义引号、反斜杠与控制字符。 */
    fun jsonString(v: String): String {
        val sb = StringBuilder(v.length + 2)
        sb.append('"')
        for (c in v) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
