package app.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import i18n.I18n
import i18n.Lang
import i18n.Str

/**
 * 当前语言的 CompositionLocal（UI 层）。
 *
 * 主窗口与设置窗口是**两棵独立 composition**（`DialogWindow`），各自都要 [ProvideI18n] 包裹，
 * 不能只靠外层 MaterialTheme。可组合 [t] 读本值 → 语言切换自动重组。
 */
val LocalLang = staticCompositionLocalOf { Lang.ZH }

/** 在子树内提供语言。语言变化 → 所有读 [LocalLang] 的组件重组。 */
@Composable
fun ProvideI18n(lang: Lang, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLang provides lang, content = content)
}

/** Composable 取词：读 [LocalLang]（即时切换），委托 [I18n.t] 做替换。 */
@Composable
fun t(key: Str, vararg args: Any?): String = I18n.t(LocalLang.current, key, *args)

/** Composable 复数取词（`n == 1` → [one]，否则 [other]）。 */
@Composable
fun tn(one: Str, other: Str, n: Int): String = I18n.tn(LocalLang.current, one, other, n)
