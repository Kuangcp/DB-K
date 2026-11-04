package app.sync

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncPrefsTest {

    @TempDir
    lateinit var dir: Path

    private fun prefs() = SyncPrefs(dir.resolve("sync.properties"))

    @Test
    fun `missing file yields defaults`() {
        val loaded = prefs().load()
        assertEquals("", loaded.remoteUrl)
        assertEquals("sync", loaded.localDir)
        assertEquals("main", loaded.branch)
        assertNull(loaded.passphrase)
        assertTrue(loaded.autoPushAfterPull)
        assertEquals(false, loaded.autoPushOnExit)
        assertEquals(0, loaded.lastSyncAtMs)
    }

    @Test
    fun `settings roundtrip`() {
        val settings = SyncSettings(
            remoteUrl = "https://example.com/dbk.git",
            localDir = "/tmp/custom-sync",
            branch = "trunk",
            passphrase = "pw",
            autoPushAfterPull = false,
            autoPushOnExit = true,
            lastSyncAtMs = 12345,
        )
        prefs().save(settings)
        assertEquals(settings, prefs().load())
    }

    @Test
    fun `malformed file falls back to defaults without throwing`() {
        // 非法 \u 转义会让 Properties.load 抛异常 → 回落默认值
        Files.writeString(dir.resolve("sync.properties"), "bad=\\uZZZZ\n")
        assertEquals(SyncSettings(), prefs().load())

        // 单个值不可解析：按默认处理，其它键照常
        Files.writeString(dir.resolve("sync.properties"), "lastSyncAtMs=abc\nremoteUrl=x\n")
        val loaded = prefs().load()
        assertEquals("x", loaded.remoteUrl)
        assertEquals(0, loaded.lastSyncAtMs)
    }

    @Test
    fun `saved file is not world readable on posix`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux") ||
            System.getProperty("os.name").lowercase().contains("mac"))
        prefs().save(SyncSettings(passphrase = "secret"))
        val perms = Files.getPosixFilePermissions(dir.resolve("sync.properties"))
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            perms,
        )
    }
}
