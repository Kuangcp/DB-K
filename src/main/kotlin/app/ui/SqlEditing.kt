package app.ui

import jdbc.QueryColumn
import jdbc.QueryResult
import jdbc.model.SchemaMeta

/*
 * SQL 编辑器补全 / 结果交互的纯逻辑（app.ui 层但无 compose 依赖，便于后续单测）。
 */

/** 补全用的 SQL 关键字（大写；匹配时忽略大小写）。 */
val SQL_KEYWORDS: List<String> = listOf(
    "SELECT", "FROM", "WHERE", "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "OFFSET", "AS",
    "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS", "NATURAL", "UNION", "ALL",
    "INTERSECT", "EXCEPT", "DISTINCT", "AND", "OR", "NOT", "IN", "EXISTS", "BETWEEN", "LIKE",
    "ILIKE", "IS", "NULL", "TRUE", "FALSE", "CASE", "WHEN", "THEN", "ELSE", "END", "CAST",
    "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "ALTER", "DROP", "TABLE",
    "VIEW", "INDEX", "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "UNIQUE", "CHECK", "DEFAULT",
    "WITH", "RECURSIVE", "RETURNING", "BEGIN", "COMMIT", "ROLLBACK", "TRANSACTION", "EXPLAIN",
    "ANALYZE", "SHOW", "DESCRIBE", "USE", "USING", "LATERAL", "ANY", "SOME", "ASC", "DESC",
)

private fun isIdentChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

/** caret 前的可补全词（含已键入前缀）。 */
data class CompletionWord(val start: Int, val end: Int, val text: String)

/**
 * 计算 caret 处的标识符前缀词（[start, end)）。
 * 返回 null：光标不在词内（前一个字符不是标识符）、有选区，或位于字符串/注释中。
 */
fun sqlCompletionWord(text: String, caret: Int): CompletionWord? {
    if (caret <= 0 || caret > text.length) return null
    val before = text.substring(0, caret)
    if (isInsideCommentOrString(before)) return null
    var end = caret
    var i = caret - 1
    while (i >= 0 && isIdentChar(before[i])) i--
    val start = i + 1
    if (start == end) return null
    // 纯数字前缀不补
    val word = text.substring(start, end)
    if (word.all { it.isDigit() }) return null
    return CompletionWord(start, end, word)
}

/** 简单词法扫描：光标位于单/双引号字符串、-- 行注释或 /* */ 块注释内时返回 true。 */
private fun isInsideCommentOrString(before: String): Boolean {
    var state = 0 // 0 普通 | 1 单引号 | 2 双引号 | 3 行注释 | 4 块注释
    var i = 0
    while (i < before.length) {
        val c = before[i]
        when (state) {
            0 -> when {
                c == '\'' -> state = 1
                c == '"' -> state = 2
                c == '-' && before.getOrNull(i + 1) == '-' -> { state = 3; i++ }
                c == '/' && before.getOrNull(i + 1) == '*' -> { state = 4; i++ }
            }
            1 -> if (c == '\'') { if (before.getOrNull(i + 1) == '\'') i++ else state = 0 }
            2 -> if (c == '"') { if (before.getOrNull(i + 1) == '"') i++ else state = 0 }
            3 -> if (c == '\n') state = 0
            4 -> if (c == '*' && before.getOrNull(i + 1) == '/') { state = 0; i++ }
        }
        i++
    }
    return state != 0
}

/** 补全候选类别（弹层前缀点用；色取语义色板，文字仍走主题色）。 */
enum class CompletionKind { COLUMN, ALIAS, FUNCTION, TABLE, KEYWORD, EXPAND }

/** 一条补全候选：文本 + 右侧详情（列类型 / 别名指向表 / 表所属 schema）+ 类别。
 * [insertText] 非空时上屏写它（如 `*` 展开为列清单），否则写 [text]。 */
data class CompletionItem(
    val text: String,
    val detail: String? = null,
    val kind: CompletionKind = CompletionKind.KEYWORD,
    val insertText: String? = null,
)

/**
 * 补全候选排序：星号展开 → 上下文列 → 别名 → 函数 → 表/视图 → 关键字；大小写不敏感前缀匹配；
 * 长度不短于前缀的不出现（已输完整即关闭弹层）；文本去重，靠前者优先。
 * 空前缀仅由显式唤起（Ctrl+Space）产生，此时列出上下文对象；完全不传上下文则返回空。
 */
