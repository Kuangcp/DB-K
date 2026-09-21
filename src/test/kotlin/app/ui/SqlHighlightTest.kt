package app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 守卫 [sqlHighlightSpans]：编辑器与只读 DDL 查看器共用这份规则，
 * 抽出来后用纯逻辑断言 token 命中情况（不依赖 Compose 运行时，可直接跑单测）。
 *
 * 规则此前是单条正则，因 `('(?:[^']|'')*')` 的 `(a|b)*` 在 java.util.regex 里按重复次数
 * **递归**，长字符串字面量会 StackOverflowError（2026-09-16 卡死事故），故改为手写扫描。
 */
class SqlHighlightTest {

    private val pal = sqlSyntaxPalette(themeById("light")!!.colors)
    private val keywords = sqlHighlightKeywordSet()

    private fun highlight(text: String): AnnotatedString =
        AnnotatedString(text, spanStyles = sqlHighlightSpans(text, pal, keywords))

    /** 覆盖 [index] 的最内层 span 颜色；无 span 覆盖则 null。 */
    private fun colorAt(a: AnnotatedString, index: Int): Color? =
        a.spanStyles.lastOrNull { index >= it.start && index < it.end }?.item?.color

    @Test
    fun highlightsCommentsStringsNumbersKeywordsAndPunctuation() {
        //                                     0123456789...
        val text = "SELECT 'x', 42 -- note"
        val a = highlight(text)

        assertEquals(pal.keyword, colorAt(a, 0), "关键字 SELECT 应取 keyword 色")
        assertEquals(pal.string, colorAt(a, 8), "字符串 'x' 应取 string 色")
        assertEquals(pal.punctuation, colorAt(a, 10), "逗号应取 punctuation 色")
        assertEquals(pal.number, colorAt(a, 12), "数字 42 应取 number 色")
        assertEquals(pal.comment, colorAt(a, 18), "行注释应取 comment 色")

        // 各类 token 使用不同色（互不串色）
        val kw = colorAt(a, 0)
        assertNotEquals(kw, colorAt(a, 8))
        assertNotEquals(kw, colorAt(a, 12))
        assertNotEquals(colorAt(a, 8), colorAt(a, 18))
    }

    @Test
    fun leavesPlainIdentifiersUncolored() {
        val a = highlight("CREATE TABLE my_table")
        // DDL 关键字应着色
        assertEquals(pal.keyword, colorAt(a, 0))
        assertEquals(pal.keyword, colorAt(a, 7))
        // my_table 是普通标识符，不应被任一规则命中
        assertNull(colorAt(a, 15), "普通标识符被误着色")
    }

    @Test
    fun keywordInsideStringOrCommentIsNotRecolored() {
        val a = highlight("'select' -- select")
        // 整个字符串（含内部关键字）都是 string 色
        assertEquals(pal.string, colorAt(a, 2), "字符串内关键字串色")
        assertEquals(pal.string, colorAt(a, 7))
        // 整个行注释（含内部关键字）都是 comment 色
        assertEquals(pal.comment, colorAt(a, 12), "注释内关键字串色")
        assertEquals(pal.comment, colorAt(a, 15))
    }

    @Test
    fun quoteAndDashDoNotLeakAcrossStringAndComment() {
        // 注释里的撇号不该开启字符串
        assertEquals(pal.comment, colorAt(highlight("-- don't"), 4))
        // 字符串里的 -- 不该被当作注释
        assertEquals(pal.string, colorAt(highlight("'-- x'"), 3))
    }

    @Test
    fun blockCommentAndDoubleQuotedString() {
        val a = highlight("/* c */ \"tbl\" ;")
        assertEquals(pal.comment, colorAt(a, 1), "块注释应取 comment 色")
        assertEquals(pal.string, colorAt(a, 9), "双引号标识符应取 string 色")
        assertEquals(pal.punctuation, colorAt(a, 14), "分号应取 punctuation 色")
    }

    @Test
    fun bothPalettesRenderWithoutError() {
        listOf(true, false).forEach { dark ->
            val p = sqlSyntaxPalette((if (dark) themeById("dark") else themeById("light"))!!.colors)
            val a = AnnotatedString(
                "CREATE TABLE t (id INT DEFAULT 0); -- c",
                spanStyles = sqlHighlightSpans("CREATE TABLE t (id INT DEFAULT 0); -- c", p, keywords),
            )
            assertTrue(a.spanStyles.isNotEmpty(), "isDark=$dark 未产生任何高亮 span")
        }
    }

    /** 回归：长字符串字面量（含 `''` 转义）必须先扫完不爆栈——旧正则在 ~5k 字符就崩。 */
    @Test
    fun longStringLiteralDoesNotOverflowStack() {
        val literal = "'" + "x".repeat(300_000) + "'"
        val a = highlight("SELECT $literal AS s")
        assertEquals(pal.string, colorAt(a, 100_000), "超长字符串字面量应整体着色且不爆栈")

        val escaped = "'" + "''".repeat(150_000) + "'"
        val b = highlight("SELECT $escaped")
        assertEquals(pal.string, colorAt(b, 100_000), "大量 '' 转义字面量不应爆栈")
    }

    /** 未闭合的字面量/注释不能越界，也不能吞掉后续扫描。 */
    @Test
    fun unterminatedTokensAreClamped() {
        val a = highlight("SELECT 'oops")
        assertEquals(pal.string, colorAt(a, 7))
        val b = highlight("-- no newline")
        assertEquals(pal.comment, colorAt(b, 3))
        val c = highlight("/* unterminated")
        assertEquals(pal.comment, colorAt(c, 3))
    }
}
