package db

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 控制台正文 = 一个 .sql 文件。读写为纯文件操作（小文件，同步足够），
 * 由 ConnectionsRepository 按 console.file_path 调用，控制台层不直接碰盘。
 */
object ConsoleFiles {

    /** 读取控制台正文；文件不存在（首次/被外部删除）返回空串。 */
    fun read(path: Path): String {
        return runCatching {
            Files.readAllBytes(path).toString(StandardCharsets.UTF_8)
        }.getOrDefault("")
    }

    /**
     * 原子写：同目录临时文件 + ATOMIC_MOVE，避免外部工具/监听读到半截内容。
     * 跨文件系统等无法原子移动时回落普通写。
     */
    fun write(path: Path, text: String) {
        Files.createDirectories(path.parent)
        val tmp = runCatching { Files.createTempFile(path.parent, ".${path.fileName}", ".tmp") }.getOrNull()
        if (tmp == null) {
            Files.write(path, text.toByteArray(StandardCharsets.UTF_8))
            return
        }
        runCatching {
            Files.write(tmp, text.toByteArray(StandardCharsets.UTF_8))
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.onFailure {
            runCatching { Files.deleteIfExists(tmp) }
            Files.write(path, text.toByteArray(StandardCharsets.UTF_8))
        }
    }

    fun delete(path: Path) {
        runCatching { Files.deleteIfExists(path) }
    }

    fun exists(path: Path): Boolean = Files.isRegularFile(path)
}
