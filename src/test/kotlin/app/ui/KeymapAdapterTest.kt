package app.ui

import app.settings.ShortcutKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 键映射穷尽性：`ShortcutKey` 每个枚举值都必须能映射到 Compose `Key` 与 AWT `VK_*`，
 * 否则录制/匹配会在运行时出 undefined。新增键时此测试会立刻失败。
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
}
