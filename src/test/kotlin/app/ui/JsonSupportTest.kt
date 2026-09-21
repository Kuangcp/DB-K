package app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JSON 识别 / 树扁平化 / 原文高亮的纯逻辑单测（不依赖 Compose 运行时）。
 */
class JsonSupportTest {

    private fun parse(text: String) = parseJsonDocument(text).getOrThrow()

    // ---------- 识别与解析 ----------

    @Test
    fun looksLikeJsonOnlyForObjectOrArray() {
        assertTrue(looksLikeJson("{}"))
        assertTrue(looksLikeJson("  [1, 2] "))
        assertTrue(looksLikeJson("{\"a\": 1}"))
        assertFalse(looksLikeJson("123"))
        assertFalse(looksLikeJson("\"hello\""))
        assertFalse(looksLikeJson("true"))
        assertFalse(looksLikeJson("{"))
        assertFalse(looksLikeJson(""))
        assertFalse(looksLikeJson("hello world"))
    }

    @Test
    fun parsesObjectAndArray() {
        assertTrue(parse("{\"a\": 1}") is JsonObject)
        assertTrue(parse("[1, 2, 3]") is JsonArray)
    }

    @Test
    fun rejectsInvalidOrNonContainer() {
        assertTrue(parseJsonDocument("{\"a\": }").isFailure)
        assertTrue(parseJsonDocument("[1, 2").isFailure)
        assertTrue(parseJsonDocument("42").isFailure)
        assertTrue(parseJsonDocument("   ").isFailure)
    }

    // ---------- 默认展开策略 ----------

    @Test
    fun defaultExpandsRootAndSmallScalarContainers() {
        assertTrue(jsonDefaultExpanded(0, parse("{}")), "根节点总是展开")
        assertTrue(jsonDefaultExpanded(0, parse("[1, 2, 3, 4, 5]")))
        assertTrue(jsonDefaultExpanded(1, parse("[1, 2]")))
        assertTrue(jsonDefaultExpanded(1, parse("{\"a\": 1, \"b\": \"x\"}")))
        assertFalse(jsonDefaultExpanded(1, parse("[1, 2, 3, 4]")), "成员过多默认收起")
        assertFalse(jsonDefaultExpanded(1, parse("{\"a\": [1]}")), "含嵌套容器默认收起")
        assertFalse(jsonDefaultExpanded(1, parse("[]")), "空容器默认收起")
        assertTrue(jsonDefaultExpanded(2, parse("[1]")), "深层小标量容器也展开")
    }

    // ---------- 扁平化 ----------

    private fun allExpanded(lines: List<JsonTreeLine>) =
        lines.all { it.kind != JsonLineKind.OPEN || it.expanded }

    @Test
    fun flattensExpandedTreeInOrder() {
        val root = parse("{\"a\": 1, \"b\": [2, 3]}")
        val lines = flattenJsonTree(root) { _, depth, node -> jsonDefaultExpanded(depth, node) }

        assertEquals(7, lines.size)
        assertEquals(
            listOf("0", "0.0", "0.1", "0.1.0", "0.1.1", "0.1", "0"),
            lines.map { it.path },
        )
        assertEquals(JsonLineKind.OPEN, lines[0].kind)
        assertEquals(2, lines[0].childCount)
        assertTrue(lines[0].expanded)
        assertFalse(lines[0].comma)
        // 对象成员键落在 LEAF 上
        assertEquals("a", lines[1].key)
        assertEquals(JsonLineKind.LEAF, lines[1].kind)
        assertTrue(lines[1].comma)
        assertEquals("b", lines[2].key)
        assertEquals(JsonLineKind.OPEN, lines[2].kind)
        // 数组元素无键
        assertNull(lines[3].key)
        assertNull(lines[4].key)
        assertFalse(lines[4].comma)
        assertEquals(JsonLineKind.CLOSE, lines[5].kind)
        assertFalse(lines[6].comma)
        assertTrue(allExpanded(lines))
    }

    @Test
    fun collapsedContainerBecomesSingleOpenLine() {
        val root = parse("{\"a\": 1, \"b\": [2, 3]}")
        val lines = flattenJsonTree(root) { _, _, _ -> false }

        assertEquals(1, lines.size, "整体收起后应只有根 OPEN 一行")
        val only = lines.single()
        assertEquals(JsonLineKind.OPEN, only.kind)
        assertFalse(only.expanded)
        assertEquals(2, only.childCount)
        assertTrue(jsonInlinePreview(only.value!!).contains("\"a\": 1"))
    }

    @Test
    fun commaStaysOnContainerWhenCollapsed() {
        val root = parse("{\"a\": {}, \"b\": 2}")
        // 只有根展开，子容器 {} 收起
        val lines = flattenJsonTree(root) { path, _, _ -> path == "0" }
        // 0=根OPEN, 0.0={}收起(带逗号), 0.1=LEAF, 0=根CLOSE
        val collapsedObj = lines.first { it.path == "0.0" }
        assertEquals(JsonLineKind.OPEN, collapsedObj.kind)
        assertTrue(collapsedObj.comma, "收起容器行应保留与后续兄弟相隔的逗号")
    }

    // ---------- 摘要 ----------

