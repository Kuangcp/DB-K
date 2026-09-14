package app.ui

/**
 * Elasticsearch JSON DSL 输入补全（纯逻辑，便于单测）。
 *
 * 控制台里 ES 查询是一段 JSON 对象。本模块按「caret 所在的 JSON 上下文」给出候选：
 *   - 键位置：顶层键 / 查询类型 / bool 子句 / 排序项 / 聚合参数 / 高亮键…
 *   - 值位置：`index`（索引名）、`order`（asc/desc）、`field`（字段名）、`sort`/`_source` 数组元素…
 *   - 字段名来自目标索引的 `_mapping`（由上层异步预取，[fields] 传入；为空则只出键/枚举）。
 *
 * 插入文本按「是否已在字符串内 / 右侧是否已有收尾引号」自适应补引号，鼠标键盘上屏即可继续写。
 */
data class EsDslSuggestion(val word: CompletionWord, val items: List<CompletionItem>)

// ---------- 关键词表 ----------

private val TOP_LEVEL = listOf(
    "index", "query", "size", "from", "sort", "_source", "aggs", "highlight",
    "track_total_hits", "search_after", "post_filter", "min_score", "collapse", "timeout", "docvalue_fields",
)
private val QUERY_TYPES = listOf(
    "match_all", "match", "match_phrase", "match_phrase_prefix", "multi_match", "term", "terms",
    "range", "bool", "exists", "wildcard", "prefix", "regexp", "ids", "nested", "query_string",
    "simple_query_string", "constant_score", "function_score", "dis_max", "fuzzy", "more_like_this",
)
private val BOOL_CLAUSES = listOf("must", "should", "must_not", "filter")
private val BOOL_KEYS = BOOL_CLAUSES + listOf("minimum_should_match", "boost")
private val MATCH_OPTS = listOf(
    "query", "operator", "fuzziness", "analyzer", "boost", "minimum_should_match",
    "prefix_length", "max_expansions", "zero_terms_query", "lenient",
)
private val RANGE_OPS = listOf("gte", "gt", "lte", "lt", "boost", "format", "time_zone", "from", "to")
private val TERM_OPTS = listOf("value", "boost", "case_insensitive")
private val TERMS_OPTS = listOf("terms", "boost")
private val VALUE_BOOST_OPTS = listOf("value", "boost")
private val SORT_OPTS = listOf("order", "missing", "mode", "unmapped_type", "numeric_type", "format")
private val ORDER_VALUES = listOf("asc", "desc")
private val AGG_TYPES = listOf(
    "terms", "avg", "sum", "min", "max", "stats", "extended_stats", "cardinality", "value_count",
    "percentiles", "date_histogram", "histogram", "range", "date_range", "filter", "filters",
    "top_hits", "significant_terms", "nested", "reverse_nested", "scripted_metric",
)
private val AGG_PARAMS = listOf(
    "field", "size", "order", "aggs", "missing", "min_doc_count", "script", "format",
    "calendar_interval", "fixed_interval", "interval", "time_zone", "ranges", "terms",
)
private val HIGHLIGHT_KEYS = listOf("fields", "pre_tags", "post_tags", "type", "fragment_size")
private val AGG_OBJECT_KEYS = AGG_TYPES + listOf("aggs", "meta")
private val BOOL_VALUES = listOf("true", "false")
private val OPERATOR_VALUES = listOf("and", "or")

// ---------- 入口 ----------

fun esDslSuggestions(
    text: String,
    caret: Int,
    fields: List<String> = emptyList(),
    indices: List<String> = emptyList(),
): EsDslSuggestion? {
    if (caret < 0 || caret > text.length) return null
    val quoted = countUnescapedQuotes(text, caret) % 2 == 1
    val closes = closingQuoteAt(text, caret)
    val word: CompletionWord
    val parseLimit: Int
    if (quoted) {
        val open = text.lastIndexOf('"', caret - 1)
        if (open < 0) return null
        word = CompletionWord(open + 1, if (closes) caret + 1 else caret, text.substring(open + 1, caret))
        parseLimit = open
    } else {
        var i = caret - 1
        while (i >= 0 && isDslWordChar(text[i])) i--
        word = CompletionWord(i + 1, if (closes) caret + 1 else caret, text.substring(i + 1, caret))
        parseLimit = caret
    }

    val stack = ArrayList<Frame>().apply { add(Frame(obj = true, key = null)) }
    parseStructure(text, parseLimit, stack)
    val frame = stack.last()
    val inKeyPosition = frame.obj && !frame.awaitingValue
    val valueKey = when {
        frame.obj && frame.awaitingValue -> frame.lastKey
        !frame.obj -> frame.key
        else -> null
    }
    val path = stack.drop(1).mapNotNull { it.key }

    val items = if (inKeyPosition) {
        keySuggestions(path, fields)
    } else {
        valueSuggestions(valueKey, fields, indices)
    }.map { it.forPosition(quoted = quoted) }

    val prefix = word.text
    val filtered = if (prefix.isEmpty()) {
        items
    } else {
        items.filter { it.text.startsWith(prefix, ignoreCase = true) }
    }
    return if (filtered.isEmpty()) null else EsDslSuggestion(word, filtered)
}

