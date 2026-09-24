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
