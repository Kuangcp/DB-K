package app.settings

import app.ui.LiveTemplate
import app.ui.defaultLiveTemplates
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveTemplatesStoreTest {

    @TempDir
    lateinit var dir: Path

    private fun file() = dir.resolve("live-templates.json").toFile()

    @Test
    fun `missing file yields builtins`() {
        assertEquals(defaultLiveTemplates(), LiveTemplatesStore.load(file()))
    }

    @Test
    fun `existing file is the source of truth and deletion of builtins persists`() {
        file().writeText(
            """{"fileVersion":1,"templates":[{"abbreviation":"SEL","body":"SELECT 1","description":"mine"}]}""",
        )
        val loaded = LiveTemplatesStore.load(file())
        assertEquals(listOf("SEL"), loaded.map { it.abbreviation })
        assertEquals("SELECT 1", loaded[0].body)
        assertEquals("mine", loaded[0].description)
    }

    @Test
    fun `corrupt json falls back to builtins`() {
        file().writeText("{ not json")
        assertEquals(defaultLiveTemplates(), LiveTemplatesStore.load(file()))
    }

    @Test
    fun `blank fields and unknown context are skipped`() {
        file().writeText(
            """{"templates":[
              {"abbreviation":"","body":"SELECT 1"},
              {"abbreviation":"a","body":""},
              {"abbreviation":"b","body":"SELECT 1","context":"redis"}
            ]}""",
        )
        assertEquals(defaultLiveTemplates(), LiveTemplatesStore.load(file()))
    }

    @Test
    fun `createSample seeds the full default list once`() {
        val f = file()
        LiveTemplatesStore.createSample(f)
        assertTrue(f.exists())
        val loaded = LiveTemplatesStore.load(f)
        assertEquals(defaultLiveTemplates().map { it.abbreviation }, loaded.map { it.abbreviation })
        // 已存在时不覆盖
        LiveTemplatesStore.createSample(f)
        assertEquals(loaded, LiveTemplatesStore.load(f))
    }

    @Test
    fun `save then load roundtrips the full list`() {
        val f = file()
        val full = defaultLiveTemplates() + LiveTemplate("zzz", "SELECT 1")
        LiveTemplatesStore.save(f, full)
        assertEquals(full.map { it.abbreviation }, LiveTemplatesStore.load(f).map { it.abbreviation })
    }

    @Test
    fun `save sanitizes blank bodies and duplicate abbreviations`() {
        val f = file()
        LiveTemplatesStore.save(
            f,
            listOf(
                LiveTemplate("", "SELECT 1"),
                LiveTemplate("a", ""),
                LiveTemplate("A", "SELECT 2"),
                LiveTemplate("a", "SELECT 3"),
            ),
        )
        val loaded = LiveTemplatesStore.load(f)
        assertTrue(loaded.any { it.abbreviation == "A" && it.body == "SELECT 2" })
        assertEquals(1, loaded.count { it.abbreviation.equals("a", ignoreCase = true) })
    }
}
