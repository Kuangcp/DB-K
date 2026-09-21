package app.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeymapTest {

    private fun chord(
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        key: ShortcutKey,
    ) = KeyChord(ctrl = ctrl, alt = alt, shift = shift, key = key)

    @Test
    fun `chord format and config string`() {
        assertEquals("Ctrl+Alt+L", chord(ctrl = true, alt = true, key = ShortcutKey.L).format())
        assertEquals("CTRL+ALT+L", chord(ctrl = true, alt = true, key = ShortcutKey.L).toConfigString())
        assertEquals("↑", chord(key = ShortcutKey.UP).format())
        assertEquals("UP", chord(key = ShortcutKey.UP).toConfigString())
        assertEquals("Ctrl+Shift+F5", chord(ctrl = true, shift = true, key = ShortcutKey.F5).format())
    }

    @Test
    fun `parse accepts config string display and lowercase`() {
        val expected = chord(ctrl = true, alt = true, key = ShortcutKey.L)
        assertEquals(expected, KeyChord.parse("CTRL+ALT+L"))
        assertEquals(expected, KeyChord.parse("ctrl + alt + l"))
        assertEquals(expected, KeyChord.parse("Ctrl+Alt+L"))
        assertEquals(chord(key = ShortcutKey.UP), KeyChord.parse("UP"))
        assertEquals(chord(key = ShortcutKey.UP), KeyChord.parse("↑"))
        assertEquals(chord(key = ShortcutKey.ESCAPE), KeyChord.parse("esc"))
    }

    @Test
    fun `format round trips for every key`() {
        for (key in ShortcutKey.entries) {
            val value = chord(ctrl = true, shift = true, key = key)
            assertEquals(value, KeyChord.parse(value.toConfigString()), "config round-trip: $key")
            assertEquals(value, KeyChord.parse(value.format()), "display round-trip: $key")
        }
    }

    @Test
    fun `parse rejects malformed`() {
        assertNull(KeyChord.parse(""))
        assertNull(KeyChord.parse("   "))
        assertNull(KeyChord.parse("CTRL+"))
        assertNull(KeyChord.parse("CTRL+FOO"))
        assertNull(KeyChord.parse("HYPER+L"))
    }

    @Test
    fun `valid binding requires modifier or function key`() {
        assertTrue(chord(ctrl = true, key = ShortcutKey.S).isValidBinding())
        assertTrue(chord(alt = true, key = ShortcutKey.D).isValidBinding())
        assertTrue(chord(key = ShortcutKey.F5).isValidBinding())
        assertFalse(chord(key = ShortcutKey.A).isValidBinding())
        assertFalse(chord(shift = true, key = ShortcutKey.A).isValidBinding())
    }

    @Test
    fun `configurable commands are exactly the business features`() {
        assertEquals(
            listOf(
                "execute", "formatSql", "viewDdl", "toggleResults", "transpose", "refreshResult",
                "switchConsoleNext", "switchConsolePrev",
            ),
            ShortcutCommand.configurableCommands.map { it.id },
        )
        // 基础编辑键不进设置
        assertFalse(ShortcutCommand.SAVE_CONSOLE.configurable)
        assertFalse(ShortcutCommand.FIND_REPLACE.configurable)
        assertFalse(ShortcutCommand.COMPLETE.configurable)
        assertFalse(ShortcutCommand.COPY_CELL.configurable)
        assertFalse(ShortcutCommand.CANCEL_RUN.configurable)
    }

    @Test
    fun `default keymap has no conflicts and every configurable command is bound`() {
        assertTrue(Keymap.Default.conflicts().isEmpty())
        for (command in ShortcutCommand.configurableCommands) {
            assertTrue(Keymap.Default.chordsOf(command).isNotEmpty(), "缺少默认键: ${command.id}")
        }
    }

    @Test
    fun `overrides unbind and reset`() {
        val custom = chord(ctrl = true, alt = true, key = ShortcutKey.ENTER)
        val keymap = Keymap.Default
            .withChord(ShortcutCommand.EXECUTE, custom)
            .unbind(ShortcutCommand.TRANSPOSE)
        assertEquals(listOf(custom), keymap.chordsOf(ShortcutCommand.EXECUTE))
        assertTrue(keymap.chordsOf(ShortcutCommand.TRANSPOSE).isEmpty())
        assertFalse(keymap.isDefault(ShortcutCommand.EXECUTE))
        assertTrue(keymap.resetCommand(ShortcutCommand.EXECUTE).isDefault(ShortcutCommand.EXECUTE))
        assertTrue(keymap.resetAll().isDefault(ShortcutCommand.TRANSPOSE))
    }

    @Test
    fun `conflicts only reported within same scope among configurable commands`() {
        // WINDOW 的两条：ViewDdl / ToggleResults 同键 → 冲突
        val sameScope = Keymap.Default.withChord(
            ShortcutCommand.TOGGLE_RESULTS,
            chord(ctrl = true, key = ShortcutKey.Q),
        )
        val conflicts = sameScope.conflicts()
        assertEquals(1, conflicts.size)
        assertEquals(ShortcutCommand.VIEW_DDL, conflicts.single().first)
        assertEquals(ShortcutCommand.TOGGLE_RESULTS, conflicts.single().second)

        // 跨 scope 同键不算冲突：EXECUTE(EDITOR) 用 Ctrl+Q 不影响 VIEW_DDL(WINDOW)
        val crossScope = Keymap.Default.withChord(
            ShortcutCommand.EXECUTE,
            chord(ctrl = true, key = ShortcutKey.Q),
        )
        assertTrue(crossScope.conflicts().isEmpty())
    }

    @Test
    fun `from overrides keeps defaults for untouched commands and applies unbound`() {
        val keymap = Keymap.fromOverrides(
            mapOf(
                ShortcutCommand.EXECUTE to listOf(chord(ctrl = true, key = ShortcutKey.F9)),
                ShortcutCommand.TRANSPOSE to emptyList(),
            ),
        )
        assertEquals(listOf(chord(ctrl = true, key = ShortcutKey.F9)), keymap.chordsOf(ShortcutCommand.EXECUTE))
        assertTrue(keymap.chordsOf(ShortcutCommand.TRANSPOSE).isEmpty())
        assertEquals(ShortcutCommand.VIEW_DDL.defaultChords, keymap.chordsOf(ShortcutCommand.VIEW_DDL))
    }
}
