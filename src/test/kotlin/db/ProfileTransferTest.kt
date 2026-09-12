package db

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileTransferTest {

    @TempDir
    lateinit var dir: Path

    private val folders = listOf(FolderRow("f1", "生产", parentId = null, sortOrder = 0))

    private val connections = listOf(
        ConnectionProfile(
            id = "c1", name = "pg", folderId = "f1", dbType = DbType.POSTGRES,
            host = "db.local", port = 5432, database = "app", user = "u", password = "s3cret",
            extraParams = "sslmode=require",
        ),
        ConnectionProfile(id = "c2", name = "lite", dbType = DbType.SQLITE, database = "/tmp/a.db"),
    )

    @Test
    fun `export without passwords omits password field`() {
        val text = ProfileTransfer.encode(folders, connections, includePasswords = false)
        assertFalse(text.contains("s3cret"))
        assertFalse(text.contains("password"))
        assertTrue(text.contains("\"formatVersion\": 1"))

        val back = ProfileTransfer.decode(text)
        assertEquals(1, back.formatVersion)
        assertEquals(1, back.folders.size)
        assertEquals(2, back.connections.size)
        val pg = back.connections.first { it.id == "c1" }
        assertNull(pg.password)
        assertEquals("sslmode=require", pg.extraParams)
        assertEquals("f1", pg.folderId)
        assertEquals(DbType.POSTGRES, pg.dbType)
    }

    @Test
    fun `export with passwords writes plaintext and round-trips`() {
        val text = ProfileTransfer.encode(folders, connections, includePasswords = true)
        assertTrue(text.contains("s3cret"))
        assertEquals("s3cret", ProfileTransfer.decode(text).connections.first { it.id == "c1" }.password)
    }

    @Test
    fun `decode rejects newer format version`() {
        val text = ProfileTransfer.encode(folders, connections, includePasswords = false)
            .replace("\"formatVersion\": 1", "\"formatVersion\": 99")
        assertFailsWith<IllegalArgumentException> { ProfileTransfer.decode(text) }
    }

    @Test
    fun `decode skips unknown db type and drops dangling folder reference`() {
        val text = """
            {
              "formatVersion": 1,
              "app": "db-k",
              "exportedAt": 1,
              "folders": [{"id":"f1","name":"F","sortOrder":0}],
              "connections": [
                {"id":"c1","name":"x","dbType":"ORACLE","folderId":"f1"},
                {"id":"c2","name":"y","dbType":"FOO","folderId":"f1"},
                {"id":"c3","name":"z","dbType":"SQLITE","folderId":"nope"}
              ]
            }
        """.trimIndent()
        val back = ProfileTransfer.decode(text)
        assertEquals(1, back.skipped)
        assertEquals(listOf("c1", "c3"), back.connections.map { it.id })
        assertNull(back.connections.first { it.id == "c3" }.folderId)
    }

    @Test
    fun `file write and read round-trips`() {
        val file = dir.resolve("conns.json").toFile()
        ProfileTransfer.write(file, folders, connections, includePasswords = false)
        val back = ProfileTransfer.read(file)
        assertEquals(listOf("f1"), back.folders.map { it.id })
        assertEquals(listOf("c1", "c2"), back.connections.map { it.id })
    }
}
