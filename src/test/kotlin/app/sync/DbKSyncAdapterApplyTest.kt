package app.sync

import db.ConnectionProfile
import db.ConnectionsRepository
import db.DbType
import org.junit.jupiter.api.io.TempDir
import sync.ConsoleMetaSync
import sync.ConsoleSync
import sync.ConnectionSync
import sync.FolderSync
import sync.KeymapSync
import sync.RemovedIds
import sync.SyncSnapshot
import sync.WorkspaceSync
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DbKSyncAdapterApplyTest {

    @TempDir
    lateinit var dir: Path

    private fun repo(name: String = "app.db") = ConnectionsRepository(dir.resolve(name), dir.resolve("consoles"))

    private fun profile(id: String, folderId: String? = null, password: String? = "pw") = ConnectionProfile(
        id = id, name = "conn-$id", folderId = folderId, dbType = DbType.SQLITE,
        database = "$id.db", user = "u", password = password,
    )

    private fun sampleSnapshot() = SyncSnapshot(
        folders = listOf(FolderSync("f1", "prod", sortOrder = 2)),
        connections = listOf(
            ConnectionSync(
                id = "c1", name = "conn-c1", dbType = "SQLITE", folderId = "f1",
                database = "c1.db", user = "u", password = "pw", sortOrder = 1, updatedAt = 111,
            ),
        ),
        workspaces = listOf(WorkspaceSync("w1", "默认", autoNamed = true, lastActiveConsoleId = "s1", consoleIds = listOf("s1"))),
        consoles = listOf(ConsoleSync(ConsoleMetaSync("s1", "c1", "脚本", target = "public", updatedAt = 222), "select 1;")),
    )

    @Test
    fun `apply roundtrips export`() {
        repo().use { source ->
            val fid = source.createFolder("prod")
            val cid = source.createConnection(profile("c1", fid, "pw"))
            val console = source.createConsole(cid, "daily")
            source.writeConsoleContent(console.id, "select 42;")
            val snapshot = DbKSyncAdapter(source, dir).exportSnapshot()

            repo("target.db").use { target ->
                DbKSyncAdapter(target, dir).applySnapshot(snapshot, RemovedIds())
                val imported = target.listConnections().single()
                assertEquals("conn-c1", imported.name)
                assertEquals("pw", imported.password)
                assertEquals("select 42;", target.readConsoleContent(target.listAllConsoles().single().id))
                assertEquals(listOf("prod"), target.listFolders().map { it.name })
            }
        }
    }

    @Test
    fun `apply writes console bodies to consoles dir`() {
        repo().use { r ->
            DbKSyncAdapter(r, dir).applySnapshot(sampleSnapshot(), RemovedIds())
            assertEquals("select 1;", Files.readString(dir.resolve("consoles/s1.sql")))
        }
    }

    @Test
    fun `apply removes entities not in snapshot`() {
        repo().use { r ->
            val cid = r.createConnection(profile("c1"))
            val console = r.createConsole(cid, "n")
            r.writeConsoleContent(console.id, "x")
            assertTrue(Files.exists(dir.resolve("consoles/${console.id}.sql")))

            DbKSyncAdapter(r, dir).applySnapshot(
                SyncSnapshot(),
                RemovedIds(connections = setOf(cid), consoles = setOf(console.id)),
            )
            assertNull(r.getConnection(cid))
            assertTrue(r.listAllConsoles().isEmpty())
            assertFalse(Files.exists(dir.resolve("consoles/${console.id}.sql")))
        }
    }

    @Test
    fun `apply keeps external consoles and caret`() {
        repo().use { r ->
            val cid = r.createConnection(profile("c1"))
            val console = r.createConsole(cid, "n")
            r.setConsoleCaret(console.id, 5, 7)
            val external = r.createExternalConsole(cid, "ext", dir.resolve("outside.sql").toString())

            val snapshot = sampleSnapshot().copy(connections = listOf(sampleSnapshot().connections.single().copy(id = cid)))
            DbKSyncAdapter(r, dir).applySnapshot(snapshot, RemovedIds())

            assertEquals(5, r.getConsole(console.id)!!.caretStart)
            assertTrue(r.getConsole(external.id)!!.external)
            assertEquals(2, r.listAllConsoles().size)
        }
    }

    @Test
    fun `apply writes settings files`() {
        repo().use { r ->
            val snapshot = sampleSnapshot().copy(
                liveTemplatesJson = """{"fileVersion":1,"templates":[]}""",
                themesJson = """{"version":1,"themes":[]}""",
                keymap = KeymapSync(mapOf("execute" to "CTRL+ALT+P")),
            )
            DbKSyncAdapter(r, dir).applySnapshot(snapshot, RemovedIds())
            assertEquals("""{"fileVersion":1,"templates":[]}""", Files.readString(dir.resolve("live-templates.json")))
            assertEquals("""{"version":1,"themes":[]}""", Files.readString(dir.resolve("themes.json")))
            val props = java.util.Properties().apply { Files.newInputStream(dir.resolve("keymap.properties")).use { load(it) } }
            assertEquals("CTRL+ALT+P", props.getProperty("execute"))
        }
    }

    @Test
    fun `apply clears settings files that the snapshot does not have`() {
        repo().use { r ->
            Files.writeString(dir.resolve("live-templates.json"), "{}")
            DbKSyncAdapter(r, dir).applySnapshot(sampleSnapshot(), RemovedIds())
            assertFalse(Files.exists(dir.resolve("live-templates.json")))
        }
    }

    @Test
    fun `apply is idempotent`() {
        repo().use { r ->
            val snapshot = sampleSnapshot()
            DbKSyncAdapter(r, dir).applySnapshot(snapshot, RemovedIds())
            DbKSyncAdapter(r, dir).applySnapshot(snapshot, RemovedIds())
            assertEquals(1, r.listConnections().size)
            assertEquals(1, r.listAllConsoles().size)
            assertEquals(1, r.listWorkspaces().size)
            assertEquals("select 1;", r.readConsoleContent("s1"))
        }
    }

    @Test
    fun `unknown db type is skipped without failing the import`() {
        repo().use { r ->
            val snapshot = sampleSnapshot().copy(
                connections = listOf(sampleSnapshot().connections.single().copy(dbType = "NOT_A_DB")),
            )
            DbKSyncAdapter(r, dir).applySnapshot(snapshot, RemovedIds())
            assertTrue(r.listConnections().isEmpty())
            // 连接不存在 → 其控制台也不导入
            assertTrue(r.listAllConsoles().isEmpty())
        }
    }
}
