package app.ui

/**
 * 轻量 SQL 格式化（N2）：自研、零重依赖。
 *
 * 设计要点：
 * - 先用 [tokenizeForFormat] 把 SQL 切成**不可再分**的词元（字符串/引号标识符/注释/数字/词/运算符），
 *   格式化只在词元之间调整空白与关键字大小写，**绝不增删/重排词元** → 语义不变。
 * - `SqlFormatterTest` 用「词元签名」双向断言：`tokens(format(x)) == tokens(x)`（大小写归一），
 *   再加幂等性 `format(format(x)) == format(x)`。
 */

internal enum class FormatTokenKind { WORD, QUOTED, NUMBER, COMMENT, PUNCT }

internal data class FormatToken(val kind: FormatTokenKind, val text: String)

/** 词元签名：词元文本序列，WORD 归一为小写（允许格式化改变关键字大小写）。 */
internal fun formatTokenSignature(tokens: List<FormatToken>): List<String> =
    tokens.map { if (it.kind == FormatTokenKind.WORD) it.text.lowercase() else it.text }

private val MULTI_CHAR_OPS = listOf(
    "<=>", "->>", "!<", "!>", "->", "::", ":=", "<=", ">=", "<>", "!=", "||", "&&", "<<", ">>",
)

/**
 * 把 SQL 切成词元（跳过空白）。字符串支持 `''` 转义；引号标识符支持 `""` / ` `` ` / `]]` 转义。
 */
internal fun tokenizeForFormat(sql: String): List<FormatToken> {
    val out = ArrayList<FormatToken>()
    val n = sql.length
    var i = 0
    fun charAt(o: Int): Char = if (i + o < n) sql[i + o] else '\u0000'
    while (i < n) {
        val c = sql[i]
        when {
            c.isWhitespace() -> i++

            c == '-' && charAt(1) == '-' -> {
                val start = i
                i += 2
                while (i < n && sql[i] != '\n') i++
                out += FormatToken(FormatTokenKind.COMMENT, sql.substring(start, i))
            }

            c == '#' -> {
                val start = i
                i++
                while (i < n && sql[i] != '\n') i++
                out += FormatToken(FormatTokenKind.COMMENT, sql.substring(start, i))
            }

            c == '/' && charAt(1) == '*' -> {
                val start = i
                i += 2
                while (i < n && !(sql[i] == '*' && charAt(1) == '/')) i++
                if (i < n) i += 2
                out += FormatToken(FormatTokenKind.COMMENT, sql.substring(start, i))
            }

            c == '\'' -> out += readQuoted(sql, i, '\'', FormatTokenKind.QUOTED).also { i = it.second }.first
            c == '"' -> out += readQuoted(sql, i, '"', FormatTokenKind.QUOTED).also { i = it.second }.first
            c == '`' -> out += readQuoted(sql, i, '`', FormatTokenKind.QUOTED).also { i = it.second }.first

            // PostgreSQL 美元引用 `$$…$$` / `$tag$…$tag$`：整体作字面量，内部空白/分号不得改动
            c == '$' -> {
                val dq = readDollarQuote(sql, i)
                if (dq != null) {
                    out += dq.first
                    i = dq.second
                } else {
                    val start = i
                    while (i < n && (sql[i].isLetterOrDigit() || sql[i] == '_' || sql[i] == '$')) i++
                    out += FormatToken(FormatTokenKind.WORD, sql.substring(start, i))
                }
            }

            c.isDigit() || (c == '.' && charAt(1).isDigit()) -> {
                val start = i
                while (i < n && (sql[i].isDigit() || sql[i] == '.')) i++
                // 科学计数法：1e10 / 1.5E-3
                if (i < n && (sql[i] == 'e' || sql[i] == 'E')) {
                    val save = i
                    i++
                    if (i < n && (sql[i] == '+' || sql[i] == '-')) i++
                    if (i < n && sql[i].isDigit()) {
                        while (i < n && sql[i].isDigit()) i++
                    } else {
                        i = save
                    }
                }
                out += FormatToken(FormatTokenKind.NUMBER, sql.substring(start, i))
            }

            c.isLetter() || c == '_' -> {
                val start = i
                while (i < n && (sql[i].isLetterOrDigit() || sql[i] == '_' || sql[i] == '$')) i++
                out += FormatToken(FormatTokenKind.WORD, sql.substring(start, i))
            }

            else -> {
                val op = MULTI_CHAR_OPS.firstOrNull { sql.startsWith(it, i) }
                if (op != null) {
                    out += FormatToken(FormatTokenKind.PUNCT, op)
                    i += op.length
                } else {
                    out += FormatToken(FormatTokenKind.PUNCT, c.toString())
                    i++
                }
            }
        }
    }
    return out
}

