package app.settings

import db.AppPaths
import java.io.File
import java.util.Properties

/** 主题偏好：当前主题 id（`<dataDir>/theme.properties`，key `theme`）。 */
object ThemePrefs {

    private fun prefsFile(): File = AppPaths.dataDirectory().resolve("theme.properties").toFile()

    fun load(): String? = readActive(prefsFile())

    fun save(id: String) {
        runCatching {
            val props = Properties()
            props.setProperty("theme", id)
            prefsFile().parentFile?.mkdirs()
            prefsFile().outputStream().use { props.store(it, "theme preference (active theme id)") }
        }
    }

    /** 纯读取 + 老档迁移：`theme` 优先；否则 `dark=true|false` → `dark`/`light`；都没有 → null。 */
    internal fun readActive(f: File): String? {
        if (!f.exists()) return null
        return runCatching {
            val props = Properties()
            f.inputStream().use { props.load(it) }
            props.getProperty("theme")?.takeIf { it.isNotBlank() }
                ?: props.getProperty("dark")?.toBooleanStrictOrNull()?.let { if (it) "dark" else "light" }
        }.getOrNull()
    }
}
