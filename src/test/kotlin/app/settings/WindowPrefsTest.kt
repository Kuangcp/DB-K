package app.settings

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 窗口几何 + 三栏布局的读写往返（纯文件，不触碰真实数据目录）。 */
class WindowPrefsTest {

    @TempDir
    lateinit var dir: Path

    private val file get() = dir.resolve("window.properties").toFile()

    @Test
    fun `missing file yields null geometry and default layout`() {
        assertNull(WindowPrefs.loadGeometry(file))
        assertEquals(
            WindowPrefs.Layout(280f, 0.5f),
            WindowPrefs.loadLayout(file),
        )
    }

    @Test
    fun `saves and loads geometry and layout together`() {
        WindowPrefs.save(
            file,
            WindowPrefs.Geometry(x = 10, y = 20, width = 1180, height = 760),
            WindowPrefs.Layout(treeWidthDp = 360f, resultFrac = 0.62f),
        )
        assertEquals(WindowPrefs.Geometry(10, 20, 1180, 760), WindowPrefs.loadGeometry(file))
        assertEquals(WindowPrefs.Layout(360f, 0.62f), WindowPrefs.loadLayout(file))
    }

    @Test
    fun `null geometry keeps previously saved geometry but updates layout`() {
        WindowPrefs.save(
            file,
            WindowPrefs.Geometry(x = 1, y = 2, width = 900, height = 600),
            WindowPrefs.Layout(300f, 0.4f),
        )
        // 最大化/全屏退出：只更新布局，几何保留
        WindowPrefs.save(file, geometry = null, WindowPrefs.Layout(240f, 0.75f))

        assertEquals(WindowPrefs.Geometry(1, 2, 900, 600), WindowPrefs.loadGeometry(file))
        assertEquals(WindowPrefs.Layout(240f, 0.75f), WindowPrefs.loadLayout(file))
    }

    @Test
    fun `non-absolute position drops stale x y`() {
        WindowPrefs.save(
            file,
            WindowPrefs.Geometry(x = 5, y = 6, width = 900, height = 600),
            WindowPrefs.Layout(),
        )
        WindowPrefs.save(
            file,
            WindowPrefs.Geometry(x = null, y = null, width = 1000, height = 700),
            WindowPrefs.Layout(),
        )
        val geo = WindowPrefs.loadGeometry(file)!!
        assertNull(geo.x)
        assertNull(geo.y)
        assertEquals(1000, geo.width)
        assertEquals(700, geo.height)
    }
}
