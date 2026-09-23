package app.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Divider
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.rememberDialogState
import app.i18n.ProvideI18n
import app.i18n.t
import app.settings.ThemesStore
import app.ui.LocalThemeColors
import app.ui.ScaledDialogWindow
import app.ui.ThemeColors
import app.ui.scaledSize
import app.ui.ThemeSpec
import app.ui.defaultThemeId
import app.ui.sqlHighlightKeywordSet
import app.ui.sqlHighlightSpans
import app.ui.sqlSyntaxPalette
import i18n.I18n
import i18n.Lang
import i18n.Str

private val COLOR_FIELDS: List<Pair<String, Str>> = listOf(
    "background" to Str.ThemeColorBackground,
    "surface" to Str.ThemeColorSurface,
    "onSurface" to Str.ThemeColorOnSurface,
    "primary" to Str.ThemeColorPrimary,
    "editorBackground" to Str.ThemeColorEditorBackground,
    "editorForeground" to Str.ThemeColorEditorForeground,
    "keyword" to Str.ThemeColorKeyword,
    "string" to Str.ThemeColorString,
    "number" to Str.ThemeColorNumber,
    "comment" to Str.ThemeColorComment,
    "punctuation" to Str.ThemeColorPunctuation,
    "function" to Str.ThemeColorFunction,
)

private fun hexOf(c: ThemeColors): Map<String, String> = mapOf(
    "background" to ThemeColors.toHex(c.background),
    "surface" to ThemeColors.toHex(c.surface),
    "onSurface" to ThemeColors.toHex(c.onSurface),
    "primary" to ThemeColors.toHex(c.primary),
    "editorBackground" to ThemeColors.toHex(c.editorBackground),
    "editorForeground" to ThemeColors.toHex(c.editorForeground),
    "keyword" to ThemeColors.toHex(c.keyword),
    "string" to ThemeColors.toHex(c.string),
    "number" to ThemeColors.toHex(c.number),
    "comment" to ThemeColors.toHex(c.comment),
    "punctuation" to ThemeColors.toHex(c.punctuation),
    "function" to ThemeColors.toHex(c.function),
)

/** 全部字段合法才返回色板；任一非法/缺失返回 null。 */
private fun colorsOf(hex: Map<String, String>): ThemeColors? {
    fun c(k: String): Color? = hex[k]?.let { ThemeColors.parse(it) }
    return ThemeColors(
        background = c("background") ?: return null,
        surface = c("surface") ?: return null,
        onSurface = c("onSurface") ?: return null,
        primary = c("primary") ?: return null,
        editorBackground = c("editorBackground") ?: return null,
        editorForeground = c("editorForeground") ?: return null,
        keyword = c("keyword") ?: return null,
        string = c("string") ?: return null,
        number = c("number") ?: return null,
        comment = c("comment") ?: return null,
        punctuation = c("punctuation") ?: return null,
        function = c("function") ?: return null,
    )
}

private enum class NameMode { SaveAs, Rename }

/**
 * 主题管理：左侧主题列表，右侧 11 项色板编辑 + 实时预览。
 * 内置主题只读（提示另存为新主题）；自定义主题可保存/重命名/删除。
 */
