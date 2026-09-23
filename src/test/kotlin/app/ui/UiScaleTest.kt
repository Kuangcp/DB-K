package app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [scaledDensity] 的幂等性：祖先窗口已放大后再包一次不得把倍率平方。
 * 这是 DialogWindow 可能继承父 composition 密度时的关键保护。
 */
class UiScaleTest {

    @Test
    fun `no previous scale applies target directly`() {
        assertEquals(1.5f, scaledDensity(currentDensity = 1f, applied = 1f, target = 1.5f))
    }

    @Test
    fun `already applied scale does not compound`() {
        assertEquals(1.5f, scaledDensity(currentDensity = 1.5f, applied = 1.5f, target = 1.5f))
    }

    @Test
    fun `system hidpi density is preserved once and idempotently`() {
        val once = scaledDensity(1.25f, 1f, 1.5f)
        assertEquals(1.875f, once)
        assertEquals(1.875f, scaledDensity(once, 1.5f, 1.5f))
    }

    @Test
    fun `target one leaves system density unchanged`() {
        assertEquals(1.25f, scaledDensity(1.25f, 1f, 1f))
    }
}
