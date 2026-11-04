package sync

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncEngineInitTest {

    @TempDir
    lateinit var dir: Path

    private class FakeAdapter(var snapshot: SyncSnapshot = SyncSnapshot()) : SyncAdapter {
        override fun exportSnapshot(): SyncSnapshot = snapshot
        override fun applySnapshot(snapshot: SyncSnapshot, removed: RemovedIds): Int = 0
    }

    private fun engine(tree: Path = dir.resolve("repo")) =
        SyncEngine("db-k", FakeAdapter(), GitRepo(tree), tree)

    @Test
    fun `init writes readme and gitattributes once`() {
        assumeTrue(GitRepo.isAvailable())
        val e = engine()
        e.ensureRepo(dir.resolve("remote.git").toUri().toString(), "main")
        e.writeTree(SecretBox.deriveKey("pw", ByteArray(16)))

        val attrs = dir.resolve("repo/.gitattributes")
        assertTrue(Files.exists(attrs))
        val first = Files.readString(attrs)
        assertTrue(first.contains("*.enc -text"))
        assertTrue(first.contains("*.sql text eol=lf"))
        assertTrue(Files.exists(e.root().resolve(SyncPaths.README_FILE)))

        // 再跑一次不重复追加
        e.ensureRepo(dir.resolve("remote.git").toUri().toString(), "main")
        assertEquals(first, Files.readString(attrs))
    }

    @Test
    fun `existing gitattributes is extended not overwritten`() {
        assumeTrue(GitRepo.isAvailable())
        val tree = dir.resolve("repo2")
        Files.createDirectories(tree)
        val attrs = tree.resolve(".gitattributes")
        Files.writeString(attrs, "# hand written\n*.md text\n")

        engine(tree).ensureRepo(dir.resolve("remote2.git").toUri().toString(), "main")

        val text = Files.readString(attrs)
        assertTrue(text.contains("# hand written"), text)
        assertTrue(text.contains("*.md text"), text)
        assertTrue(text.contains("*.enc -text"), text)
        assertFalse(text.lines().count { it.trim() == "*.enc -text" } > 1, "不应重复追加")
    }
}
