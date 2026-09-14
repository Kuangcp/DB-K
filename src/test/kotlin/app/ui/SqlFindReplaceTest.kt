package app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class SqlFindReplaceTest {

    private val text = "SELECT id, name FROM users WHERE name = 'Bob'"

    @Test
    fun `literal find is case insensitive by default and non overlapping`() {
        assertEquals(listOf(11 until 13, 33 until 35), findMatches(text, "nA", FindOptions()))
        assertEquals(listOf(0 until 2), findMatches("aaa", "aa", FindOptions()))
    }

    @Test
    fun `case sensitive find`() {
        assertEquals(emptyList(), findMatches(text, "select", FindOptions(caseSensitive = true)))
        assertEquals(listOf(0 until 6), findMatches(text, "SELECT", FindOptions(caseSensitive = true)))
    }

    @Test
    fun `regex find`() {
        assertEquals(listOf(11 until 13, 33 until 35), findMatches(text, "\\bna", FindOptions(regex = true)))
        val nums = findMatches("a1 b22 c333", "\\d+", FindOptions(regex = true))
        assertEquals(listOf(1 until 2, 4 until 6, 8 until 11), nums)
    }

    @Test
    fun `whole word find skips partial identifiers`() {
        val t = "user username user"
        assertEquals(listOf(0 until 4, 14 until 18), findMatches(t, "user", FindOptions(wholeWord = true)))
    }

    @Test
    fun `replace range and replace all literal`() {
        assertEquals("SELECT id, name FROM users WHERE title = 'Bob'", replaceRange(text, 33 until 37, "title"))
        val (out, n) = replaceAll(text, "name", FindOptions(), "title")
        assertEquals(2, n)
        assertEquals("SELECT id, title FROM users WHERE title = 'Bob'", out)
    }

    @Test
    fun `replace all regex expands groups`() {
        val (out, n) = replaceAll("a1 b2", "(\\w)(\\d)", FindOptions(regex = true), "$2$1")
        assertEquals(2, n)
        assertEquals("1a 2b", out)
    }

    @Test
    fun `invalid regex yields no matches and unchanged text`() {
        assertEquals(emptyList(), findMatches("abc", "(", FindOptions(regex = true)))
        assertEquals("abc" to 0, replaceAll("abc", "(", FindOptions(regex = true), "x"))
    }
}
