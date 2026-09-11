package app.settings

import db.AppPaths
import java.io.File
import java.util.Properties

/** 编辑器外观设置（字体族 / 字号），由设置窗口「通用设置」编辑。 */
data class EditorSettings(
    /** 系统字体名；空 = 默认等宽字体。 */
    val fontFamilyName: String = "",
    /** 编辑器字号（sp）。 */
    val fontSizeSp: Float = DEFAULT_FONT_SP,
) {
    companion object {
        const val MIN_FONT_SP = 8f
        const val MAX_FONT_SP = 24f
        const val DEFAULT_FONT_SP = 13f

        /** 字号与行高比例（与历史固定值 13sp/20sp 一致，字号变化时行高等比缩放）。 */
        const val LINE_HEIGHT_RATIO = 20f / 13f

        /** 规范化：字体名去首尾空白、字号夹到允许范围。 */
        fun sanitized(fontFamilyName: String, fontSizeSp: Float): EditorSettings = EditorSettings(
            fontFamilyName = fontFamilyName.trim(),
            fontSizeSp = fontSizeSp.coerceIn(MIN_FONT_SP, MAX_FONT_SP),
        )

        /** 行高（sp）：跟随字号等比缩放，保证行号槽与文本排版一致。 */
        fun lineHeightSp(fontSizeSp: Float): Float = fontSizeSp * LINE_HEIGHT_RATIO
    }
}

/** 编辑器设置持久化（<dataDir>/editor.properties）。 */
object EditorPrefs {

    private const val KEY_FONT_FAMILY = "editor.fontFamily"
    private const val KEY_FONT_SIZE = "editor.fontSizeSp"

    private fun prefsFile(): File = AppPaths.dataDirectory().resolve("editor.properties").toFile()

    fun load(): EditorSettings {
        val f = prefsFile()
        if (!f.exists()) return EditorSettings()
        return runCatching {
            val props = Properties()
            f.inputStream().use { props.load(it) }
            EditorSettings.sanitized(
                fontFamilyName = props.getProperty(KEY_FONT_FAMILY, ""),
                fontSizeSp = props.getProperty(KEY_FONT_SIZE)?.toFloatOrNull() ?: EditorSettings.DEFAULT_FONT_SP,
            )
        }.getOrElse { EditorSettings() }
    }

    fun save(settings: EditorSettings) {
        runCatching {
            val props = Properties()
            props.setProperty(KEY_FONT_FAMILY, settings.fontFamilyName)
            props.setProperty(KEY_FONT_SIZE, settings.fontSizeSp.toString())
            prefsFile().outputStream().use { props.store(it, "editor appearance") }
        }
    }
}
