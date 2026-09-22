package jdbc

/**
 * 把一段 SQL 文本按语句边界切分（语句分隔符 `;`）。
 *
 * 规则：
 * - 字符串（`'…'`，`''` 转义）、双引号标识符（`"…"`）、反引号标识符（`` `…` ``）、
 *   行注释（`--`）与块注释（`/ *…* /`）内部的分号不算分隔符；
 * - 每条语句去首尾空白，丢弃空白片段；
 * - 仅含注释/空白的片段（如独立的 `-- xxx` 行）被忽略，不交给驱动执行。
 */
fun splitSqlStatements(sql: String): List<String> =
    splitSqlStatementRanges(sql).map { sql.substring(it.first, it.last + 1) }

/**
 * 与 [splitSqlStatements] 同一词法规则，但保留每条语句在原文中的字符区间（含首尾索引）。
 * 区间按首尾非空白裁剪（`substring(r.first, r.last + 1)` 即语句文本，与
 * [splitSqlStatements] 的产物逐条相等）；仅含注释/空白的片段不产出。
 */
fun splitSqlStatementRanges(sql: String): List<IntRange> {
    val out = mutableListOf<IntRange>()
    var segStart = 0
    var state = 0 // 0 普通 | 1 单引号 | 2 双引号 | 3 反引号 | 4 行注释 | 5 块注释
    var i = 0
    fun flush(endExclusive: Int) {
        var s = segStart
        var e = endExclusive - 1
        while (s < endExclusive && sql[s].isWhitespace()) s++
        while (e >= s && sql[e].isWhitespace()) e--
        if (e >= s && !sql.substring(s, e + 1).isCommentOnlySql()) out += s..e
    }
    while (i < sql.length) {
        val c = sql[i]
        val next = sql.getOrNull(i + 1)
        when (state) {
            0 -> when {
                c == '\'' -> state = 1
                c == '"' -> state = 2
                c == '`' -> state = 3
                c == '-' && next == '-' -> { state = 4; i++ }
                c == '/' && next == '*' -> { state = 5; i++ }
                c == ';' -> { flush(i); segStart = i + 1 }
                else -> {}
            }
            1 -> if (c == '\'') { if (next == '\'') i++ else state = 0 }
            2 -> if (c == '"') { if (next == '"') i++ else state = 0 }
            3 -> if (c == '`') { if (next == '`') i++ else state = 0 }
            4 -> if (c == '\n') state = 0
            5 -> if (c == '*' && next == '/') { state = 0; i++ }
        }
        i++
    }
    flush(sql.length)
    return out
}

/** 是否仅含注释与空白（无任何可执行 token）。字符串/引号标识符均视为真实 token。 */
fun String.isCommentOnlySql(): Boolean {
    var state = 0 // 0 普通 | 1 行注释 | 2 块注释
    var i = 0
    while (i < length) {
        val c = this[i]
        val next = getOrNull(i + 1)
        when (state) {
            0 -> when {
                c == '-' && next == '-' -> { state = 1; i++ }
                c == '/' && next == '*' -> { state = 2; i++ }
                c.isWhitespace() -> {}
                else -> return false
            }
            1 -> if (c == '\n') state = 0
            2 -> if (c == '*' && next == '/') { state = 0; i++ }
        }
        i++
    }
    return true
}
