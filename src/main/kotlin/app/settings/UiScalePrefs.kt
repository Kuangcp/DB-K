package app.settings

import db.AppPaths
import java.io.File
import java.util.Properties

/**
 * 全局界面缩放偏好（`<dataDir>/app.properties`，key `ui.scale`）。
 *
 * 与 [LanguagePrefs] 同放一个文件：每次读-改-写，互不覆盖其它 key。
 * `1.0` = 100%（不缩放）；缩放与系统已有密度是**乘算**关系。
 */
object UiScalePrefs {

    private const val KEY_UI_SCALE = "ui.scale"

    const val MIN_SCALE = 1.0f
    const val MAX_SCALE = 2.0f
    const val STEP = 0.05f
    const val DEFAULT_SCALE = 1.0f

    /** 夹到 [MIN_SCALE, MAX_SCALE]；NaN/Infinity 回落默认。 */
    fun sanitized(value: Float): Float =
        if (value.isFinite()) value.coerceIn(MIN_SCALE, MAX_SCALE) else DEFAULT_SCALE

    private fun prefsFile(): File = AppPaths.dataDirectory().resolve("app.properties").toFile()

    fun load(): Float = load(prefsFile())

    fun save(scale: Float) = save(prefsFile(), scale)

    // ---------- 纯文件实现（便于单测注入临时文件，不触碰真实数据目录） ----------

    internal fun load(f: File): Float {
        if (!f.exists()) return DEFAULT_SCALE
        return runCatching {
            val props = Properties()
            f.inputStream().use { props.load(it) }
            val raw = props.getProperty(KEY_UI_SCALE)?.toFloatOrNull()
            if (raw == null) DEFAULT_SCALE else sanitized(raw)
        }.getOrDefault(DEFAULT_SCALE)
    }

    internal fun save(f: File, scale: Float) {
        runCatching {
            val props = Properties()
            if (f.exists()) f.inputStream().use { props.load(it) }
            props.setProperty(KEY_UI_SCALE, sanitized(scale).toString())
            f.parentFile?.mkdirs()
            f.outputStream().use { props.store(it, "db-k app preferences") }
        }
    }
}
