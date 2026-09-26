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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ContentAlpha
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Divider
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Slider
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextFieldColors
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.rememberDialogState
import app.build.Version
import app.core.openDirectory
import app.core.writeClipboardText
import app.i18n.ProvideI18n
import app.i18n.t
import app.settings.EditorSettings
import app.settings.Keymap
import app.settings.LiveTemplatesStore
import app.settings.UiScalePrefs
import app.state.UiScaleState
import app.ui.LocalThemeColors
import app.ui.LiveTemplate
import app.ui.ScaledDialogWindow
import app.ui.defaultLiveTemplates
import app.ui.ThemeSpec
import app.ui.editorFontFamily
import app.ui.scaledSize
import db.AppPaths
import i18n.I18n
import i18n.Lang
import i18n.Str

/** 设置窗口的完整快照：编辑器外观 + 快捷键 + 语言。保存时一并落盘。 */
data class SettingsSnapshot(
    val editor: EditorSettings,
    val keymap: Keymap,
    /** 语言偏好；`null` = 跟随系统。 */
    val language: Lang? = null,
    /** 全局界面缩放；1.0 = 100%。 */
    val uiScale: Float = UiScalePrefs.DEFAULT_SCALE,
    /** 活模板（含内置，整份保存）；保存后即时生效。无默认值，强制调用方传入避免误清空。 */
    val liveTemplates: List<LiveTemplate>,
    /** 控制台标签多行换行；false = 单行横向滚动。 */
    val multiRowTabs: Boolean = false,
)

/**
 * 设置窗口（独立 [DialogWindow]，与主窗口同款主题）。
 * 左侧分区导航 + 右侧内容；左下角展示版本号。当前有「通用设置」「快捷键」两个分区。
 */
@Composable
fun SettingsDialog(
    visible: Boolean,
    theme: ThemeSpec,
    language: Lang,
    initial: SettingsSnapshot,
    onDismiss: () -> Unit,
    onSave: (SettingsSnapshot) -> Unit,
    onManageThemes: () -> Unit = {},
    /** 实时预览：拖动滑块时立即把比例广播到全局（取消时由调用方回退）。 */
    onUiScalePreview: (Float) -> Unit = {},
) {
    if (!visible) return
    // 实时预览会改 UiScaleState；`rememberDialogState` 只在首次组合取 size，
    // 故用 LaunchedEffect 把原生窗口尺寸同步到当前缩放，避免从 100% 拖大后设置窗内容溢出。
    val scale = UiScaleState.scale
    val dialogState = rememberDialogState(size = scaledSize(760.dp, 520.dp, scale))
    LaunchedEffect(scale) {
        dialogState.size = scaledSize(760.dp, 520.dp, scale)
    }
    ScaledDialogWindow(
        onCloseRequest = onDismiss,
        title = t(Str.SettingsTitle),
        state = dialogState,
    ) {
        // DialogWindow 是独立 composition，不继承主窗口的 CompositionLocal → 自行提供语言
        ProvideI18n(language) {
            MaterialTheme(colors = theme.colors.toMaterialColors()) {
                // 独立窗口是另一棵 composition：M2 MaterialTheme 不设 LocalContentColor，需同样兜底
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colors.onSurface,
                    LocalThemeColors provides theme.colors,
                ) {
                    SettingsBody(
                        initial = initial,
                        onCancel = onDismiss,
                        onSave = onSave,
                        onManageThemes = onManageThemes,
                        onUiScalePreview = onUiScalePreview,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsBody(
    initial: SettingsSnapshot,
    onCancel: () -> Unit,
    onSave: (SettingsSnapshot) -> Unit,
    onManageThemes: () -> Unit,
    onUiScalePreview: (Float) -> Unit = {},
) {
    var section by remember { mutableIntStateOf(0) }
    var fontFamily by remember { mutableStateOf(initial.editor.fontFamilyName) }
    var fontSize by remember { mutableFloatStateOf(initial.editor.fontSizeSp) }
    var keymap by remember { mutableStateOf(initial.keymap) }
    var languagePref by remember { mutableStateOf(initial.language) }
    var uiScale by remember { mutableFloatStateOf(initial.uiScale) }
    var liveTemplates by remember { mutableStateOf(initial.liveTemplates) }
    var multiRowTabs by remember { mutableStateOf(initial.multiRowTabs) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
            .padding(16.dp),
    ) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // 左侧分区导航
            Column(
                modifier = Modifier
                    .width(168.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colors.surface)
                    .padding(vertical = 8.dp),
            ) {
                SettingsNavRow(label = t(Str.SettingsSectionGeneral), selected = section == 0) { section = 0 }
                SettingsNavRow(label = t(Str.SettingsSectionShortcuts), selected = section == 1) { section = 1 }
                SettingsNavRow(label = t(Str.SettingsSectionLiveTemplates), selected = section == 2) { section = 2 }
            }
            Divider(
                modifier = Modifier.width(1.dp).fillMaxHeight(),
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.12f),
            )
            // 右侧内容
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (section) {
                    0 -> {
                        GeneralSection(
                            fontFamily = fontFamily,
                            onFontFamilyChange = { fontFamily = it },
                            fontSize = fontSize,
                            onFontSizeChange = { fontSize = it },
                            uiScale = uiScale,
                            onUiScaleChange = { uiScale = it; onUiScalePreview(it) },
                            languagePref = languagePref,
                            onLanguageChange = { languagePref = it },
                            multiRowTabs = multiRowTabs,
                            onMultiRowTabsChange = { multiRowTabs = it },
                            onManageThemes = onManageThemes,
                        )
                        DiagnosticsSection()
                    }
                    1 -> ShortcutSettingsSection(
                        keymap = keymap,
                        onKeymapChange = { keymap = it },
                    )
                    2 -> LiveTemplatesSection(
                        templates = liveTemplates,
                        onChange = { liveTemplates = it },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = t(Str.SettingsVersion, Version.NAME, Version.COMMIT),
                fontSize = 11.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium),
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text(t(Str.CommonCancel)) }
            TextButton(onClick = {
                onSave(
                    SettingsSnapshot(
                        editor = EditorSettings.sanitized(fontFamily, fontSize),
                        keymap = keymap,
                        language = languagePref,
                        uiScale = uiScale,
                        liveTemplates = liveTemplates,
                        multiRowTabs = multiRowTabs,
                    ),
                )
            }) {
                Text(t(Str.CommonSave))
            }
        }
    }
}

@Composable
private fun SettingsNavRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colors.primary.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (selected) MaterialTheme.colors.onSurface
            else MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium),
            style = MaterialTheme.typography.body2,
        )
    }
}

