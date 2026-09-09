package app.settings

import db.AppPaths
import java.io.File
import java.util.Properties

/** 主题偏好持久化（深浅色切换后保存，下次启动恢复）。 */
object ThemePrefs {

    private fun prefsFile(): File = AppPaths.dataDirectory().resolve("theme.properties").toFile()

    /** @return null = 无存档（用默认浅色）。 */
    fun load(): Boolean? {
        val f = prefsFile()
        if (!f.exists()) return null
        return runCatching {
            val props = Properties()
            f.inputStream().use { props.load(it) }
            props.getProperty("dark")?.toBooleanStrictOrNull()
        }.getOrNull()
    }

    fun save(dark: Boolean) {
        runCatching {
            val props = Properties()
            props.setProperty("dark", dark.toString())
            prefsFile().outputStream().use { props.store(it, "theme preference (dark mode)") }
        }
    }
}
