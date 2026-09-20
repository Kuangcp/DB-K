package i18n

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * catalog 一致性与取词行为测试。
 *
 * `when` 穷尽已保证每个 [Str] 在两种语言都有分支；这里再防「手误填空串 / 占位符写错 / 复数选错」。
 */
class I18nCatalogTest {

    private var saved: Lang = Lang.ZH

    @BeforeTest
    fun setUp() {
        saved = I18n.lang
    }

    @AfterTest
    fun tearDown() {
        I18n.lang = saved
    }

    private val placeholder = Regex("""\{(\d+)\}""")
    private fun indices(s: String): List<Int> =
        placeholder.findAll(s).map { it.groupValues[1].toInt() }.sorted().toList()

    @Test
    fun allKeysNonBlankInBothLanguages() {
        for (lang in Lang.entries) {
            for (key in Str.entries) {
                val text = I18n.t(lang, key)
                assertTrue(text.isNotBlank(), "$lang.$key is blank")
            }
        }
    }

    @Test
    fun placeholdersMatchAcrossLanguages() {
        for (key in Str.entries) {
            assertEquals(
                indices(I18n.t(Lang.ZH, key)),
                indices(I18n.t(Lang.EN, key)),
                "placeholder mismatch for $key",
            )
        }
    }

    @Test
    fun placeholdersAreContiguousFromZero() {
        for (lang in Lang.entries) {
            for (key in Str.entries) {
                val idx = indices(I18n.t(lang, key))
                assertEquals((0 until idx.size).toList(), idx, "$lang.$key placeholders not contiguous")
            }
        }
    }

    @Test
    fun substitutesArgumentsAndLeavesUnknownPlaceholder() {
        val text = I18n.t(Lang.EN, Str.ConfirmFolderMovedConnectionsOther, 5)
        assertTrue(text.contains("5"), "arg not substituted: $text")
        assertFalse(text.contains("{0}"), "placeholder left behind: $text")
    }

    @Test
    fun pluralSelectsOneAndOther() {
        assertEquals(
            I18n.t(Lang.EN, Str.ConfirmFolderMovedConnectionsOne),
            I18n.tn(Lang.EN, Str.ConfirmFolderMovedConnectionsOne, Str.ConfirmFolderMovedConnectionsOther, 1),
        )
        assertEquals(
            I18n.t(Lang.EN, Str.ConfirmFolderMovedConnectionsOther),
            I18n.tn(Lang.EN, Str.ConfirmFolderMovedConnectionsOne, Str.ConfirmFolderMovedConnectionsOther, 0),
        )
        assertEquals(
            I18n.t(Lang.EN, Str.ConfirmFolderMovedConnectionsOther),
            I18n.tn(Lang.EN, Str.ConfirmFolderMovedConnectionsOne, Str.ConfirmFolderMovedConnectionsOther, 3),
        )
    }

    @Test
    fun globalLangDrivesNonComposableLookup() {
        I18n.lang = Lang.EN
        assertEquals(I18n.t(Lang.EN, Str.CommonOk), I18n.t(Str.CommonOk))
        I18n.lang = Lang.ZH
        assertEquals(I18n.t(Lang.ZH, Str.CommonOk), I18n.t(Str.CommonOk))
    }
}