    @Test
    fun inlinePreviewRendersShallowSummary() {
        assertEquals("{}", jsonInlinePreview(parse("{}")))
        assertEquals("[]", jsonInlinePreview(parse("[]")))
        assertEquals("{ \"a\": 1, \"b\": {…} }", jsonInlinePreview(parse("{\"a\": 1, \"b\": {\"c\": 2}}")))
        assertEquals("[ 1, \"x\", null ]", jsonInlinePreview(parse("[1, \"x\", null]")))
        // 超出 maxItems 时以 … 收尾
        assertTrue(jsonInlinePreview(parse("[1,2,3,4,5,6]"), maxItems = 2).contains("…"))
    }

    @Test
    fun inlinePreviewRespectsMaxChars() {
        val long = parse("[${(1..200).joinToString(",")}]")
        val preview = jsonInlinePreview(long, maxChars = 30)
        assertTrue(preview.length <= 30)
    }

    // ---------- 原文高亮 ----------

    private val pal = jsonSyntaxPalette(themeById("light")!!.colors)

    private fun highlight(text: String): AnnotatedString =
        AnnotatedString(text, spanStyles = jsonHighlightSpans(text, pal))

    private fun colorAt(a: AnnotatedString, index: Int): Color? =
        a.spanStyles.lastOrNull { index >= it.start && index < it.end }?.item?.color

    @Test
    fun highlightsKeysStringsNumbersBooleansNullAndPunctuation() {
        val text = "{\"a\": 1, \"b\": true, \"c\": null, \"d\": \"x\"}"
        val a = highlight(text)

        assertEquals(pal.punctuation, colorAt(a, 0), "左花括号应取标点色")
        assertEquals(pal.key, colorAt(a, 2), "字段名 a 应取 key 色")
        assertEquals(pal.number, colorAt(a, 6), "数字应取 number 色")
        assertEquals(pal.key, colorAt(a, 10), "字段名 b 应取 key 色")
        assertEquals(pal.boolean, colorAt(a, 14), "true 应取 boolean 色")
        assertEquals(pal.nullLiteral, colorAt(a, 25), "null 应取 nullLiteral 色")
        assertEquals(pal.key, colorAt(a, 32), "字段名 d 应取 key 色")
        assertEquals(pal.string, colorAt(a, 37), "字符串值 x 应取 string 色")
    }

    @Test
    fun keyColorDiffersFromStringValueColor() {
        val a = highlight("{\"k\": \"v\"}")
        assertEquals(pal.key, colorAt(a, 2))
        assertEquals(pal.string, colorAt(a, 7))
    }

    @Test
    fun keyDetectionToleratesWhitespaceBeforeColon() {
        assertEquals(pal.key, colorAt(highlight("{\"a\"   : 1}"), 1))
        assertEquals(pal.string, colorAt(highlight("{\"a\"  1}"), 1), "后面不是冒号则按普通字符串着色")
    }

    @Test
    fun numbersWithSignDotAndExponent() {
        val a = highlight("[-1, 2.5, 3e10, -4.2E-3]")
        assertEquals(pal.number, colorAt(a, 1), "-1")
        assertEquals(pal.number, colorAt(a, 5), "2.5")
        assertEquals(pal.number, colorAt(a, 10), "3e10")
        assertEquals(pal.number, colorAt(a, 16), "-4.2E-3")
    }

    @Test
    fun nestedObjectsHighlightEachLevel() {
        val a = highlight("{\"a\":{\"b\":[{\"c\":1}]}}")
        assertEquals(pal.key, colorAt(a, 1), "外层键")
        assertEquals(pal.key, colorAt(a, 6), "内层键")
        assertEquals(pal.key, colorAt(a, 13), "数组内对象的键")
        assertEquals(pal.number, colorAt(a, 16), "内层数字")
    }

    @Test
    fun bareWordsThatAreNotLiteralsStayUncolored() {
        assertNull(colorAt(highlight("{\"k\": truestuff}"), 7), "truestuff 不是布尔字面量")
    }

    /**
     * 回归：长字符串值（含大量转义）必须先扫完不爆栈。
     * 旧实现的正则 `("(?:\\.|[^"\\])*")` 在 ~5k 字符就 StackOverflowError（UI 线程卡死事故）。
     */
    @Test
    fun longStringValueDoesNotOverflowStack() {
        val long = "{\"k\":\"" + "x".repeat(300_000) + "\"}"
        assertEquals(pal.string, colorAt(highlight(long), 100_000), "超长字符串值应整体着色且不爆栈")

        val escapes = "{\"k\":\"" + "\\n".repeat(150_000) + "\"}"
        assertEquals(pal.string, colorAt(highlight(escapes), 100_000), "大量转义序列不应爆栈")

        val manyKeys = "{" + "\"k\":1,".repeat(50_000) + "\"z\":0}"
        assertTrue(highlight(manyKeys).spanStyles.isNotEmpty(), "海量短字段不应崩")
    }

    @Test
    fun primitiveCheckUsedByTree() {
        // 保障 jsonLeafText 依赖的判定（isString / booleanOrNull）与解析结果一致
        val obj = parse("{\"s\": \"x\", \"b\": false, \"n\": 1.5}") as JsonObject
        val s = obj["s"] as JsonPrimitive
        val b = obj["b"] as JsonPrimitive
        val n = obj["n"] as JsonPrimitive
        assertTrue(s.isString)
        assertNull(s.booleanOrNull)
        assertFalse(b.isString)
        assertEquals(false, b.booleanOrNull)
        assertNull(n.booleanOrNull)
    }
}
