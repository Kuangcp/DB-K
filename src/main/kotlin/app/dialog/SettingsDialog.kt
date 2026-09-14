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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ContentAlpha
import androidx.compose.material.Divider
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Slider
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextFieldColors
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import app.build.Version
import app.core.openDirectory
import app.core.writeClipboardText
import app.settings.EditorSettings
import app.settings.Keymap
import app.ui.appMaterialColors
import app.ui.editorFontFamily
import db.AppPaths

/** 设置窗口的完整快照：编辑器外观 + 快捷键。保存时一并落盘。 */
data class SettingsSnapshot(val editor: EditorSettings, val keymap: Keymap)

/**
 * 设置窗口（独立 [DialogWindow]，与主窗口同款主题）。
 * 左侧分区导航 + 右侧内容；左下角展示版本号。当前有「通用设置」「快捷键」两个分区。
 */
@Composable
fun SettingsDialog(
    visible: Boolean,
    isDark: Boolean,
    initial: SettingsSnapshot,
    onDismiss: () -> Unit,
    onSave: (SettingsSnapshot) -> Unit,
) {
    if (!visible) return
    DialogWindow(
        onCloseRequest = onDismiss,
        title = "设置",
        state = rememberDialogState(width = 760.dp, height = 520.dp),
    ) {
        MaterialTheme(colors = appMaterialColors(isDark)) {
            // 独立窗口是另一棵 composition：M2 MaterialTheme 不设 LocalContentColor，需同样兜底
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colors.onSurface) {
                SettingsBody(initial = initial, onCancel = onDismiss, onSave = onSave)
            }
        }
    }
}

@Composable
private fun SettingsBody(
    initial: SettingsSnapshot,
    onCancel: () -> Unit,
    onSave: (SettingsSnapshot) -> Unit,
) {
    var section by remember { mutableIntStateOf(0) }
    var fontFamily by remember { mutableStateOf(initial.editor.fontFamilyName) }
    var fontSize by remember { mutableFloatStateOf(initial.editor.fontSizeSp) }
    var keymap by remember { mutableStateOf(initial.keymap) }

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
                SettingsNavRow(label = "通用设置", selected = section == 0) { section = 0 }
                SettingsNavRow(label = "快捷键", selected = section == 1) { section = 1 }
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
                        )
                        DiagnosticsSection()
                    }
                    1 -> ShortcutSettingsSection(
                        keymap = keymap,
                        onKeymapChange = { keymap = it },
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
                text = "版本：v${Version.NAME}-${Version.COMMIT}",
                fontSize = 11.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium),
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("取消") }
            TextButton(onClick = { onSave(SettingsSnapshot(EditorSettings.sanitized(fontFamily, fontSize), keymap)) }) {
                Text("保存")
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
) {
    Text(
        "编辑器外观",
        style = MaterialTheme.typography.subtitle2,
        color = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium),
    )
    OutlinedTextField(
        value = fontFamily,
        onValueChange = onFontFamilyChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("编辑器字体") },
        placeholder = { Text("留空 = 默认等宽字体；如 JetBrains Mono、Fira Code") },
        singleLine = true,
        colors = settingsFieldColors(),
    )
    Text(
        "编辑器字号（sp）：${"%.0f".format(fontSize)}",
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
        "预览",
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
        "诊断",
        style = MaterialTheme.typography.subtitle2,
        color = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium),
    )
    Text(
        "日志目录",
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
        TextButton(onClick = { openOrCopy(logDir, "日志目录") { hint = it } }) { Text("打开日志目录") }
        TextButton(onClick = { openOrCopy(dataDir, "数据目录") { hint = it } }) { Text("打开数据目录") }
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

/** 尝试打开目录；失败则复制路径并回传提示文案（成功时清空提示）。 */
private fun openOrCopy(dir: java.nio.file.Path, label: String, setHint: (String?) -> Unit) {
    if (openDirectory(dir)) {
        setHint(null)
    } else {
        writeClipboardText(dir.toString())
        setHint("当前环境无法自动打开$label，已复制路径到剪贴板")
    }
}
