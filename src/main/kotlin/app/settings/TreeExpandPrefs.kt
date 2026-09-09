package app.settings

import db.AppPaths
import java.io.File
import java.util.Properties

/** 树展开状态持久化（轻量 properties 文件）。 */
object TreeExpandPrefs {

    private fun prefsFile(): File = AppPaths.dataDirectory().resolve("tree-expand.properties").toFile()

    fun load(): Set<String> {
        val f = prefsFile()
        if (!f.exists()) return emptySet()
        return runCatching {
            val props = Properties()
            f.inputStream().use { props.load(it) }
            props.getProperty("expanded")?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        }.getOrDefault(emptySet())
    }

    fun save(expandedIds: Set<String>) {
        runCatching {
            val props = Properties()
            props.setProperty("expanded", expandedIds.joinToString(","))
            prefsFile().outputStream().use { props.store(it, "tree expanded folder ids") }
        }
    }
}
