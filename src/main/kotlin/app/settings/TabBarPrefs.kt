package app.settings

import db.AppPaths
import java.io.File
import java.util.Properties

/**
 * 控制台标签布局偏好：多行换行 vs 单行横向滚动（`<dataDir>/app.properties`，key `tabbar.multiRow`）。
 *
 * 与 [LanguagePrefs] / [UiScalePrefs] / [WorkspacePrefs] 同放一个文件：每次读-改-写，互不覆盖其它 key。
 * 默认 `false` = 单行（维持历史行为，激活 tab 自动滚入可见范围）。
 */
object TabBarPrefs {

    private const val KEY_MULTI_ROW = "tabbar.multiRow"

    /** 缺省：单行。 */
    const val DEFAULT_MULTI_ROW = false

    private fun prefsFile(): File = AppPaths.dataDirectory().resolve("app.properties").toFile()

    fun load(): Boolean = load(prefsFile())

    fun save(multiRow: Boolean) = save(prefsFile(), multiRow)

    // ---------- 纯文件实现（便于单测注入临时文件，不触碰真实数据目录） ----------

    internal fun load(f: File): Boolean {
        if (!f.exists()) return DEFAULT_MULTI_ROW
        return runCatching {
            val props = Properties()
            f.inputStream().use { props.load(it) }
            props.getProperty(KEY_MULTI_ROW)?.trim()?.toBooleanStrictOrNull() ?: DEFAULT_MULTI_ROW
        }.getOrDefault(DEFAULT_MULTI_ROW)
    }

    internal fun save(f: File, multiRow: Boolean) {
        runCatching {
            val props = Properties()
            if (f.exists()) f.inputStream().use { props.load(it) }
            props.setProperty(KEY_MULTI_ROW, multiRow.toString())
            f.parentFile?.mkdirs()
            f.outputStream().use { props.store(it, "db-k app preferences") }
        }
    }
}
