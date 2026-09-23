package app.settings

import db.AppPaths
import java.io.File
import java.util.Properties

/** 主窗口几何 + 三栏布局持久化（退出时保存，下次启动恢复位置/尺寸与分栏宽高）。 */
object WindowPrefs {

    private fun prefsFile(): File = AppPaths.dataDirectory().resolve("window.properties").toFile()

    data class Geometry(val x: Int?, val y: Int?, val width: Int, val height: Int)

    /**
     * 三栏布局：左树宽度（dp）+ 编辑/结果分隔比例（结果区占内容区高度）。
     * 左树显隐**不持久化**（每次启动都显示树）。
     */
    data class Layout(
        val treeWidthDp: Float = DEFAULT_TREE_WIDTH_DP,
        val resultFrac: Float = DEFAULT_RESULT_FRAC,
    )

    const val DEFAULT_TREE_WIDTH_DP = 280f
    const val DEFAULT_RESULT_FRAC = 0.5f

    fun load(): Geometry? = loadGeometry(prefsFile())

    fun loadLayout(): Layout = loadLayout(prefsFile())

    /**
     * 一次写完几何 + 布局（避免两次 store 互相覆盖）。
     * [geometry] 为 null 时保留文件中已有的几何（如最大化/全屏退出时尺寸不是用户的恢复尺寸）。
     */
    fun save(geometry: Geometry?, layout: Layout) = save(prefsFile(), geometry, layout)

    // ---------- 纯文件实现（便于单测注入临时文件，不触碰真实数据目录） ----------

    internal fun loadGeometry(f: File): Geometry? {
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

    internal fun loadLayout(f: File): Layout {
        if (!f.exists()) return Layout()
        return runCatching {
            val props = Properties()
            f.inputStream().use { props.load(it) }
            Layout(
                treeWidthDp = props.getProperty("treeWidth")?.toFloatOrNull() ?: DEFAULT_TREE_WIDTH_DP,
                resultFrac = props.getProperty("resultFrac")?.toFloatOrNull() ?: DEFAULT_RESULT_FRAC,
            )
        }.getOrDefault(Layout())
    }

    internal fun save(f: File, geometry: Geometry?, layout: Layout) {
        runCatching {
            val props = Properties()
            if (f.exists()) f.inputStream().use { props.load(it) }
            if (geometry != null) {
                if (geometry.x != null && geometry.y != null) {
                    props.setProperty("x", geometry.x.toString())
                    props.setProperty("y", geometry.y.toString())
                } else {
                    props.remove("x")
                    props.remove("y")
                }
                props.setProperty("width", geometry.width.toString())
                props.setProperty("height", geometry.height.toString())
            }
            props.setProperty("treeWidth", layout.treeWidthDp.toString())
            props.setProperty("resultFrac", layout.resultFrac.toString())
            f.outputStream().use { props.store(it, "main window geometry + layout") }
        }
    }
}
