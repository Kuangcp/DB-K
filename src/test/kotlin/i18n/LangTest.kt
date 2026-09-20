package i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.util.Locale

class LangTest {

    @Test
    fun parseStrictlyRecognizesKnownTags() {
        assertEquals(Lang.ZH, Lang.parse("zh"))
        assertEquals(Lang.ZH, Lang.parse("zh-CN"))
        assertEquals(Lang.ZH, Lang.parse("ZH-hans"))
        assertEquals(Lang.EN, Lang.parse("en"))
        assertEquals(Lang.EN, Lang.parse("en-US"))
        assertNull(Lang.parse(null))
        assertNull(Lang.parse(""))
        assertNull(Lang.parse("de"))
    }

    @Test
    fun fromTagFallsBackToEnglish() {
        assertEquals(Lang.ZH, Lang.fromTag("zh-TW"))
        assertEquals(Lang.EN, Lang.fromTag("de-DE"))
        assertEquals(Lang.EN, Lang.fromTag(null))
    }

    @Test
    fun systemLanguageUsesLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            assertEquals(Lang.ZH, Lang.system())
            Locale.setDefault(Locale.US)
            assertEquals(Lang.EN, Lang.system())
            Locale.setDefault(Locale.GERMANY)
            assertEquals(Lang.EN, Lang.system())
        } finally {
            Locale.setDefault(original)
        }
    }
}
