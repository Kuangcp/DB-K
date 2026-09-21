package app.settings

import app.ui.ThemeColors
import app.ui.ThemeSpec
import app.ui.builtInThemes
import app.ui.themeById
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThemeStoreTest {

    @TempDir
    lateinit var dir: Path

    private fun store() = ThemesStore(dir.resolve("themes.json").toFile())

    private fun custom(name: String, baseId: String = "monokai", bg: String = "#101112") = ThemeSpec(
        id = "t-$name",
        name = name,
        builtIn = false,
        baseId = baseId,
        colors = builtInThemes.first { it.id == baseId }.colors.copy(background = ThemeColors.parse(bg)!!),
    )

    @Test
    fun `save then load roundtrips custom themes`() {
        val s = store()
        s.save(listOf(custom("我的主题"), custom("Second", baseId = "dracula", bg = "#1E1F22")))
        val loaded = s.load()
        assertEquals(listOf("我的主题", "Second"), loaded.map { it.name })
        assertTrue(loaded.all { !it.builtIn })
        assertEquals("#1E1F22", ThemeColors.toHex(loaded[1].colors.background))
        assertEquals("dracula", loaded[1].baseId)
        // function 项一并往返
        assertEquals(
            ThemeColors.toHex(themeById("dracula")!!.colors.function),
            ThemeColors.toHex(loaded[1].colors.function),
        )
    }

    @Test
    fun `corrupt file yields empty list`() {
        val f = dir.resolve("themes.json").toFile()
        f.writeText("{ not json ")
        assertTrue(store().load().isEmpty())
    }

    @Test
    fun `missing file yields empty list`() {
        assertTrue(store().load().isEmpty())
    }

    @Test
    fun `invalid color falls back to base theme and unknown base falls back to light`() {
        val f = dir.resolve("themes.json").toFile()
        f.writeText(
            """{"version":1,"themes":[
              {"id":"a","name":"A","baseId":"dracula","colors":{"background":"zzz"}},
              {"id":"b","name":"B","baseId":"nope","colors":{"background":"#010203"}}
            ]}""".trimIndent(),
        )
        val loaded = store().load()
        assertEquals(themeById("dracula")!!.colors.background, loaded[0].colors.background)
        assertEquals(ThemeColors.parse("#010203"), loaded[1].colors.background)
        assertEquals(themeById("light")!!.colors.keyword, loaded[1].colors.keyword)
        // 旧档缺 function 项 → 回退 baseId 主题的函数色
        assertEquals(themeById("dracula")!!.colors.function, loaded[0].colors.function)
        assertEquals(themeById("light")!!.colors.function, loaded[1].colors.function)
    }

    @Test
    fun `theme prefs migrate legacy dark flag and prefer theme key`() {
        val f = File(dir.toFile(), "theme.properties")
        f.writeText("dark=true\n")
        assertEquals("dark", ThemePrefs.readActive(f))
        f.writeText("dark=false\n")
        assertEquals("light", ThemePrefs.readActive(f))
        f.writeText("theme=monokai\n")
        assertEquals("monokai", ThemePrefs.readActive(f))
        f.writeText("dark=true\ntheme=dracula\n")
        assertEquals("dracula", ThemePrefs.readActive(f))
        f.writeText("")
        assertNull(ThemePrefs.readActive(f))
    }
}
