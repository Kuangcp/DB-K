package app.ui

import app.settings.KeyChord
import app.settings.ShortcutKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 键映射穷尽性：`ShortcutKey` 每个枚举值都必须能映射到 Compose `Key` 与 AWT `VK_*`，
 * 否则录制/匹配会在运行时出 undefined。新增键时此测试会立刻失败。
 *
 * 另含 AWT `KEY_TYPED` 字符匹配：X11 下 Ctrl+可打印键除 `KEY_PRESSED` 外还会派发一次
 * `KEY_TYPED`（`keyCode=VK_UNDEFINED`），必须据此命中并吞掉，否则字符会漏进编辑器
 * （如 Ctrl+/ 输入 '/'）。
 */
class KeymapAdapterTest {

    @Test
    fun `every key maps to compose and awt`() {
        for (key in ShortcutKey.entries) {
            assertTrue(key.toComposeKey().keyCode != 0L, "缺少 Compose 映射: $key")
            assertTrue(key.toAwtKeyCode() != 0, "缺少 AWT 映射: $key")
        }
    }

    @Test
    fun `awt reverse lookup finds a key for every mapped code`() {
        for (key in ShortcutKey.entries) {
            assertTrue(shortcutKeyFromAwt(key.toAwtKeyCode()) != null, "AWT 反查失败: $key")
        }
        assertEquals(ShortcutKey.ENTER, shortcutKeyFromAwt(ShortcutKey.ENTER.toAwtKeyCode()))
        assertEquals(ShortcutKey.A, shortcutKeyFromAwt(ShortcutKey.A.toAwtKeyCode()))
        assertEquals(ShortcutKey.F5, shortcutKeyFromAwt(ShortcutKey.F5.toAwtKeyCode()))
    }

    // ---- KEY_TYPED 字符匹配（X11 额外字符事件消费） ----

    @Test
    fun `typed char of a bound key matches its chord`() {
        assertTrue(KeyChord(ctrl = true, key = ShortcutKey.SLASH).matchesTypedChar('/'))
        assertTrue(KeyChord(ctrl = true, key = ShortcutKey.Y).matchesTypedChar('y'))
        assertTrue(KeyChord(ctrl = true, key = ShortcutKey.Y).matchesTypedChar(0x19.toChar())) // Ctrl+Y 控制字符
        assertTrue(KeyChord(ctrl = true, key = ShortcutKey.TAB).matchesTypedChar('\t'))
        assertTrue(KeyChord(ctrl = true, key = ShortcutKey.BRACKET_LEFT).matchesTypedChar(0x1B.toChar()))
    }

    @Test
    fun `ctrl slash also matches its x11 control char`() {
        assertTrue(KeyChord(ctrl = true, key = ShortcutKey.SLASH).matchesTypedChar(0x1F.toChar()))
    }

    @Test
    fun `unrelated chars do not match`() {
        assertFalse(KeyChord(ctrl = true, key = ShortcutKey.SLASH).matchesTypedChar('a'))
        assertFalse(KeyChord(ctrl = true, key = ShortcutKey.COMMA).matchesTypedChar('.'))
    }

    @Test
    fun `keys without a printable char never match a typed char`() {
        assertFalse(KeyChord(ctrl = true, key = ShortcutKey.ENTER).matchesTypedChar('\n'))
        assertFalse(KeyChord(ctrl = true, key = ShortcutKey.UP).matchesTypedChar('↑'))
        assertFalse(KeyChord(key = ShortcutKey.F5).matchesTypedChar('5'))
    }
}