fun completionItems(
    word: String,
    expands: List<CompletionItem> = emptyList(),
    columns: List<CompletionItem> = emptyList(),
    aliases: List<CompletionItem> = emptyList(),
    functions: List<CompletionItem> = emptyList(),
    objects: List<CompletionItem> = emptyList(),
    includeKeywords: Boolean = true,
): List<CompletionItem> {
    val prefix = word.lowercase()
    if (prefix.isEmpty() && expands.isEmpty() && columns.isEmpty() && aliases.isEmpty() &&
        functions.isEmpty() && objects.isEmpty()
    ) {
        return emptyList()
    }
    val out = ArrayList<CompletionItem>()
    fun add(items: List<CompletionItem>) {
        for (item in items) if (matchesPrefix(item.text, prefix)) out.add(item)
    }
    add(expands)
    add(columns)
    add(aliases)
    add(functions)
    add(objects)
    if (includeKeywords) {
        for (kw in SQL_KEYWORDS) {
            if (matchesPrefix(kw, prefix)) out.add(CompletionItem(kw, kind = CompletionKind.KEYWORD))
        }
    }
    return out.distinctBy { it.text }
}

/** 纯文本候选（兼容既有调用/自检）：列名 + 对象名 + 关键字。 */
fun completionCandidates(
    word: String,
    identifiers: List<String>,
    columns: List<String> = emptyList(),
    includeKeywords: Boolean = true,
): List<String> = completionItems(
    word = word,
    columns = columns.map { CompletionItem(it, kind = CompletionKind.COLUMN) },
    objects = identifiers.map { CompletionItem(it, kind = CompletionKind.TABLE) },
    includeKeywords = includeKeywords,
).map { it.text }

/** caret 处是否允许补全（不在字符串/注释内）。显式 Ctrl+Space 唤起空前缀时用。 */
fun sqlCompletionAllowed(text: String, caret: Int): Boolean {
    if (caret <= 0 || caret > text.length) return false
    return !isInsideCommentOrString(text.substring(0, caret))
}

/** caret 所在语句及其解析结果（[caretInStmt] 为 caret 在 [stmt] 内的偏移）。 */
data class PreparedScope(val stmt: String, val scope: SqlScope, val caretInStmt: Int)

/** 取 caret 所在语句并解析为 [SqlScope]（无语句/空返回 null）。 */
fun buildPreparedScope(text: String, caret: Int): PreparedScope? {
    val range = statementRangeAt(text, caret) ?: return null
    val stmt = text.substring(range.first, range.last + 1)
    return PreparedScope(stmt, parseTableRefs(stmt), caret - range.first)
}

/**
 * caret 前是否是「可展开的 select-list 星号」：`SELECT *` / `SELECT DISTINCT *` / `a, *`。
 * 命中返回星号下标；`count(*)` 等函数内星号返回 null。字符串/注释内不算。
 */
fun selectStarBeforeCaret(text: String, caret: Int): Int? {
    if (caret <= 0 || caret > text.length) return null
    if (isInsideCommentOrString(text.substring(0, caret))) return null
    if (text[caret - 1] != '*') return null
    val starPos = caret - 1
    var i = starPos - 1
    while (i >= 0 && text[i].isWhitespace()) i--
    if (i < 0) return null
    if (text[i] == ',') return starPos
    var j = i
    while (j >= 0 && isIdentChar(text[j])) j--
    val prev = text.substring(j + 1, i + 1).lowercase()
    return if (prev == "select" || prev == "distinct" || prev == "all") starPos else null
}

private fun matchesPrefix(candidate: String, prefix: String): Boolean =
    if (prefix.isEmpty()) candidate.isNotEmpty()
    else candidate.length > prefix.length && candidate.lowercase().startsWith(prefix)

// ---------- 列补全上下文解析（纯逻辑，无 compose 依赖） ----------

/** 已缓存对象清单中的一项：表/视图名 + 所属 schema（把未限定表名解析到 schema 用）。 */
data class CompletionTable(val name: String, val schema: SchemaMeta)

/** FROM/JOIN 解析出的表引用。[qualifier] = 书写原文（含 schema 前缀），[alias] = 显式/裸别名。
 * [nameStart]/[nameEnd] 为表名 token 在语句中的源码区间（引号标识符含引号），供判断是否写完。
 * [derived] = 派生表（`FROM (子查询) alias`）；[cte] = 引用 WITH 定义的 CTE。 */
