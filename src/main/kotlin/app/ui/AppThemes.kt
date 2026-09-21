package app.ui

import androidx.compose.material.Colors
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import i18n.Str
import kotlin.math.roundToInt

/**
 * 一个主题的 11 个语义色（界面 4 + 编辑区 2 + 语法 5）。
 * 行号/选区/当前行/边框/光标等派生色不在此，由这些按透明度导出（见 EditorPane）。
 */
data class ThemeColors(
    val background: Color,
    val surface: Color,
    val onSurface: Color,
    val primary: Color,
    val editorBackground: Color,
    val editorForeground: Color,
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val punctuation: Color,
) {
    /** 背景亮度决定明暗：驱动 Material isLight 与语法色板。 */
    val isLight: Boolean get() = background.luminance() > 0.5f

    /** 映射到 Material `Colors`（界面代码只依赖它）。 */
    fun toMaterialColors(): Colors {
        val onPrimary = if (primary.luminance() > 0.5f) Color.Black else Color.White
        return if (isLight) {
            lightColors(
                primary = primary,
                primaryVariant = primary,
                secondary = primary,
                secondaryVariant = primary,
                background = background,
                surface = surface,
                error = Color(0xFFB00020),
                onPrimary = onPrimary,
                onSecondary = onPrimary,
                onBackground = onSurface,
                onSurface = onSurface,
                onError = Color.White,
            )
        } else {
            darkColors(
                primary = primary,
                primaryVariant = primary,
                secondary = primary,
                secondaryVariant = primary,
                background = background,
                surface = surface,
                error = Color(0xFFCF6679),
                onPrimary = onPrimary,
                onSecondary = onPrimary,
                onBackground = onSurface,
                onSurface = onSurface,
                onError = Color.Black,
            )
        }
    }

    companion object {
        /** 解析 `#RRGGBB` / `RRGGBB` / `#RGB`；非法返回 null。 */
        fun parse(hex: String): Color? {
            val raw = hex.trim().removePrefix("#")
            val full = when (raw.length) {
                3 -> raw.map { "$it$it" }.joinToString("")
                6 -> raw
                else -> return null
            }
            val value = full.toLongOrNull(16) ?: return null
            return Color(0xFF000000L or value)
        }

        /** `Color` → `#RRGGBB`（丢 alpha）。 */
        fun toHex(c: Color): String {
            val r = (c.red * 255f).roundToInt().coerceIn(0, 255)
            val g = (c.green * 255f).roundToInt().coerceIn(0, 255)
            val b = (c.blue * 255f).roundToInt().coerceIn(0, 255)
            return "#%02X%02X%02X".format(r, g, b)
        }
    }
}

/**
 * 主题 = 一整套色板。内置主题只读；自定义主题由内置克隆而来（[baseId] 记录来源）。
 * [nameKey] 非空 = 名字走 i18n（Dark/Light）；否则用 [name]（专有名/用户数据）。
 */
data class ThemeSpec(
    val id: String,
    val nameKey: Str? = null,
    val name: String = "",
    val builtIn: Boolean,
    val baseId: String? = null,
    val colors: ThemeColors,
)

private val DarkTheme = ThemeSpec(
    id = "dark", nameKey = Str.ThemeNameDark, name = "Dark", builtIn = true,
    colors = ThemeColors(
        background = Color(0xFF292B2E), surface = Color(0xFF32353B),
        onSurface = Color(0xFFFFFFFF), primary = Color(0xFF90CAF9),
        editorBackground = Color(0xFF32353B), editorForeground = Color(0xFFFFFFFF),
        keyword = Color(0xFF569CD6), string = Color(0xFFCE9178), number = Color(0xFFB5CEA8),
        comment = Color(0xFF6A9955), punctuation = Color(0xFFD4D4D4),
    ),
)

private val LightTheme = ThemeSpec(
    id = "light", nameKey = Str.ThemeNameLight, name = "Light", builtIn = true,
    colors = ThemeColors(
        background = Color(0xFFEBECF0), surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1F2328), primary = Color(0xFF1976D2),
        editorBackground = Color(0xFFFFFFFF), editorForeground = Color(0xFF1F2328),
        keyword = Color(0xFF0000FF), string = Color(0xFFA31515), number = Color(0xFF098658),
        comment = Color(0xFF008000), punctuation = Color(0xFF242424),
    ),
)

private val MonokaiTheme = ThemeSpec(
    id = "monokai", name = "Monokai", builtIn = true,
    colors = ThemeColors(
        background = Color(0xFF272822), surface = Color(0xFF2E2F27),
        onSurface = Color(0xFFF8F8F2), primary = Color(0xFFA6E22E),
        editorBackground = Color(0xFF272822), editorForeground = Color(0xFFF8F8F2),
        keyword = Color(0xFFF92672), string = Color(0xFFE6DB74), number = Color(0xFFAE81FF),
        comment = Color(0xFF75715E), punctuation = Color(0xFFF8F8F2),
    ),
)

private val DraculaTheme = ThemeSpec(
    id = "dracula", name = "Dracula", builtIn = true,
    colors = ThemeColors(
        background = Color(0xFF282A36), surface = Color(0xFF343746),
        onSurface = Color(0xFFF8F8F2), primary = Color(0xFFBD93F9),
        editorBackground = Color(0xFF282A36), editorForeground = Color(0xFFF8F8F2),
        keyword = Color(0xFFFF79C6), string = Color(0xFFF1FA8C), number = Color(0xFFBD93F9),
        comment = Color(0xFF6272A4), punctuation = Color(0xFFF8F8F2),
    ),
)

private val SublimeTheme = ThemeSpec(
    id = "sublime", name = "Sublime", builtIn = true,
    colors = ThemeColors(
        background = Color(0xFF343D46), surface = Color(0xFF3E4750),
        onSurface = Color(0xFFD8DEE9), primary = Color(0xFF6699CC),
        editorBackground = Color(0xFF343D46), editorForeground = Color(0xFFD8DEE9),
        keyword = Color(0xFFC594C5), string = Color(0xFF99C794), number = Color(0xFFF99157),
        comment = Color(0xFF65737E), punctuation = Color(0xFFD8DEE9),
    ),
)

val builtInThemes: List<ThemeSpec> = listOf(DarkTheme, LightTheme, MonokaiTheme, DraculaTheme, SublimeTheme)

const val defaultThemeId: String = "light"

fun themeById(id: String): ThemeSpec? = builtInThemes.firstOrNull { it.id == id }

/** 合并内置与自定义主题并去重（防御自定义列表混入内置/重复项，避免下拉里出现两份内置）。 */
fun mergeThemes(customThemes: List<ThemeSpec>): List<ThemeSpec> {
    val seen = HashSet<String>()
    return (builtInThemes + customThemes).filter { seen.add(it.id) }
}

/** 当前主题色板；默认 Light（未被 Main 覆盖时也能安全取用）。 */
val LocalThemeColors = staticCompositionLocalOf { LightTheme.colors }
