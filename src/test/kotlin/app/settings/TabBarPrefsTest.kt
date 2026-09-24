package app.settings

import java.nio.file.Path
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** 控制台标签多行偏好的读写往返（纯文件，不触碰真实数据目录）。 */
class TabBarPrefsTest {

    @TempDir
    lateinit var dir: Path

    private val file get() = dir.resolve("app.properties").toFile()

    private fun readProps(): Properties =
        Properties().apply { file.inputStream().use { load(it) } }

    @Test
    fun `missing file yields single row default`() {
        assertFalse(TabBarPrefs.load(file))
    }

    @Test
    fun `saves and loads round trip`() {
        TabBarPrefs.save(file, true)
        assertTrue(TabBarPrefs.load(file))
        TabBarPrefs.save(file, false)
        assertFalse(TabBarPrefs.load(file))
    }

    @Test
    fun `invalid stored value falls back to single row`() {
        file.writeText("tabbar.multiRow=maybe\n")
        assertFalse(TabBarPrefs.load(file))
    }

    @Test
    fun `saving multi row preserves other app keys`() {
        file.parentFile?.mkdirs()
        file.writeText("language=en\nui.scale=1.25\n")
        TabBarPrefs.save(file, true)
        val props = readProps()
        assertEquals("en", props.getProperty("language"))
        assertEquals("1.25", props.getProperty("ui.scale"))
        assertEquals("true", props.getProperty("tabbar.multiRow"))
    }

    @Test
    fun `other keys written after preference do not clobber it`() {
        TabBarPrefs.save(file, true)
        val props = readProps().apply { setProperty("language", "zh") }
        file.outputStream().use { props.store(it, "db-k app preferences") }
        assertTrue(TabBarPrefs.load(file))
    }
}