@Composable
private fun GeneralSection(
    fontFamily: String,
    onFontFamilyChange: (String) -> Unit,
    fontSize: Float,
    onFontSizeChange: (Float) -> Unit,
    uiScale: Float,
    onUiScaleChange: (Float) -> Unit,
    languagePref: Lang?,
    onLanguageChange: (Lang?) -> Unit,
    multiRowTabs: Boolean,
    onMultiRowTabsChange: (Boolean) -> Unit,
    onManageThemes: () -> Unit,
) {
    Text(
        t(Str.SettingsEditorAppearance),
        style = MaterialTheme.typography.subtitle2,
        color = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium),
    )
    OutlinedTextField(
        value = fontFamily,
        onValueChange = onFontFamilyChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(t(Str.SettingsEditorFont)) },
        placeholder = { Text(t(Str.SettingsEditorFontPlaceholder)) },
        singleLine = true,
        colors = settingsFieldColors(),
    )
    Text(
        t(Str.SettingsEditorFontSize, "%.0f".format(fontSize)),
        style = MaterialTheme.typography.body2,
        color = MaterialTheme.colors.onSurface,
    )
    Slider(
        value = fontSize,
        onValueChange = onFontSizeChange,
        valueRange = EditorSettings.MIN_FONT_SP..EditorSettings.MAX_FONT_SP,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        t(Str.SettingsPreview),
        style = MaterialTheme.typography.subtitle2,
        color = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium),
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 84.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colors.surface)
            .border(
                1.dp,
                MaterialTheme.colors.onSurface.copy(alpha = 0.15f),
                RoundedCornerShape(6.dp),
            )
            .padding(12.dp),
    ) {
        Text(
            text = "SELECT id, name\nFROM users\nWHERE created_at > '2024-01-01';",
            fontFamily = editorFontFamily(fontFamily),
            fontSize = fontSize.sp,
            lineHeight = EditorSettings.lineHeightSp(fontSize).sp,
            color = MaterialTheme.colors.onSurface,
        )
    }
    Text(
        t(Str.SettingsUiScale, "%.0f".format(uiScale * 100)),
        style = MaterialTheme.typography.body2,
        color = MaterialTheme.colors.onSurface,
    )
    Slider(
        value = uiScale,
        onValueChange = onUiScaleChange,
        // 1.0..2.0、步进 0.05 → 20 段 → steps = 19
        valueRange = UiScalePrefs.MIN_SCALE..UiScalePrefs.MAX_SCALE,
        steps = 19,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        t(Str.SettingsUiScaleHint),
        style = MaterialTheme.typography.caption,
        color = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium),
    )
    Text(
        t(Str.SettingsTabMultiRow),
        style = MaterialTheme.typography.subtitle2,
        color = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = multiRowTabs, onCheckedChange = onMultiRowTabsChange)
        Spacer(Modifier.width(10.dp))
        Text(
            t(Str.SettingsTabMultiRowHint),
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium),
            modifier = Modifier.weight(1f),
        )
    }
    Text(
        t(Str.SettingsLanguage),
        style = MaterialTheme.typography.subtitle2,
        color = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium),
    )
    LanguageRow(current = languagePref, onChange = onLanguageChange)

    TextButton(onClick = onManageThemes) { Text(t(Str.ThemeManage)) }
}

