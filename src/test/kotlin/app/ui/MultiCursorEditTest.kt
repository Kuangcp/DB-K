package app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MultiCursorEditTest {

    @Test
    fun `addCursorUpDown keeps column and clamps to shorter line`() {
        assertEquals(3, addCursorUpDown("aa\nbb\ncc", 0, 1))
        assertEquals(1, addCursorUpDown("aa\nbb\ncc", 4, -1))
        assertEquals(5, addCursorUpDown("abc\nx", 2, 1))
    }

    @Test
    fun `addCursorUpDown returns null at boundaries and handles trailing empty line`() {
        assertNull(addCursorUpDown("aa", 0, -1))
        assertNull(addCursorUpDown("aa", 0, 1))
        assertEquals(3, addCursorUpDown("aa\n", 0, 1))
    }

    @Test
    fun `shiftCursors moves heads and clamps to text bounds`() {
        val cursors = listOf(CursorSel(0, 0), CursorSel(4, 4))
        assertEquals(listOf(CursorSel(0, 1), CursorSel(4, 5)), shiftCursors(cursors, 1, 10))
        assertEquals(listOf(CursorSel(0, 0), CursorSel(4, 3)), shiftCursors(cursors, -1, 10))
    }

    @Test
    fun `applyMultiCursorEdit inserts at every cursor`() {
        val res = applyMultiCursorEdit("aa\nbb", listOf(CursorSel(1, 1), CursorSel(4, 4)), MultiEditOp.Insert('x'))
        assertEquals("axa\nbxb", res.text)
        assertEquals(listOf(CursorSel(2, 2), CursorSel(6, 6)), res.cursors)
    }

    @Test
    fun `applyMultiCursorEdit backspace collapses cursors and merges lines`() {
        val res = applyMultiCursorEdit("aa\nbb", listOf(CursorSel(1, 1), CursorSel(4, 4)), MultiEditOp.Backspace)
        assertEquals("a\nb", res.text)
        assertEquals(listOf(CursorSel(0, 0), CursorSel(2, 2)), res.cursors)
    }

    @Test
    fun `applyMultiCursorEdit delete at end keeps that cursor at new end`() {
        val res = applyMultiCursorEdit("aa\nbb", listOf(CursorSel(1, 1), CursorSel(4, 4)), MultiEditOp.Delete)
        assertEquals("a\nb", res.text)
        assertEquals(listOf(CursorSel(1, 1), CursorSel(3, 3)), res.cursors)
    }

    @Test
    fun `applyMultiCursorEdit replaces selections`() {
        val res = applyMultiCursorEdit(
            "ab\ncd",
            listOf(CursorSel(0, 1), CursorSel(3, 4)),
            MultiEditOp.Insert('X'),
        )
        assertEquals("Xb\nXd", res.text)
        assertEquals(listOf(CursorSel(1, 1), CursorSel(4, 4)), res.cursors)
    }

    @Test
    fun `applyMultiCursorEdit inserts newline at every cursor`() {
        val res = applyMultiCursorEdit(
            "ab\ncd",
            listOf(CursorSel(0, 0), CursorSel(3, 3)),
            MultiEditOp.Insert('\n'),
        )
        assertEquals("\nab\n\ncd", res.text)
        assertEquals(listOf(CursorSel(1, 1), CursorSel(5, 5)), res.cursors)
    }

    @Test
    fun `applyMultiCursorEdit backspaceWord deletes previous word at every cursor`() {
        // "foo bar\nbaz qux"：光标在 bar 末尾(7) 与 qux 末尾(15)
        val res = applyMultiCursorEdit(
            "foo bar\nbaz qux",
            listOf(CursorSel(7, 7), CursorSel(15, 15)),
            MultiEditOp.BackspaceWord,
        )
        assertEquals("foo \nbaz ", res.text)
        assertEquals(listOf(CursorSel(4, 4), CursorSel(9, 9)), res.cursors)
    }

    @Test
    fun `applyMultiCursorEdit deleteWord deletes next word at every cursor`() {
        // "foo bar\nbaz qux"：光标在 foo 开头(0) 与 baz 开头(8)
        val res = applyMultiCursorEdit(
            "foo bar\nbaz qux",
            listOf(CursorSel(0, 0), CursorSel(8, 8)),
            MultiEditOp.DeleteWord,
        )
        assertEquals(" bar\n qux", res.text)
        assertEquals(listOf(CursorSel(0, 0), CursorSel(5, 5)), res.cursors)
    }

    @Test
    fun `applyMultiCursorEdit word deletion removes selection when present`() {
        val res = applyMultiCursorEdit(
            "ab\ncd",
            listOf(CursorSel(0, 1), CursorSel(3, 4)),
            MultiEditOp.BackspaceWord,
        )
        assertEquals("b\nd", res.text)
        assertEquals(listOf(CursorSel(0, 0), CursorSel(2, 2)), res.cursors)
    }

    @Test
    fun `applyMultiCursorEdit with no cursors returns original text`() {
        val res = applyMultiCursorEdit("aa", emptyList(), MultiEditOp.Insert('x'))
        assertEquals("aa", res.text)
        assertTrue(res.cursors.isEmpty())
    }
}
