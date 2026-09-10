package db

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsoleFilesTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `read missing file returns empty`() {
        assertEquals("", ConsoleFiles.read(dir.resolve("nope.sql")))
    }

    @Test
    fun `write then read round trip`() {
        val f = dir.resolve("c.sql")
        ConsoleFiles.write(f, "SELECT 1;")
        assertTrue(ConsoleFiles.exists(f))
        assertEquals("SELECT 1;", ConsoleFiles.read(f))
    }

    @Test
    fun `delete removes file and is idempotent`() {
        val f = dir.resolve("c.sql")
        ConsoleFiles.write(f, "x")
        ConsoleFiles.delete(f)
        assertFalse(ConsoleFiles.exists(f))
        ConsoleFiles.delete(f) // 不存在时静默
    }
}