/** 读引号体（`'`/`"`/`` ` ``），双写转义；未闭合则读到结尾。返回 (词元, 新下标)。 */
private fun readQuoted(sql: String, from: Int, quote: Char, kind: FormatTokenKind): Pair<FormatToken, Int> {
    val n = sql.length
    var i = from + 1
    while (i < n) {
        if (sql[i] == quote) {
            if (i + 1 < n && sql[i + 1] == quote) {
                i += 2
                continue
            }
            i++
            break
        }
        // 反斜杠转义（MySQL 等）：跳过下一个字符，避免 \' 提前收尾
        if (sql[i] == '\\' && i + 1 < n) {
            i += 2
            continue
        }
        i++
    }
    return FormatToken(kind, sql.substring(from, i)) to i
}

private fun readDollarQuote(sql: String, from: Int): Pair<FormatToken, Int>? {
    val n = sql.length
    var j = from + 1
    while (j < n && (sql[j].isLetterOrDigit() || sql[j] == '_')) j++
    if (j >= n || sql[j] != '$') return null
    val tag = sql.substring(from, j + 1)
    val end = sql.indexOf(tag, j + 1)
    val to = if (end < 0) n else end + tag.length
    return FormatToken(FormatTokenKind.QUOTED, sql.substring(from, to)) to to
}

enum class KeywordCase { UPPER, LOWER, PRESERVE }

data class SqlFormatOptions(
    val indentWidth: Int = 4,
    val keywordCase: KeywordCase = KeywordCase.UPPER,
    /** 顶层逗号列表（SELECT 列表 / INSERT 列清单…）每个元素一行。 */
    val oneItemPerLine: Boolean = true,
    /** 顶层 AND/OR 另起一行并缩进。 */
    val newlineBeforeAndOr: Boolean = true,
)

/** 另起一行的子句关键字（`GROUP BY` 拆成 GROUP / BY 两个词元，只需在 GROUP 前换行）。 */
private val LINE_START_KEYWORDS = setOf(
    "SELECT", "FROM", "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "OFFSET",
    "UNION", "EXCEPT", "INTERSECT", "INSERT", "UPDATE", "DELETE", "VALUES", "SET",
    "RETURNING", "WITH", "WINDOW", "QUALIFY", "FETCH", "FOR",
)

/** JOIN 修饰词（其后若跟 JOIN/OUTER 才另起一行，避免误伤 `LEFT(col,1)`）。 */
private val JOIN_MODIFIERS = setOf("INNER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL")

/** 子查询开头（`(` 之后换行缩进）。 */
private val SUBQUERY_HEADS = setOf("SELECT", "WITH", "VALUES")

private val SQL_KEYWORD_SET: Set<String> by lazy { sqlHighlightKeywords().map { it.uppercase() }.toSet() }

/**
 * 格式化 [sql]。空输入返回空串；仅调整空白与关键字大小写，词元序列不变。
 */