data class TableRef(
    val qualifier: String?,
    val schema: String?,
    val table: String,
    val alias: String?,
    val nameStart: Int = -1,
    val nameEnd: Int = -1,
    val derived: Boolean = false,
    val cte: Boolean = false,
)

/**
 * 表名是否已写完整：后面跟了分隔符（`nameEnd < 语句长` 时必然是分隔符），
 * 或表名在语句末尾但 caret 不在其 token 内（已写到别处）。前者排除边敲边查。
 */
fun TableRef.isComplete(statementLength: Int, caret: Int): Boolean =
    nameEnd >= 0 && (nameEnd < statementLength || caret != nameEnd)

/** 一条语句的表引用集合；[complex] = 含无法引用的子查询（如无别名派生表）。
 * [cteColumns] = CTE 名（小写）→ 显式列名（仅 `WITH c(a,b) AS …` 这种可静态得知的）。 */
data class SqlScope(
    val tables: List<TableRef>,
    val complex: Boolean,
    val cteColumns: Map<String, List<String>> = emptyMap(),
)

/** caret 前形如 `t.` / `schema.t.` 的限定符及其后的前缀词。 */
data class SqlQualifier(val qualifier: String, val wordStart: Int, val wordEnd: Int, val wordText: String)

/**
 * caret 所在语句的字符区间 `[start, endExclusive)`（不含分隔符 `;`；含首尾空白）。
 * 词法规则与 splitSqlStatements 一致：字符串/引号标识符/注释内的 `;` 不算分隔符。
 * caret 落在分号之后且后续为空时返回 null。
 */
fun statementRangeAt(sql: String, caret: Int): IntRange? {
    if (sql.isBlank()) return null
    val pos = caret.coerceIn(0, sql.length)
    var start = 0
    var end = sql.length
    var state = 0 // 0 普通 | 1 单引号 | 2 双引号 | 3 反引号 | 4 行注释 | 5 块注释
    var i = 0
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
                c == ';' -> {
                    if (i < pos) start = i + 1 else if (end == sql.length) end = i
                }
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
    if (sql.substring(start, end).isBlank()) return null
    return start until end
}

private data class SqlToken(
    val text: String,
    val punct: Boolean,
    val literal: Boolean,
    /** 源码区间 [start, end)（引号标识符含引号）。 */
    val start: Int = -1,
    val end: Int = -1,
)

/** 极简词法：标识符/标点/引号标识符；字符串字面量与注释被跳过或标记为 literal。 */
private fun tokenizeSql(statement: String): List<SqlToken> {
    val out = mutableListOf<SqlToken>()
    var i = 0
    while (i < statement.length) {
        val c = statement[i]
        when {
            c.isWhitespace() -> i++
            c == '-' && statement.getOrNull(i + 1) == '-' -> {
                while (i < statement.length && statement[i] != '\n') i++
            }
            c == '/' && statement.getOrNull(i + 1) == '*' -> {
                i += 2
                while (i < statement.length && !(statement[i] == '*' && statement.getOrNull(i + 1) == '/')) i++
                i = (i + 2).coerceAtMost(statement.length)
            }
            c == '\'' -> {
                val start = i
                val sb = StringBuilder()
                i++
                while (i < statement.length) {
                    if (statement[i] == '\'') {
                        if (statement.getOrNull(i + 1) == '\'') { sb.append('\''); i += 2 } else { i++; break }
                    } else { sb.append(statement[i]); i++ }
                }
                out += SqlToken(sb.toString(), punct = false, literal = true, start = start, end = i)
            }
            c == '"' || c == '`' -> {
                val start = i
                val quote = c
                val sb = StringBuilder()
                i++
                while (i < statement.length) {
                    if (statement[i] == quote) {
                        if (statement.getOrNull(i + 1) == quote) { sb.append(quote); i += 2 } else { i++; break }
                    } else { sb.append(statement[i]); i++ }
                }
                out += SqlToken(sb.toString(), punct = false, literal = false, start = start, end = i)
            }
            c == '.' || c == ',' || c == '(' || c == ')' || c == ';' -> {
                out += SqlToken(c.toString(), punct = true, literal = false, start = i, end = i + 1)
                i++
            }
            c.isLetterOrDigit() || c == '_' || c == '$' -> {
                var j = i
                while (j < statement.length &&
                    (statement[j].isLetterOrDigit() || statement[j] == '_' || statement[j] == '$')
                ) j++
                out += SqlToken(statement.substring(i, j), punct = false, literal = false, start = i, end = j)
                i = j
            }
            else -> i++
        }
    }
    return out
}

