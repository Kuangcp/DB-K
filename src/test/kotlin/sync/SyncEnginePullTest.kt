package sync

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncEnginePullTest {

    @TempDir
    lateinit var dir: Path

    private val salt = ByteArray(16) { 7 }
    private val key = SecretBox.deriveKey("pw", salt)

    private class FakeAdapter(var snapshot: SyncSnapshot) : SyncAdapter {
        var applyCalls = 0
        var lastApplied: SyncSnapshot? = null
        var lastRemoved: RemovedIds? = null
        override fun exportSnapshot(): SyncSnapshot = snapshot
        override fun applySnapshot(snapshot: SyncSnapshot, removed: RemovedIds): Int {
            applyCalls++
            lastApplied = snapshot
            lastRemoved = removed
            return 0
        }
    }

    private fun engine(adapter: SyncAdapter, tree: Path) = SyncEngine("db-k", adapter, GitRepo(tree), tree)

    private fun bare(name: String): Path {
        val p = dir.resolve(name)
        val proc = ProcessBuilder("git", "init", "--bare", p.toString()).redirectErrorStream(true).start()
        proc.inputStream.readBytes()
        assertEquals(0, proc.waitFor())
        return p
    }

    private fun console(id: String, conn: String, body: String) =
        ConsoleSync(ConsoleMetaSync(id = id, connectionId = conn, name = id), body)

    private fun connection(id: String, host: String, port: Int = 5432, password: String? = "secret-$id") =
        ConnectionSync(id = id, name = "c-$id", dbType = "POSTGRES", host = host, port = port, password = password)

    private data class Device(val engine: SyncEngine, val adapter: FakeAdapter)

    /** A 先推送初始状态，返回 A 与 B；B 已 clone 到独立工作树。 */
    private fun twoDevices(initial: SyncSnapshot): Pair<Device, Device> {
        val remote = bare("remote.git")
        val aAdapter = FakeAdapter(initial)
        val a = engine(aAdapter, dir.resolve("a"))
        assertNull(a.ensureRepo(remote.toString(), "main"))
        a.writeTree(key)
        assertTrue(a.commitLocal("dev-a"))
        assertTrue(a.push("origin", "main").ok)

        val bAdapter = FakeAdapter(initial)
        val b = engine(bAdapter, dir.resolve("b"))
        assertNull(b.gitClone(remote.toString(), "main"))
        return Device(a, aAdapter) to Device(b, bAdapter)
    }

    @Test
    fun `pull merges non-overlapping console edits without conflict`() {
        assumeTrue(GitRepo.isAvailable())
        val initial = SyncSnapshot(
            connections = listOf(connection("c1", "10.0.0.1")),
            consoles = listOf(console("s1", "c1", "select 1;"), console("s2", "c1", "select 2;")),
        )
        val (aDev, bDev) = twoDevices(initial)
        val (a, aAdapter) = aDev
        val (b, bAdapter) = bDev

        // B 改 s1 并推送
        bAdapter.snapshot = initial.copy(
            consoles = listOf(console("s1", "c1", "select 'from-b';"), console("s2", "c1", "select 2;")),
        )
        b.writeTree(key)
        assertTrue(b.commitLocal("dev-b"))
        assertTrue(b.push("origin", "main").ok)

        // A 改 s2，然后拉取
        aAdapter.snapshot = initial.copy(
            consoles = listOf(console("s1", "c1", "select 1;"), console("s2", "c1", "select 'from-a';")),
        )
        val outcome = a.pull("origin", "main", key, passphraseOk = true, hostLabel = "dev-a")
        assertTrue(outcome is PullOutcome.Synced, outcome.toString())
        val applied = aAdapter.lastApplied!!
        assertEquals("select 'from-b';", applied.consoles.single { it.meta.id == "s1" }.body)
        assertEquals("select 'from-a';", applied.consoles.single { it.meta.id == "s2" }.body)
    }

    @Test
    fun `pull reports conflict for same connection changed on both sides`() {
        assumeTrue(GitRepo.isAvailable())
        val initial = SyncSnapshot(connections = listOf(connection("c1", "10.0.0.1")))
        val (aDev, bDev) = twoDevices(initial)
        val (a, aAdapter) = aDev
        val (b, bAdapter) = bDev

        bAdapter.snapshot = initial.copy(connections = listOf(connection("c1", "10.0.0.2", 5433)))
        b.writeTree(key)
        assertTrue(b.commitLocal("dev-b"))
        assertTrue(b.push("origin", "main").ok)

        aAdapter.snapshot = initial.copy(connections = listOf(connection("c1", "10.0.0.3", 5434)))
        val outcome = a.pull("origin", "main", key, passphraseOk = true, hostLabel = "dev-a")
        val conflict = (outcome as PullOutcome.NeedResolution).conflicts.single()
        assertEquals(SyncEntityKind.CONNECTION, conflict.kind)
        assertEquals("c1", conflict.id)
        val local = conflict.local as ConflictPreview.Connection
        val remote = conflict.remote as ConflictPreview.Connection
        assertEquals("10.0.0.3", local.host)
        assertEquals("10.0.0.2", remote.host)
        assertEquals(0, aAdapter.applyCalls)
    }

    @Test
    fun `resolving with REMOTE writes remote version and converges`() {
        assumeTrue(GitRepo.isAvailable())
        val initial = SyncSnapshot(connections = listOf(connection("c1", "10.0.0.1")))
        val (aDev, bDev) = twoDevices(initial)
        val (a, aAdapter) = aDev
        val (b, bAdapter) = bDev

        bAdapter.snapshot = initial.copy(connections = listOf(connection("c1", "10.0.0.2", 5433)))
        b.writeTree(key)
        assertTrue(b.commitLocal("dev-b"))
        assertTrue(b.push("origin", "main").ok)

        aAdapter.snapshot = initial.copy(connections = listOf(connection("c1", "10.0.0.3", 5434)))
        val outcome = a.pull("origin", "main", key, passphraseOk = true, hostLabel = "dev-a")
        val conflict = (outcome as PullOutcome.NeedResolution).conflicts.single()

        val resolved = a.resolveConflicts(mapOf(conflict.path to ConflictChoice.REMOTE), key, autoPush = true)
        assertTrue(resolved.ok, resolved.toString())
        assertEquals("10.0.0.2", aAdapter.lastApplied!!.connections.single().host)
        assertEquals(5433, aAdapter.lastApplied!!.connections.single().port)

        val bOutcome = b.pull("origin", "main", key, passphraseOk = true, hostLabel = "dev-b")
        assertTrue(bOutcome is PullOutcome.Synced, bOutcome.toString())
        assertEquals("10.0.0.2", bAdapter.lastApplied!!.connections.single().host)
    }

    @Test
    fun `delete on one side propagates when other side untouched`() {
        assumeTrue(GitRepo.isAvailable())
        val initial = SyncSnapshot(
            connections = listOf(connection("c1", "h1"), connection("c2", "h2")),
        )
        val (aDev, bDev) = twoDevices(initial)
        val (a, aAdapter) = aDev
        val (b, bAdapter) = bDev

        bAdapter.snapshot = initial.copy(connections = listOf(connection("c1", "h1")))
        b.writeTree(key)
        assertTrue(b.commitLocal("dev-b"))
        assertTrue(b.push("origin", "main").ok)

        val outcome = a.pull("origin", "main", key, passphraseOk = true, hostLabel = "dev-a")
        assertTrue(outcome is PullOutcome.Synced, outcome.toString())
        assertEquals(setOf("c2"), aAdapter.lastRemoved!!.connections)
        assertEquals(listOf("c1"), aAdapter.lastApplied!!.connections.map { it.id })
    }

    @Test
    fun `conflict preview never contains password`() {
        assumeTrue(GitRepo.isAvailable())
        val initial = SyncSnapshot(connections = listOf(connection("c1", "10.0.0.1", password = "secret-c1")))
        val (aDev, bDev) = twoDevices(initial)
        val (a, aAdapter) = aDev
        val (b, bAdapter) = bDev

        bAdapter.snapshot = initial.copy(connections = listOf(connection("c1", "10.0.0.2", password = "secret-b")))
        b.writeTree(key); assertTrue(b.commitLocal("dev-b")); assertTrue(b.push("origin", "main").ok)

        aAdapter.snapshot = initial.copy(connections = listOf(connection("c1", "10.0.0.3", password = "secret-a")))
        val outcome = a.pull("origin", "main", key, passphraseOk = true, hostLabel = "dev-a")
        val conflict = (outcome as PullOutcome.NeedResolution).conflicts.single()
        val rendered = listOf(conflict.local, conflict.remote).joinToString(" ") { it.toString() }
        assertFalse(rendered.contains("secret"), rendered)
        assertFalse(rendered.contains("password", ignoreCase = true), rendered)
    }

    @Test
    fun `wrong passphrase stops before touching local state`() {
        assumeTrue(GitRepo.isAvailable())
        val initial = SyncSnapshot(connections = listOf(connection("c1", "10.0.0.1")))
        val (a, aAdapter) = twoDevices(initial).first

        val outcome = a.pull("origin", "main", key, passphraseOk = false, hostLabel = "dev-a")
        assertEquals(PullOutcome.Failed(SyncFailure.WRONG_PASSPHRASE), outcome)
        assertEquals(0, aAdapter.applyCalls)
    }

    @Test
    fun `console body conflict is detected and previewed as sql`() {
        assumeTrue(GitRepo.isAvailable())
        val initial = SyncSnapshot(
            connections = listOf(connection("c1", "h")),
            consoles = listOf(console("s1", "c1", "select 1;")),
        )
        val (aDev, bDev) = twoDevices(initial)
        val (a, aAdapter) = aDev
        val (b, bAdapter) = bDev

        bAdapter.snapshot = initial.copy(consoles = listOf(console("s1", "c1", "select 'b';")))
        b.writeTree(key); assertTrue(b.commitLocal("dev-b")); assertTrue(b.push("origin", "main").ok)

        aAdapter.snapshot = initial.copy(consoles = listOf(console("s1", "c1", "select 'a';")))
        val outcome = a.pull("origin", "main", key, passphraseOk = true, hostLabel = "dev-a")
        val conflict = (outcome as PullOutcome.NeedResolution).conflicts.single()
        assertEquals(SyncEntityKind.CONSOLE_BODY, conflict.kind)
        assertTrue(conflict.local is ConflictPreview.Sql)
        assertTrue(conflict.remote is ConflictPreview.Sql)
        assertEquals(listOf("select 'a';"), conflict.local.head)
        assertEquals(listOf("select 'b';"), conflict.remote.head)
    }

    @Test
    fun `cancelling a conflicted pull leaves local data untouched`() {
        assumeTrue(GitRepo.isAvailable())
        val initial = SyncSnapshot(connections = listOf(connection("c1", "10.0.0.1")))
        val (aDev, bDev) = twoDevices(initial)
        val (a, aAdapter) = aDev
        val (b, bAdapter) = bDev

        bAdapter.snapshot = initial.copy(connections = listOf(connection("c1", "10.0.0.2")))
        b.writeTree(key); assertTrue(b.commitLocal("dev-b")); assertTrue(b.push("origin", "main").ok)

        aAdapter.snapshot = initial.copy(connections = listOf(connection("c1", "10.0.0.3")))
        a.pull("origin", "main", key, passphraseOk = true, hostLabel = "dev-a")
        a.abortMerge()

        assertEquals(0, aAdapter.applyCalls)
        assertFalse(GitRepo(a.workTree).mergeInProgress())
        // 放弃后本地仓库不再处于冲突状态，重新拉取会重新给出冲突
        val again = a.pull("origin", "main", key, passphraseOk = true, hostLabel = "dev-a")
        assertTrue(again is PullOutcome.NeedResolution, again.toString())
    }
}

