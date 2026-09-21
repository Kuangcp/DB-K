package app.ui

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import i18n.I18n
import i18n.Str

/**
 * 单元格内容的 JSON 识别 / 语法着色 / 树形折叠（纯逻辑，便于单测）。
 *
 * 渲染见 `app/dialog/JsonTreeView.kt`：把这里扁平化出来的 [JsonTreeLine] 列表逐行画出来，
 * 容器行可点击展开/收起。这里不碰 Compose 状态，只做纯函数。
 *
 * 配色与 SQL 高亮同级：属于「语法 token 色」（AGENTS.md 允许写死的语义色范畴），
 * 界面常规文字仍一律走主题色。
 */

/** JSON 语法 token 色板（深/浅两套，随主题切换）。 */
internal data class JsonSyntaxPalette(
    val key: Color,          // "field" 的字段名
    val string: Color,       // "value" 字符串字面量
    val number: Color,       // 数字
    val boolean: Color,      // true / false
    val nullLiteral: Color,  // null
    val punctuation: Color,  // { } [ ] , : 等结构符号
)

internal fun jsonSyntaxPalette(colors: ThemeColors): JsonSyntaxPalette = JsonSyntaxPalette(
    key = colors.keyword,
    string = colors.string,
    number = colors.number,
    boolean = colors.keyword,
    nullLiteral = colors.keyword,
    punctuation = colors.punctuation,
)

// TODO(Task 4): 迁移调用点后删除过渡重载
internal fun jsonSyntaxPalette(isDark: Boolean): JsonSyntaxPalette =
    jsonSyntaxPalette((if (isDark) themeById("dark") else themeById("light"))!!.colors)

/**
 * 廉价初筛：内容（去空白后）首尾是配对的 `{}` / `[]`，看起来像顶层 JSON 对象/数组。
 * 只用于决定要不要起线程解析，**合法性以 [parseJsonDocument] 为准**。
 */
internal fun looksLikeJson(content: String): Boolean {
    val t = content.trim()
    if (t.length < 2) return false
    return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))
}

/** 解析单元格内容为 JSON；仅当顶层是对象/数组且语法合法时返回成功。 */
internal fun parseJsonDocument(content: String): Result<JsonElement> = runCatching {
    val trimmed = content.trim()
    require(trimmed.isNotEmpty()) { I18n.t(Str.JsonEmpty) }
    require(trimmed.first() == '{' || trimmed.first() == '[') { I18n.t(Str.JsonTopLevel) }
    val element = Json.parseToJsonElement(trimmed)
    require(element is JsonObject || element is JsonArray) { I18n.t(Str.JsonTopLevel) }
    element
}

/**
 * 默认展开策略：根节点总是展开；小而扁的容器（1~3 个纯标量成员）也默认展开，
 * 其余容器默认收起——避免一段大 JSON 一打开就铺满整屏。
 */
internal fun jsonDefaultExpanded(depth: Int, node: JsonElement): Boolean {
    if (depth == 0) return true
    val children: Collection<JsonElement> = when (node) {
        is JsonObject -> node.values
        is JsonArray -> node
        else -> return false
    }
    return children.size in 1..3 && children.all { it !is JsonObject && it !is JsonArray }
}

/** JSON 树的一行（扁平化后的可渲染单元）。 */
internal enum class JsonLineKind { OPEN, CLOSE, LEAF }

internal data class JsonTreeLine(
    val depth: Int,
    val path: String,
    val kind: JsonLineKind,
    /** object 成员的键；array 元素与 CLOSE 行为 null。 */
    val key: String?,
    /** LEAF 的标量值 / OPEN、CLOSE 的容器本体。 */
    val value: JsonElement?,
    /** 该行行尾是否补逗号（收起时逗号落在 OPEN 行，展开时落在 CLOSE 行）。 */
    val comma: Boolean,
    /** OPEN：成员数；其它 0。 */
    val childCount: Int,
    /** OPEN 且展开时为 true（收起时为 false）。 */
    val expanded: Boolean,
)

/** 容器元素（对象/数组）判定。 */
internal fun isJsonContainer(element: JsonElement): Boolean =
    element is JsonObject || element is JsonArray

/**
 * 按展开状态把 JSON 扁平化为可逐行渲染的列表。
 *
 * 路径用「层级下标」编码（根 `0`，子 `0.1.2`），与键内容无关，因此可作为稳定的
 * 展开状态 key（键里含 `.`、`[` 也不会串）。[maxLines] 兜底防止超大 JSON 撑爆内存。
 */
internal fun flattenJsonTree(
    root: JsonElement,
    maxLines: Int = 50_000,
    isExpanded: (path: String, depth: Int, node: JsonElement) -> Boolean,
): List<JsonTreeLine> {
    val out = ArrayList<JsonTreeLine>()

    fun childrenOf(node: JsonElement): List<Pair<String?, JsonElement>> = when (node) {
        is JsonObject -> node.entries.map { it.key to it.value }
        is JsonArray -> node.map { null to it }
        else -> emptyList()
    }

    fun emit(node: JsonElement, depth: Int, path: String, key: String?, comma: Boolean) {
        if (out.size >= maxLines) return
        if (isJsonContainer(node)) {
            val expanded = isExpanded(path, depth, node)
            val count = if (node is JsonObject) node.size else (node as JsonArray).size
            out.add(
                JsonTreeLine(
                    depth = depth,
                    path = path,
                    kind = JsonLineKind.OPEN,
                    key = key,
                    value = node,
                    comma = comma && !expanded,
                    childCount = count,
                    expanded = expanded,
                ),
            )
            if (expanded) {
                val children = childrenOf(node)
                children.forEachIndexed { i, (k, v) ->
                    emit(v, depth + 1, "$path.$i", k, comma = i < children.size - 1)
                }
                out.add(
                    JsonTreeLine(
                        depth = depth,
                        path = path,
                        kind = JsonLineKind.CLOSE,
                        key = null,
                        value = node,
                        comma = comma,
                        childCount = count,
                        expanded = true,
                    ),
                )
            }
        } else {
            out.add(
                JsonTreeLine(
                    depth = depth,
                    path = path,
                    kind = JsonLineKind.LEAF,
                    key = key,
                    value = node,
                    comma = comma,
                    childCount = 0,
                    expanded = false,
                ),
            )
        }
    }

    emit(root, depth = 0, path = "0", key = null, comma = false)
    return out
}

/**
 * 收起时的单行摘要：标量原样，容器折叠成 `{…}` / `[…]`，最多取 [maxItems] 项、[maxChars] 字符。
 * 用于在收起行右侧展示「里面大概是什么」。
 */
internal fun jsonInlinePreview(
    node: JsonElement,
    maxItems: Int = 4,
    maxChars: Int = 80,
): String {
    fun render(n: JsonElement): String = when (n) {
        is JsonObject -> "{…}"
        is JsonArray -> "[…]"
        is JsonNull -> "null"
        is JsonPrimitive -> n.toString()
    }

    val (open, close) = if (node is JsonObject) "{" to "}" else "[" to "]"
    val items: List<String> = when (node) {
        is JsonObject -> node.entries.map { "${JsonPrimitive(it.key)}: ${render(it.value)}" }
        is JsonArray -> node.map { render(it) }
        else -> return node.toString()
    }
    if (items.isEmpty()) return "$open$close"

    val shown = items.take(maxItems)
    val s = buildString {
        append(open)
        append(' ')
        append(shown.joinToString(", "))
        if (items.size > shown.size) append(", …")
        append(' ')
        append(close)
    }
    return if (s.length <= maxChars) s else s.take(maxChars - 1) + "…"
}
