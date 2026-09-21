package app.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppThemesTest {

    @Test
    fun `registry has five builtins with unique ids and default`() {
        assertEquals(5, builtInThemes.size)
        assertEquals(5, builtInThemes.map { it.id }.toSet().size)
        assertNotNull(themeById(defaultThemeId))
        assertTrue(builtInThemes.all { it.builtIn })
        assertEquals(listOf("dark", "light", "monokai", "dracula", "sublime"), builtInThemes.map { it.id })
        assertEquals("ThemeNameLight", themeById("light")!!.nameKey!!.name)
        assertNull(themeById("monokai")!!.nameKey)
        assertEquals("Monokai", themeById("monokai")!!.name)
    }

    @Test
    fun `dark and light keep existing values`() {
        val dark = themeById("dark")!!.colors
        assertEquals(Color(0xFF292B2E), dark.background)
        assertEquals(Color(0xFF32353B), dark.surface)
        assertEquals(Color(0xFFFFFFFF), dark.onSurface)
        assertEquals(Color(0xFF90CAF9), dark.primary)
        assertEquals(Color(0xFF569CD6), dark.keyword)
        assertEquals(Color(0xFFCE9178), dark.string)
        assertEquals(Color(0xFFB5CEA8), dark.number)
        assertEquals(Color(0xFF6A9955), dark.comment)
        assertEquals(Color(0xFFD4D4D4), dark.punctuation)

        val light = themeById("light")!!.colors
        assertEquals(Color(0xFFEBECF0), light.background)
        assertEquals(Color(0xFFFFFFFF), light.surface)
        assertEquals(Color(0xFF1F2328), light.onSurface)
        assertEquals(Color(0xFF1976D2), light.primary)
    }

    @Test
    fun `isLight derives from background luminance`() {
        assertTrue(themeById("light")!!.colors.isLight)
        assertFalse(themeById("dark")!!.colors.isLight)
        assertFalse(themeById("monokai")!!.colors.isLight)
        assertFalse(themeById("dracula")!!.colors.isLight)
        assertFalse(themeById("sublime")!!.colors.isLight)
    }

    @Test
    fun `toMaterialColors maps roles and error per lightness`() {
        val dark = themeById("dark")!!.colors.toMaterialColors()
        assertFalse(dark.isLight)
        assertEquals(Color(0xFFFFFFFF), dark.onSurface)
        assertEquals(Color(0xFFCF6679), dark.error)
        assertEquals(themeById("dark")!!.colors.primary, dark.primary)

        val light = themeById("light")!!.colors.toMaterialColors()
        assertTrue(light.isLight)
        assertEquals(Color(0xFF1F2328), light.onSurface)
        assertEquals(Color(0xFFB00020), light.error)
    }

    @Test
    fun `hex parse accepts forms and roundtrips`() {
        assertEquals(Color(0xFF1E1F22), ThemeColors.parse("#1e1f22"))
        assertEquals(Color(0xFF1E1F22), ThemeColors.parse("1e1f22"))
        assertEquals(Color(0xFF11EE22), ThemeColors.parse("#1E2"))
        assertNull(ThemeColors.parse("zzz"))
        assertNull(ThemeColors.parse("#12345"))
        assertEquals("#1E1F22", ThemeColors.toHex(Color(0xFF1E1F22)))
        assertEquals("#000000", ThemeColors.toHex(Color(0xFF000000)))
    }

    @Test
    fun `syntax palettes map from theme colors`() {
        val c = themeById("monokai")!!.colors
        val sql = sqlSyntaxPalette(c)
        assertEquals(c.keyword, sql.keyword)
        assertEquals(c.string, sql.string)
        assertEquals(c.number, sql.number)
        assertEquals(c.comment, sql.comment)
        assertEquals(c.punctuation, sql.punctuation)

        val json = jsonSyntaxPalette(c)
        assertEquals(c.string, json.string)
        assertEquals(c.number, json.number)
        assertEquals(c.keyword, json.key)
        assertEquals(c.keyword, json.boolean)
        assertEquals(c.keyword, json.nullLiteral)
        assertEquals(c.punctuation, json.punctuation)
    }
}
