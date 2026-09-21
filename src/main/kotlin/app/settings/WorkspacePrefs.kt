package app.settings

import db.AppPaths
import java.io.File
import java.util.Properties

/**
 * 应用级偏好：当前激活的工作区。
 * 与 [LanguagePrefs] 共用 `<dataDir>/app.properties`（不同 key），符合「同类项并入已有文件」。
 */
object WorkspacePrefs {

    private const val KEY_ACTIVE = "activeWorkspace"

    private fun prefsFile(): File = AppPaths.dataDirectory().resolve("app.properties").toFile()

    fun load(): String? {
        val f = prefsFile()
        if (!f.exists()) return null
        return runCatching {
            val props = Properties()
            f.inputStream().use { props.load(it) }
            props.getProperty(KEY_ACTIVE)?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** [id] = null 移除 key（零工作区态）。 */
    fun save(id: String?) {
        runCatching {
            val f = prefsFile()
            val props = Properties()
            if (f.exists()) f.inputStream().use { props.load(it) }
            if (id == null) props.remove(KEY_ACTIVE) else props.setProperty(KEY_ACTIVE, id)
            f.parentFile?.mkdirs()
            f.outputStream().use { props.store(it, "db-k app preferences") }
        }
    }
}
