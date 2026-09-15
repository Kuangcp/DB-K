package app.dialog

import kotlin.test.Test
import kotlin.test.assertEquals

class ViewerDialogsTest {

    @Test
    fun `small image keeps dimensions`() {
        assertEquals(800 to 600, previewSize(800, 600))
        assertEquals(1600 to 1200, previewSize(1600, 1200))
    }

    @Test
    fun `large image scales down within pixel budget`() {
        val (w, h) = previewSize(4000, 3000)
        val pixels = w.toLong() * h
        assertEquals(true, pixels <= 2_000_000L, "像素数应不超过预算")
        assertEquals(true, pixels >= 1_500_000L, "不应缩得过小")
        assertEquals(4.0 / 3.0, w.toDouble() / h, 0.01, "应保持宽高比")
    }

    @Test
    fun `extreme aspect ratio never collapses to zero`() {
        val (w, h) = previewSize(100_000, 10)
        assertEquals(true, w >= 1 && h >= 1)
        assertEquals(100_000.0 / 10.0, w.toDouble() / h, 1.0)
    }

    @Test
    fun `zero dimensions safe`() {
        assertEquals(0 to 0, previewSize(0, 0))
    }
}