class SyncEngineFirstSyncGuardTest {

    @org.junit.jupiter.api.io.TempDir
    lateinit var dir: Path

    private val key = SecretBox.deriveKey("pw", ByteArray(16) { 7 })

    private class FakeAdapter(var snapshot: SyncSnapshot) : SyncAdapter {
        var lastApplied: SyncSnapshot? = null
        override fun exportSnapshot(): SyncSnapshot = snapshot
        override fun applySnapshot(snapshot: SyncSnapshot, removed: RemovedIds): Int {
            lastApplied = snapshot
            return 0
        }
    }

    private fun engine(adapter: SyncAdapter, tree: Path) = SyncEngine("db-k", adapter, GitRepo(tree), tree)

    private fun bare(name: String): Path {
        val p = dir.resolve(name)
        val proc = ProcessBuilder("git", "init", "--bare", p.toString()).redirectErrorStream(true).start()
        proc.inputStream.readBytes()
        assertEquals(0, proc.waitFor())
        return p
    }

    @Test
    fun `first sync on a cloned tree must not treat remote-only entities as local deletions`() {
        assumeTrue(GitRepo.isAvailable())
        val remote = bare("remote.git")
        val full = SyncSnapshot(
            connections = listOf(
                ConnectionSync(id = "c1", name = "one", dbType = "SQLITE", database = "a.db"),
                ConnectionSync(id = "c2", name = "two", dbType = "SQLITE", database = "b.db"),
            ),
        )
        val server = engine(FakeAdapter(full), dir.resolve("server"))
        assertNull(server.ensureRepo(remote.toString(), "main"))
        server.writeTree(key)
        assertTrue(server.commitLocal("server"))
        assertTrue(server.push("origin", "main").ok)

        // 新设备：clone 到工作树，本地只有一个连接（非空，所以「空库守卫」不会触发）
        val local = FakeAdapter(SyncSnapshot(connections = listOf(full.connections.first())))
        val device = engine(local, dir.resolve("device"))
        assertNull(device.gitClone(remote.toString(), "main"))
        val outcome = device.pull("origin", "main", key, passphraseOk = true, hostLabel = "dev", firstSync = true)
        assertTrue(outcome is PullOutcome.Synced, outcome.toString())
        assertTrue(
            java.nio.file.Files.exists(SyncPaths.connectionFile(device.root(), "c2")),
            "远端独有实体不能被首次同步误删",
        )
        assertEquals(setOf("c1", "c2"), local.lastApplied!!.connections.map { it.id }.toSet())
    }
}
