package sync

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GitRepoTest {

    @TempDir
    lateinit var dir: Path

    private fun bare(name: String): Path {
        val p = dir.resolve(name)
        val proc = ProcessBuilder("git", "init", "--bare", p.toString()).redirectErrorStream(true).start()
        proc.inputStream.readBytes()
        assertEquals(0, proc.waitFor())
        return p
    }

    private fun repo(name: String): GitRepo = GitRepo(dir.resolve(name)).apply {
        init()
        setLocalIdentity()
    }

    @Test
    fun `git availability probe`() {
        assumeTrue(GitRepo.isAvailable())
        val v = GitRepo.version()
        assertNotNull(v)
        assertTrue(v.startsWith("git version"))
    }

    @Test
    fun `commit returns false when nothing changed`() {
        assumeTrue(GitRepo.isAvailable())
        val g = repo("empty")
        g.writeFile("db-k/x.txt", "x".toByteArray())
        assertTrue(g.add(listOf("db-k")).ok)
        assertTrue(g.commit("first"))
        assertFalse(g.commit("second"))
    }

    @Test
    fun `init commit push clone and merge conflict detection`() {
        assumeTrue(GitRepo.isAvailable())
        val remote = bare("remote.git")
        val a = repo("a")
        a.setRemote("origin", remote.toString())
        a.writeFile("db-k/a.txt", "one".toByteArray())
        assertTrue(a.add(listOf("db-k")).ok)
        assertTrue(a.commit("first"))
        assertTrue(a.push("origin", "main").ok)

        val b = GitRepo(dir.resolve("b"))
        assertTrue(b.clone(remote.toString(), "main").ok)
        assertEquals("one", b.readFile("db-k/a.txt"))

        b.writeFile("db-k/a.txt", "theirs".toByteArray())
        assertTrue(b.add(listOf("db-k")).ok)
        assertTrue(b.commit("b edit"))
        assertTrue(b.push("origin", "main").ok)

        a.writeFile("db-k/a.txt", "ours".toByteArray())
        assertTrue(a.add(listOf("db-k")).ok)
        assertTrue(a.commit("a edit"))

        assertTrue(a.fetch("origin", "main").ok)
        assertFalse(a.merge("origin/main").ok) // 冲突 → 非 0
        assertTrue(a.mergeInProgress())
        assertEquals(listOf("db-k/a.txt"), a.conflictedPaths())
        assertEquals("ours", String(a.stageBlob(2, "db-k/a.txt")))
        assertEquals("theirs", String(a.stageBlob(3, "db-k/a.txt")))

        a.writeFile("db-k/a.txt", a.stageBlob(3, "db-k/a.txt"))
        assertTrue(a.add(listOf("db-k/a.txt")).ok)
        assertTrue(a.commit("resolve with remote"))
        assertFalse(a.mergeInProgress())
        assertTrue(a.push("origin", "main").ok)
        val check = GitRepo(dir.resolve("check"))
        assertTrue(check.clone(remote.toString(), "main").ok)
        assertEquals("theirs", check.readFile("db-k/a.txt"))
    }

    @Test
    fun `chinese paths and commit messages are handled`() {
        assumeTrue(GitRepo.isAvailable())
        val g = repo("cn")
        g.writeFile("db-k/控制台/一.sql", "select 1;".toByteArray())
        assertTrue(g.add(listOf("db-k")).ok)
        assertTrue(g.commit("中文路径"))
        assertEquals("select 1;", g.readFile("db-k/控制台/一.sql"))
        assertTrue(g.logOneLine().any { it.contains("中文路径") })
    }

    @Test
    fun `push to advanced remote is rejected and stays clean`() {
        assumeTrue(GitRepo.isAvailable())
        val remote = bare("reject.git")
        val a = repo("a2").apply { setRemote("origin", remote.toString()) }
        a.writeFile("db-k/x.txt", "1".toByteArray()); a.add(listOf("db-k")); a.commit("first"); a.push("origin", "main")

        val b = GitRepo(dir.resolve("b2")).apply { clone(remote.toString(), "main") }
        b.writeFile("db-k/x.txt", "2".toByteArray()); b.add(listOf("db-k")); b.commit("second"); b.push("origin", "main")

        a.writeFile("db-k/x.txt", "3".toByteArray()); a.add(listOf("db-k")); a.commit("third")
        val rejected = a.push("origin", "main")
        assertFalse(rejected.ok)
        assertTrue(rejected.out.contains("rejected") || rejected.out.contains("non-fast-forward"), rejected.out)
        assertFalse(a.mergeInProgress())
    }

    @Test
    fun `clone into existing non empty directory fails`() {
        assumeTrue(GitRepo.isAvailable())
        val remote = bare("clone-target.git")
        val existing = dir.resolve("existing")
        java.nio.file.Files.createDirectories(existing)
        java.nio.file.Files.writeString(existing.resolve("keep.txt"), "keep")
        assertFalse(GitRepo(existing).clone(remote.toString(), "main").ok)
        assertEquals("keep", java.nio.file.Files.readString(existing.resolve("keep.txt")))
    }
}
