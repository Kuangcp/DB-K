package app.ui

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** 单元格内容工具单测：MD5 / 宽松 Base64 解码 / 图片格式识别 / 体积格式化。 */
class CellContentToolsTest {

    @Test
    fun md5KnownVectors() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", md5Hex(""))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", md5Hex("abc"))
        // 多字节（UTF-8）
        assertEquals("e10adc3949ba59abbe56e057f20f883e", md5Hex("123456"))
    }

    @Test
    fun decodesPlainBase64() {
        val bytes = decodeBase64Bytes("aGVsbG8=")
        assertEquals("hello", String(bytes, Charsets.UTF_8))
    }

    @Test
    fun stripsDataUriPrefixAndWhitespace() {
        val withPrefix = "data:image/png;base64, aGVs\nbG8=\n"
        assertContentEquals("hello".toByteArray(), decodeBase64Bytes(withPrefix))
    }

    @Test
    fun toleratesMissingPadding() {
        // "hello" 无 = 填充
        assertContentEquals("hello".toByteArray(), decodeBase64Bytes("aGVsbG8"))
    }

    @Test
    fun decodesUrlSafeAlphabet() {
        val raw = byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xBF.toByte())
        val urlSafe = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        assertContentEquals(raw, decodeBase64Bytes(urlSafe))
    }

    @Test
    fun rejectsEmptyAndGarbage() {
        assertFailsWith<IllegalArgumentException> { decodeBase64Bytes("   ") }
        assertFailsWith<IllegalArgumentException> { decodeBase64Bytes("!!!not base64!!!") }
    }

    @Test
    fun detectsImageFormatsByMagic() {
        assertEquals("PNG", imageFormatName(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D)))
        assertEquals("JPEG", imageFormatName(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertEquals("GIF", imageFormatName(byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39)))
        assertEquals("BMP", imageFormatName(byteArrayOf(0x42, 0x4D, 0x00)))
        val webp = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50)
        assertEquals("WebP", imageFormatName(webp))
        assertNull(imageFormatName(byteArrayOf(1, 2, 3)))
        assertNull(imageFormatName(byteArrayOf()))
    }

    @Test
    fun humanSizeFormatting() {
        assertEquals("512 B", humanSize(512))
        assertEquals("1.0 KB", humanSize(1024))
        assertEquals("1.5 MB", humanSize(1024 * 1024 * 3 / 2))
    }
}
