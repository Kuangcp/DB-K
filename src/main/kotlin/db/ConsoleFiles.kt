package db

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

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

    fun write(path: Path, text: String) {
        Files.createDirectories(path.parent)
        Files.write(path, text.toByteArray(StandardCharsets.UTF_8))
    }

    fun delete(path: Path) {
        runCatching { Files.deleteIfExists(path) }
    }

    fun exists(path: Path): Boolean = Files.isRegularFile(path)
}
