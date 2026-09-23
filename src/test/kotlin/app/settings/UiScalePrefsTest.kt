package app.settings

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

/** 全局界面缩放偏好的读写往返（纯文件，不触碰真实数据目录）。 */
class UiScalePrefsTest {

    @TempDir
    lateinit var dir: Path

    private val file get() = dir.resolve("app.properties").toFile()

    private fun readProps(): Properties =
        Properties().apply { file.inputStream().use { load(it) } }

    @Test
    fun `missing file yields default 100 percent`() {
        assertEquals(1.0f, UiScalePrefs.load(file))
    }

    @Test
    fun `saves and loads round trip`() {
        UiScalePrefs.save(file, 1.5f)
        assertEquals(1.5f, UiScalePrefs.load(file))
    }

    @Test
    fun `out of range is clamped on save`() {
        UiScalePrefs.save(file, 5.0f)
        assertEquals(UiScalePrefs.MAX_SCALE, UiScalePrefs.load(file))
        UiScalePrefs.save(file, 0.1f)
        assertEquals(UiScalePrefs.MIN_SCALE, UiScalePrefs.load(file))
    }

    @Test
    fun `non finite falls back to default`() {
        assertEquals(UiScalePrefs.DEFAULT_SCALE, UiScalePrefs.sanitized(Float.NaN))
        assertEquals(UiScalePrefs.DEFAULT_SCALE, UiScalePrefs.sanitized(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `invalid stored value falls back to default`() {
        file.writeText("ui.scale=abc\n")
        assertEquals(UiScalePrefs.DEFAULT_SCALE, UiScalePrefs.load(file))
    }

    @Test
    fun `saving scale preserves existing language key`() {
        file.parentFile?.mkdirs()
        file.writeText("language=en\n")
        UiScalePrefs.save(file, 1.25f)
        val props = readProps()
        assertEquals("en", props.getProperty("language"))
        assertEquals("1.25", props.getProperty("ui.scale"))
    }

    @Test
    fun `language written after scale does not clobber ui scale`() {
        // 模拟 LanguagePrefs.save 的读-改-写：先 load 再补 language key 后 store
        UiScalePrefs.save(file, 1.5f)
        val props = readProps().apply { setProperty("language", "zh") }
        file.outputStream().use { props.store(it, "db-k app preferences") }
        assertEquals(1.5f, UiScalePrefs.load(file))
    }
}