/** FROM/JOIN 之后、别名之前会终止表引用的关键字。 */
private val TABLE_STOP: Set<String> = setOf(
    "where", "group", "order", "having", "limit", "offset", "union", "intersect", "except",
    "join", "inner", "left", "right", "full", "cross", "natural", "on", "using", "window",
    "returning", "for", "fetch", "set", "values", "select", "from", "into",
)

/** 解析 WITH 子句里的 CTE 名与显式列名（仅 `WITH c(a,b) AS (…)` 可静态得知列）。 */
private fun parseCtes(toks: List<SqlToken>): Map<String, List<String>> {
    val out = linkedMapOf<String, List<String>>()
    if (toks.isEmpty() || toks[0].punct || toks[0].literal || !toks[0].text.equals("with", true)) return out
    var i = 1
    if (i < toks.size && !toks[i].punct && toks[i].text.equals("recursive", true)) i++
    while (i < toks.size && !toks[i].punct && !toks[i].literal) {
        val name = toks[i].text.lowercase()
        i++
        val cols = mutableListOf<String>()
        if (i < toks.size && toks[i].punct && toks[i].text == "(") {
            i++
            while (i < toks.size && !(toks[i].punct && toks[i].text == ")")) {
                if (!toks[i].punct && !toks[i].literal) cols += toks[i].text
                i++
            }
            if (i < toks.size) i++
        }
        if (i < toks.size && !toks[i].punct && toks[i].text.equals("as", true)) i++
        if (i >= toks.size || !toks[i].punct || toks[i].text != "(") break
        i = skipBalancedParens(toks, i) ?: break
        out[name] = cols
        if (i < toks.size && toks[i].punct && toks[i].text == ",") { i++; continue }
        break
    }
    return out
}

/**
 * 解析一条语句里的 FROM/JOIN 表引用（含别名）。只看括号深度 0（内层子查询的 FROM 不计入）；
 * `FROM (子查询) alias` 记录为派生表（[TableRef.derived]，无列）；无别名的派生表置
 * [SqlScope.complex]；`WITH` 定义的 CTE 名标记到 [TableRef.cte] 并带出显式列名。
 */
fun parseTableRefs(statement: String): SqlScope {
    val toks = tokenizeSql(statement)
    val ctes = parseCtes(toks)
    val tables = mutableListOf<TableRef>()
    var complex = false
    var depth = 0
    var i = 0
    while (i < toks.size) {
        val t = toks[i]
        if (t.punct) {
            if (t.text == "(") depth++ else if (t.text == ")") depth = (depth - 1).coerceAtLeast(0)
            i++
            continue
        }
        if (depth != 0) { i++; continue }
        val isFromJoin = !t.literal && (t.text.equals("from", true) || t.text.equals("join", true))
        if (!isFromJoin) { i++; continue }
        i++
        while (i < toks.size) {
            // 派生表：FROM ( … ) alias
            if (toks[i].punct && toks[i].text == "(") {
                i = skipBalancedParens(toks, i) ?: i
                val aliasTok = readAlias(toks, i).also { i = it.second }.first
                if (aliasTok != null) {
                    tables += TableRef(
                        qualifier = aliasTok.text,
                        schema = null,
                        table = aliasTok.text,
                        alias = aliasTok.text,
                        nameStart = aliasTok.start,
                        nameEnd = aliasTok.end,
                        derived = true,
                    )
                } else {
                    complex = true
                }
                if (i < toks.size && toks[i].punct && toks[i].text == ",") { i++; continue }
                break
            }
            if (toks[i].punct || toks[i].literal) break
            val parts = mutableListOf(toks[i])
            i++
            while (i + 1 < toks.size && toks[i].punct && toks[i].text == "." &&
                !toks[i + 1].punct && !toks[i + 1].literal
            ) {
                parts += toks[i + 1]
                i += 2
            }
            val table = parts.last().text
            val schema = if (parts.size >= 2) parts[parts.size - 2].text else null
            val aliasTok = readAlias(toks, i).also { i = it.second }.first
            tables += TableRef(
                qualifier = parts.joinToString(".") { it.text },
                schema = schema,
                table = table,
                alias = aliasTok?.text,
                nameStart = parts.first().start,
                nameEnd = parts.last().end,
                cte = table.lowercase() in ctes,
            )
            if (i < toks.size && toks[i].punct && toks[i].text == ",") { i++; continue }
            break
        }
    }
    return SqlScope(tables, complex, ctes)
}

