package app.core

import java.io.File
import java.net.URI

/**
 * Compose Desktop 的 `DragData.FilesList.readFiles()` 返回的是 **URI 字符串**（`file:/...`，
 * 见 `DragDataFilesListImpl` 里的 `File.toURI().toString()`），不是裸路径。
 * 直接 `File(raw)` 会把 `file:/...` 当成相对路径拼到 CWD 下（历史 bug：拖入文件被当成“文件已丢失”）。
 *
 * 这里统一还原为 [File]：优先按 URI 解析（顺带解码 `%20` 等转义、兼容 Windows `file:/C:/...`），
 * 非 file: 或非法 URI 回落按裸路径处理（兼容其它平台/实现）。
 */
internal fun dragUriToFile(raw: String): File? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    runCatching { File(URI(trimmed)) }.getOrNull()?.let { return it }
    return runCatching { File(trimmed) }.getOrNull()
}
