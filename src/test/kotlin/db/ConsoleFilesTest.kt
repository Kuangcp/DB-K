package db

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConsoleFilesTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `write then read round-trips utf8`() {
        val f = dir.resolve("sub").resolve("a.sql")
        ConsoleFiles.write(f, "SELECT '中文';\n")
        assertEquals("SELECT '中文';\n", ConsoleFiles.read(f))
    }

    @Test
    fun `write leaves no temp file behind`() {
        val f = dir.resolve("a.sql")
        ConsoleFiles.write(f, "SELECT 1")
        Files.list(dir).use { s ->
            val names = s.map { it.fileName.toString() }.toList()
            assertEquals(listOf("a.sql"), names)
        }
    }

    @Test
    fun `read of missing file returns empty`() {
        assertFalse(Files.exists(dir.resolve("nope.sql")))
        assertEquals("", ConsoleFiles.read(dir.resolve("nope.sql")))
    }

    /**
     * 原子写 = 临时文件 + REPLACE_EXISTING 移动：目标 inode 会变。
     * 原地 truncate+write 则 inode 不变（外部工具/监听可能读到半截内容）。
     * 仅在支持 unix:ino 的平台断言（Windows 无该属性，跳过）。
     */
    @Test
    fun `write replaces via atomic move changing inode`() {
        val f = dir.resolve("a.sql")
        ConsoleFiles.write(f, "SELECT 1")
        val ino = runCatching { Files.getAttribute(f, "unix:ino") }.getOrNull() ?: return
        ConsoleFiles.write(f, "SELECT 2")
        val ino2 = Files.getAttribute(f, "unix:ino")
        assertNotEquals(ino, ino2, "原子写应通过临时文件替换目标（inode 变化）")
        assertEquals("SELECT 2", ConsoleFiles.read(f))
    }

    @Test
    fun `write creates parent directories`() {
        val f = dir.resolve("deep").resolve("nested").resolve("a.sql")
        ConsoleFiles.write(f, "SELECT 1")
        assertTrue(Files.isRegularFile(f))
    }
}
