package app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 结果表列宽估算回归：CJK 列名按双宽计，且 12 字符内不省略。
 * 历史 bug：`当前会话数`（5 个汉字）被估成 64dp，减去 38dp 表头固定占位后只剩 26dp → 渲染成「当…」。
 */
class ResultTableWidthTest {

    @Test
    fun `cjk chars count double width`() {
        assertEquals(5 * 12, textWidthDp("当前会话数"))
        assertEquals(5 * 7, textWidthDp("count"))
        // 混合：ASCII 单宽 + CJK 双宽
        assertEquals(2 * 7 + 2 * 12, textWidthDp("id当前"))
    }

    @Test
    fun `short cjk column name is not truncated`() {
        val name = "当前会话数"
        val minWidth = headerMinWidthDp(name)
        // 减去固定占位后仍要放得下完整列名
        assertTrue(
            minWidth - HEADER_CHROME_DP >= textWidthDp(name),
            "header min width $minWidth cannot fit \"$name\" (${textWidthDp(name)}dp)",
        )
    }

    @Test
    fun `names within the 12 char limit all fit`() {
        for (n in 1..HEADER_NAME_MAX_CHARS) {
            val name = "列".repeat(n)
            val minWidth = headerMinWidthDp(name)
            assertTrue(
                minWidth - HEADER_CHROME_DP >= textWidthDp(name),
                "name of $n chars does not fit in $minWidth dp",
            )
        }
    }

    @Test
    fun `long names are capped at the limit plus ellipsis`() {
        val capped = headerMinWidthDp("列".repeat(HEADER_NAME_MAX_CHARS))
        val longer = headerMinWidthDp("列".repeat(HEADER_NAME_MAX_CHARS + 8))
        // 长列名不再无限撑宽，只比上限多一个省略号宽度
        assertEquals(capped + textWidthDp("…"), longer)
        assertEquals(
            HEADER_NAME_MAX_CHARS * 12 + textWidthDp("…") + HEADER_CHROME_DP,
            longer,
        )
    }
}

