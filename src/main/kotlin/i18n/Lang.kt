package i18n

import java.util.Locale

/**
 * 界面语言（纯叶层，只依赖 JDK；见 `doc/I18N.md`）。
 *
 * [tag] 为持久化用的短标签（写进 `<dataDir>/app.properties` 的 `language`）。
 */
enum class Lang(val tag: String) {
    ZH("zh"),
    EN("en"),
    ;

    companion object {
        /** 严格解析：只认 zh/ en 前缀，未知/空返回 null（用于「跟随系统」= 无存档）。 */
        fun parse(tag: String?): Lang? {
            val t = tag?.trim()?.lowercase().orEmpty()
            return when {
                t.startsWith("zh") -> ZH
                t.startsWith("en") -> EN
                else -> null
            }
        }

        /** 宽松解析：未知回落到 [EN]（默认外语）。 */
        fun fromTag(tag: String?): Lang = parse(tag) ?: EN

        /** 系统 locale：zh 系 → [ZH]，其余 → [EN]。 */
        fun system(): Lang = if (Locale.getDefault().language.lowercase().startsWith("zh")) ZH else EN
    }
}
