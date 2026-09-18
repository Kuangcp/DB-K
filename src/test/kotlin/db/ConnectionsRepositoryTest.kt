package db

import engine.model.SchemaMeta
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionsRepositoryTest {

    @TempDir
    lateinit var dir: Path

    private val dbPath: Path get() = dir.resolve("app.db")

    private fun open() = ConnectionsRepository(dbPath, dir.resolve("consoles"))

    private fun newProfile(
        id: String = "c1",
        folderId: String? = null,
        password: String? = null,
    ) = ConnectionProfile(
        id = id, name = "conn", folderId = folderId, dbType = DbType.SQLITE,
        database = "x.db", user = "u", password = password,
    )

    private fun rawPassword(id: String): String? =
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { raw ->
            raw.prepareStatement("SELECT password FROM connections WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    @Test
    fun `folder crud`() {
        open().use { repo ->
            val id = repo.createFolder("f1")
            assertEquals("f1", repo.listFolders().single().name)
            repo.renameFolder(id, "f2")
            assertEquals("f2", repo.listFolders().single().name)
            repo.deleteFolder(id)
            assertTrue(repo.listFolders().isEmpty())
        }
    }

    @Test
    fun `delete folder moves its connections to root`() {
        open().use { repo ->
            val fid = repo.createFolder("f")
            val pid = repo.createConnection(newProfile(folderId = fid))
            assertEquals(1, repo.countConnectionsInFolder(fid))
            assertEquals(1, repo.deleteFolder(fid))
            assertNull(repo.getConnection(pid)!!.folderId)
        }
    }

    @Test
    fun `connection keySeparator round-trips`() {
        open().use { repo ->
            val pid = repo.createConnection(newProfile().copy(keySeparator = "::"))
            assertEquals("::", repo.getConnection(pid)!!.keySeparator)
            repo.updateConnection(repo.getConnection(pid)!!.copy(keySeparator = "/"))
            assertEquals("/", repo.getConnection(pid)!!.keySeparator)
        }
    }

    @Test
    fun `connection password encrypted on disk and round-trips`() {
        open().use { repo ->
            val pid = repo.createConnection(newProfile(password = "s3cret"))
            assertEquals("s3cret", repo.getConnection(pid)!!.password)
            val raw = rawPassword(pid)
            assertNotNull(raw)
            assertTrue(raw.startsWith("enc:v1:"))
            assertFalse(raw.contains("s3cret"))

            repo.updateConnection(newProfile(id = pid, password = "newpass"))
            assertEquals("newpass", repo.getConnection(pid)!!.password)
            assertFalse(rawPassword(pid)!!.contains("newpass"))
        }
    }

    @Test
    fun `console create rename target content delete`() {
        open().use { repo ->
            val pid = repo.createConnection(newProfile())
            val console = repo.createConsole(pid, "控制台 1")
            assertEquals("控制台 1", console.name)
            assertEquals("", console.target)
            assertEquals("", repo.readConsoleContent(console.id))

            repo.writeConsoleContent(console.id, "SELECT 1;")
            assertEquals("SELECT 1;", repo.readConsoleContent(console.id))
            repo.renameConsole(console.id, "改名")
            repo.setConsoleTarget(console.id, "main")

            val rec = repo.getConsole(console.id)!!
            assertEquals("改名", rec.name)
            assertEquals("main", rec.target)
            assertEquals("SELECT 1;", repo.readConsoleContent(console.id))

            val file = Path.of(console.filePath)
            assertTrue(java.nio.file.Files.isRegularFile(file))
            repo.deleteConsole(console.id)
            assertNull(repo.getConsole(console.id))
            assertFalse(java.nio.file.Files.exists(file))
        }
    }

    @Test
    fun `setConsoleCaret does not touch updatedAt`() {
        open().use { repo ->
            val pid = repo.createConnection(newProfile())
            val console = repo.createConsole(pid, "c")
            val before = repo.getConsole(console.id)!!.updatedAt
            repo.setConsoleCaret(console.id, 840, 846)
            val after = repo.getConsole(console.id)!!
            assertEquals(840, after.caretStart)
            assertEquals(846, after.caretEnd)
            assertEquals(before, after.updatedAt)
            // listConsoles 也带出 caret
            assertEquals(840, repo.listConsoles(pid).single().caretStart)
        }
    }

    @Test
    fun `history insert list clear`() {
        open().use { repo ->
            val pid = repo.createConnection(newProfile())
            repo.insertHistory(pid, "SELECT 1", ok = true, executedAtMs = 1000, durationMs = 5, rowCount = 42)
            repo.insertHistory(pid, "SELECT 2", ok = false, executedAtMs = 2000, durationMs = 5, rowCount = 0, errorMessage = "boom")
            val list = repo.listHistoryByProfile(pid)
            assertEquals(2, list.size)
            assertEquals("SELECT 2", list.first().sqlText) // 新在前
            assertFalse(list.first().ok)
            assertEquals("boom", list.first().errorMessage)
            assertEquals(42, list.last().rowCount)
            repo.clearHistoryForProfile(pid)
            assertTrue(repo.listHistoryByProfile(pid).isEmpty())
        }
    }

    @Test
    fun `history prunes to 200 per profile`() {
        open().use { repo ->
            val pid = repo.createConnection(newProfile())
            repeat(205) { i ->
                repo.insertHistory(pid, "SELECT $i", ok = true, executedAtMs = i.toLong(), durationMs = 0, rowCount = 0)
            }
            assertEquals(200, repo.listHistoryByProfile(pid, limit = 1000).size)
        }
    }

    @Test
    fun `deleteConnection cascades consoles meta_cache and history`() {
        open().use { repo ->
            val pid = repo.createConnection(newProfile())
            val console = repo.createConsole(pid, "c")
            repo.insertHistory(pid, "SELECT 1", ok = true, executedAtMs = 1, durationMs = 1, rowCount = 1)

            val mc = MetaCache(dbPath)
            val profile = repo.getConnection(pid)!!
            val schemas = listOf(SchemaMeta(null, "main"))
            mc.save(profile, schemas, emptyMap())
            assertNotNull(mc.load(profile))

            repo.deleteConnection(pid)

            assertNull(repo.getConnection(pid))
            assertTrue(repo.listConsoles(pid).isEmpty())
            assertTrue(repo.listHistoryByProfile(pid).isEmpty()) // 孤儿历史被清
            assertFalse(java.nio.file.Files.exists(Path.of(console.filePath))) // 控制台文件被删
            assertNull(mc.load(profile)) // meta_cache 级联清理
        }
    }

    @Test
    fun `importProfiles remaps conflicting ids without overwriting and keeps linkage`() {
        open().use { repo ->
            val folders = listOf(FolderRow("f1", "生产", parentId = null, sortOrder = 0))
            val conns = listOf(
                ConnectionProfile(
                    id = "c1", name = "pg", folderId = "f1", dbType = DbType.POSTGRES,
                    host = "h", database = "app", user = "u", password = "pw",
                ),
            )
            val first = repo.importProfiles(folders, conns)
            assertEquals(1, first.foldersAdded)
            assertEquals(1, first.connectionsAdded)
            assertEquals("pw", repo.getConnection("c1")!!.password)
            assertTrue(rawPassword("c1")!!.startsWith("enc:v1:"))

            // 改名后二次导入（相同 id）不得覆盖现有档案
            repo.updateConnection(repo.getConnection("c1")!!.copy(name = "renamed"))
            val second = repo.importProfiles(folders, conns)
            assertEquals(1, second.foldersAdded)
            assertEquals(1, second.connectionsAdded)
            assertEquals("renamed", repo.getConnection("c1")!!.name)

            val allFolders = repo.listFolders().sortedBy { it.sortOrder }
            val allConns = repo.listConnections().sortedBy { it.sortOrder }
            assertEquals(2, allFolders.size)
            assertEquals(2, allConns.size)
            assertTrue(allFolders[0].id != allFolders[1].id)
            // 第二次导入的连接 folderId 必须被重映射到第二次导入的文件夹
            assertEquals(allFolders[0].id, allConns[0].folderId)
            assertEquals(allFolders[1].id, allConns[1].folderId)
        }
    }

    @Test
    fun `importProfiles skips connections selected to skip`() {
        open().use { repo ->
            val conns = listOf(
                ConnectionProfile(id = "c1", name = "pg", dbType = DbType.POSTGRES),
                ConnectionProfile(id = "c2", name = "pg2", dbType = DbType.POSTGRES),
            )
            val summary = repo.importProfiles(emptyList(), conns, skipConnectionIds = setOf("c1"))
            assertEquals(1, summary.connectionsAdded)
            assertEquals(1, summary.connectionsSkipped)
            assertNull(repo.getConnection("c1"))
            assertNotNull(repo.getConnection("c2"))
        }
    }
}
