package app.ui

import androidx.compose.ui.graphics.Color
import com.neoutils.highlight.core.extension.textColor
import com.neoutils.highlight.core.scope.HighlightScope
import com.neoutils.highlight.core.util.UiColor

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
)

internal fun sqlSyntaxPalette(isDark: Boolean): SqlSyntaxPalette =
    if (isDark) {
        SqlSyntaxPalette(
            keyword = Color(0xFF569CD6),      // VSCode Dark+ 蓝
            string = Color(0xFFCE9178),       // 橙
            number = Color(0xFFB5CEA8),       // 绿
            comment = Color(0xFF6A9955),      // 注释绿（压暗）
            punctuation = Color(0xFFD4D4D4),  // 浅灰
        )
    } else {
        SqlSyntaxPalette(
            keyword = Color(0xFF0000FF),      // api-x 浅色关键字蓝
            string = Color(0xFFA31515),       // 暗红
            number = Color(0xFF098658),       // 墨绿
            comment = Color(0xFF008000),      // 绿
            punctuation = Color(0xFF242424),  // 近黑
        )
    }

/** androidx Color → NeoUtils UiColor（api-x 同款转换，ARGB 打包）。 */
internal fun Color.toUiColor(): UiColor {
    val r = (this.red * 255).toInt()
    val g = (this.green * 255).toInt()
    val b = (this.blue * 255).toInt()
    val a = (this.alpha * 255).toInt()
    return UiColor.Integer((a shl 24) or (r shl 16) or (g shl 8) or b)
}

/**
 * 向 [HighlightScope] 注册 SQL 高亮规则（编辑器与只读 DDL 查看器共用同一份，避免两边漂移）。
 *
 * 用**单次交替正则**（leftmost-first）分词，而非多条独立规则：字符串/注释排在关键字前，
 * 一旦整段被消费就不再回扫，因此字符串/注释内的关键字不会被再染（`'select'` 全橙、
 * `-- select` 全绿），同时 `-- don't` 的撇号不会误开字符串、`'-- x'` 的 `--` 不会误当注释。
 * [keywords] 可在调用处 remember，避免每次重组重建词表。
 */
internal fun HighlightScope.applySqlHighlightRules(
    pal: SqlSyntaxPalette,
    keywords: List<String> = sqlHighlightKeywords(),
) {
    val pattern = buildString {
        // 分组顺序即取色顺序（1..7），字符串/注释必须在关键字之前
        append("('(?:[^']|'')*')") // 1 单引号字符串
        append("|(\"(?:[^\"]|\"\")*\")") // 2 双引号字符串
        append("|(--[^\n]*)") // 3 行注释
        append("|(/\\*[\\s\\S]*?\\*/)") // 4 块注释
        append("|(\\b\\d+(?:\\.\\d+)?\\b)") // 5 数字
        append("|(\\b(?:${keywords.distinct().joinToString("|")})\\b)") // 6 关键字
        append("|([(),;.])") // 7 标点
    }
    // 顺序与 pattern 内的分组一一对应（groups 按 1..n 取色）。
    val colors = arrayOf(
        pal.string.toUiColor(), pal.string.toUiColor(),
        pal.comment.toUiColor(), pal.comment.toUiColor(),
        pal.number.toUiColor(), pal.keyword.toUiColor(), pal.punctuation.toUiColor(),
    )
    textColor { Regex(pattern, RegexOption.IGNORE_CASE).groups(*colors) }
}

/**
 * SQL 关键字词表（高亮与补全共用）。规则说明见 [applySqlHighlightRules]。
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
