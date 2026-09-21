package app.ui

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.SystemFont

/**
 * 由设置里的系统字体名解析编辑器字体族：空名/解析失败均回退等宽默认。
 * 用 [SystemFont] 按系统名解析（Compose Desktop 特性），无需内置字体文件。
 */
@OptIn(ExperimentalTextApi::class)
fun editorFontFamily(name: String): FontFamily {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return FontFamily.Monospace
    return runCatching { FontFamily(SystemFont(trimmed)) }.getOrElse { FontFamily.Monospace }
}