/** 顶层/嵌套的 `"index"`（或 `"_index"`）字符串值，用于定位字段补全的来源索引。 */
fun esDslIndexName(text: String): String? =
    Regex("\"(_?index)\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(2)?.takeIf { it.isNotBlank() }

// ---------- 上下文 → 候选 ----------

private fun keySuggestions(path: List<String>, fields: List<String>): List<CompletionItem> {
    val last = path.lastOrNull()
    val inAggs = path.any { it == "aggs" || it == "aggregations" }
    if (!inAggs) {
        // { "<查询类型>": { <字段>: … } }：字段对象的键是查询选项
        if (path.size >= 2 && path[path.size - 2] in QUERY_TYPES) return optionItems(path[path.size - 2])
        // { "sort": [ { "<字段>": { order } } ] }：排序元素对象的键是字段名
        if (path.size >= 2 && path[path.size - 2] == "sort") return fieldItems(fields)
    }
    if (inAggs) {
        // "aggs" 树内优先按聚合语义（terms/range/filter 等与查询类型同名，不能认成查询）
        return when {
            last != null && last in AGG_TYPES -> keyItems(AGG_PARAMS)
            path.size >= 2 && (path[path.size - 2] in AGG_TYPES ||
                path[path.size - 2] == "aggs" || path[path.size - 2] == "aggregations") -> keyItems(AGG_OBJECT_KEYS)
            else -> emptyList()
        }
    }
    return when {
        path.isEmpty() -> keyItems(TOP_LEVEL)
        last == "query" -> keyItems(QUERY_TYPES)
        last == "bool" -> keyItems(BOOL_KEYS)
        last in BOOL_CLAUSES -> keyItems(QUERY_TYPES)
        last in QUERY_TYPES -> fieldItems(fields)
        last == "sort" -> fieldItems(fields)
        last == "fields" -> fieldItems(fields)
        last == "_source" -> keyItems(listOf("includes", "excludes"))
        last == "highlight" -> keyItems(HIGHLIGHT_KEYS)
        last == "aggs" || last == "aggregations" -> emptyList()
        last in AGG_TYPES -> keyItems(AGG_PARAMS)
        else -> emptyList()
    }
}

private fun optionItems(queryType: String): List<CompletionItem> = when (queryType) {
    "range" -> keyItems(RANGE_OPS)
    "term" -> keyItems(TERM_OPTS)
    "terms" -> keyItems(TERMS_OPTS)
    "wildcard", "prefix", "regexp" -> keyItems(VALUE_BOOST_OPTS)
    "exists" -> keyItems(listOf("field"))
    "nested" -> keyItems(listOf("path", "query", "score_mode", "ignore_unmapped"))
    "match", "match_phrase", "match_phrase_prefix", "multi_match", "query_string", "simple_query_string" ->
        keyItems(MATCH_OPTS)
    else -> keyItems(listOf("boost"))
}

private fun valueSuggestions(
    valueKey: String?,
    fields: List<String>,
    indices: List<String>,
): List<CompletionItem> = when (valueKey) {
    "index", "_index" -> indexItems(indices)
    "order" -> valueItems(ORDER_VALUES)
    "sort", "fields", "_source", "field", "path" -> fieldItems(fields)
    "track_total_hits", "case_insensitive", "lenient", "include_lower", "include_upper" -> valueItems(BOOL_VALUES)
    "operator" -> valueItems(OPERATOR_VALUES)
    else -> emptyList()
}

// ---------- 候选项构造（引号由 forPosition 统一处理） ----------

private fun keyItems(words: List<String>): List<CompletionItem> =
    words.map { CompletionItem(it, "键", CompletionKind.KEYWORD, insertText = "\u0000K:$it") }

private fun valueItems(words: List<String>): List<CompletionItem> =
    words.map { CompletionItem(it, "值", CompletionKind.KEYWORD, insertText = "\u0000V:$it") }

private fun fieldItems(fields: List<String>): List<CompletionItem> =
    fields.distinct().sorted().map { CompletionItem(it, "字段", CompletionKind.COLUMN, insertText = "\u0000V:$it") }

private fun indexItems(indices: List<String>): List<CompletionItem> =
    indices.distinct().sorted().map { CompletionItem(it, "索引", CompletionKind.TABLE, insertText = "\u0000V:$it") }

/** 把内部占位（`\u0000K:` / `\u0000V:`）展开成按当前位置自适应的插入文本（含引号/冒号）。 */
private fun CompletionItem.forPosition(quoted: Boolean): CompletionItem {
    val raw = insertText ?: return this
    val value = raw.removePrefix("\u0000K:").removePrefix("\u0000V:")
    val isKey = raw.startsWith("\u0000K:")
    val insert = if (isKey) {
        (if (quoted) "" else "\"") + value + "\": "
    } else {
        (if (quoted) "" else "\"") + value + "\""
    }
    return copy(insertText = insert)
}

// ---------- JSON 结构扫描 ----------

private class Frame(val obj: Boolean, val key: String?) {
    var lastKey: String? = null
    var awaitingValue: Boolean = false
}

private fun parseStructure(text: String, limit: Int, stack: MutableList<Frame>) {
    var i = 0
    val end = limit.coerceIn(0, text.length)
    while (i < end) {
        val c = text[i]
        when {
            c.isWhitespace() -> i++
            c == '{' || c == '[' -> {
                val f = stack.last()
                val key = if (f.obj) (if (f.awaitingValue) f.lastKey else null) else f.key
                if (f.obj) f.awaitingValue = false
                stack.add(Frame(obj = c == '{', key = key))
                i++
            }
            c == '}' || c == ']' -> {
                if (stack.size > 1) stack.removeAt(stack.size - 1)
                i++
            }
            c == '"' -> {
                val close = findClosingQuote(text, i + 1, end)
                val token = text.substring(i + 1, close.coerceAtMost(end))
                val f = stack.last()
                if (f.obj && !f.awaitingValue) f.lastKey = token else if (f.obj) f.awaitingValue = false
                i = close + 1
            }
            c == ':' -> {
                stack.last().awaitingValue = true
                i++
            }
            c == ',' -> {
                stack.last().awaitingValue = false
                i++
            }
            else -> {
                while (i < end && !text[i].isWhitespace() && text[i] != ',' && text[i] != '}' && text[i] != ']') i++
                if (stack.last().obj) stack.last().awaitingValue = false
            }
        }
    }
}

/** 找 `from` 起第一个未转义收尾引号；找不到返回 [limit] - 1（结构扫描对未闭合串容错）。 */
private fun findClosingQuote(text: String, from: Int, limit: Int): Int {
    var i = from
    while (i < limit && i < text.length) {
        if (text[i] == '"' && !isEscaped(text, i)) return i
        i++
    }
    return (limit - 1).coerceAtLeast(from - 1)
}

private fun isDslWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c == '*'

private fun isEscaped(text: String, index: Int): Boolean {
    var backslashes = 0
    var i = index - 1
    while (i >= 0 && text[i] == '\\') {
        backslashes++
        i--
    }
    return backslashes % 2 == 1
}

/** 统计 `[0, caret)` 内未转义的双引号数（奇数 = caret 在字符串内）。 */
private fun countUnescapedQuotes(text: String, caret: Int): Int {
    var count = 0
    var i = 0
    val end = caret.coerceAtMost(text.length)
    while (i < end) {
        if (text[i] == '"' && !isEscaped(text, i)) count++
        i++
    }
    return count
}

/** caret 处是否紧邻一个未转义的收尾引号（决定插入文本是否要再补引号）。 */
private fun closingQuoteAt(text: String, caret: Int): Boolean =
    caret < text.length && text[caret] == '"' && !isEscaped(text, caret)
