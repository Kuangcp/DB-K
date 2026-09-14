package es

import db.ConnectionProfile
import engine.BackendCapabilities
import engine.DataSourceSession
import engine.EditorLanguage
import engine.Protocol
import engine.model.ColumnMeta
import engine.model.DbObjectMeta
import engine.model.ObjectKind
import engine.model.ObjectSearch
import engine.model.ObjectSearchResult
import engine.model.QueryResult
import engine.model.SchemaMeta
import engine.model.SchemaObjects
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/**
 * Elasticsearch 后端（`engine.DataSourceSession` 的 ES 版）。
 *
 * - 客户端选型：JDK `java.net.http.HttpClient` + `kotlinx.serialization.json`（**零重依赖**）。
 * - 命名空间 = 集群（`GET /` 的 `cluster_name`）；对象组 = 索引 / 别名（懒加载 + 计数）。
 * - 控制台输入 JSON DSL（见 [ElasticsearchProtocol]）→ `_search` → 二维网格；`from/size` 支持「取更多」。
 * - 所有请求走异步 API 并登记 `CompletableFuture`，[cancel] 即取消在途请求。
 *
 * 连接档案映射：host/port 为服务地址；`extraParams` 支持 `scheme=https` 与 `path=/es`（前缀）；
 * `user`+`password` = Basic 认证；**用户名为空、密码非空 = API Key**（`Authorization: ApiKey <密码>`）。
 * `database` = 默认索引（控制台 DSL 未写 `index` 时使用，可空）。
 */
class ElasticsearchSession(private val profile: ConnectionProfile) : DataSourceSession {

    override val profileId: String = profile.id
    override val protocol: Protocol = Protocol.ELASTICSEARCH

    override val capabilities: BackendCapabilities = BackendCapabilities(
        editableResult = false,
        sqlCompletion = false,
        objectDdl = true,
        objectPreview = true,
        sessionContext = false,
        // 索引清单在连接时一次拉全（权限宽容的多级回落只跑一次；刷新元数据重取），不做按组懒加载
        lazyObjectGroups = false,
        namespaceAsFilter = false,
        editorLanguage = EditorLanguage.JSON,
        fetchMore = true,
    )

    private val baseUri: URI = buildBaseUri()
    private val authHeader: String? = buildAuthHeader()

    /** HttpClient 不可重复使用：close 过的实例会拒绝后续请求，重连时重建（见 [open]）。 */
    private var http: HttpClient = newClient()
    private var httpUsable = true

    private fun newClient(): HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    @Volatile
    private var opened = false

    /** 在途请求（供 [cancel] 取消）。 */
    @Volatile
    private var inflight: CompletableFuture<HttpResponse<String>>? = null

    override val isOpen: Boolean get() = opened

    override fun open() {
        if (opened) return
        send("GET", "/", null) // 建连即验证可达 + 认证
        opened = true
    }

    override fun close() {
        opened = false
        inflight?.cancel(true)
        inflight = null
        runCatching { http.close() }
        httpUsable = false
    }

    // ---------- 元数据 ----------

    override fun objectGroups(): List<ObjectKind> = listOf(ObjectKind.INDEX, ObjectKind.ALIAS)

    override fun loadNamespaces(): List<SchemaMeta> {
        val cluster = runCatching {
            val root = Json.parseToJsonElement(send("GET", "/", null)) as? JsonObject
            (root?.get("cluster_name") as? JsonPrimitive)?.contentOrNull
        }.getOrNull()
        return listOf(SchemaMeta(catalog = null, schema = cluster?.takeIf { it.isNotBlank() } ?: "elasticsearch"))
    }

    override fun loadObjects(ns: SchemaMeta): SchemaObjects {
        val listing = listing()
        listing.error?.let { throw IllegalStateException(it) }
        return SchemaObjects(
            objects = mapOf(
                ObjectKind.INDEX to listing.indices,
                ObjectKind.ALIAS to listing.aliases,
            ),
        )
    }

    override fun loadObjectCounts(ns: SchemaMeta): Map<ObjectKind, Int> {
        val listing = listing()
        listing.error?.let { throw IllegalStateException(it) }
        return mapOf(ObjectKind.INDEX to listing.indices.size, ObjectKind.ALIAS to listing.aliases.size)
    }

    override fun loadCoreObjects(ns: SchemaMeta): SchemaObjects = SchemaObjects()

    override fun loadObjectsForKind(ns: SchemaMeta, kind: ObjectKind): List<DbObjectMeta> {
        val listing = listing()
        return when (kind) {
            ObjectKind.INDEX -> listing.indices
            ObjectKind.ALIAS -> listing.aliases
            else -> emptyList()
        }
    }

