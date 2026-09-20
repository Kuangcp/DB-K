package app.ui

import java.security.MessageDigest
import java.util.Base64
import i18n.I18n
import i18n.Str

/**
 * 单元格内容工具（纯逻辑，便于单测）：MD5 摘要 / 内容当 Base64 图片解码 / 图片格式识别。
 * 渲染与异步调度在 `ViewerDialogs.CellViewerDialog`，这里只做无 UI 的纯函数。
 */

/** 内容 UTF-8 字节的 MD5，小写 32 位 hex。 */
internal fun md5Hex(content: String): String {
    val digest = MessageDigest.getInstance("MD5").digest(content.toByteArray(Charsets.UTF_8))
    val digits = "0123456789abcdef"
    val out = CharArray(digest.size * 2)
    digest.forEachIndexed { i, b ->
        val v = b.toInt() and 0xFF
        out[i * 2] = digits[v ushr 4]
        out[i * 2 + 1] = digits[v and 0x0F]
    }
    return String(out)
}

/**
 * 宽松解析 Base64：去掉 `data:image/png;base64,` 前缀与所有空白，必要时补 `=` 对齐；
 * 按字符集选标准 / URL-safe 解码器（避免 MIME 解码器静默吞掉非字母表字符而出错）。
 * 全部失败抛 [IllegalArgumentException]。
 */
internal fun decodeBase64Bytes(content: String): ByteArray {
    val cleaned = content
        .substringAfter("base64,", content) // 有 data URI 前缀则取其后的纯数据部分
        .filterNot { it.isWhitespace() }
    require(cleaned.isNotEmpty()) { I18n.t(Str.ImageEmptyContent) }

    val candidates = buildList {
        add(cleaned)
        val rem = cleaned.length % 4
        if (rem != 0) add(cleaned.padEnd(cleaned.length + (4 - rem), '='))
    }
    // 含 URL-safe 专用字符（-_）时优先用 URL 解码器，否则先用标准解码器
    val decoders = if (cleaned.any { it == '-' || it == '_' }) {
        listOf(Base64.getUrlDecoder(), Base64.getDecoder())
    } else {
        listOf(Base64.getDecoder(), Base64.getUrlDecoder())
    }
    candidates.forEach { s ->
        decoders.forEach { d ->
            runCatching { d.decode(s) }.onSuccess { return it }
        }
    }
    throw IllegalArgumentException(I18n.t(Str.ImageInvalidBase64))
}

/** 依据文件头识别图片格式（与解码结果无关，仅用于展示信息行）。 */
internal fun imageFormatName(bytes: ByteArray): String? {
    fun at(i: Int) = bytes[i].toInt() and 0xFF
    fun prefix(vararg sig: Int) = sig.withIndex().all { (i, v) -> i < bytes.size && at(i) == v }
    fun atOffset(offset: Int, vararg sig: Int) =
        sig.withIndex().all { (i, v) -> offset + i < bytes.size && at(offset + i) == v }

    return when {
        prefix(0x89, 0x50, 0x4E, 0x47) -> "PNG"
        prefix(0xFF, 0xD8, 0xFF) -> "JPEG"
        prefix(0x47, 0x49, 0x46, 0x38) -> "GIF"
        prefix(0x42, 0x4D) -> "BMP"
        prefix(0x52, 0x49, 0x46, 0x46) && atOffset(8, 0x57, 0x45, 0x42, 0x50) -> "WebP"
        else -> null
    }
}

/** 字节数的人类可读形式（B / KB / MB）。 */
internal fun humanSize(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