/** 从 [open]（`(` 的下标）跳到配对 `)` 之后，返回新下标；不配对返回 null。 */
private fun skipBalancedParens(toks: List<SqlToken>, open: Int): Int? {
    var depth = 0
    var i = open
    while (i < toks.size) {
        val x = toks[i]
        if (x.punct && x.text == "(") depth++
        else if (x.punct && x.text == ")") {
            depth--
            if (depth == 0) return i + 1
        }
        i++
    }
    return null
}

/** 从 [from] 读可选的 `AS x` / 裸别名，返回（别名 token, 新下标）。 */
private fun readAlias(toks: List<SqlToken>, from: Int): Pair<SqlToken?, Int> {
    var i = from
    if (i < toks.size && !toks[i].punct && !toks[i].literal && toks[i].text.equals("as", true)) {
        if (i + 1 < toks.size && !toks[i + 1].punct && !toks[i + 1].literal) return toks[i + 1] to i + 2
        return null to i + 1
    }
    if (i < toks.size && !toks[i].punct && !toks[i].literal && toks[i].text.lowercase() !in TABLE_STOP) {
        return toks[i] to i + 1
    }
    return null to i
}

/**
 * caret 前是否为限定符引用 `t.` / `schema.t.`：返回限定符与点后的前缀词（可为空）。
 * 字符串/注释内、以非标识符开头、连续点均返回 null。
 */
fun sqlQualifiedPrefix(text: String, caret: Int): SqlQualifier? {
    if (caret <= 0 || caret > text.length) return null
    if (isInsideCommentOrString(text.substring(0, caret))) return null
    var i = caret
    while (i > 0 && isIdentChar(text[i - 1])) i--
    if (i == 0 || text[i - 1] != '.') return null
    val dot = i - 1
    var j = dot
    while (j > 0 && (isIdentChar(text[j - 1]) || text[j - 1] == '.')) j--
    val qualifier = text.substring(j, dot)
    if (qualifier.isEmpty() || qualifier.contains("..") || qualifier.endsWith(".")) return null
    if (!(qualifier.first().isLetter() || qualifier.first() == '_')) return null
    return SqlQualifier(qualifier, i, caret, text.substring(i, caret))
}

/** schema 是否以 [name] 指代（displayName / schema / catalog 任一大小写不敏感匹配）。 */
private fun SchemaMeta.matchesName(name: String): Boolean =
    displayName.equals(name, ignoreCase = true) ||
        schema?.equals(name, ignoreCase = true) == true ||
        catalog?.equals(name, ignoreCase = true) == true

/**
 * 把解析出的表引用解析到具体 schema：限定名按 schema 名匹配；未限定先查已缓存对象清单
 * （同名跨 schema 时优先 [defaultSchema]），查不到回落 [defaultSchema]。
 */
fun resolveTableRef(
    ref: TableRef,
    knownTables: List<CompletionTable>,
    schemas: List<SchemaMeta>,
    defaultSchema: SchemaMeta?,
): SchemaMeta? {
    if (ref.derived || ref.cte) return null
    if (ref.schema != null) {
        return schemas.firstOrNull { it.matchesName(ref.schema) } ?: defaultSchema
    }
    val candidates = knownTables
        .filter { it.name.equals(ref.table, ignoreCase = true) }
        .map { it.schema }
        .distinctBy { it.key }
    if (candidates.isEmpty()) return defaultSchema
    if (candidates.size == 1) return candidates.single()
    return candidates.firstOrNull { it.key == defaultSchema?.key } ?: candidates.first()
}

/** 限定符（别名 / 表名 / schema.table）是否指向该表引用。 */
fun TableRef.matchesQualifier(qualifier: String): Boolean {
    val q = qualifier.lowercase()
    return alias?.lowercase() == q ||
        table.lowercase() == q ||
        (schema != null && "${schema.lowercase()}.${table.lowercase()}" == q)
}

/** 光标处标识符解析出的表/视图（Ctrl+Q 查看定义）。 */
data class TableAtCaret(val name: String, val schema: SchemaMeta?)

