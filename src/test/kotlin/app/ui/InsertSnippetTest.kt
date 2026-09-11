package app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 守卫历史 SQL「插入到当前控制台」的插入语义：在光标/选区处插入、不覆盖整段草稿，
 * 光标落到插入内容之后；仅行尾紧跟非空白时补换行，避免粘句。
 */
class InsertSnippetTest {

    @Test
    fun insertsIntoEmptyConsole() {
        val (text, caret) = insertSnippetAtCaret("", 0, 0, "SELECT 1")
        assertEquals("SELECT 1", text)
        assertEquals(8, caret)
    }

    @Test
    fun appendsAtEndWithNewlineSeparator() {
        val (text, caret) = insertSnippetAtCaret("SELECT 1", 8, 8, "SELECT 2")
        assertEquals("SELECT 1\nSELECT 2", text)
        assertEquals(17, caret)
    }

    @Test
    fun noDoubleNewlineWhenTextAlreadyEndsWithNewline() {
        val (text, caret) = insertSnippetAtCaret("SELECT 1\n", 9, 9, "SELECT 2")
        assertEquals("SELECT 1\nSELECT 2", text)
        assertEquals(17, caret)
    }

    @Test
    fun insertsInlineWhenNotAtLineEnd() {
        val (text, caret) = insertSnippetAtCaret("ABCDEF", 3, 3, "X")
        assertEquals("ABCXDEF", text)
        assertEquals(4, caret)
    }

    @Test
    fun addsNewlineWhenInsertingAtLineEndBeforeBreak() {
        val (text, caret) = insertSnippetAtCaret("AB\nCD", 2, 2, "X")
        assertEquals("AB\nX\nCD", text)
        assertEquals(4, caret)
    }

    @Test
    fun replacesSelectionWithoutTouchingRest() {
        val (text, caret) = insertSnippetAtCaret("ABC", 0, 3, "Z")
        assertEquals("Z", text)
        assertEquals(1, caret)
    }

    @Test
    fun replacesMidSelectionInline() {
        // 选中 "ABC" 中的 "B" 并替换
        val (text, caret) = insertSnippetAtCaret("ABC", 1, 2, "X")
        assertEquals("AXC", text)
        assertEquals(2, caret)
    }

    @Test
    fun reversedSelectionStillWorks() {
        // 反向选择（start > end）
        val (text, caret) = insertSnippetAtCaret("ABC", 3, 0, "Z")
        assertEquals("Z", text)
        assertEquals(1, caret)
    }
}
