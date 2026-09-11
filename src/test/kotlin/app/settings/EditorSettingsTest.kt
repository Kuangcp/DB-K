package app.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class EditorSettingsTest {

    @Test
    fun sanitizedTrimsNameAndClampsSize() {
        assertEquals("JetBrains Mono", EditorSettings.sanitized("  JetBrains Mono  ", 13f).fontFamilyName)
        assertEquals(EditorSettings.MIN_FONT_SP, EditorSettings.sanitized("", 1f).fontSizeSp)
        assertEquals(EditorSettings.MAX_FONT_SP, EditorSettings.sanitized("", 99f).fontSizeSp)
        assertEquals(13f, EditorSettings.sanitized("", 13f).fontSizeSp)
    }

    @Test
    fun lineHeightScalesWithFontSize() {
        // 默认 13sp 对应历史固定行高 20sp
        assertEquals(20f, EditorSettings.lineHeightSp(13f), 0.01f)
        assertEquals(40f, EditorSettings.lineHeightSp(26f), 0.01f)
    }
}
