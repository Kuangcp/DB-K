package sync

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncEnginePushTest {

    @TempDir
    lateinit var dir: Path

    private val key = SecretBox.deriveKey("pw", ByteArray(16) { 7 })

    private class FakeAdapter(var snapshot: SyncSnapshot) : SyncAdapter {
        override fun exportSnapshot(): SyncSnapshot = snapshot
        override fun applySnapshot(snapshot: SyncSnapshot, removed: RemovedIds): Int = 0
    }

    private fun engine(adapter: SyncAdapter, tree: Path) =
        SyncEngine("db-k", adapter, GitRepo(tree), tree)

    private fun snapshot(vararg ids: String) = SyncSnapshot(
        connections = ids.map {
            ConnectionSync(id = it, name = "c-$it", dbType = "POSTGRES", host = "h", port = 1, password = "p-$it")
        },
    )

    private fun bare(name: String): Path {
        val p = dir.resolve(name)
        val proc = ProcessBuilder("git", "init", "--bare", p.toString()).redirectErrorStream(true).start()
        proc.inputStream.readBytes()
        assertEquals(0, proc.waitFor())
        return p
    }

    @Test
    fun `ensureRepo rejects missing remote url`() {
        val e = engine(FakeAdapter(snapshot("c1")), dir.resolve("work"))
        assertEquals(SyncFailure.NOT_CONFIGURED, e.ensureRepo("", "main"))
    }

    @Test
    fun `push commits and updates remote`() {
        assumeTrue(GitRepo.isAvailable())
        val remote = bare("remote.git")
        val e = engine(FakeAdapter(snapshot("c1")), dir.resolve("work"))
        assertNull(e.ensureRepo(remote.toString(), "main"))
        e.writeTree(key)
        assertTrue(e.commitLocal("dev-a"))
        assertTrue(e.push("origin", "main").ok)

        val clone = GitRepo(dir.resolve("clone"))
        assertTrue(clone.clone(remote.toString(), "main").ok)
        assertTrue(Files.exists(dir.resolve("clone/db-k/connections/c1.enc")))
    }

    @Test
    fun `no changes produce no commit`() {
        assumeTrue(GitRepo.isAvailable())
        val e = engine(FakeAdapter(snapshot("c1")), dir.resolve("work2"))
        assertNull(e.ensureRepo(bare("r2.git").toString(), "main"))
        e.writeTree(key)
        assertTrue(e.commitLocal("dev-a"))
        assertFalse(e.commitLocal("dev-a"))
    }

    @Test
    fun `push rejected when remote advanced`() {
        assumeTrue(GitRepo.isAvailable())
        val remote = bare("r3.git")
        val a = engine(FakeAdapter(snapshot("c1")), dir.resolve("a"))
        assertNull(a.ensureRepo(remote.toString(), "main"))
        a.writeTree(key)
        assertTrue(a.commitLocal("a"))
        assertTrue(a.push("origin", "main").ok)

        // b 克隆后先推送，远端前进；a 之后仍然基于旧提交推送 → 被拒。
        val bAdapter = FakeAdapter(snapshot("c1"))
        val b = engine(bAdapter, dir.resolve("b"))
        assertNull(b.gitClone(remote.toString(), "main"))
        bAdapter.snapshot = snapshot("c1").copy(
            connections = listOf(ConnectionSync(id = "c1", name = "renamed", dbType = "POSTGRES", host = "h2", port = 1)),
        )
        b.writeTree(key)
        assertTrue(b.commitLocal("b"))
        assertTrue(b.push("origin", "main").ok)

        val aAdapter = FakeAdapter(snapshot("c1"))
        val a2 = engine(aAdapter, a.workTree)
        aAdapter.snapshot = snapshot("c1", "c9")
        a2.writeTree(key)
        assertTrue(a2.commitLocal("a"))
        val outcome = a2.push("origin", "main")
        assertEquals(SyncFailure.PUSH_REJECTED, outcome.failure)
        assertFalse(outcome.ok)
    }

    @Test
    fun `empty local snapshot against non-empty tree trips guard`() {
        val e = engine(FakeAdapter(snapshot("c1")), dir.resolve("guard"))
        e.writeTree(key)
        val empty = engine(FakeAdapter(SyncSnapshot()), e.workTree)
        assertTrue(empty.isRemoteEmptyGuardTripped())
        val nonEmpty = engine(FakeAdapter(snapshot("c1")), e.workTree)
        assertFalse(nonEmpty.isRemoteEmptyGuardTripped())
    }

    @Test
    fun `gitClone refuses an already initialized tree`() {
        assumeTrue(GitRepo.isAvailable())
        val remote = bare("r4.git")
        val e = engine(FakeAdapter(snapshot("c1")), dir.resolve("c4"))
        assertNull(e.ensureRepo(remote.toString(), "main"))
        assertEquals(SyncFailure.GIT_FAILED, e.gitClone(remote.toString(), "main"))
    }
}
