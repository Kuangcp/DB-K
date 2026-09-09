package app.ui

import jdbc.QueryColumn
import jdbc.QueryResult

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

/**
 * 补全候选：数据源对象名（表/视图）优先，其次关键字；大小写不敏感前缀匹配。
 */
fun completionCandidates(word: String, identifiers: List<String>): List<String> {
    if (word.isBlank()) return emptyList()
    val prefix = word.lowercase()
    val out = ArrayList<String>()
    for (id in identifiers) {
        if (id.length > prefix.length && id.lowercase().startsWith(prefix)) out.add(id)
    }
    for (kw in SQL_KEYWORDS) {
        if (kw.length > prefix.length && kw.lowercase().startsWith(prefix)) out.add(kw)
    }
    return out.distinct()
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
