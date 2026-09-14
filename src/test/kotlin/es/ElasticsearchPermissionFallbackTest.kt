package es

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import db.ConnectionProfile
import db.DbType
import engine.model.ObjectKind
import engine.model.SchemaMeta
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ES 元数据列出的**权限宽容回落**：`_cat/indices` 被 403 时依次降级到
 * `_alias` / `_mapping` / `_search` 聚合 / 连接默认索引；全失败给出可操作提示。
 * 用本地 HttpServer 假装 ES，可逐端点开关 403。
 */
class ElasticsearchPermissionFallbackTest {

    private lateinit var server: HttpServer
    private var port = 0

    /** 端点权限开关：true = 返回 403（模拟缺权限）。 */
    private var denyCat = false
    private var denyAlias = false
    private var denyMapping = false
    private var denySearch = false

    private val forbidden = """{"error":{"type":"security_exception","reason":"no permissions"},"status":403}"""
    private val aliasJson = """{"i1":{"aliases":{"cur":{}}},"i2":{"aliases":{}}}"""
    private val mappingJson = """{"i1":{"mappings":{"properties":{"a":{"type":"keyword"}}}},"i2":{"mappings":{}}}"""
    private val searchAggJson = """{"hits":{"total":{"value":0},"hits":[]},"aggregations":{"dbk_indices":{"buckets":[{"key":"i9","doc_count":1}]}}}"""

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex -> route(ex) }
        server.start()
        port = server.address.port
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun route(ex: HttpExchange) {
        val path = ex.requestURI.path
        val status = when {
            path == "/" -> 200
            path.startsWith("/_cat/") && denyCat -> 403
            path == "/_alias" && denyAlias -> 403
            path == "/_mapping" && denyMapping -> 403
            path == "/_search" && denySearch -> 403
            else -> 200
        }
        val body = when {
            status == 403 -> forbidden
            path == "/" -> """{"cluster_name":"sec-test"}"""
            path == "/_cat/indices" -> """[{"index":"i1"},{"index":"i2"}]"""
            path == "/_cat/aliases" -> """[{"alias":"cur","index":"i1"}]"""
            path == "/_alias" -> aliasJson
            path == "/_mapping" -> mappingJson
            path == "/_search" -> searchAggJson
            else -> "{}"
        }
        ex.requestBody.readBytes()
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun session(database: String = "") = ElasticsearchSession(
        ConnectionProfile(
            id = "es-sec", name = "es", dbType = DbType.ELASTICSEARCH,
            host = "127.0.0.1", port = port, database = database,
        ),
    )

    private val ns = SchemaMeta(catalog = null, schema = "sec-test")

    @Test
    fun `cat denied but _alias allowed`() {
        denyCat = true
        session().use { s ->
            assertEquals(listOf("i1", "i2"), s.loadObjectsForKind(ns, ObjectKind.INDEX).map { it.name })
            assertEquals(listOf("cur"), s.loadObjectsForKind(ns, ObjectKind.ALIAS).map { it.name })
            assertEquals("i1", s.loadObjectsForKind(ns, ObjectKind.ALIAS).single().detail)
        }
    }

    @Test
    fun `cat and alias denied falls back to _mapping`() {
        denyCat = true
        denyAlias = true
        session().use { s ->
            val objects = s.loadObjects(ns)
            assertEquals(listOf("i1", "i2"), objects.forKind(ObjectKind.INDEX).map { it.name })
            assertTrue(objects.forKind(ObjectKind.ALIAS).isEmpty())
        }
    }

    @Test
    fun `cat alias mapping denied falls back to search aggregation`() {
        denyCat = true
        denyAlias = true
        denyMapping = true
        session().use { s ->
            assertEquals(listOf("i9"), s.loadObjectsForKind(ns, ObjectKind.INDEX).map { it.name })
        }
    }

    @Test
    fun `all denied with no default index throws actionable hint`() {
        denyCat = true
        denyAlias = true
        denyMapping = true
        denySearch = true
        session().use { s ->
            val e = assertFailsWith<IllegalStateException> { s.loadObjects(ns) }
            assertTrue(e.message!!.contains("无权限"), e.message)
            assertTrue(e.message!!.contains("indices:monitor/settings/get"), e.message)
        }
    }

    @Test
    fun `all denied but default index configured yields that index`() {
        denyCat = true
        denyAlias = true
        denyMapping = true
        denySearch = true
        session(database = "fallback-idx").use { s ->
            assertEquals(listOf("fallback-idx"), s.loadObjectsForKind(ns, ObjectKind.INDEX).map { it.name })
        }
    }

    @Test
    fun `full cat listing still works and reports both groups`() {
        session().use { s ->
            val objects = s.loadObjects(ns)
            assertEquals(listOf("i1", "i2"), objects.forKind(ObjectKind.INDEX).map { it.name })
            assertEquals(listOf("cur"), objects.forKind(ObjectKind.ALIAS).map { it.name })
            assertEquals(2, s.loadObjectCounts(ns)[ObjectKind.INDEX])
            assertEquals(1, s.loadObjectCounts(ns)[ObjectKind.ALIAS])
        }
    }
}
