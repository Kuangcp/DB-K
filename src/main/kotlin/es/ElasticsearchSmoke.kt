package es

import db.ConnectionProfile
import db.DbType
import engine.model.ObjectKind
import org.tinylog.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * Elasticsearch 后端自检（无需 UI）：`gradle smokeEs`。
 *
 * 需要一个可连的 ES；连不上时打印 SKIP 并非失败退出。
 * 可用 `-Ddbk.esUrl=http://host:9200 -Ddbk.esUser=… -Ddbk.esPassword=… -Ddbk.esIndex=…` 覆盖。
 *
 * 覆盖：建连 → 集群名 → 建临时索引 + 写文档 → 索引计数/清单 → `_mapping` 字段 →
 * DSL `_search` 渲染 → `from/size` 分页（「取更多」）→ 删索引清理。
 * ⚠️ 会写入并删除临时索引，请使用一次性/测试集群。
 */
fun main() {
    val url = System.getProperty("dbk.esUrl") ?: System.getenv("DBK_ES_URL") ?: "http://127.0.0.1:9200"
    val user = System.getProperty("dbk.esUser") ?: System.getenv("DBK_ES_USER")
    val password = System.getProperty("dbk.esPassword") ?: System.getenv("DBK_ES_PASSWORD")
    val index = System.getProperty("dbk.esIndex") ?: System.getenv("DBK_ES_INDEX") ?: "dbk-smoke"
    Logger.info("smokeEs target: {} index={}", url, index)

    val uri = URI(url)
    val auth = when {
        !user.isNullOrBlank() -> "Basic " + Base64.getEncoder()
            .encodeToString("$user:${password.orEmpty()}".toByteArray(Charsets.UTF_8))
        !password.isNullOrBlank() -> "ApiKey $password"
        else -> null
    }
    val scheme = uri.scheme ?: "http"
    val path = uri.path?.takeIf { it.isNotBlank() && it != "/" } ?: ""
    val profile = ConnectionProfile(
        id = "smoke-es", name = "es-smoke", dbType = DbType.ELASTICSEARCH,
        host = uri.host ?: "127.0.0.1",
        port = if (uri.port > 0) uri.port else 9200,
        database = index,
        user = user,
        password = password,
        extraParams = "scheme=$scheme" + if (path.isNotEmpty()) "&path=$path" else "",
    )
    val base = "$scheme://${profile.host}:${profile.port}$path"
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    val session = ElasticsearchSession(profile)
    val checks = mutableListOf<String>()
    try {
        try {
            session.open()
        } catch (e: Exception) {
            Logger.info("smokeEs SKIP: Elasticsearch unreachable ({}) - {}", base, e.toString())
            return
        }
        checks += "connect"

        val ns = session.loadNamespaces().singleOrNull()
        require(ns != null && ns.displayName.isNotBlank()) { "集群命名空间缺失" }
        checks += "cluster(${ns.displayName})"

        // seed：建临时索引 + 写 2 条文档并刷新
        val created = request(client, base, "PUT", "/$index", """{"mappings":{"properties":{"name":{"type":"keyword"},"qty":{"type":"integer"}}}}""", auth)
        require(created.first in 200..299 || created.first == 400) { "建索引失败 HTTP ${created.first}: ${created.second.take(200)}" }
        request(client, base, "POST", "/$index/_doc?refresh=true", """{"name":"alice","qty":1}""", auth)
        request(client, base, "POST", "/$index/_doc?refresh=true", """{"name":"bob","qty":2}""", auth)
        request(client, base, "POST", "/$index/_refresh", null, auth)
        checks += "seed"

        val counts = session.loadObjectCounts(ns)
        require((counts[ObjectKind.INDEX] ?: 0) >= 1) { "索引计数异常：$counts" }
        val indices = session.loadObjectsForKind(ns, ObjectKind.INDEX)
        require(indices.any { it.name == index }) { "索引清单缺少 $index：${indices.map { it.name }}" }
        checks += "objects(${indices.size})"

        val fields = ElasticsearchProtocol.mappingFields(session.objectDdl(ns, index).orEmpty())
        require(fields.any { it.name == "name" } && fields.any { it.name == "qty" }) {
            "_mapping 字段异常：${fields.map { it.name }}"
        }
        checks += "mapping"

        val firstPage = session.runStatement("""{"index":"$index","query":{"match_all":{}},"size":1}""", null)
        require(firstPage.columns.any { it.name == "_id" } && firstPage.columns.any { it.name == "name" }) {
            "结果列异常：${firstPage.columns.map { it.name }}"
        }
        require(firstPage.rows.size == 1 && firstPage.truncated) { "首页应为 1 行且标记可继续：${firstPage.rows.size}/${firstPage.truncated}" }
        checks += "search"

        val nextStatement = session.paginate("""{"index":"$index","query":{"match_all":{}}}""", 1, 1)
        require(nextStatement != null && nextStatement.contains("\"from\":1")) { "分页语句异常：$nextStatement" }
        val secondPage = session.runStatement(checkNotNull(nextStatement), null)
        require(secondPage.rows.size == 1) { "第二页应为 1 行：${secondPage.rows.size}" }
        checks += "paging"

        val preview = session.previewQuery(ns, indices.first { it.name == index })
        require(preview.contains(index) && preview.contains("match_all")) { "预览 DSL 异常：$preview" }
        checks += "preview"

        Logger.info("smokeEs PASS: {}", checks.joinToString(" + "))
    } finally {
        runCatching { request(client, base, "DELETE", "/$index", null, auth) }
        session.close()
    }
}

private fun request(
    client: HttpClient,
    base: String,
    method: String,
    path: String,
    body: String?,
    auth: String?,
): Pair<Int, String> {
    val builder = HttpRequest.newBuilder(URI(base.trimEnd('/') + path)).timeout(Duration.ofSeconds(30))
    auth?.let { builder.header("Authorization", it) }
    builder.header("Content-Type", "application/json")
    when (method) {
        "GET" -> builder.GET()
        "PUT" -> builder.PUT(HttpRequest.BodyPublishers.ofString(body ?: "{}", Charsets.UTF_8))
        "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(body ?: "{}", Charsets.UTF_8))
        "DELETE" -> builder.DELETE()
        else -> error(method)
    }
    val resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    return resp.statusCode() to resp.body()
}
