package app.sync

import db.ConnectionProfile
import db.ConnectionsRepository
import db.DbType
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DbKSyncAdapterExportTest {

    @TempDir
    lateinit var dir: Path

    private fun repo(): ConnectionsRepository = ConnectionsRepository(dir.resolve("app.db"), dir.resolve("consoles"))

    private fun profile(id: String, folderId: String? = null, password: String? = "pw") = ConnectionProfile(
        id = id, name = "conn-$id", folderId = folderId, dbType = DbType.SQLITE,
        database = "$id.db", user = "u", password = password,
    )

    @Test
    fun `exports folders connections workspaces and console bodies`() {
        repo().use { r ->
            val fid = r.createFolder("prod")
            val cid = r.createConnection(profile("c1", fid))
            val console = r.createConsole(cid, "daily")
            r.writeConsoleContent(console.id, "select 1;")
            r.setConsoleTarget(console.id, "public")
            val ws = r.createWorkspace("默认", true)
            r.addConsoleToWorkspace(ws.id, console.id)

            val snapshot = DbKSyncAdapter(r, dir).exportSnapshot()

            assertEquals(listOf("prod"), snapshot.folders.map { it.name })
            assertEquals(fid, snapshot.folders.single().id)
            val connection = snapshot.connections.single()
            assertEquals(cid, connection.id)
            assertEquals(fid, connection.folderId)
            assertEquals("SQLITE", connection.dbType)
            assertEquals("pw", connection.password)
            assertTrue(connection.updatedAt > 0)
            assertEquals("select 1;", snapshot.consoles.single().body)
            assertEquals("public", snapshot.consoles.single().meta.target)
            assertEquals(ws.id, snapshot.workspaces.single().id)
            assertEquals(listOf(console.id), snapshot.workspaces.single().consoleIds)
        }
    }

    @Test
    fun `external consoles are excluded`() {
        repo().use { r ->
            val cid = r.createConnection(profile("c1"))
            val external = r.createExternalConsole(cid, "ext", dir.resolve("outside.sql").toString())
            r.writeConsoleContent(external.id, "select 2;")
            val snapshot = DbKSyncAdapter(r, dir).exportSnapshot()
            assertTrue(snapshot.consoles.isEmpty())
        }
    }

    @Test
    fun `missing settings files export as null and keymap as empty`() {
        repo().use { r ->
            val snapshot = DbKSyncAdapter(r, dir).exportSnapshot()
            assertNull(snapshot.liveTemplatesJson)
            assertNull(snapshot.themesJson)
            assertNotNull(snapshot.keymap)
            assertTrue(snapshot.keymap.overrides.isEmpty())
        }
    }

    @Test
    fun `settings files are exported verbatim and keymap overrides are decoded`() {
        repo().use { r ->
            Files.writeString(dir.resolve("live-templates.json"), """{"fileVersion":1,"templates":[]}""")
            Files.writeString(dir.resolve("themes.json"), """{"version":1,"themes":[]}""")
            Files.writeString(dir.resolve("keymap.properties"), "execute=CTRL+ALT+P\nviewDdl=\n")

            val snapshot = DbKSyncAdapter(r, dir).exportSnapshot()
            assertEquals("""{"fileVersion":1,"templates":[]}""", snapshot.liveTemplatesJson)
            assertEquals("""{"version":1,"themes":[]}""", snapshot.themesJson)
            assertNotNull(snapshot.keymap)
            assertEquals(mapOf("execute" to "CTRL+ALT+P", "viewDdl" to ""), snapshot.keymap.overrides)
        }
    }

    @Test
    fun `connection password is plaintext only inside the snapshot`() {
        repo().use { r ->
            val cid = r.createConnection(profile("c1", password = "topsecret"))
            val snapshot = DbKSyncAdapter(r, dir).exportSnapshot()
            assertEquals("topsecret", snapshot.connections.single().password)
            // 本地库里仍然是机器本地密文
            val raw = java.sql.DriverManager.getConnection("jdbc:sqlite:${dir.resolve("app.db")}").use { c ->
                c.prepareStatement("SELECT password FROM connections WHERE id = ?").use { ps ->
                    ps.setString(1, cid)
                    ps.executeQuery().use { rs -> rs.next(); rs.getString(1) }
                }
            }
            assertFalse(raw.contains("topsecret"))
        }
    }
}