/** 语言选择：跟随系统 / 简体中文 / English（选中项高亮，徽章样式与主题选择一致）。 */
@Composable
private fun LanguageRow(current: Lang?, onChange: (Lang?) -> Unit) {
    val options: List<Pair<Lang?, String>> = listOf(
        null to t(Str.SettingsLanguageSystem),
        Lang.ZH to t(Str.SettingsLanguageZh),
        Lang.EN to t(Str.SettingsLanguageEn),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val selected = value == current
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (selected) MaterialTheme.colors.primary.copy(alpha = 0.18f) else Color.Transparent,
                    )
                    .border(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                    .clickable { onChange(value) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    label,
                    color = if (selected) MaterialTheme.colors.onSurface
                    else MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium),
                    style = MaterialTheme.typography.body2,
                )
            }
        }
    }
}

@Composable
private fun settingsFieldColors(): TextFieldColors = TextFieldDefaults.outlinedTextFieldColors(
    textColor = MaterialTheme.colors.onSurface,
    cursorColor = MaterialTheme.colors.primary,
    focusedBorderColor = MaterialTheme.colors.primary.copy(alpha = ContentAlpha.high),
    unfocusedBorderColor = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.disabled),
    focusedLabelColor = MaterialTheme.colors.primary.copy(alpha = ContentAlpha.high),
    unfocusedLabelColor = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium),
    placeholderColor = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium),
)

/**
 * 诊断分区（P9）：展示日志 / 数据目录路径，支持一键用系统文件管理器打开。
 * 无桌面环境时回落「复制路径 + 行内提示」（设置窗口是独立窗口，主窗口 Toast 会被遮住）。
 */
