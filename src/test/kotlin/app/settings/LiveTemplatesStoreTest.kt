package app.settings

import app.ui.LiveTemplate
import app.ui.LiveTemplateContext
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
    fun `user entry overrides builtin by abbreviation`() {
        file().writeText(
            """{"fileVersion":1,"templates":[{"abbreviation":"SEL","body":"SELECT 1","description":"mine"}]}""",
        )
        val loaded = LiveTemplatesStore.load(file())
        val sel = loaded.first { it.abbreviation.equals("sel", ignoreCase = true) }
        assertEquals("SELECT 1", sel.body)
        assertEquals("mine", sel.description)
        // 数量不变（同名覆盖，不追加）
        assertEquals(defaultLiveTemplates().size, loaded.size)
    }

    @Test
    fun `user entry appends unknown abbreviation`() {
        file().writeText("""{"templates":[{"abbreviation":"zzz","body":"SELECT ${'$'}x${'$'}"}]}""")
        val loaded = LiveTemplatesStore.load(file())
        assertTrue(loaded.any { it.abbreviation == "zzz" })
        assertEquals(defaultLiveTemplates().size + 1, loaded.size)
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
    fun `createSample writes a loadable file once`() {
        val f = file()
        LiveTemplatesStore.createSample(f)
        assertTrue(f.exists())
        val loaded = LiveTemplatesStore.load(f)
        assertTrue(loaded.any { it.abbreviation == "sel2" })
        // 已存在时不覆盖
        LiveTemplatesStore.createSample(f)
        assertEquals(loaded, LiveTemplatesStore.load(f))
    }

    @Test
    fun `overlay keeps builtin order and appends user entries`() {
        val out = overlayTemplates(
            listOf(LiveTemplate("a", "A"), LiveTemplate("b", "B")),
            listOf(LiveTemplate("b", "B2", context = LiveTemplateContext.SQL), LiveTemplate("c", "C")),
        )
        assertEquals(listOf("a", "b", "c"), out.map { it.abbreviation })
        assertEquals("B2", out[1].body)
    }
}
