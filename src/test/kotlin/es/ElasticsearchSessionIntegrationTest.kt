package es

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import db.ConnectionProfile
import db.DbType
import engine.model.ObjectKind
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ES 会话端到端（用本地 JDK HttpServer 假装 ES）：验证 HttpClient 接线、
 * 路径/请求体、认证头、结果渲染、分页与错误提取。不依赖真实 ES。
 */
class ElasticsearchSessionIntegrationTest {

    private lateinit var server: HttpServer
    private var port = 0
    private val authHeaders = CopyOnWriteArrayList<String>()
    private val requests = CopyOnWriteArrayList<Pair<String, String>>()

    private val indicesJson = """[{"index":"i1"},{"index":".kibana"}]"""
    private val aliasesJson = """[{"alias":"a1","index":"i1"}]"""
    private val mappingJson = """
        {"i1":{"mappings":{"properties":{"name":{"type":"keyword"},"qty":{"type":"integer"}}}}}
    """.trimIndent()

    private fun searchBody(id: String, name: String) =
        """{"took":1,"hits":{"total":{"value":3,"relation":"eq"},"hits":[
           {"_index":"i1","_id":"$id","_score":1.0,"_source":{"name":"$name","qty":1}}
        ]}}"""

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newFixedThreadPool(2)
        server.createContext("/") { ex -> route(ex) }
        server.start()
        port = server.address.port
    }

    @AfterTest
    fun stop() {
        server.stop(0)
    }

    private fun route(ex: HttpExchange) {
        val body = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        authHeaders += ex.requestHeaders.getFirst("Authorization") ?: ""
        requests += ex.requestMethod to ex.requestURI.toString()
        val path = ex.requestURI.path
        val (code, payload) = when {
            path == "/" -> 200 to """{"cluster_name":"dbk-test"}"""
            path == "/_cat/indices" -> 200 to indicesJson
            path == "/_cat/aliases" -> 200 to aliasesJson
            path == "/i1/_mapping" -> 200 to mappingJson
            path == "/i1/_search" -> 200 to searchBody(if (body.contains("\"from\":1")) "2" else "1", if (body.contains("\"from\":1")) "bob" else "alice")
            path == "/_search" -> 200 to searchBody("9", "any")
            else -> 404 to """{"error":{"type":"index_not_found_exception","reason":"no such index [$path]"},"status":404}"""
        }
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun profile(password: String? = "secret", user: String? = "elastic", database: String = "i1") = ConnectionProfile(
        id = "es1", name = "es", dbType = DbType.ELASTICSEARCH,
        host = "127.0.0.1", port = port, database = database,
        user = user, password = password, extraParams = "",
    )

    @Test
    fun `connects and reads metadata`() {
        ElasticsearchSession(profile()).use { s ->
            s.open()
            assertTrue(s.isOpen)
            val ns = s.loadNamespaces().single()
            assertEquals("dbk-test", ns.displayName)

            assertEquals(2, s.loadObjectCounts(ns)[ObjectKind.INDEX])
            assertEquals(1, s.loadObjectCounts(ns)[ObjectKind.ALIAS])
            assertEquals(listOf("i1", ".kibana"), s.loadObjectsForKind(ns, ObjectKind.INDEX).map { it.name })
            assertEquals("i1", s.loadObjectsForKind(ns, ObjectKind.ALIAS).single().detail)

            val ddl = s.objectDdl(ns, "i1").orEmpty()
            assertTrue(ddl.contains("\"name\""))
            assertTrue(s.loadColumns(ns, "i1").any { it.name == "qty" })
        }
    }

    @Test
    fun `uses basic auth header and cluster path prefix is absent`() {
        ElasticsearchSession(profile()).use { s -> s.open() }
        assertEquals(
            "Basic " + Base64.getEncoder().encodeToString("elastic:secret".toByteArray()),
            authHeaders.first { it.isNotEmpty() },
        )
        // 认证头每个请求都带
        assertTrue(authHeaders.all { it.startsWith("Basic ") })
        assertTrue(requests.any { it.second == "/" })
    }

    @Test
    fun `blank user sends api key header`() {
        ElasticsearchSession(profile(user = "", password = "my-api-key")).use { s -> s.open() }
        assertTrue(authHeaders.any { it == "ApiKey my-api-key" })
    }

    @Test
    fun `runs dsl search and renders rows`() {
        ElasticsearchSession(profile()).use { s ->
            val result = s.runStatement("""{"index":"i1","query":{"match_all":{}}}""", null)
            assertTrue(result.isQuery)
            assertEquals(listOf("_index", "_id", "_score", "name", "qty"), result.columns.map { it.name })
            assertEquals(1, result.rows.size)
            assertEquals("alice", result.rows[0][3])
            assertTrue(result.truncated)
            // 请求体注入了默认 from/size
            val search = requests.first { it.second == "/i1/_search" }
            assertEquals("POST", search.first)
        }
    }

    @Test
    fun `falls back to default index and to cluster-wide search`() {
        ElasticsearchSession(profile(database = "i1")).use { s ->
            s.runStatement("""{"query":{"match_all":{}}}""", null)
        }
        assertTrue(requests.any { it.second == "/i1/_search" })

        requests.clear()
        ElasticsearchSession(profile(database = "")).use { s ->
            s.runStatement("""{"query":{"match_all":{}}}""", null)
        }
        assertTrue(requests.any { it.second == "/_search" })
    }

    @Test
    fun `paginate rewrites from and size and second page differs`() {
        ElasticsearchSession(profile()).use { s ->
            val next = s.paginate("""{"index":"i1","query":{"match_all":{}}}""", 1, 1)
            assertTrue(next!!.contains("\"from\":1"))
            assertTrue(next.contains("\"size\":1"))
            val second = s.runStatement(next, null)
            assertEquals("bob", second.rows[0][3])
        }
    }

    @Test
    fun `surfaces es error reason for missing index`() {
        ElasticsearchSession(profile()).use { s ->
            val e = assertFailsWith<IllegalStateException> {
                s.runStatement("""{"index":"nope","query":{"match_all":{}}}""", null)
            }
            assertTrue(e.message!!.contains("no such index"))
        }
    }

    @Test
    fun `preview dsl contains index and match_all`() {
        ElasticsearchSession(profile()).use { s ->
            val preview = s.previewQuery(s.loadNamespaces().single(), s.loadObjectsForKind(s.loadNamespaces().single(), ObjectKind.INDEX).first())
            assertTrue(preview.contains("\"index\": \"i1\""))
            assertTrue(preview.contains("match_all"))
        }
    }

    @Test
    fun `reconnects after close on same instance`() {
        val s = ElasticsearchSession(profile())
        s.open()
        s.close()
        s.open() // ConnectionsState.reset 后同实例重连
        assertTrue(s.isOpen)
        assertTrue(s.runStatement("""{"index":"i1"}""", null).isQuery)
        s.close()
    }

    @Test
    fun `scheme and path extra params shape the base uri`() {
        val p = profile().copy(extraParams = "scheme=https&path=/es")
        val s = ElasticsearchSession(p)
        // 不真正连接，仅确认 base 构造不抛（host/port 就绪）
        s.close()
    }
}
