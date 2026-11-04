package sync

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncEngineExportTest {

    @TempDir
    lateinit var dir: Path

    private val key = SecretBox.deriveKey("pw", ByteArray(16) { 7 })

    private class FakeAdapter(var snapshot: SyncSnapshot) : SyncAdapter {
        override fun exportSnapshot(): SyncSnapshot = snapshot
        override fun applySnapshot(snapshot: SyncSnapshot, removed: RemovedIds): Int = 0
    }

    private fun engine(adapter: SyncAdapter, tree: Path = dir.resolve("repo")) =
        SyncEngine("db-k", adapter, GitRepo(tree), tree)

    private fun snapshot(vararg ids: String) = SyncSnapshot(
        connections = ids.map {
            ConnectionSync(
                id = it, name = "c-$it", dbType = "POSTGRES", host = "10.0.0.1",
                port = 5432, database = "db", user = "u", password = "secret-$it",
            )
        },
        consoles = listOf(
            ConsoleSync(
                ConsoleMetaSync(id = "s1", connectionId = ids.firstOrNull() ?: "c1", name = "n"),
                "select 1;",
            ),
        ),
    )

    @Test
    fun `writes entities as separate files and encrypts connections`() {
        val e = engine(FakeAdapter(snapshot("c1", "c2")))
        val salt = e.loadOrCreateSalt()
        assertEquals(16, salt.size)
        assertContentEquals(salt, Files.readAllBytes(SyncPaths.saltFile(e.root())))

        val stats = e.writeTree(key)
        assertEquals(4, stats.written) // 2 enc + 1 sql + 1 meta
        val root = e.root()
        assertTrue(Files.exists(SyncPaths.connectionFile(root, "c1")))
        val stored = Files.readString(SyncPaths.connectionFile(root, "c1"))
        assertFalse(stored.contains("secret-c1"), "连接条目必须加密")
        assertTrue(SecretBox.isEncrypted(stored))
        assertEquals("select 1;", Files.readString(SyncPaths.consoleBodyFile(root, "s1")))
        val plain = String(SecretBox.decrypt(key, stored))
        assertTrue(plain.contains("\"host\": \"10.0.0.1\""))
        assertTrue(plain.contains("\"password\": \"secret-c1\""))
        assertFalse(Files.exists(root.resolve("meta.json")), "数据源 meta 信息不进仓库")
        // 旧版本残留的 meta.json 在下次导出时清理
        Files.writeString(root.resolve("meta.json"), """{"formatVersion":1}""")
        e.writeTree(key)
        assertFalse(Files.exists(root.resolve("meta.json")))
    }

    @Test
    fun `unchanged connection is not rewritten (IV debounce)`() {
        val adapter = FakeAdapter(snapshot("c1"))
        val e = engine(adapter)
        e.writeTree(key)
        val first = Files.readString(SyncPaths.connectionFile(e.root(), "c1"))
        val stats = e.writeTree(key)
        assertEquals(0, stats.written)
        // 连接 + 控制台正文 + 控制台元数据，三份都未变
        assertEquals(3, stats.unchanged)
        assertEquals(first, Files.readString(SyncPaths.connectionFile(e.root(), "c1")))
    }

    @Test
    fun `entities removed locally are deleted from tree`() {
        val adapter = FakeAdapter(snapshot("c1", "c2"))
        val e = engine(adapter)
        e.writeTree(key)
        adapter.snapshot = snapshot("c1")
        val stats = e.writeTree(key)
        assertEquals(1, stats.deleted)
        assertFalse(Files.exists(SyncPaths.connectionFile(e.root(), "c2")))
        assertTrue(Files.exists(SyncPaths.connectionFile(e.root(), "c1")))
    }

    @Test
    fun `salt check and readme survive tree rewrites`() {
        val e = engine(FakeAdapter(snapshot("c1")))
        assertTrue(e.loadOrCreateCheck(key))
        e.writeTree(key)
        val stats = e.writeTree(key)
        assertEquals(0, stats.deleted)
        assertEquals(16, e.loadOrCreateSalt().size)
        assertTrue(Files.exists(SyncPaths.checkFile(e.root())))
        assertTrue(Files.exists(e.root().resolve(SyncPaths.README_FILE)))
        assertFalse(e.loadOrCreateCheck(SecretBox.deriveKey("other", e.loadOrCreateSalt())))
    }

    @Test
    fun `existing plaintext or corrupt enc file is overwritten`() {
        val e = engine(FakeAdapter(snapshot("c1")))
        val file = SyncPaths.connectionFile(e.root(), "c1")
        e.writeTree(key)
        Files.writeString(file, "garbage")
        val stats = e.writeTree(key)
        assertEquals(1, stats.written)
        assertTrue(SecretBox.isEncrypted(Files.readString(file)))
    }

    @Test
    fun `settings files are written only when present`() {
        val adapter = FakeAdapter(
            snapshot("c1").copy(
                liveTemplatesJson = """{"fileVersion":1,"templates":[]}""",
                keymap = KeymapSync(mapOf("execute" to "CTRL+ENTER")),
                themesJson = null,
            ),
        )
        val e = engine(adapter)
        e.writeTree(key)
        assertTrue(Files.exists(SyncPaths.settingFile(e.root(), "live-templates.json")))
        assertTrue(Files.readString(SyncPaths.settingFile(e.root(), "keymap.json")).contains("CTRL+ENTER"))
        assertFalse(Files.exists(SyncPaths.settingFile(e.root(), "themes.json")))
        adapter.snapshot = snapshot("c1")
        e.writeTree(key)
        assertFalse(Files.exists(SyncPaths.settingFile(e.root(), "live-templates.json")))
    }

    @Test
    fun `stray unknown files are left alone`() {
        val e = engine(FakeAdapter(snapshot("c1")))
        val stray = e.root().resolve("connections/keep.me")
        Files.createDirectories(stray.parent)
        Files.writeString(stray, "hand written")
        e.writeTree(key)
        assertTrue(Files.exists(stray))
    }
}