@Composable
private fun DiagnosticsSection() {
    // 路径在窗口存活期间不变，remember 避免每帧拼字符串
    val logDir = remember { AppPaths.logsDirectory() }
    val dataDir = remember { AppPaths.dataDirectory() }
    var hint by remember { mutableStateOf<String?>(null) }

    Text(
        t(Str.SettingsDiagnostics),
        style = MaterialTheme.typography.subtitle2,
        color = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium),
    )
    Text(
        t(Str.SettingsLogDir),
        style = MaterialTheme.typography.body2,
        color = MaterialTheme.colors.onSurface,
    )
    Text(
        logDir.toString(),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val logLabel = t(Str.SettingsLogDir)
        val dataLabel = t(Str.SettingsDataDir)
        TextButton(onClick = { openOrCopy(logDir, logLabel) { hint = it } }) {
            Text(t(Str.SettingsOpenLogDir))
        }
        TextButton(onClick = { openOrCopy(dataDir, dataLabel) { hint = it } }) {
            Text(t(Str.SettingsOpenDataDir))
        }
    }
    val templateFile = remember { LiveTemplatesStore.templateFile() }
    val templateLabel = t(Str.SettingsLiveTemplates)
    val templateCreateLabel = t(Str.SettingsLiveTemplatesCreate)
    Text(
        templateLabel,
        style = MaterialTheme.typography.body2,
        color = MaterialTheme.colors.onSurface,
    )
    Text(
        templateFile.toString(),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            runCatching { LiveTemplatesStore.createSample(templateFile) }
            val dir = templateFile.parentFile ?: AppPaths.dataDirectory().toFile()
            openOrCopy(dir.toPath(), templateLabel) { hint = it }
        }) {
            Text(templateCreateLabel)
        }
    }
    hint?.let {
        Text(
            it,
            fontSize = 11.sp,
            color = MaterialTheme.colors.primary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Live Templates 表格：缩写 / 模板体 / 描述，可增删改；配合底部「保存」即时生效。
 * 空缩写/空模板体/重复缩写会在行内标红，实际保存前由 [LiveTemplatesStore] 清洗丢弃。
 */
@Composable
private fun LiveTemplatesSection(
    templates: List<LiveTemplate>,
    onChange: (List<LiveTemplate>) -> Unit,
) {
    val lower = templates.map { it.abbreviation.trim().lowercase() }
    fun isDup(t: LiveTemplate) = t.abbreviation.trim().let { a -> a.isNotEmpty() && lower.count { it == a.lowercase() } > 1 }

    Text(
        t(Str.SettingsLiveTemplatesHint),
        style = MaterialTheme.typography.caption,
        color = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        HeaderCell(t(Str.SettingsLiveTemplatesColAbbr), Modifier.width(110.dp))
        HeaderCell(t(Str.SettingsLiveTemplatesColBody), Modifier.weight(1f))
        HeaderCell(t(Str.SettingsLiveTemplatesColDesc), Modifier.width(130.dp))
        Spacer(Modifier.width(36.dp))
    }
    templates.forEachIndexed { index, tmpl ->
        val invalid = tmpl.abbreviation.trim().isEmpty() || tmpl.body.isEmpty() || isDup(tmpl)
        Row(verticalAlignment = Alignment.Top) {
            OutlinedTextField(
                value = tmpl.abbreviation,
                onValueChange = { v ->
                    onChange(templates.toMutableList().also { it[index] = tmpl.copy(abbreviation = v) })
                },
                modifier = Modifier.width(110.dp),
                singleLine = true,
                isError = invalid,
                textStyle = MaterialTheme.typography.body2,
                colors = settingsFieldColors(),
            )
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
                value = tmpl.body,
                onValueChange = { v ->
                    onChange(templates.toMutableList().also { it[index] = tmpl.copy(body = v) })
                },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                textStyle = MaterialTheme.typography.body2.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                ),
                colors = settingsFieldColors(),
            )
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
                value = tmpl.description ?: "",
                onValueChange = { v ->
                    onChange(templates.toMutableList().also { it[index] = tmpl.copy(description = v.ifEmpty { null }) })
                },
                modifier = Modifier.width(130.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.body2,
                colors = settingsFieldColors(),
            )
            IconButton(onClick = { onChange(templates.toMutableList().also { it.removeAt(index) }) }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
    if (templates.any { it.abbreviation.trim().isEmpty() || it.body.isEmpty() || isDup(it) }) {
        Text(t(Str.SettingsLiveTemplatesWarn), fontSize = 11.sp, color = MaterialTheme.colors.error)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { onChange(templates + LiveTemplate("", "", null)) }) {
            Text(t(Str.SettingsLiveTemplatesAdd))
        }
        TextButton(onClick = { onChange(defaultLiveTemplates()) }) {
            Text(t(Str.SettingsLiveTemplatesReset))
        }
    }
}

/** 表格表头单元。 */
@Composable
private fun HeaderCell(label: String, modifier: Modifier) {
    Text(
        label,
        modifier = modifier,
        style = MaterialTheme.typography.caption,
        color = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium),
    )
}

/** 尝试打开目录；失败则复制路径并回传提示文案（成功时清空提示）。 */
private fun openOrCopy(dir: java.nio.file.Path, label: String, setHint: (String?) -> Unit) {
    if (openDirectory(dir)) {
        setHint(null)
    } else {
        writeClipboardText(dir.toString())
        setHint(I18n.t(Str.SettingsOpenFailed, label))
    }
}
