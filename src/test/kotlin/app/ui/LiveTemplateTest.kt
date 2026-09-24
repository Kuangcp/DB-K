package app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class LiveTemplateExpansionTest {

    @Test
    fun `placeholder becomes empty with a stop`() {
        val e = expandTemplate("SELECT \$columns\$ FROM \$table\$")
        assertEquals("SELECT  FROM ", e.text)
        assertEquals(listOf(TemplateStop("columns", 7, 7), TemplateStop("table", 13, 13)), e.stops)
        assertEquals(13, e.endOffset)
    }

    @Test
    fun `END sets final offset and is not a stop`() {
        val e = expandTemplate("A\$END\$B")
        assertEquals("AB", e.text)
        assertEquals(emptyList<TemplateStop>(), e.stops)
        assertEquals(1, e.endOffset)
    }

    @Test
    fun `missing END defaults to text end`() {
        val e = expandTemplate("SELECT 1")
        assertEquals("SELECT 1", e.text)
        assertEquals(emptyList<TemplateStop>(), e.stops)
        assertEquals(8, e.endOffset)
    }

    @Test
    fun `double dollar is a literal dollar`() {
        assertEquals("a\$b", expandTemplate("a\$\$b").text)
    }

    @Test
    fun `unclosed markers stay literal and well-formed placeholder is removed`() {
        assertEquals("a\$b", expandTemplate("a\$b").text)
        assertEquals("B\$", expandTemplate("\$A\$B\$").text)
        assertEquals("", expandTemplate("\$A\$").text)
    }

    @Test
    fun `repeated names are separate stops`() {
        val e = expandTemplate("\$a\$-\$a\$")
        assertEquals("-", e.text)
        assertEquals(listOf(TemplateStop("a", 0, 0), TemplateStop("a", 1, 1)), e.stops)
    }

    @Test
    fun `builtins expose the documented abbreviations`() {
        val abbrevs = defaultLiveTemplates().map { it.abbreviation }
        assertEquals(
            listOf("sel", "selw", "selc", "ins", "upd", "del", "whe", "ob", "gb", "case"),
            abbrevs,
        )
        assertEquals("UPDATE \$table\$ SET \$column\$ = \$value\$ WHERE \$condition\$", defaultLiveTemplates()[4].body)
    }
}

class LiveTemplateSessionTest {

    private fun session() = beginSession("sel x", 0 until 3, "SELECT \$c\$ FROM \$t\$").second

    @Test
    fun `beginSession replaces the word and selects first stop`() {
        val (text, s) = beginSession("sel x", 0 until 3, "SELECT \$c\$ FROM \$t\$")
        assertEquals("SELECT  FROM  x", text)
        assertEquals(0, s.anchorStart)
        assertEquals(listOf(TemplateStop("c", 7, 7), TemplateStop("t", 13, 13)), s.stops)
        assertEquals(0, s.activeIndex)
        assertEquals(13, s.endOffset)
        assertEquals(TemplateStop("c", 7, 7), activeStop(s))
    }

    @Test
    fun `beginSession with no stops has no active stop`() {
        val (_, s) = beginSession("sel", 0 until 3, "SELECT 1")
        assertEquals(emptyList<TemplateStop>(), s.stops)
        assertEquals(-1, s.activeIndex)
        assertEquals(null, activeStop(s))
    }

    @Test
    fun `sessionNext walks stops then ends at endOffset`() {
        val s = session()
        val (n1, sel1) = sessionNext(s)
        assertEquals(1, n1!!.activeIndex)
        assertEquals(13 to 13, sel1)
        val (n2, sel2) = sessionNext(n1)
        assertEquals(null, n2)
        assertEquals(13 to 13, sel2)
    }

    @Test
    fun `sessionPrev walks back and clamps at first`() {
        val s = session()
        val (a, selA) = sessionPrev(s)
        assertEquals(0, a.activeIndex)
        assertEquals(7 to 7, selA)
        val (n1, _) = sessionNext(s)
        val (b, selB) = sessionPrev(n1!!)
        assertEquals(0, b.activeIndex)
        assertEquals(7 to 7, selB)
    }
}
