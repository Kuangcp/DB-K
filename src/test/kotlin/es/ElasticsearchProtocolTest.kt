package es

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Elasticsearch 纯逻辑：DSL 解析 / 分页改写 / `_search` 响应渲染 / `_cat` 与 `_mapping` 解析。 */
class ElasticsearchProtocolTest {

    // ---------- parseRequest ----------

    @Test
    fun `parses index from dsl and strips it from body`() {
        val req = ElasticsearchProtocol.parseRequest(
            """{"index":"logs-2024","query":{"match_all":{}},"size":5}""",
        )
        assertEquals("logs-2024", req.index)
        assertEquals(5, req.size)
        assertEquals(0, req.from)
        assertTrue("index" !in req.body)
        assertTrue("query" in req.body)
    }

    @Test
    fun `accepts _index alias and falls back to default index`() {
        assertEquals("a", ElasticsearchProtocol.parseRequest("""{"_index":"a"}""").index)
        assertEquals("d", ElasticsearchProtocol.parseRequest("""{"query":{}}""", "d").index)
        assertNull(ElasticsearchProtocol.parseRequest("""{"query":{}}""").index)
    }

    @Test
    fun `injects default paging when absent`() {
        val req = ElasticsearchProtocol.parseRequest("""{"index":"i"}""")
        assertEquals(ElasticsearchProtocol.DEFAULT_SIZE, req.size)
        assertEquals(0, req.from)
        assertTrue(req.body.toString().contains("\"size\""))
    }

    @Test
    fun `rejects blank and non-object dsl`() {
        assertFailsWith<IllegalArgumentException> { ElasticsearchProtocol.parseRequest("   ") }
        assertFailsWith<IllegalArgumentException> { ElasticsearchProtocol.parseRequest("[1,2]") }
        assertFailsWith<IllegalArgumentException> { ElasticsearchProtocol.parseRequest("{oops") }
    }

    @Test
    fun `search path uses index when present`() {
        assertEquals("/i/_search", ElasticsearchProtocol.searchPath(ElasticsearchProtocol.parseRequest("""{"index":"i"}""")))
        assertEquals("/_search", ElasticsearchProtocol.searchPath(ElasticsearchProtocol.parseRequest("""{}""")))
    }

    // ---------- withPagination ----------

    @Test
    fun `pagination rewrites existing from and size`() {
        val next = ElasticsearchProtocol.withPagination(
            """{"index":"i","from":0,"size":100,"query":{"match_all":{}}}""",
            100,
            100,
        )
        assertTrue(next!!.contains("\"from\":100"))
        assertTrue(next.contains("\"size\":100"))
        assertEquals(1, Regex("\"from\"").findAll(next).count())
    }

    @Test
    fun `pagination returns null on invalid dsl`() {
        assertNull(ElasticsearchProtocol.withPagination("not json", 0, 10))
    }

    // ---------- searchResponseToResult ----------

    @Test
    fun `renders hits into columns and rows`() {
        val json = """
            {"took":3,"hits":{"total":{"value":2,"relation":"eq"},"hits":[
              {"_index":"i","_id":"1","_score":1.0,"_source":{"name":"alice","tags":["a","b"]}},
              {"_index":"i","_id":"2","_score":1.0,"_source":{"name":"bob","qty":3}}
            ]}}
        """.trimIndent()
        val result = ElasticsearchProtocol.searchResponseToResult("{dsl}", json, from = 0, size = 100, durationMs = 7)

        assertEquals(listOf("_index", "_id", "_score", "name", "tags", "qty"), result.columns.map { it.name })
        assertEquals(2, result.rows.size)
        assertEquals("alice", result.rows[0][3])
        assertEquals("""["a","b"]""", result.rows[0][4])
        // 第二行没有 tags → null，qty 存在于第 4 列
        assertNull(result.rows[1][4])
        assertEquals("3", result.rows[1][5])
        assertEquals(7, result.durationMs)
        assertEquals(false, result.truncated)
    }

    @Test
    fun `truncated when total exceeds fetched window`() {
        val json = """{"hits":{"total":{"value":17},"hits":[{"_id":"1","_source":{}},{"_id":"2","_source":{}}]}}"""
        val result = ElasticsearchProtocol.searchResponseToResult("{dsl}", json, from = 10, size = 10, durationMs = 1)
        assertTrue(result.truncated)
    }

    @Test
    fun `surfaces es error payload`() {
        val json = """{"error":{"type":"index_not_found_exception","reason":"no such index [x]"},"status":404}"""
        val e = assertFailsWith<IllegalStateException> {
            ElasticsearchProtocol.searchResponseToResult("{dsl}", json, 0, 10, 0)
        }
        assertTrue(e.message!!.contains("no such index [x]"))
    }

    @Test
    fun `handles legacy numeric total`() {
        val json = """{"hits":{"total":5,"hits":[]}}"""
        val result = ElasticsearchProtocol.searchResponseToResult("{dsl}", json, 0, 10, 0)
        assertTrue(result.truncated)
    }

    // ---------- cat / mapping / pretty ----------

    @Test
    fun `parses cat indices and aliases`() {
        assertEquals(
            listOf("logs-1", "logs-2"),
            ElasticsearchProtocol.catIndexNames("""[{"index":"logs-1"},{"index":"logs-2"}]"""),
        )
        assertEquals(
            listOf("current" to "logs-1", "all" to "logs-2"),
            ElasticsearchProtocol.catAliases("""[{"alias":"current","index":"logs-1"},{"alias":"all","index":"logs-2"}]"""),
        )
    }

    @Test
    fun `parses alias listing for indices and aliases in one request`() {
        val json = """{
            "logs-1":{"aliases":{"current":{},"all":{}}},
            "logs-2":{"aliases":{}}
        }""".trimIndent()
        val parsed = ElasticsearchProtocol.parseAliasListing(json)
        assertEquals(listOf("logs-1", "logs-2"), parsed.indices)
        assertEquals(listOf("current" to "logs-1", "all" to "logs-1"), parsed.aliases)
    }

    @Test
    fun `extracts index names from cluster mapping and search agg`() {
        assertEquals(
            listOf("i1", "i2"),
            ElasticsearchProtocol.mappingIndexNames("""{"i1":{"mappings":{}},"i2":{"mappings":{}}}"""),
        )
        val agg = """{"aggregations":{"dbk_indices":{"buckets":[{"key":"i1","doc_count":3},{"key":"i2"}]}}}"""
        assertEquals(listOf("i1", "i2"), ElasticsearchProtocol.searchIndexNames(agg))
        assertEquals(emptyList(), ElasticsearchProtocol.searchIndexNames("""{"hits":{}}"""))
    }

    @Test
    fun `flattens mapping fields including nested and multi-field`() {
        val mapping = """
            {"my-index":{"mappings":{"properties":{
              "title":{"type":"text","fields":{"keyword":{"type":"keyword"}}},
              "user":{"properties":{"name":{"type":"keyword"},"age":{"type":"integer"}}},
              "tags":{"type":"keyword"}
            }}}}
        """.trimIndent()
        val names = ElasticsearchProtocol.mappingFields(mapping).map { it.name }
        assertEquals(listOf("title", "title.keyword", "user.name", "user.age", "tags"), names)
    }

    @Test
    fun `pretty prints valid json and passes through invalid`() {
        assertTrue(ElasticsearchProtocol.pretty("""{"a":1}""").contains("\n"))
        assertEquals("not json", ElasticsearchProtocol.pretty("not json"))
    }
}