/** 光标处标识符的完整区间（左右各扩展到标识符边界）；不在标识符上返回 null。 */
private fun identRangeAt(text: String, caret: Int): IntRange? {
    val pos = caret.coerceIn(0, text.length)
    if (isInsideCommentOrString(text.substring(0, pos))) return null
    var s = pos
    var e = pos
    while (s > 0 && isIdentChar(text[s - 1])) s--
    while (e < text.length && isIdentChar(text[e])) e++
    return if (s == e) null else s until e
}

/**
 * 光标所在标识符若指向表/视图则解析出来（列名/关键字/CTE/派生表返回 null）。用于 Ctrl+Q：
 * - 别名（含 `u.id` 里的 `u`）→ 该别名指向的表；
 * - 表名（裸名，或作为 `users.id` 的限定符）→ 该表；
 * - `schema.表名` → 该 schema 下的表。
 * 同名跨 schema 时优先 [defaultSchema]；查不到则返回 null。
 */
fun tableAtCaret(
    text: String,
    caret: Int,
    knownTables: List<CompletionTable>,
    schemas: List<SchemaMeta>,
    defaultSchema: SchemaMeta?,
): TableAtCaret? {
    val range = identRangeAt(text, caret) ?: return null
    val token = text.substring(range.first, range.last + 1)
    if (token.all { it.isDigit() }) return null
    // 点前缀：`schema.` 或 `表/别名.`
    val before = text.substring(0, range.first)
    val qualifier = if (before.endsWith('.')) {
        var j = before.length - 1
        var k = j
        while (k > 0 && isIdentChar(before[k - 1])) k--
        before.substring(k, j).takeIf { it.isNotEmpty() }
    } else {
        null
    }
    // 1) 当前语句内的表引用（别名 / 表名 / 限定符；CTE、派生表无定义可看）
    buildPreparedScope(text, caret)?.scope?.tables?.let { refs ->
        val asRef = qualifier ?: token
        refs.firstOrNull { !it.derived && !it.cte && it.matchesQualifier(asRef) }?.let { ref ->
            return TableAtCaret(ref.table, resolveTableRef(ref, knownTables, schemas, defaultSchema))
        }
    }
    // 2) 已缓存对象清单（裸表名 / schema.表名）
    val candidates = knownTables.filter { t ->
        t.name.equals(token, ignoreCase = true) && (qualifier == null || t.schema.matchesName(qualifier))
    }
    if (candidates.isEmpty()) return null
    val chosen = candidates.firstOrNull { it.schema.key == defaultSchema?.key } ?: candidates.first()
    return TableAtCaret(chosen.name, chosen.schema)
}

/**
 * 结果集行列转制（仅展示视图）：原列 → 新表行，首列标签为原列名，另起一列“行 N”作表头。
 * 转制基于已读出的行（截断后仍只含 MAX_ROWS 内的行）。
 */
fun transposeResult(result: QueryResult): QueryResult {
    val rowCount = result.rows.size
    // 新表头：首列“列名”承载原列名标签，其后每列对应原表一行
    val newCols = listOf(QueryColumn("列名")) + (1..rowCount).map { QueryColumn("行 $it") }
    val newRows: List<List<String?>> = result.columns.mapIndexed { c, col ->
        listOf(col.name) + result.rows.map { it[c] }
    }
    return result.copy(columns = newCols, rows = newRows, truncated = false)
}

/** 从 SQL 中粗提取可回填的表名（FROM/INTO/UPDATE 后首个标识符，允许 schema.表 / 引号）。 */
fun extractTableName(sql: String): String? {
    val m = Regex("(?i)\\b(?:from|into|update)\\s+([`\"\\[]?[A-Za-z_][A-Za-z0-9_$`\"\\[\\].-]*)")
        .find(sql)
    return m?.groupValues?.get(1)
}

/**
 * 把一行结果组成 INSERT 语句（值一律加引号、单引号翻倍；NULL → NULL）。
 * 返回 null 表示无法确定源表（复杂查询），调用方应隐藏对应菜单项。
 */
fun rowToInsertSql(sql: String, colNames: List<String>, row: List<String?>): String? {
    val table = extractTableName(sql) ?: return null
    val cols = colNames.joinToString(", ") { name ->
        if (Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(name)) name
        else "\"${name.replace("\"", "\"\"")}\""
    }
    val vals = row.joinToString(", ") { v ->
        if (v == null) "NULL" else "'${v.replace("'", "''")}'"
    }
    return "INSERT INTO $table ($cols) VALUES ($vals);"
}
