package es

import engine.model.ColumnMeta
import engine.model.QueryColumn
import engine.model.QueryResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.sql.Types
import i18n.I18n
import i18n.Str

/**
 * Elasticsearch 后端纯逻辑（无 HTTP / 无 compose），便于单测。
 *
 * 控制台输入 = 一个 JSON 对象：顶层可选 `index`（或 `_index`）指定目标索引，
 * 其余键原样作为 `_search` 请求体（`query` / `from` / `size` / `sort` / `aggs` …）。
 * 未指定 `index` 时回落到连接档案的「默认索引」，仍为空则搜全集群（`/_search`）。
 */
object ElasticsearchProtocol {

    /** 默认页大小（用户 DSL 未写 `size` 时注入）。 */
    const val DEFAULT_SIZE = 100

    private val compact = Json

    private val pretty = Json { prettyPrint = true }

    /** 解析后的 ES 查询：目标索引 + 请求体（已补齐 `from`/`size`）。 */
    data class Request(val index: String?, val body: JsonObject, val from: Int, val size: Int)

    /**
     * 解析控制台语句。失败抛 [IllegalArgumentException]（UI 转为可读错误）。
     * @param defaultIndex 连接档案的默认索引（DSL 未指定时用）。
     */
    fun parseRequest(statement: String, defaultIndex: String? = null): Request {
        val trimmed = statement.trim()
        require(trimmed.isNotEmpty()) { I18n.t(Str.EsEmptyQuery) }
        val element = runCatching { compact.parseToJsonElement(trimmed) }
            .getOrElse { throw IllegalArgumentException(I18n.t(Str.EsInvalidJson, it.message?.take(120))) }
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException(I18n.t(Str.EsBodyNotObject))
        val explicitIndex = (obj["index"] ?: obj["_index"])
            ?.let { if (it is JsonPrimitive) it.contentOrNull else null }
            ?.takeIf { it.isNotBlank() }
        val index = explicitIndex ?: defaultIndex?.takeIf { it.isNotBlank() }
        val raw = obj - "index" - "_index"
        val from = (raw["from"] as? JsonPrimitive)?.intOrNull ?: 0
        val size = (raw["size"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_SIZE
        return Request(index, withFromSize(raw, from, size), from, size)
    }

    /** 把 `from`/`size` 显式写入请求体（覆盖用户值），用于「取更多」翻页。 */
    fun withPagination(statement: String, offset: Long, limit: Int, defaultIndex: String? = null): String? {
        val req = runCatching { parseRequest(statement, defaultIndex) }.getOrNull() ?: return null
        val body = withFromSize(req.body, offset.toInt(), limit)
        return compact.encodeToString(JsonObject.serializer(), body)
    }

    /** 目标 `_search` 端点路径：`/<index>/_search` 或 `/_search`。 */
    fun searchPath(request: Request): String =
        request.index?.let { "/$it/_search" } ?: "/_search"

    /**
     * `_search` 响应 → [QueryResult]：列 = `_index` / `_id` / `_score` + `_source` 顶层字段并集；
     * 行 = 各 hit；对象/数组字段以紧凑 JSON 文本落格（查看器可识别为 JSON 树）。
     * 响应含 `error` 时抛 [IllegalStateException]。
     */
    fun searchResponseToResult(
        statement: String,
        responseJson: String,
        from: Int,
        size: Int,
        durationMs: Long,
    ): QueryResult {
        val root = parseObject(responseJson)
        root["error"]?.let { throw IllegalStateException(errorMessage(it)) }
        val hits = root["hits"]?.jsonObject ?: throw IllegalStateException(I18n.t(Str.EsResponseMissingHits, responseJson.take(200)))
        val hitList = hits["hits"]?.jsonArray ?: JsonArray(emptyList())

        val sourceKeys = LinkedHashSet<String>()
        hitList.forEach { h ->
            ((h as? JsonObject)?.get("_source") as? JsonObject)?.keys?.forEach { sourceKeys += it }
        }
        val columns = buildList {
            add(QueryColumn("_index", baseColumn = "_index", sqlType = Types.VARCHAR, readOnly = true))
            add(QueryColumn("_id", baseColumn = "_id", sqlType = Types.VARCHAR, readOnly = true))
            add(QueryColumn("_score", baseColumn = "_score", sqlType = Types.DOUBLE, readOnly = true))
            sourceKeys.forEach {
                add(QueryColumn(it, baseColumn = it, sqlType = Types.OTHER, readOnly = true))
            }
        }
        val rows = hitList.map { h ->
            val o = h.jsonObject
            val source = o["_source"] as? JsonObject
            buildList {
                add(o["_index"]?.let(::textOrNull))
                add(o["_id"]?.let(::textOrNull))
                add(o["_score"]?.let(::textOrNull))
                sourceKeys.forEach { add(cellText(source?.get(it))) }
            }
        }
        val total = totalHits(hits["total"])
        // 还有未取回的行 → 可「取更多」
        val truncated = total != null && total > from + hitList.size
        return QueryResult(
            sql = statement,
            columns = columns,
            rows = rows,
            truncated = truncated,
            durationMs = durationMs,
        )
    }

    /** `_cat/indices?format=json` → 索引名（按响应顺序）。 */
    fun catIndexNames(json: String): List<String> {
        val arr = runCatching { parseArray(json) }
            .getOrElse { throw IllegalArgumentException(I18n.t(Str.EsParseIndicesFailed, it.message?.take(120))) }
        return arr.mapNotNull { (it as? JsonObject)?.get("index")?.let(::textOrNull)?.takeIf { n -> n.isNotBlank() } }
    }

    /** `_cat/aliases?format=json` → (别名, 指向的索引) 列表。 */
    fun catAliases(json: String): List<Pair<String, String?>> {
        val arr = runCatching { parseArray(json) }
            .getOrElse { throw IllegalArgumentException(I18n.t(Str.EsParseAliasesFailed, it.message?.take(120))) }
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val alias = o["alias"]?.let(::textOrNull)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            alias to o["index"]?.let(::textOrNull)
        }
    }

    /** `GET /_alias` 解析结果：索引名 + (别名→索引)。 */
    data class AliasListing(val indices: List<String>, val aliases: List<Pair<String, String>>)

    /**
     * `GET /_alias` → 索引 + 别名（一次请求，权限面比 `_cat/indices` 窄，作无权 `_cat` 时的回落）。
     * 响应形如 `{"idx":{"aliases":{"a":{}}}, …}`。
     */
    fun parseAliasListing(json: String): AliasListing {
        val root = parseObject(json)
        val indices = mutableListOf<String>()
        val aliases = mutableListOf<Pair<String, String>>()
        root.forEach { (index, value) ->
            indices += index
            (((value as? JsonObject)?.get("aliases") as? JsonObject))?.keys?.forEach { aliases += it to index }
        }
        return AliasListing(indices, aliases)
    }

    /** `GET /_mapping`（集群级）→ 顶层索引名清单。 */
    fun mappingIndexNames(json: String): List<String> = parseObject(json).keys.toList()

    /**
     * `_search` 在 `_index` 上的 terms 聚合 → 命中的索引名。
     * 用于只有读权限（`indices:data/read/search`）时的最后回落；空索引不会出现。
     */
    fun searchIndexNames(json: String): List<String> {
        val root = parseObject(json)
        val aggs = root["aggregations"] as? JsonObject ?: root["aggs"] as? JsonObject ?: return emptyList()
        val buckets = aggs.values.firstOrNull()?.jsonObject?.get("buckets")?.jsonArray ?: return emptyList()
        return buckets.mapNotNull { (it as? JsonObject)?.get("key")?.let(::textOrNull)?.takeIf { n -> n.isNotBlank() } }
    }

    /**
     * `_mapping` 响应 → 扁平字段清单（嵌套 `properties` 与 multi-`fields` 展开为 `a.b.c`）。
     * 计 `parent` 自身不计为列，只取其叶子字段；同一字段名去重。
     */
    fun mappingFields(json: String): List<ColumnMeta> {
        val root = parseObject(json)
        val out = LinkedHashMap<String, ColumnMeta>()
        var ordinal = 0
        root.values.forEach { indexEntry ->
            val props = (indexEntry as? JsonObject)
                ?.get("mappings")?.jsonObject
                ?.get("properties") as? JsonObject ?: return@forEach
            flattenProperties(props, prefix = null, out = out) { ++ordinal }
        }
        return out.values.toList()
    }

    /** 格式化任意 JSON（`_mapping` 展示用）；非法 JSON 原样返回。 */
    fun pretty(json: String): String =
        runCatching { pretty.encodeToString(JsonElement.serializer(), compact.parseToJsonElement(json)) }
            .getOrDefault(json)

    /** 紧凑序列化请求体。 */
    fun encodeBody(body: JsonObject): String = compact.encodeToString(JsonObject.serializer(), body)

    // ---------- 内部 ----------

    private fun withFromSize(body: Map<String, JsonElement>, from: Int, size: Int): JsonObject = buildJsonObject {
        body.forEach { (k, v) -> if (k != "from" && k != "size") put(k, v) }
        put("from", JsonPrimitive(from))
        put("size", JsonPrimitive(size))
    }

    private fun flattenProperties(
        properties: JsonObject,
        prefix: String?,
        out: MutableMap<String, ColumnMeta>,
        nextOrdinal: () -> Int,
    ) {
        properties.forEach { (name, value) ->
            val full = if (prefix == null) name else "$prefix.$name"
            val def = value as? JsonObject
            val nested = def?.get("properties") as? JsonObject
            val fields = def?.get("fields") as? JsonObject
            if (nested != null) {
                flattenProperties(nested, full, out, nextOrdinal)
            } else {
                val typeName = def?.get("type")?.let(::textOrNull)
                out.putIfAbsent(full, ColumnMeta(full, typeName, ordinal = nextOrdinal()))
            }
            // multi-fields（如 title.keyword）
            fields?.forEach { (sub, subDef) ->
                val subName = "$full.$sub"
                val subType = (subDef as? JsonObject)?.get("type")?.let(::textOrNull)
                out.putIfAbsent(subName, ColumnMeta(subName, subType, ordinal = nextOrdinal()))
            }
        }
    }

    private fun totalHits(el: JsonElement?): Long? = when (el) {
        null, is JsonNull -> null
        is JsonObject -> (el["value"] as? JsonPrimitive)?.longOrNull
        is JsonPrimitive -> el.longOrNull
        else -> null
    }

    private fun cellText(el: JsonElement?): String? = when (el) {
        null, is JsonNull -> null
        is JsonPrimitive -> el.contentOrNull ?: el.toString()
        else -> compact.encodeToString(JsonElement.serializer(), el)
    }

    private fun textOrNull(el: JsonElement): String? = when (el) {
        is JsonNull -> null
        is JsonPrimitive -> el.contentOrNull ?: el.toString()
        else -> compact.encodeToString(JsonElement.serializer(), el)
    }

    private fun parseObject(json: String): JsonObject =
        compact.parseToJsonElement(json.trim()) as? JsonObject
            ?: throw IllegalArgumentException(I18n.t(Str.EsResponseNotObject))

    private fun parseArray(json: String): JsonArray =
        compact.parseToJsonElement(json.trim()) as? JsonArray
            ?: throw IllegalArgumentException(I18n.t(Str.EsResponseNotArray))

    /** 从 ES 错误响应提取可读原因（`error.reason` / `error.root_cause[0].reason` / 原始文本）。 */
    fun errorMessage(error: JsonElement): String {
        val obj = error as? JsonObject
        val reason = obj?.get("reason")?.let(::textOrNull)
            ?: (obj?.get("root_cause")?.jsonArray?.firstOrNull() as? JsonObject)?.get("reason")?.let(::textOrNull)
        val type = obj?.get("type")?.let(::textOrNull)
        return listOfNotNull(type, reason).joinToString(": ").ifBlank {
            compact.encodeToString(JsonElement.serializer(), error).take(200)
        }
    }
}
