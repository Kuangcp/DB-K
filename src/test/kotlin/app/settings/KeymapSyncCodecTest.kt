package app.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeymapSyncCodecTest {

    private fun withBinding(command: ShortcutCommand, vararg chords: KeyChord) =
        Keymap.Default.copy(bindings = Keymap.Default.bindings + (command to chords.toList()))

    @Test
    fun `encode writes nothing for a default keymap`() {
        assertTrue(KeymapPrefs.encodeOverrides(Keymap.Default).isEmpty())
    }

    @Test
    fun `encode only writes non-default commands`() {
        val custom = KeyChord(ctrl = true, alt = true, key = ShortcutKey.P)
        val encoded = KeymapPrefs.encodeOverrides(withBinding(ShortcutCommand.EXECUTE, custom))
        assertEquals(setOf("execute"), encoded.keys)
        assertEquals("CTRL+ALT+P", encoded["execute"])
    }

    @Test
    fun `unbound command encodes as empty string and decodes back`() {
        val encoded = KeymapPrefs.encodeOverrides(withBinding(ShortcutCommand.EXECUTE))
        assertEquals("", encoded["execute"])
        val decoded = KeymapPrefs.decodeOverrides(encoded)
        assertEquals(emptyList(), decoded.chordsOf(ShortcutCommand.EXECUTE))
    }

    @Test
    fun `unknown command id and malformed chord are ignored`() {
        val decoded = KeymapPrefs.decodeOverrides(mapOf("nope" to "CTRL+X", "execute" to "NOT+A+CHORD"))
        assertEquals(Keymap.Default, decoded)
    }

    @Test
    fun `roundtrip preserves overrides`() {
        val keymap = Keymap.Default
            .copy(bindings = Keymap.Default.bindings + (ShortcutCommand.VIEW_DDL to emptyList()))
            .copy(bindings = Keymap.Default.bindings + (ShortcutCommand.VIEW_DDL to emptyList()) +
                (ShortcutCommand.TOGGLE_TREE to listOf(KeyChord(ctrl = true, shift = true, key = ShortcutKey.G))))
        val decoded = KeymapPrefs.decodeOverrides(KeymapPrefs.encodeOverrides(keymap))
        assertEquals(emptyList(), decoded.chordsOf(ShortcutCommand.VIEW_DDL))
        assertEquals(keymap.chordsOf(ShortcutCommand.TOGGLE_TREE), decoded.chordsOf(ShortcutCommand.TOGGLE_TREE))
        assertEquals(keymap.chordsOf(ShortcutCommand.EXECUTE), decoded.chordsOf(ShortcutCommand.EXECUTE))
    }
}
