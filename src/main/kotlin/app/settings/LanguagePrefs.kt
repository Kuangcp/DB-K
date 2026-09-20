package app.settings

import db.AppPaths
import i18n.Lang
import java.io.File
import java.util.Properties

/**
 * 应用级偏好持久化：`<dataDir>/app.properties`（key `language`）。
 *
 * 语言属「应用级全局偏好」（见 AGENTS.md 持久化分层），走 properties 不落 SQLite。
 * `null` = **跟随系统**（不写该 key）；后续新增 app 级标量偏好都并入本文件。
 */
object LanguagePrefs {

    private const val KEY_LANGUAGE = "language"

    private fun prefsFile(): File = AppPaths.dataDirectory().resolve("app.properties").toFile()

    /**
     * @return 存档的语言；`null` = 无存档 → 跟随系统 locale。
     * 文件不存在 / key 缺失 / 值非法都返回 null（回落跟随系统）。
     */
    fun load(): Lang? {
        val f = prefsFile()
        if (!f.exists()) return null
        return runCatching {
            val props = Properties()
            f.inputStream().use { props.load(it) }
            Lang.parse(props.getProperty(KEY_LANGUAGE))
        }.getOrNull()
    }

    /** [lang] = null 表示「跟随系统」（移除 key，保留同文件其它 key）。 */
    fun save(lang: Lang?) {
        runCatching {
            val f = prefsFile()
            val props = Properties()
            if (f.exists()) f.inputStream().use { props.load(it) }
            if (lang == null) props.remove(KEY_LANGUAGE) else props.setProperty(KEY_LANGUAGE, lang.tag)
            f.parentFile?.mkdirs()
            f.outputStream().use { props.store(it, "db-k app preferences") }
        }
    }
}
