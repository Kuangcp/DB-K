package app.settings

import db.AppPaths
import java.io.File
import java.util.Properties

/** 主窗口几何持久化（退出时保存，下次启动恢复位置与尺寸）。 */
object WindowPrefs {

    private fun prefsFile(): File = AppPaths.dataDirectory().resolve("window.properties").toFile()

    data class Geometry(val x: Int?, val y: Int?, val width: Int, val height: Int)

    fun load(): Geometry? {
        val f = prefsFile()
        if (!f.exists()) return null
        return runCatching {
            val props = Properties()
            f.inputStream().use { props.load(it) }
            val w = props.getProperty("width")?.toIntOrNull() ?: return null
            val h = props.getProperty("height")?.toIntOrNull() ?: return null
            Geometry(
                x = props.getProperty("x")?.toIntOrNull(),
                y = props.getProperty("y")?.toIntOrNull(),
                width = w,
                height = h,
            )
        }.getOrNull()
    }

    fun save(geometry: Geometry) {
        runCatching {
            val props = Properties()
            geometry.x?.let { props.setProperty("x", it.toString()) }
            geometry.y?.let { props.setProperty("y", it.toString()) }
            props.setProperty("width", geometry.width.toString())
            props.setProperty("height", geometry.height.toString())
            prefsFile().outputStream().use { props.store(it, "main window geometry") }
        }
    }
}
