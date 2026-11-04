package sync

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SyncEngineReadTest {

    @TempDir
    lateinit var dir: Path

    private val key = SecretBox.deriveKey("pw", ByteArray(16) { 7 })

    private class FakeAdapter(var snapshot: SyncSnapshot) : SyncAdapter {
        var applyCalls = 0
        override fun exportSnapshot(): SyncSnapshot = snapshot
        override fun applySnapshot(snapshot: SyncSnapshot, removed: RemovedIds): Int {
            applyCalls++
            return 0
        }
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
    fun `read tree restores what was written`() {
        val e = engine(FakeAdapter(snapshot("c1", "c2")))
        e.writeTree(key)
        val plan = e.readTree(key).getOrThrow()
        assertEquals(setOf("c1", "c2"), plan.snapshot.connections.map { it.id }.toSet())
        assertEquals("secret-c1", plan.snapshot.connections.single { it.id == "c1" }.password)
        assertEquals("select 1;", plan.snapshot.consoles.single().body)
        assertEquals(0, plan.skippedConsoles)
    }

    @Test
    fun `removed ids computed from local snapshot`() {
        val adapter = FakeAdapter(snapshot("c1", "c2"))
        val e = engine(adapter)
        e.writeTree(key)
        Files.delete(SyncPaths.connectionFile(e.root(), "c2"))
        val plan = e.readTree(key).getOrThrow()
        assertEquals(setOf("c2"), plan.removed.connections)
        assertEquals(setOf("c1"), plan.snapshot.connections.map { it.id }.toSet())
    }

    @Test
    fun `orphan console is skipped and counted`() {
        val adapter = FakeAdapter(
            snapshot("c1").copy(
                consoles = listOf(
                    ConsoleSync(ConsoleMetaSync(id = "s1", connectionId = "c1", name = "ok"), "a"),
                    ConsoleSync(ConsoleMetaSync(id = "s2", connectionId = "gone", name = "orphan"), "b"),
                ),
            ),
        )
        val e = engine(adapter)
        e.writeTree(key)
        val plan = e.readTree(key).getOrThrow()
        assertEquals(1, plan.skippedConsoles)
        assertEquals(listOf("s1"), plan.snapshot.consoles.map { it.meta.id })
    }

    @Test
    fun `wrong key makes read fail instead of returning partial data`() {
        val e = engine(FakeAdapter(snapshot("c1")))
        e.writeTree(key)
        val other = SecretBox.deriveKey("other", ByteArray(16) { 7 })
        assertTrue(e.readTree(other).isFailure)
    }

    @Test
    fun `corrupt json is reported but does not abort the whole read`() {
        val e = engine(FakeAdapter(snapshot("c1")))
        e.writeTree(key)
        val folder = SyncPaths.folderFile(e.root(), "f-broken")
        Files.createDirectories(folder.parent)
        Files.writeString(folder, "{not json")
        val plan = e.readTree(key).getOrThrow()
        assertEquals(listOf("folders/f-broken.json"), plan.corruptFiles)
        assertEquals(setOf("c1"), plan.snapshot.connections.map { it.id }.toSet())
    }

    @Test
    fun `apply delegates to adapter and returns skipped count`() {
        val adapter = FakeAdapter(SyncSnapshot())
        val e = engine(adapter)
        e.writeTree(key)
        val plan = e.readTree(key).getOrThrow()
        assertNotNull(plan)
        e.apply(plan)
        assertEquals(1, adapter.applyCalls)
    }

    @Test
    fun `missing tree root reads as empty snapshot`() {
        val adapter = FakeAdapter(snapshot("c1"))
        val e = engine(adapter, dir.resolve("never-created"))
        val plan = e.readTree(key).getOrThrow()
        assertTrue(plan.snapshot.isEmpty())
        assertEquals(setOf("c1"), plan.removed.connections)
    }
}
