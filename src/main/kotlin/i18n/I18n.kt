package i18n

/**
 * 国际化取词入口（纯叶层）。
 *
 * - **可组合 / UI**：用 `app/i18n` 的可组合 `t(...)`（读 `LocalLang`，语言切换触发重组）。
 * - **非 UI（jdbc/engine/tree/db…）**：用 `I18n.t(key, args…)`，读进程级 [lang]；
 *   语言在 `Main` 切换时同步写入 [lang]。
 *
 * 占位符只识别 `{0}`、`{1}`（只替换 `{数字}`，不会误伤 SQL/JSON 的花括号）；复数用
 * `XxxOne` / `XxxOther` 两 key + [tn]。
 */
object I18n {

    /** 进程级当前语言；由 `Main` 在启动与切换时写入。 */
    @Volatile
    var lang: Lang = Lang.ZH

    /** 非 UI 入口：用当前 [lang]。 */
    fun t(key: Str, vararg args: Any?): String = t(lang, key, *args)

    /** 显式指定语言（Composable 用 `LocalLang.current`，避免与全局状态竞态）。 */
    fun t(lang: Lang, key: Str, vararg args: Any?): String = substitute(render(lang, key), args)

    /** 复数：`n == 1` 取 [one]，否则取 [other]（中文两 key 通常同文）。 */
    fun tn(one: Str, other: Str, n: Int): String = tn(lang, one, other, n)

    fun tn(lang: Lang, one: Str, other: Str, n: Int): String =
        render(lang, if (n == 1) one else other)

    private fun render(lang: Lang, key: Str): String = when (lang) {
        Lang.ZH -> zh(key)
        Lang.EN -> en(key)
    }

    private val PLACEHOLDER = Regex("""\{(\d+)\}""")

    private fun substitute(template: String, args: Array<out Any?>): String =
        PLACEHOLDER.replace(template) { m ->
            val i = m.groupValues[1].toInt()
            args.getOrNull(i)?.toString() ?: m.value
        }
}
