package app.ui

import androidx.compose.ui.graphics.Color

/**
 * SQL 编辑器语法高亮的色板与词表。
 *
 * 配色参照 api-x / VS Code 语义色，深/浅两套（随主题切换，见 EditorPane 内 `MaterialTheme.colors.isLight`）。
 * 这些是「语法 token 色」：与 AGENTS.md 的对象徽章语义色同级，仅用于编辑器着色，
 * 界面常规文字色仍一律走主题（onSurface 等）。
 */
internal data class SqlSyntaxPalette(
    val keyword: Color,      // SELECT/FROM/WHERE…
    val string: Color,       // '...' 与 "..."（字符串/标识符引号体）
    val number: Color,       // 数字字面量
    val comment: Color,      // -- 行注释 与 /* */ 块注释
    val punctuation: Color,  // ( ) , ; . 等符号（低饱和，微光）
    val function: Color,     // 函数名（标识符后紧跟 `(`，中间可含空白）
)

internal fun sqlSyntaxPalette(colors: ThemeColors): SqlSyntaxPalette = SqlSyntaxPalette(
    keyword = colors.keyword,
    string = colors.string,
    number = colors.number,
    comment = colors.comment,
    punctuation = colors.punctuation,
    function = colors.function,
)

/**
 * 高亮用关键字集合（大写，供 [sqlHighlightSpans] O(1) 比对；调用处 remember 一次即可）。
 *
 * 高亮本体在 `SyntaxHighlight.kt`：**不能用正则**——`('(?:[^']|'')*')` 这类 `(a|b)*` 在
 * java.util.regex 里按重复次数递归，长字符串字面量会把栈打爆（见那里的注释）。
 */
internal fun sqlHighlightKeywordSet(): Set<String> =
    sqlHighlightKeywords().mapTo(HashSet()) { it.uppercase() }

/**
 * 语法高亮降级阈值：「段落数 × span 数」上限。
 *
 * Compose 的 MultiParagraph 排版会为每个段落扫描全部 spanStyles，成本近似 O(段数 × span 数)：
 * 数千行 SQL 会产生数万 span，首帧/每次重排版可达数秒并卡死 UI（实测 2973 行 / 5.9 万 span
 * 首帧 ~2.8s，1.2 万行 / 9.2 万 span ~7.7s）。超过预算时不再生成高亮 span，
 * 让排版回落到与文档长度线性的纯文本排版。
 */
internal const val MAX_HIGHLIGHT_WORK = 20_000_000L

/** 是否保留语法高亮 span：`span 数 × 段落（行）数` 在预算内才保留。[lineCount] 至少为 1。 */
internal fun shouldHighlight(spans: Int, lineCount: Int): Boolean =
    spans.toLong() * lineCount.coerceAtLeast(1) <= MAX_HIGHLIGHT_WORK

/**
 * SQL 关键字词表（高亮与补全共用）。
 */
internal fun sqlHighlightKeywords(): List<String> = listOf(
    // 查询
    "SELECT", "FROM", "WHERE", "GROUP", "BY", "HAVING", "ORDER", "LIMIT", "OFFSET",
    "DISTINCT", "ALL", "AS", "ALIAS", "UNION", "EXCEPT", "INTERSECT", "WITH", "RECURSIVE",
    // 连接
    "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS", "NATURAL", "ON", "USING",
    // 条件/表达式
    "AND", "OR", "NOT", "IN", "IS", "NULL", "LIKE", "ILIKE", "GLOB", "BETWEEN", "CASE",
    "WHEN", "THEN", "ELSE", "END", "EXISTS", "ANY", "SOME", "COLLATE", "ESCAPE", "CAST",
    // DML
    "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "MERGE", "UPSERT", "REPLACE",
    "RETURNING", "DEFAULT", "CONFLICT", "ON", "DO", "NOTHING",
    // DDL
    "CREATE", "TABLE", "TEMPORARY", "TEMP", "VIEW", "MATERIALIZED", "INDEX", "UNIQUE",
    "DROP", "ALTER", "ADD", "COLUMN", "RENAME", "TRUNCATE", "IF", "EXISTS", "PRIMARY",
    "KEY", "FOREIGN", "REFERENCES", "CONSTRAINT", "CHECK", "AUTOINCREMENT", "GENERATED",
    // 事务/控制
    "BEGIN", "COMMIT", "ROLLBACK", "TRANSACTION", "SAVEPOINT", "RELEASE", "PRAGMA",
    "EXPLAIN", "QUERY", "PLAN", "ANALYZE", "VACUUM", "ATTACH", "DETACH", "USE", "DATABASE",
    // 其它
    "DESC", "ASC", "NULLS", "FIRST", "LAST", "INTO", "OUT", "FETCH", "FOR", "UPDATE",
    "OVER", "PARTITION", "WINDOW", "LATERAL", "SYSTEM",
)