    override fun searchObjects(ns: SchemaMeta, kind: ObjectKind, search: ObjectSearch): ObjectSearchResult {
        val all = loadObjectsForKind(ns, kind)
        val pattern = search.pattern.takeIf { it.isNotBlank() && it != "*" }
        val filtered = if (pattern == null) all else {
            val regex = globToRegex(pattern)
            all.filter { regex.matches(it.name) }
        }
        return ObjectSearchResult(filtered)
    }

    /** `_mapping` 扁平字段（编辑器列补全用；ES 关闭 SQL 补全，此处主要供结构展示）。 */
    override fun loadColumns(ns: SchemaMeta?, table: String): List<ColumnMeta> =
        ElasticsearchProtocol.mappingFields(send("GET", "/$table/_mapping", null))

    /** 对象定义 = `_mapping`（格式化 JSON）。 */
    override fun objectDdl(ns: SchemaMeta?, name: String): String? =
        ElasticsearchProtocol.pretty(send("GET", "/$name/_mapping", null))

    /** 双击索引 / 别名 → 生成 match_all DSL（带 `index` 与分页大小），插入控制台。 */
    override fun previewQuery(ns: SchemaMeta?, obj: DbObjectMeta): String = """
        {
          "index": "${obj.name}",
          "query": { "match_all": {} },
          "size": ${ElasticsearchProtocol.DEFAULT_SIZE}
        }
    """.trimIndent()

    override fun sessionContextSql(ns: SchemaMeta): String? = null

    // ---------- 执行 ----------

    override fun runStatement(statement: String, sessionContextSql: String?): QueryResult {
        if (!opened) open()
        val request = ElasticsearchProtocol.parseRequest(statement, defaultIndex())
        val started = System.currentTimeMillis()
        val response = send(
            "POST",
            ElasticsearchProtocol.searchPath(request),
            ElasticsearchProtocol.encodeBody(request.body),
        )
        return ElasticsearchProtocol.searchResponseToResult(
            statement = statement,
            responseJson = response,
            from = request.from,
            size = request.size,
            durationMs = System.currentTimeMillis() - started,
        )
    }

    override fun paginate(statement: String, offset: Long, limit: Int): String? =
        ElasticsearchProtocol.withPagination(statement, offset, limit, defaultIndex())

    override fun cancel(): Boolean {
        val future = inflight ?: return false
        return runCatching { future.cancel(true) }.getOrDefault(false)
    }

    // ---------- 内部 ----------

    private fun defaultIndex(): String? = profile.database.trim().takeIf { it.isNotEmpty() }

    /** 索引/别名清单（含失败原因）。 */
    private data class IndexListing(
        val indices: List<DbObjectMeta>,
        val aliases: List<DbObjectMeta>,
        val error: String?,
    )

    /**
     * 列出索引/别名。权限宽容：`_cat/indices`（需 `indices:monitor/settings/get`）被 403 时依次回落
     * `_alias` / `_mapping` / `_search` 聚合 / 连接档案的默认索引，尽量在只读权限下也能浏览。
     * 全部失败才返回 [IndexListing.error]（含可操作提示），由上层转为树上的可读错误。
     */
    private fun listing(): IndexListing {
        val errors = mutableListOf<String>()

        // A) _cat/*：字段裁剪，响应最小
        try {
            val names = ElasticsearchProtocol.catIndexNames(send("GET", "/_cat/indices?format=json&h=index", null))
            val aliases = try {
                ElasticsearchProtocol.catAliases(send("GET", "/_cat/aliases?format=json&h=alias,index", null))
                    .map { (alias, index) -> DbObjectMeta(alias, ObjectKind.ALIAS, detail = index) }
            } catch (t: Throwable) {
                // 索引拿到但别名不行：再试 _alias（别名可选，失败就空）
                errors += brief(t)
                runCatching {
                    ElasticsearchProtocol.parseAliasListing(send("GET", "/_alias", null))
                        .aliases.map { (alias, index) -> DbObjectMeta(alias, ObjectKind.ALIAS, detail = index) }
                }.getOrDefault(emptyList())
            }
            return IndexListing(names.map { DbObjectMeta(it, ObjectKind.INDEX) }, aliases, null)
        } catch (t: Throwable) {
            errors += brief(t)
        }

        // B) GET /_alias：一次拿到索引 + 别名
        try {
            val parsed = ElasticsearchProtocol.parseAliasListing(send("GET", "/_alias", null))
            return IndexListing(
                indices = parsed.indices.map { DbObjectMeta(it, ObjectKind.INDEX) },
                aliases = parsed.aliases.map { (alias, index) -> DbObjectMeta(alias, ObjectKind.ALIAS, detail = index) },
                error = null,
            )
        } catch (t: Throwable) {
            errors += brief(t)
        }

        // C) GET /_mapping
        try {
            return IndexListing(
                ElasticsearchProtocol.mappingIndexNames(send("GET", "/_mapping", null))
                    .map { DbObjectMeta(it, ObjectKind.INDEX) },
                emptyList(),
                null,
            )
        } catch (t: Throwable) {
            errors += brief(t)
        }

        // D) _search + _index 聚合（只要有读权限即可；空索引不出现）
        try {
            val body = """{"size":0,"aggs":{"dbk_indices":{"terms":{"field":"_index","size":10000}}}}"""
            return IndexListing(
                ElasticsearchProtocol.searchIndexNames(send("POST", "/_search", body))
                    .map { DbObjectMeta(it, ObjectKind.INDEX) },
                emptyList(),
                null,
            )
        } catch (t: Throwable) {
            errors += brief(t)
        }

        // E) 默认索引兜底
        defaultIndex()?.let {
            return IndexListing(listOf(DbObjectMeta(it, ObjectKind.INDEX)), emptyList(), null)
        }

        return IndexListing(emptyList(), emptyList(), permissionHint(errors))
    }

