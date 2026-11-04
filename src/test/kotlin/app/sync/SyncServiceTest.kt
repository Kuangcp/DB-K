package app.sync

import db.ConnectionsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import sync.ConnectionSync
import sync.GitRepo
import sync.PullOutcome
import sync.RemovedIds
import sync.SecretBox
import sync.SyncAdapter
import sync.SyncEngine
import sync.SyncFailure
import sync.SyncSnapshot
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncServiceTest {

    @TempDir
    lateinit var dir: Path

    private val passphrase = "pw"

    private class FakeAdapter(var snapshot: SyncSnapshot) : SyncAdapter {
        var applyCalls = 0
        var lastApplied: SyncSnapshot? = null
        override fun exportSnapshot(): SyncSnapshot = snapshot
        override fun applySnapshot(snapshot: SyncSnapshot, removed: RemovedIds): Int {
            applyCalls++
            lastApplied = snapshot
            return 0
        }
    }

    private fun bare(name: String): Path {
        val p = dir.resolve(name)
        val proc = ProcessBuilder("git", "init", "--bare", p.toString()).redirectErrorStream(true).start()
        proc.inputStream.readBytes()
        assertEquals(0, proc.waitFor())
        return p
    }

    private fun snapshot() = SyncSnapshot(
        connections = listOf(
            ConnectionSync(id = "c1", name = "one", dbType = "SQLITE", database = "a.db", password = "pw"),
        ),
    )

    /** 远端先有一份数据（模拟另一台设备推送过）。 */
    private fun seedRemote(remote: Path, password: String = passphrase): Path {
        val tree = dir.resolve("server")
        val server = SyncEngine("db-k", FakeAdapter(snapshot()), GitRepo(tree), tree)
        assertNull(server.ensureRepo(remote.toString(), "main"))
        val key = SecretBox.deriveKey(password, server.loadOrCreateSalt())
        assertTrue(server.loadOrCreateCheck(key))
        server.writeTree(key)
        assertTrue(server.commitLocal("server"))
        assertTrue(server.push("origin", "main").ok)
        return remote
    }

    private fun service(adapter: FakeAdapter, dataDir: Path = dir): SyncService {
        val repo = ConnectionsRepository(dataDir.resolve("app.db"), dataDir.resolve("consoles"))
        return SyncService(
            repo = repo,
            dataDir = dataDir,
            hostLabel = "test-device",
            engineFactory = { settings ->
                val tree = dataDir.resolve(settings.localDir)
                SyncEngine("db-k", adapter, GitRepo(tree), tree)
            },
        )
    }

    private fun configure(service: SyncService, remote: Path) = runTest {
        service.configure(
            SyncSettings(remoteUrl = remote.toString(), localDir = "sync", branch = "main", passphrase = null),
        )
    }

    private fun cloneLocalTree(remote: Path) {
        val tree = dir.resolve("sync")
        assertTrue(GitRepo(tree).clone(remote.toString(), "main").ok)
    }

    @Test
    fun `wrong passphrase is not saved`() = runTest {
        assumeTrue(GitRepo.isAvailable())
        val remote = seedRemote(bare("r1.git"))
        cloneLocalTree(remote)
        val service = service(FakeAdapter(snapshot()))
        configure(service, remote)

        val outcome = service.submitPassphrase("bad")
        assertEquals(SyncFailure.WRONG_PASSPHRASE, outcome.failure)
        assertNull(SyncPrefs(dir.resolve("sync.properties")).load().passphrase)
        assertFalse(service.state.passphraseSet)
    }

    @Test
    fun `correct passphrase is saved and marks state`() = runTest {
        assumeTrue(GitRepo.isAvailable())
        val remote = seedRemote(bare("r2.git"))
        cloneLocalTree(remote)
        val service = service(FakeAdapter(snapshot()))
        configure(service, remote)

        val outcome = service.submitPassphrase(passphrase)
        assertTrue(outcome.ok, outcome.toString())
        assertEquals(passphrase, SyncPrefs(dir.resolve("sync.properties")).load().passphrase)
        assertTrue(service.state.passphraseSet)
    }

    @Test
    fun `pull with a stale stored passphrase leaves local untouched`() = runTest {
        assumeTrue(GitRepo.isAvailable())
        val remote = seedRemote(bare("r3.git"))
        cloneLocalTree(remote)
        val adapter = FakeAdapter(snapshot())
        val service = service(adapter)
        configure(service, remote)
        assertTrue(service.submitPassphrase(passphrase).ok)

        // 另一台设备换了口令：仓库里的 check 被新口令重写
        val tree = dir.resolve("sync/db-k")
        val newKey = SecretBox.deriveKey("new-pass", Files.readAllBytes(tree.resolve("salt.bin")))
        Files.writeString(
            tree.resolve("secret.check"),
            SecretBox.encrypt(newKey, SecretBox.CHECK_PLAINTEXT.toByteArray()),
        )

        val outcome = service.pull()
        assertTrue(outcome is PullOutcome.Failed)
        assertEquals(SyncFailure.WRONG_PASSPHRASE, outcome.failure)
        assertEquals(0, adapter.applyCalls)
    }

    @Test
    fun `pull imports remote snapshot and records last sync time`() = runTest {
        assumeTrue(GitRepo.isAvailable())
        val remote = seedRemote(bare("r4.git"))
        cloneLocalTree(remote)
        val adapter = FakeAdapter(SyncSnapshot())
        val service = service(adapter)
        configure(service, remote)
        assertTrue(service.submitPassphrase(passphrase).ok)

        val outcome = service.pull()
        assertTrue(outcome is PullOutcome.Synced, outcome.toString())
        assertEquals("c1", adapter.lastApplied!!.connections.single().id)
        assertTrue(SyncPrefs(dir.resolve("sync.properties")).load().lastSyncAtMs > 0)
        assertTrue(service.state.lastResult!!.ok)
    }

    @Test
    fun `clearPassphrase removes it from prefs and state`() = runTest {
        assumeTrue(GitRepo.isAvailable())
        val remote = seedRemote(bare("r5.git"))
        cloneLocalTree(remote)
        val service = service(FakeAdapter(snapshot()))
        configure(service, remote)
        assertTrue(service.submitPassphrase(passphrase).ok)

        service.clearPassphrase()
        assertNull(SyncPrefs(dir.resolve("sync.properties")).load().passphrase)
        assertFalse(service.state.passphraseSet)
    }

    @Test
    fun `running flag resets when an action fails`() = runTest {
        val service = service(FakeAdapter(snapshot()))
        // 未配置远端
        val outcome = service.push()
        assertEquals(SyncFailure.NOT_CONFIGURED, outcome.failure)
        assertFalse(service.state.running)
    }
}
