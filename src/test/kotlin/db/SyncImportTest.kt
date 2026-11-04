package db

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncImportTest {

    @TempDir
    lateinit var dir: Path

    private fun open() = ConnectionsRepository(dir.resolve("app.db"), dir.resolve("consoles"))

    private fun profile(id: String, folderId: String? = null, password: String? = null) = ConnectionProfile(
        id = id, name = "conn-$id", folderId = folderId, dbType = DbType.SQLITE,
        database = "$id.db", user = "u", password = password,
    )

    @Test
    fun `import upserts folders connections workspaces consoles with fixed ids`() {
        open().use { repo ->
            repo.importSyncedState(
                SyncImportRequest(
                    folders = listOf(FolderRow("f1", "prod", null, 3)),
                    connections = listOf(profile("c1", "f1", "pw")),
                    connectionUpdatedAt = mapOf("c1" to 1234L),
                    workspaces = listOf(WorkspaceRecord("w1", "默认", true, 0, 99, "s1")),
                    workspaceConsoles = mapOf("w1" to listOf("s1")),
                    consoles = listOf(ConsoleRecord("s1", "c1", "脚本", "/ignored/ignored.sql")),
                ),
            )
            assertEquals(listOf("f1"), repo.listFolders().map { it.id })
            assertEquals("pw", repo.getConnection("c1")!!.password)
            assertEquals(1234L, repo.connectionUpdatedAt()["c1"])
            assertEquals(listOf("s1"), repo.listAllConsoles().map { it.id })
            assertEquals(listOf("s1"), repo.listWorkspaceConsoleIds()["w1"])
            assertEquals("w1", repo.listWorkspaces().single().id)
            assertEquals(99, repo.listWorkspaces().single().createdAt)
        }
    }

    @Test
    fun `import preserves caret closed and external flags`() {
        open().use { repo ->
            val id = repo.createConnection(profile("ignored"))
            val console = repo.createConsole(id, "n")
            repo.setConsoleCaret(console.id, 5, 7)
            repo.importSyncedState(
                SyncImportRequest(
                    connections = listOf(profile(id)),
                    consoles = listOf(console.copy(name = "renamed", filePath = "/ignored.sql")),
                ),
            )
            val after = repo.getConsole(console.id)!!
            assertEquals("renamed", after.name)
            assertEquals(5, after.caretStart)
            assertEquals(7, after.caretEnd)
            assertFalse(after.external)
            assertTrue(after.filePath.startsWith(dir.resolve("consoles").toString()))
        }
    }

    @Test
    fun `removed ids delete rows and console files`() {
        open().use { repo ->
            val id = repo.createConnection(profile("c1"))
            val console = repo.createConsole(id, "n")
            repo.writeConsoleContent(console.id, "x")
            val file = dir.resolve("consoles/${console.id}.sql")
            assertTrue(Files.exists(file))

            repo.importSyncedState(
                SyncImportRequest(
                    removedConsoleIds = setOf(console.id),
                    removedConnectionIds = setOf(id),
                ),
            )
            assertNull(repo.getConsole(console.id))
            assertNull(repo.getConnection(id))
            assertFalse(Files.exists(file))
            assertTrue(repo.listAllConsoles().isEmpty())
        }
    }

    @Test
    fun `import is idempotent`() {
        open().use { repo ->
            val req = SyncImportRequest(
                folders = listOf(FolderRow("f1", "prod")),
                connections = listOf(profile("c1")),
            )
            repo.importSyncedState(req)
            repo.importSyncedState(req)
            assertEquals(1, repo.listConnections().size)
            assertEquals(1, repo.listFolders().size)
        }
    }

    @Test
    fun `dangling folder parent and workspace last active console are nulled`() {
        open().use { repo ->
            repo.importSyncedState(
                SyncImportRequest(
                    folders = listOf(FolderRow("f1", "child", parentId = "gone")),
                    workspaces = listOf(WorkspaceRecord("w1", "ws", false, 0, 1, "gone-console")),
                ),
            )
            assertNull(repo.listFolders().single().parentId)
            assertNull(repo.listWorkspaces().single().lastActiveConsoleId)
        }
    }

    @Test
    fun `console rows referencing unknown connections are skipped`() {
        open().use { repo ->
            repo.importSyncedState(
                SyncImportRequest(
                    consoles = listOf(ConsoleRecord("s1", "gone", "orphan", "/tmp/x.sql")),
                ),
            )
            assertTrue(repo.listAllConsoles().isEmpty())
        }
    }

    @Test
    fun `workspace membership is rewritten on every import`() {
        open().use { repo ->
            val id = repo.createConnection(profile("c1"))
            val s1 = repo.createConsole(id, "a")
            val s2 = repo.createConsole(id, "b")
            val ws = repo.createWorkspace("ws", false)
            repo.addConsoleToWorkspace(ws.id, s1.id)
            repo.addConsoleToWorkspace(ws.id, s2.id)

            repo.importSyncedState(
                SyncImportRequest(
                    connections = listOf(profile(id)),
                    consoles = listOf(s1, s2),
                    workspaces = listOf(WorkspaceRecord(ws.id, "ws", false, 0, 1, s2.id)),
                    workspaceConsoles = mapOf(ws.id to listOf(s2.id)),
                ),
            )
            assertEquals(mapOf(ws.id to listOf(s2.id)), repo.listWorkspaceConsoleIds())
        }
    }
}
