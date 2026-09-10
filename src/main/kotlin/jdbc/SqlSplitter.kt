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
fun splitSqlStatements(sql: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var state = 0 // 0 普通 | 1 单引号 | 2 双引号 | 3 反引号 | 4 行注释 | 5 块注释
    var i = 0
    while (i < sql.length) {
        val c = sql[i]
        val next = sql.getOrNull(i + 1)
        when (state) {
            0 -> when {
                c == '\'' -> { cur.append(c); state = 1 }
                c == '"' -> { cur.append(c); state = 2 }
                c == '`' -> { cur.append(c); state = 3 }
                c == '-' && next == '-' -> { cur.append(c).append(next); state = 4; i++ }
                c == '/' && next == '*' -> { cur.append(c).append(next); state = 5; i++ }
                c == ';' -> flushStatement(cur, out)
                else -> cur.append(c)
            }
            1 -> {
                cur.append(c)
                if (c == '\'') {
                    if (next == '\'') { cur.append(next); i++ } else state = 0
                }
            }
            2 -> {
                cur.append(c)
                if (c == '"') {
                    if (next == '"') { cur.append(next); i++ } else state = 0
                }
            }
            3 -> {
                cur.append(c)
                if (c == '`') {
                    if (next == '`') { cur.append(next); i++ } else state = 0
                }
            }
            4 -> { cur.append(c); if (c == '\n') state = 0 }
            5 -> {
                cur.append(c)
                if (c == '*' && next == '/') { cur.append(next); state = 0; i++ }
            }
        }
        i++
    }
    flushStatement(cur, out)
    return out
}

private fun flushStatement(cur: StringBuilder, out: MutableList<String>) {
    val s = cur.toString().trim()
    cur.setLength(0)
    if (s.isEmpty() || s.isCommentOnlySql()) return
    out += s
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