@Composable
fun ThemeDialog(
    visible: Boolean,
    language: Lang,
    themes: List<ThemeSpec>,
    activeTheme: ThemeSpec,
    store: ThemesStore,
    onSelectTheme: (String) -> Unit,
    onCustomThemesChange: (List<ThemeSpec>) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    ScaledDialogWindow(
        onCloseRequest = onDismiss,
        title = t(Str.ThemeManagerTitle),
        state = rememberDialogState(size = scaledSize(780.dp, 560.dp)),
    ) {
        ProvideI18n(language) {
            MaterialTheme(colors = activeTheme.colors.toMaterialColors()) {
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colors.onSurface,
                    LocalThemeColors provides activeTheme.colors,
                ) {
                    ThemeDialogBody(
                        themes = themes,
                        activeTheme = activeTheme,
                        store = store,
                        onSelectTheme = onSelectTheme,
                        onCustomThemesChange = onCustomThemesChange,
                        onDismiss = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeDialogBody(
    themes: List<ThemeSpec>,
    activeTheme: ThemeSpec,
    store: ThemesStore,
    onSelectTheme: (String) -> Unit,
    onCustomThemesChange: (List<ThemeSpec>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedId by remember { mutableStateOf(activeTheme.id) }
    val selected = themes.firstOrNull { it.id == selectedId } ?: activeTheme
    val hex = remember(selected.id, selected.colors) {
        mutableStateMapOf<String, String>().apply { putAll(hexOf(selected.colors)) }
    }
    val editable = !selected.builtIn
    val draft = colorsOf(hex)
    var nameMode by remember { mutableStateOf<NameMode?>(null) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun persist(list: List<ThemeSpec>) {
        // 回调的契约是「自定义主题列表变更」：内置主题永不出现在其中（否则 Main 会重复拼接内置）。
        val custom = list.filterNot { it.builtIn }
        store.save(custom)
        onCustomThemesChange(custom)
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background).padding(14.dp)) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // 左：主题列表
            Column(
                modifier = Modifier
                    .width(190.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colors.surface)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
            ) {
                themes.forEach { th ->
                    val isSel = th.id == selected.id
                    val label = th.nameKey?.let { t(it) } ?: th.name
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedId = th.id
                                onSelectTheme(th.id)
                                status = null
                            }
                            .background(
                                if (isSel) MaterialTheme.colors.primary.copy(alpha = 0.16f) else Color.Transparent,
                            )
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Text(
                            label,
                            fontSize = 13.sp,
                            fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSel) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(alpha = 0.85f),
                        )
                    }
                }
            }
            Divider(
                modifier = Modifier.width(1.dp).fillMaxHeight(),
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.12f),
            )
            // 右：色板 + 预览
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!editable) {
                    Text(
                        t(Str.ThemeBuiltInReadOnly),
                        fontSize = 12.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    )
                }
                ColorFieldGroup(Str.ThemeColorsSection, COLOR_FIELDS.take(4), hex, editable)
                ColorFieldGroup(Str.ThemeEditorSection, COLOR_FIELDS.subList(4, 6), hex, editable)
                ColorFieldGroup(Str.ThemeSyntaxSection, COLOR_FIELDS.drop(6), hex, editable)
                ThemePreview(draft ?: selected.colors)
                status?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colors.primary)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                enabled = editable && draft != null,
                onClick = {
                    val colors = colorsOf(hex) ?: return@TextButton
                    val updated = selected.copy(colors = colors)
                    val next = themes.map { if (it.id == selected.id) updated else it }
                    persist(next)
                    onSelectTheme(selected.id)
                    status = I18n.t(Str.ThemeSaved, updated.name)
                },
            ) { Text(t(Str.CommonSave)) }
            TextButton(enabled = draft != null, onClick = { nameMode = NameMode.SaveAs }) {
                Text(t(Str.ThemeSaveAs))
            }
            TextButton(enabled = editable, onClick = { nameMode = NameMode.Rename }) {
                Text(t(Str.ThemeRenameTitle))
            }
            TextButton(enabled = editable, onClick = { deleteConfirm = true }) {
                Text(t(Str.ThemeDeleteTitle))
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text(t(Str.CommonCancel)) }
        }
    }

    nameMode?.let { mode ->
        ThemeNamePrompt(
            initial = if (mode == NameMode.Rename) selected.name else "",
            onDismiss = { nameMode = null },
            onConfirm = { name ->
                nameMode = null
                when (mode) {
                    NameMode.SaveAs -> {
                        val colors = colorsOf(hex) ?: selected.colors
                        val created = ThemeSpec(
                            id = store.newId(),
                            name = name,
                            builtIn = false,
                            baseId = selected.id,
                            colors = colors,
                        )
                        val next = themes + created
                        persist(next)
                        selectedId = created.id
                        onSelectTheme(created.id)
                        status = I18n.t(Str.ThemeSaved, name)
                    }
                    NameMode.Rename -> {
                        val next = themes.map { if (it.id == selected.id) it.copy(name = name) else it }
                        persist(next)
                        status = I18n.t(Str.ThemeSaved, name)
                    }
                }
            },
        )
    }

    if (deleteConfirm) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = { Text(t(Str.ThemeDeleteTitle)) },
            text = { Text(t(Str.ThemeDeleteConfirm, selected.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteConfirm = false
                    val next = themes.filterNot { it.id == selected.id }
                    persist(next)
                    if (selected.id == activeTheme.id) onSelectTheme(defaultThemeId)
                    selectedId = defaultThemeId
                    status = null
                }) { Text(t(Str.CommonDelete), color = MaterialTheme.colors.error) }
            },
            dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text(t(Str.CommonCancel)) } },
        )
    }
}

@Composable
private fun ColorFieldGroup(
    title: Str,
    fields: List<Pair<String, Str>>,
    hex: MutableMap<String, String>,
    editable: Boolean,
) {
    Text(t(title), fontSize = 12.sp, color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f))
    fields.forEach { (key, label) ->
        val value = hex[key].orEmpty()
        val invalid = ThemeColors.parse(value) == null
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(ThemeColors.parse(value) ?: MaterialTheme.colors.surface)
                    .border(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.25f), RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                t(label),
                fontSize = 12.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.85f),
                modifier = Modifier.width(110.dp),
            )
            OutlinedTextField(
                value = value,
                onValueChange = { hex[key] = it },
                enabled = editable,
                singleLine = true,
                isError = invalid,
                textStyle = MaterialTheme.typography.body2,
                modifier = Modifier.width(160.dp),
            )
            if (invalid && editable) {
                Spacer(Modifier.width(8.dp))
                Text(t(Str.ThemeColorInvalid), fontSize = 11.sp, color = MaterialTheme.colors.error)
            }
        }
    }
}

@Composable
private fun ThemePreview(colors: ThemeColors) {
    val keywords = remember { sqlHighlightKeywordSet() }
    val sample = "SELECT id, avg(price) FROM sales WHERE active = true; -- preview"
    val annotated = remember(colors, keywords) {
        AnnotatedString(sample, spanStyles = sqlHighlightSpans(sample, sqlSyntaxPalette(colors), keywords))
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(colors.background)
            .border(1.dp, colors.onSurface.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
            .padding(12.dp),
    ) {
        Column {
            Text("预览 · Preview", fontSize = 12.sp, color = colors.onSurface.copy(alpha = 0.7f))
            Spacer(Modifier.height(6.dp))
            Text(annotated, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = colors.editorForeground)
        }
    }
}

@Composable
private fun ThemeNamePrompt(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial) }
    val confirm = { if (name.isNotBlank()) onConfirm(name.trim()) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(Str.ThemeSaveAsTitle)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(t(Str.ThemeNameLabel)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent(submitOnEnter(name.isNotBlank(), confirm)),
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = confirm) { Text(t(Str.CommonOk)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(Str.CommonCancel)) } },
    )
}