    private fun brief(t: Throwable): String = (t.message ?: t.javaClass.simpleName).lineSequence().first().take(160)

    private fun permissionHint(errors: List<String>): String {
        val forbidden = errors.any {
            it.contains("403") || it.contains("security_exception") || it.contains("no permissions")
        }
        val prefix = if (forbidden) {
            "无权限列出索引/别名（OpenSearch/ES 403）。可在连接里填写「默认索引」后手动查询，或让管理员授予 " +
                "indices:monitor/settings/get（_cat/indices）或 indices:admin/aliases/get（_alias）。"
        } else {
            "无法列出索引/别名。"
        }
        return prefix + "原始错误：" + errors.joinToString("；").take(240)
    }

    /** 发送请求并返回响应体；HTTP >= 400 时解析 ES 错误并抛出可读异常。 */
    private fun send(method: String, path: String, body: String?): String {
        // 断开后同实例可重连（ConnectionsState.reset 会 close 再 open）
        if (!httpUsable) {
            http = newClient()
            httpUsable = true
        }
        val uri = URI(baseUri.toString().trimEnd('/') + path)
        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
        authHeader?.let { builder.header("Authorization", it) }
        builder.header("Accept", "application/json")
        when (method) {
            "GET" -> builder.GET()
            "POST" -> builder
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body ?: "{}", Charsets.UTF_8))
            else -> error("不支持的 HTTP 方法：$method")
        }
        val future = http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
        inflight = future
        val response = try {
            future.get()
        } catch (e: ExecutionException) {
            throw unwrap(e)
        } catch (e: java.util.concurrent.CancellationException) {
            throw IllegalStateException("查询已取消", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("请求已取消", e)
        } catch (e: CompletionException) {
            throw unwrap(e)
        } finally {
            inflight = null
        }
        if (response.statusCode() >= 400) {
            throw IllegalStateException(describeHttpError(response))
        }
        return response.body()
    }

    private fun unwrap(t: Throwable): Throwable {
        val cause = (t as? ExecutionException)?.cause ?: (t as? CompletionException)?.cause ?: t
        return cause
    }

    private fun describeHttpError(response: HttpResponse<String>): String {
        val http = "HTTP ${response.statusCode()}"
        val body = response.body().orEmpty()
        if (body.isBlank()) return http
        return runCatching {
            val root = Json.parseToJsonElement(body) as? JsonObject
            val error = root?.get("error") ?: return@runCatching body.take(200)
            "$http ${ElasticsearchProtocol.errorMessage(error)}"
        }.getOrDefault("$http ${body.take(200)}")
    }

    private fun buildBaseUri(): URI {
        val params = parseParams(profile.extraParams)
        val scheme = params["scheme"]?.lowercase()
            ?: if (params["ssl"].equals("true", ignoreCase = true)) "https" else "http"
        val host = profile.host.trim().ifBlank { "127.0.0.1" }
        val port = if (profile.port > 0) profile.port else 9200
        val path = params["path"]?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() && it != "/" } ?: ""
        return URI("$scheme://$host:$port$path")
    }

    private fun buildAuthHeader(): String? {
        val user = profile.user?.trim()?.takeIf { it.isNotEmpty() }
        val password = profile.password?.takeIf { it.isNotEmpty() }
        return when {
            user != null -> {
                val token = Base64.getEncoder().encodeToString("$user:${password.orEmpty()}".toByteArray(Charsets.UTF_8))
                "Basic $token"
            }
            // 用户名留空、密码填 API Key：按 ApiKey 认证（密码经 vault 加密存储）
            password != null -> "ApiKey $password"
            else -> null
        }
    }

    private fun parseParams(raw: String): Map<String, String> =
        raw.split('&', ';', '?')
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) null else part.substring(0, idx).trim().lowercase() to part.substring(idx + 1).trim()
            }
            .toMap()

    /** glob（`*` / `?`）→ 正则，用于客户端对象过滤。 */
    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        glob.forEach { c ->
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> sb.append(Regex.escape(c.toString()))
            }
        }
        sb.append('$')
        return Regex(sb.toString())
    }
}