fun formatSql(sql: String, options: SqlFormatOptions = SqlFormatOptions()): String {
    val tokens = tokenizeForFormat(sql)
    if (tokens.isEmpty()) return ""
    val indentUnit = " ".repeat(options.indentWidth.coerceIn(0, 8))
    val sb = StringBuilder()
    var depth = 0
    var prev: FormatToken? = null
    var tightNext = false

    fun trimTrailingSpaces() {
        while (sb.isNotEmpty() && sb.last() == ' ') sb.deleteCharAt(sb.length - 1)
    }

    /** 若当前行只有缩进空白则清掉（`(` 子查询换行后紧接着 `)` 时用）。 */
    fun stripTrailingIndent() {
        var j = sb.length
        while (j > 0 && sb[j - 1] == ' ') j--
        if (j == 0 || sb[j - 1] == '\n') sb.setLength(j)
    }

    fun newline(extra: Int = 0) {
        trimTrailingSpaces()
        if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
        stripTrailingIndent()
        repeat(depth + extra) { sb.append(indentUnit) }
    }

    fun space() {
        if (sb.isNotEmpty() && sb.last() != ' ' && sb.last() != '\n' && sb.last() != '(') sb.append(' ')
    }

    fun isKeyword(t: FormatToken) = t.kind == FormatTokenKind.WORD && t.text.uppercase() in SQL_KEYWORD_SET

    fun cased(t: FormatToken): String {
        if (t.kind != FormatTokenKind.WORD || !isKeyword(t)) return t.text
        return when (options.keywordCase) {
            KeywordCase.UPPER -> t.text.uppercase()
            KeywordCase.LOWER -> t.text.lowercase()
            KeywordCase.PRESERVE -> t.text
        }
    }

    fun spaceBeforeParen(p: FormatToken?): Boolean {
        if (p == null) return false
        if (p.text == "(") return false
        if (p.kind == FormatTokenKind.QUOTED || p.kind == FormatTokenKind.NUMBER) return false
        if (p.kind == FormatTokenKind.WORD && !isKeyword(p)) return false // 函数名
        return true
    }

    fun isUnaryContext(p: FormatToken?): Boolean {
        if (p == null) return true
        if (p.kind == FormatTokenKind.NUMBER || p.kind == FormatTokenKind.QUOTED) return false
        if (p.kind == FormatTokenKind.WORD) return isKeyword(p) // 关键字后为表达式起始；非关键字标识符是左操作数
        return when (p.text) {
            ")", "]" -> false
            else -> true // ( , 运算符等
        }
    }

    var idx = 0
    var pendingItemNewline = false
    while (idx < tokens.size) {
        val t = tokens[idx]
        val next = tokens.getOrNull(idx + 1)
        val upper = if (t.kind == FormatTokenKind.WORD) t.text.uppercase() else ""
        val tight = tightNext
        tightNext = false
        if (pendingItemNewline) {
            pendingItemNewline = false
            newline(1)
        }

        when {
            t.kind == FormatTokenKind.COMMENT -> {
                if (sb.isNotEmpty() && sb.last() != '\n') {
                    space()
                    sb.append(t.text)
                    sb.append('\n')
                    stripTrailingIndent()
                    repeat(depth) { sb.append(indentUnit) }
                } else {
                    sb.append(t.text)
                    sb.append('\n')
                    stripTrailingIndent()
                    repeat(depth) { sb.append(indentUnit) }
                }
                prev = t
            }

            t.text == ";" -> {
                trimTrailingSpaces()
                sb.append(';')
                depth = 0
                prev = null
                if (idx != tokens.lastIndex) {
                    sb.append('\n')
                    sb.append('\n')
                }
            }

            t.text == "(" -> {
                if (!tight && spaceBeforeParen(prev)) space()
                sb.append('(')
                depth++
                if (next != null && next.kind == FormatTokenKind.WORD && next.text.uppercase() in SUBQUERY_HEADS) {
                    newline()
                }
                prev = t
            }

            t.text == ")" -> {
                depth = (depth - 1).coerceAtLeast(0)
                stripTrailingIndent()
                sb.append(')')
                prev = t
            }

            t.text == "," -> {
                trimTrailingSpaces()
                sb.append(',')
                if (options.oneItemPerLine && depth == 0 && next != null) newline(1) else space()
                prev = t
            }

            t.text == "." || t.text == "::" -> {
                trimTrailingSpaces()
                sb.append(t.text)
                prev = t
            }

            t.text == "[" -> {
                trimTrailingSpaces()
                sb.append('[')
                depth++
                tightNext = true
                prev = t
            }

            t.text == "]" -> {
                depth = (depth - 1).coerceAtLeast(0)
                stripTrailingIndent()
                sb.append(']')
                prev = t
            }

            upper == "JOIN" -> {
                newline()
                sb.append(cased(t))
                prev = t
            }

            upper in JOIN_MODIFIERS && next?.text?.uppercase() in setOf("JOIN", "OUTER") -> {
                newline()
                sb.append(cased(t))
                prev = t
            }

            upper == "AND" || upper == "OR" -> {
                if (options.newlineBeforeAndOr && depth == 0) newline(1) else space()
                sb.append(cased(t))
                prev = t
            }

            upper in LINE_START_KEYWORDS -> {
                newline()
                sb.append(cased(t))
                // SELECT 列表：第一项另起一行（DISTINCT/ALL 保留在 SELECT 同行）
                if (upper == "SELECT" && options.oneItemPerLine && depth == 0) {
                    var j = idx + 1
                    while (j < tokens.size && tokens[j].kind == FormatTokenKind.WORD &&
                        tokens[j].text.uppercase() in setOf("DISTINCT", "ALL")
                    ) {
                        space()
                        sb.append(cased(tokens[j]))
                        prev = tokens[j]
                        j++
                    }
                    if (j < tokens.size) {
                        idx = j - 1
                        pendingItemNewline = true
                    }
                }
                prev = t
            }

            (t.text == "-" || t.text == "+" || t.text == "~") && isUnaryContext(prev) -> {
                trimTrailingSpaces()
                sb.append(t.text)
                tightNext = true
                prev = t
            }

            else -> {
                if (!tight && prev?.text != "(" && prev?.text != "." && prev?.text != "::") space()
                sb.append(if (t.kind == FormatTokenKind.WORD) cased(t) else t.text)
                prev = t
            }
        }
        idx++
    }
    return sb.toString().trimEnd() + "\n"
}
