package app.sync

import db.ConnectionsRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import sync.ConnectionSync
import sync.GitRepo
import sync.RemovedIds
import sync.SecretBox
import sync.SyncAdapter
import sync.SyncEngine
import sync.SyncFailure
import sync.SyncSnapshot
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncServiceFlowTest {

    @TempDir
    lateinit var dir: Path

    private val passphrase = "pw"
    private val newPassphrase = "pw2"

    private class FakeAdapter(var snapshot: SyncSnapshot) : SyncAdapter {
        var lastApplied: SyncSnapshot? = null
        var lastRemoved: RemovedIds? = null
        override fun exportSnapshot(): SyncSnapshot = snapshot
        override fun applySnapshot(snapshot: SyncSnapshot, removed: RemovedIds): Int {
            lastApplied = snapshot
            lastRemoved = removed
            this.snapshot = snapshot
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
            ConnectionSync(
                id = "c1", name = "one", dbType = "SQLITE", database = "a.db",
                host = "h", port = 1, password = "secret",
            ),
        ),
    )

    private fun seedRemote(remote: Path, password: String = passphrase) {
        val tree = dir.resolve("server")
        val server = SyncEngine("db-k", FakeAdapter(snapshot()), GitRepo(tree), tree)
        assertNull(server.ensureRepo(remote.toString(), "main"))
        val key = SecretBox.deriveKey(password, server.loadOrCreateSalt())
        server.loadOrCreateCheck(key)
        server.writeTree(key)
        assertTrue(server.commitLocal("server"))
        assertTrue(server.push("origin", "main").ok)
    }

    private fun service(adapter: FakeAdapter): SyncService = SyncService(
        repo = ConnectionsRepository(dir.resolve("app.db"), dir.resolve("consoles")),
        dataDir = dir,
        hostLabel = "test-device",
        engineFactory = { settings ->
            val tree = dir.resolve(settings.localDir)
            SyncEngine("db-k", adapter, GitRepo(tree), tree)
        },
    )

    private fun remoteHead(remote: Path): String {
        val proc = ProcessBuilder("git", "--git-dir", remote.toString(), "rev-parse", "HEAD")
            .redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        proc.waitFor()
        return out
    }

    @Test
    fun `clone and import pulls remote tree into empty local db`() = runTest {
        assumeTrue(GitRepo.isAvailable())
        val remote = bare("clone.git")
        seedRemote(remote)
        val adapter = FakeAdapter(SyncSnapshot())
        val service = service(adapter)
        service.configure(SyncSettings(remoteUrl = remote.toString(), localDir = "sync", branch = "main"))

        val outcome = service.cloneAndImport(passphrase)
        assertTrue(outcome.ok, outcome.toString())
        assertEquals("c1", adapter.lastApplied!!.connections.single().id)
        assertEquals(passphrase, SyncPrefs(dir.resolve("sync.properties")).load().passphrase)
        assertTrue(service.state.passphraseSet)
        assertTrue(service.state.settings.lastSyncAtMs > 0)
        assertTrue(Files.isDirectory(dir.resolve("sync/.git")))
    }

    @Test
    fun `clone and import keeps local-only entities instead of deleting them`() = runTest {
        assumeTrue(GitRepo.isAvailable())
        val remote = bare("clone3.git")
        seedRemote(remote)
        val localOnly = ConnectionSync(id = "c2", name = "local", dbType = "SQLITE", database = "b.db")
        val adapter = FakeAdapter(SyncSnapshot(connections = listOf(localOnly)))
        val service = service(adapter)
        service.configure(SyncSettings(remoteUrl = remote.toString(), localDir = "sync", branch = "main"))

        val outcome = service.cloneAndImport(passphrase)
        assertTrue(outcome.ok, outcome.toString())
        assertEquals(emptySet(), adapter.lastRemoved!!.connections, "首次接入不能删本地独有实体")
        assertEquals(setOf("c1"), adapter.lastApplied!!.connections.map { it.id }.toSet())
    }

    @Test
    fun `passphrase is remembered even when the post-change push is rejected`() = runTest {
        assumeTrue(GitRepo.isAvailable())
        val remote = bare("change4.git")
        seedRemote(remote)
        val adapter = FakeAdapter(SyncSnapshot())
        val service = service(adapter)
        service.configure(SyncSettings(remoteUrl = remote.toString(), localDir = "sync", branch = "main"))
        assertTrue(service.cloneAndImport(passphrase).ok)

        // 远端先前进一次，使随后的推送被拒
        val other = dir.resolve("other")
        val otherRepo = GitRepo(other)
        assertTrue(otherRepo.clone(remote.toString(), "main").ok)
        Files.writeString(other.resolve("db-k/README.md"), "changed by other device\n")
        assertTrue(otherRepo.add(listOf("db-k")).ok)
        assertTrue(otherRepo.commit("other"))
        assertTrue(otherRepo.push("origin", "main").ok)

        val outcome = service.changePassphrase(passphrase, newPassphrase)
        assertEquals(SyncFailure.PUSH_REJECTED, outcome.failure)
        assertEquals(newPassphrase, SyncPrefs(dir.resolve("sync.properties")).load().passphrase)
        val salt = Files.readAllBytes(dir.resolve("sync/db-k/salt.bin"))
        val enc = Files.readString(dir.resolve("sync/db-k/connections/c1.enc"))
        assertTrue(runCatching { SecretBox.decrypt(SecretBox.deriveKey(newPassphrase, salt), enc) }.isSuccess)
    }

    @Test
    fun `clone into a directory that already has a git repo is refused`() = runTest {
        assumeTrue(GitRepo.isAvailable())
        val remote = bare("clone2.git")
        seedRemote(remote)
        val service = service(FakeAdapter(SyncSnapshot()))
        service.configure(SyncSettings(remoteUrl = remote.toString(), localDir = "sync", branch = "main"))
        assertTrue(service.cloneAndImport(passphrase).ok)

        val again = service.cloneAndImport(passphrase)
        assertEquals(SyncFailure.GIT_FAILED, again.failure)
        assertEquals("already-initialized", again.detail)
    }

    @Test
    fun `change passphrase with wrong old passphrase changes nothing`() = runTest {
        assumeTrue(GitRepo.isAvailable())
        val remote = bare("change1.git")
        seedRemote(remote)
        val adapter = FakeAdapter(SyncSnapshot())
        val service = service(adapter)
        service.configure(SyncSettings(remoteUrl = remote.toString(), localDir = "sync", branch = "main"))
        assertTrue(service.cloneAndImport(passphrase).ok)
        val enc = dir.resolve("sync/db-k/connections/c1.enc")
        val before = Files.readString(enc)

        val outcome = service.changePassphrase("wrong", newPassphrase)
        assertEquals(SyncFailure.WRONG_PASSPHRASE, outcome.failure)
        assertEquals(before, Files.readString(enc))
        assertEquals(passphrase, SyncPrefs(dir.resolve("sync.properties")).load().passphrase)
    }

    @Test
    fun `change passphrase rewrites every enc file and keeps data readable`() = runTest {
        assumeTrue(GitRepo.isAvailable())
        val remote = bare("change2.git")
        seedRemote(remote)
        val adapter = FakeAdapter(SyncSnapshot())
        val service = service(adapter)
        service.configure(SyncSettings(remoteUrl = remote.toString(), localDir = "sync", branch = "main"))
        assertTrue(service.cloneAndImport(passphrase).ok)

        val enc = dir.resolve("sync/db-k/connections/c1.enc")
        val before = Files.readString(enc)
        val salt = Files.readAllBytes(dir.resolve("sync/db-k/salt.bin"))
        val plainBefore = SecretBox.decrypt(SecretBox.deriveKey(passphrase, salt), before)

        val outcome = service.changePassphrase(passphrase, newPassphrase)
        assertTrue(outcome.ok, outcome.toString())

        val after = Files.readString(enc)
        assertFalse(before == after, "口令更换后密文必须变化")
        val newKey = SecretBox.deriveKey(newPassphrase, salt)
        assertContentEquals(plainBefore, SecretBox.decrypt(newKey, after))
        assertTrue(
            runCatching { SecretBox.decrypt(SecretBox.deriveKey(passphrase, salt), after) }.isFailure,
            "旧口令不应再能解开",
        )
        assertEquals(newPassphrase, SyncPrefs(dir.resolve("sync.properties")).load().passphrase)
        // check 也对新口令生效
        val engine = SyncEngine("db-k", adapter, GitRepo(dir.resolve("sync")), dir.resolve("sync"))
        assertTrue(engine.loadOrCreateCheck(newKey))
    }

    @Test
    fun `changing passphrase with an empty local snapshot refuses to wipe the repo`() = runTest {
        assumeTrue(GitRepo.isAvailable())
        val remote = bare("change3.git")
        seedRemote(remote)
        val adapter = FakeAdapter(SyncSnapshot()) // 本地同步态为空（未导入）
        val service = service(adapter)
        service.configure(SyncSettings(remoteUrl = remote.toString(), localDir = "sync", branch = "main"))
        assertTrue(GitRepo(dir.resolve("sync")).clone(remote.toString(), "main").ok)
        assertTrue(service.submitPassphrase(passphrase).ok)

        val enc = dir.resolve("sync/db-k/connections/c1.enc")
        val before = Files.readString(enc)
        val outcome = service.changePassphrase(passphrase, newPassphrase)
        assertEquals(SyncFailure.EMPTY_LOCAL_GUARD, outcome.failure)
        assertEquals(before, Files.readString(enc))
    }

    @Test
    fun `exit auto push does nothing when disabled or unconfigured`() = runTest {
        assumeTrue(GitRepo.isAvailable())
        val remote = bare("exit1.git")
        seedRemote(remote)
        val adapter = FakeAdapter(SyncSnapshot())
        val service = service(adapter)
        service.configure(
            SyncSettings(
                remoteUrl = remote.toString(), localDir = "sync", branch = "main",
                autoPushOnExit = false,
            ),
        )
        assertTrue(service.cloneAndImport(passphrase).ok)
        val headBefore = remoteHead(remote)

        service.exitAutoPush()
        assertEquals(headBefore, remoteHead(remote))

        // 打开开关但没有本地改动 → 仍然不产生提交
        service.configure(service.state.settings.copy(autoPushOnExit = true))
        service.exitAutoPush()
        assertEquals(headBefore, remoteHead(remote))
    }

    @Test
    fun `probeGit reports availability`() = runTest {
        val service = service(FakeAdapter(SyncSnapshot()))
        val available = service.probeGit()
        assertEquals(GitRepo.isAvailable(), available)
        assertEquals(available, service.state.gitAvailable)
    }
}
