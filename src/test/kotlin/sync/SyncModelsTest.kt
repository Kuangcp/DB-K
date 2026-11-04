package sync

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncModelsTest {

    private val json = Json { prettyPrint = true; encodeDefaults = false; ignoreUnknownKeys = true }

    @Test
    fun `snapshot isEmpty only cares about connections and consoles`() {
        assertTrue(SyncSnapshot().isEmpty())
        assertTrue(SyncSnapshot(folders = listOf(FolderSync("f1", "prod"))).isEmpty())
        assertFalse(SyncSnapshot(connections = listOf(ConnectionSync(id = "c1", name = "n", dbType = "SQLITE"))).isEmpty())
        assertFalse(
            SyncSnapshot(
                consoles = listOf(ConsoleSync(ConsoleMetaSync(id = "s1", connectionId = "c1", name = "n"), "")),
            ).isEmpty(),
        )
    }

    @Test
    fun `connection writes id and non-default format version, omits nulls`() {
        val text = json.encodeToString(
            ConnectionSync.serializer(),
            ConnectionSync(formatVersion = 2, id = "c1", name = "prod", dbType = "POSTGRES", host = "10.0.0.1", port = 5432),
        )
        // 缺省（=1）不写出：读到没有 formatVersion 的文件即视为 v1。
        assertContains(text, "\"formatVersion\": 2")
        assertContains(text, "\"id\": \"c1\"")
        assertContains(text, "\"host\": \"10.0.0.1\"")
        assertFalse(text.contains("\"password\""))
    }

    @Test
    fun `connection roundtrips through json`() {
        val original = ConnectionSync(
            id = "c9", name = "prod", dbType = "MYSQL", folderId = "f1", host = "h", port = 3306,
            database = "db", user = "u", password = "pw", extraParams = "x=1", color = "#5586E4",
            sortOrder = 4, keySeparator = ":", displayTz = "SOURCE", queryTimeoutSeconds = 0, updatedAt = 42,
        )
        val back = json.decodeFromString(ConnectionSync.serializer(), json.encodeToString(ConnectionSync.serializer(), original))
        assertEquals(original, back)
    }

    @Test
    fun `workspace and console models default to empty members`() {
        assertEquals(emptyList(), WorkspaceSync(id = "w1", name = "默认").consoleIds)
        assertEquals(KeymapSync(), json.decodeFromString(KeymapSync.serializer(), "{}"))
    }

    @Test
    fun `removed ids default to empty sets`() {
        val removed = RemovedIds()
        assertTrue(removed.connections.isEmpty() && removed.consoles.isEmpty())
        assertTrue(removed.folders.isEmpty() && removed.workspaces.isEmpty())
    }
}
