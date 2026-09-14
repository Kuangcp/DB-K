package app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ES JSON DSL 补全纯逻辑：上下文识别 + 候选 + 自适应插入文本。 */
class EsDslCompletionTest {

    private val fields = listOf("name", "age", "nested", "created_at")
    private val indices = listOf("logs-2024", "logs-2025")

    private fun sugg(
        text: String,
        caret: Int = text.length,
        fields: List<String> = emptyList(),
        indices: List<String> = emptyList(),
    ) = esDslSuggestions(text, caret, fields, indices)

    private fun items(s: EsDslSuggestion?) = s?.items.orEmpty().map { it.text }

    @Test
    fun `top level keys after opening a quoted key`() {
        val s = assertNotNull(sugg("""{"qu"""))
        assertEquals("qu", s.word.text)
        assertTrue("query" in items(s))
        val query = s.items.single { it.text == "query" }
        assertEquals("""query": """, query.insertText)
        assertEquals(CompletionKind.KEYWORD, query.kind)
    }

    @Test
    fun `key completion consumes an existing closing quote`() {
        val text = """{"qu"}"""
        val caret = 4
        val s = assertNotNull(sugg(text, caret))
        assertEquals(5, s.word.end) // 覆盖右侧的收尾引号
        assertEquals("""query": """, s.items.single { it.text == "query" }.insertText)
    }

    @Test
    fun `query types inside query object`() {
        val s = assertNotNull(sugg("""{"query": {"bo"""))
        assertEquals(listOf("bool"), items(s))
    }

    @Test
    fun `bool clauses inside bool object`() {
        assertTrue("must" in items(sugg("""{"query": {"bool": {"mu""")))
        assertTrue("must_not" in items(sugg("""{"query":{"bool":{"must""")))
    }

    @Test
    fun `query types inside a bool clause array element`() {
        val got = items(sugg("""{"query":{"bool":{"must":[{"te"""))
        assertTrue("term" in got && "terms" in got, got.toString())
    }

    @Test
    fun `field names inside a query type object`() {
        val s = assertNotNull(sugg("""{"query": {"match": {"na""", fields = fields))
        assertEquals(listOf("name"), items(s))
        assertEquals("""name"""", s.items.single().insertText)
        assertEquals(CompletionKind.COLUMN, s.items.single().kind)
    }

    @Test
    fun `index value suggests known indices`() {
        val s = assertNotNull(sugg("""{"index": "lo""", indices = indices))
        assertEquals(listOf("logs-2024", "logs-2025"), items(s))
        assertEquals("""logs-2024"""", s.items.first().insertText)
    }

    @Test
    fun `index value with closing quote consumes it and re-adds`() {
        val text = """{"index": "logs"}"""
        val caret = 15 // 位于收尾引号之前
        assertEquals('"', text[caret])
        val s = assertNotNull(sugg(text, caret, indices = indices))
        assertEquals(caret + 1, s.word.end)
        assertEquals("""logs-2024"""", s.items.first().insertText)
    }

    @Test
    fun `order value suggests asc desc`() {
        val s = assertNotNull(sugg("""{"sort":[{"name":{"order":"d""", fields = fields))
        assertEquals(listOf("desc"), items(s))
    }

    @Test
    fun `sort array element object keys are field names`() {
        assertTrue("name" in items(sugg("""{"sort":[{"na""", fields = fields)))
    }

    @Test
    fun `agg type object and params`() {
        assertTrue("terms" in items(sugg("""{"aggs":{"my_agg":{"te""")))
        assertTrue("field" in items(sugg("""{"aggs":{"my_agg":{"terms":{"fi""")))
    }

    @Test
    fun `field value inside agg param`() {
        val s = assertNotNull(sugg("""{"aggs":{"a":{"terms":{"field":"na""", fields = fields))
        assertEquals(listOf("name"), items(s))
    }

    @Test
    fun `empty prefix in key position yields context (for Ctrl plus Space)`() {
        val s = assertNotNull(sugg("{"))
        assertEquals("", s.word.text)
        assertTrue("query" in items(s))
    }

    @Test
    fun `unknown key yields no value completion`() {
        assertNull(sugg("""{"foo": """))
    }

    @Test
    fun `non-string position inserts the opening quote too`() {
        val s = assertNotNull(sugg("""{ind"""))
        assertEquals("\"index\": ", s.items.single { it.text == "index" }.insertText)
    }

    @Test
    fun `index name extraction`() {
        assertEquals("logs-1", esDslIndexName("""{"index":"logs-1","query":{}}"""))
        assertEquals("x", esDslIndexName("""{"_index": "x"}"""))
        assertNull(esDslIndexName("""{"query":{}}"""))
    }
}
