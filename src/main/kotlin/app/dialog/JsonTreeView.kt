package app.dialog

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ui.JsonLineKind
import app.ui.JsonSyntaxPalette
import app.ui.JsonTreeLine
import app.ui.flattenJsonTree
import app.ui.jsonDefaultExpanded
import app.ui.jsonInlinePreview
import app.ui.jsonSyntaxPalette
import app.ui.LocalThemeColors
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import i18n.I18n
import i18n.Str

/**
 * JSON 树的展开/收起状态（路径 → 是否展开），由对话框持有，便于工具栏放「全部展开/全部收起」。
 * 未显式覆盖的路径回落到 [jsonDefaultExpanded]（根展开、小容器展开、其余收起）。
 */
@Stable
class JsonTreeState {
    // 路径用 flattenJsonTree 的层级下标编码，稳定且与键内容无关
    private val overrides = mutableStateMapOf<String, Boolean>()

    /** null = 用默认策略；true/false = 用户点了「全部展开/收起」。 */
    private var forceAll by mutableStateOf<Boolean?>(null)

    internal fun isExpanded(path: String, depth: Int, node: JsonElement): Boolean =
        overrides[path] ?: forceAll ?: jsonDefaultExpanded(depth, node)

    /** 点击某一行容器：翻转它的展开状态（之后的「全部展开/收起」会清空这些单点覆盖）。 */
    fun toggle(path: String, depth: Int, node: JsonElement) {
        overrides[path] = !isExpanded(path, depth, node)
    }

    fun expandAll() {
        overrides.clear()
        forceAll = true
    }

    fun collapseAll() {
        overrides.clear()
        forceAll = false
    }
}

/**
 * JSON 树视图：等宽字体逐行渲染，容器行带折叠箭头、整行可点击展开/收起；
 * 收起时右侧展示浅色摘要（`{ "id": 1, … }`）。行数可能很大，用 LazyColumn 虚拟化。
 */
@Composable
fun JsonTreeView(root: JsonElement, state: JsonTreeState, modifier: Modifier = Modifier) {
    val themeColors = LocalThemeColors.current
    val pal = remember(themeColors) { jsonSyntaxPalette(themeColors) }
    val muted = MaterialTheme.colors.onSurface.copy(alpha = 0.45f)

    // derivedStateOf 会在 state/overrides 变化时重算；remember 让内容不变时复用
    val lines by remember(root, state) {
        derivedStateOf {
            flattenJsonTree(root) { path, depth, node -> state.isExpanded(path, depth, node) }
        }
    }
    val listState = rememberLazyListState()

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colors.onSurface.copy(alpha = 0.04f)),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        ) {
            itemsIndexed(lines, key = { _, line -> "${line.path}#${line.kind}" }) { _, line ->
                JsonTreeRow(
                    line = line,
                    pal = pal,
                    muted = muted,
                    onToggle = {
                        line.value?.let { state.toggle(line.path, line.depth, it) }
                    },
                )
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            style = viewerScrollbarStyle(),
        )
    }
}

@Composable
private fun JsonTreeRow(
    line: JsonTreeLine,
    pal: JsonSyntaxPalette,
    muted: Color,
    onToggle: () -> Unit,
) {
    val clickable = line.kind == JsonLineKind.OPEN && line.value != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable(onClick = onToggle) else Modifier),
        verticalAlignment = Alignment.Top,
    ) {
        // 缩进（每层 14dp）+ 折叠箭头占位（非容器留空，保证同级文字左对齐）
        Box(Modifier.padding(start = (line.depth * 14).dp).size(width = 16.dp, height = 18.dp)) {
            if (clickable) {
                Icon(
                    imageVector = if (line.expanded) {
                        Icons.Filled.KeyboardArrowDown
                    } else {
                        Icons.AutoMirrored.Filled.KeyboardArrowRight
                    },
                    contentDescription = if (line.expanded) I18n.t(Str.TreeCollapse) else I18n.t(Str.TreeExpand),
                    tint = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.size(14.dp).align(Alignment.Center),
                )
            }
        }
        Text(
            text = remember(line, pal, muted) { jsonLineAnnotated(line, pal, muted) },
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.9f),
            modifier = Modifier.weight(1f).padding(start = 2.dp),
        )
    }
}

/** 把一行渲染成带语法着色的 [AnnotatedString]（缩进与箭头由布局负责，不在这里）。 */
private fun jsonLineAnnotated(line: JsonTreeLine, pal: JsonSyntaxPalette, muted: Color): AnnotatedString =
    buildAnnotatedString {
        fun token(text: String, color: Color) = withStyle(SpanStyle(color = color)) { append(text) }

        fun appendKey() {
            line.key?.let {
                token(JsonPrimitive(it).toString(), pal.key)
                token(": ", muted)
            }
        }

        val isObject = line.value is JsonObject
        val open = if (isObject) "{" else "["
        val close = if (isObject) "}" else "]"

        when (line.kind) {
            JsonLineKind.OPEN -> {
                appendKey()
                token(open, pal.punctuation)
                if (!line.expanded) {
                    line.value?.let { token(" " + jsonInlinePreview(it), muted) }
                    token(" $close", pal.punctuation)
                    if (line.comma) token(",", pal.punctuation)
                }
            }
            JsonLineKind.CLOSE -> {
                token(close, pal.punctuation)
                if (line.comma) token(",", pal.punctuation)
            }
            JsonLineKind.LEAF -> {
                appendKey()
                line.value?.let { v ->
                    val (text, color) = jsonLeafText(v, pal)
                    token(text, color)
                }
                if (line.comma) token(",", pal.punctuation)
            }
        }
    }

/** 标量值 → (原文, 颜色)。字符串保留引号与转义（用 JsonPrimitive.toString）。 */
private fun jsonLeafText(value: JsonElement, pal: JsonSyntaxPalette): Pair<String, Color> = when {
    value is JsonNull -> "null" to pal.nullLiteral
    value is JsonPrimitive -> {
        val color = when {
            value.isString -> pal.string
            value.booleanOrNull != null -> pal.boolean
            else -> pal.number
        }
        value.toString() to color
    }
    else -> value.toString() to pal.punctuation
}
