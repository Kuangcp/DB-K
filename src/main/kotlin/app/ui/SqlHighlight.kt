package app.ui

import androidx.compose.ui.graphics.Color
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
 * 高亮规则（调用方在 `rememberHighlight { }` 作用域内逐个 textColor { … } 注册）：
 * 注释 → 字符串（单/双引号，含 '' 转义）→ 数字 → 关键字（大小写不敏感）。
 * 规则之间基本互斥；注释与字符串先注册，其内部关键字/数字不会误染。
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
