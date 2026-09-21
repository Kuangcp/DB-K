package app.state

import db.ConnectionProfile
import db.ConnectionsRepository
import db.DbType
import i18n.I18n
import i18n.Str
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** WorkspaceState 单元测试：真实 SQLite（临时目录），不碰网络/Compose。 */
class WorkspaceStateTest {

    @TempDir
    lateinit var dir: Path

    private fun open() = ConnectionsRepository(dir.resolve("app.db"), dir.resolve("consoles"))

    private fun profile() = ConnectionProfile(id = "p1", name = "conn", dbType = DbType.SQLITE, database = "x.db")

    /** 会话内保存的「激活工作区」；注入内存实现，避免污染真实 <dataDir>/app.properties。 */
    private var savedActive: String? = null

    private fun newWs(repo: ConnectionsRepository) =
        WorkspaceState(repo, loadActive = { savedActive }, saveActive = { savedActive = it })

    @Test
    fun `ensureActive creates Default auto named workspace when none`() {
        open().use { repo ->
            val wsState = newWs(repo)
            assertNull(wsState.activeWorkspace())
            val ws = wsState.ensureActive()
            assertTrue(ws.autoNamed)
            assertEquals(I18n.t(Str.WorkspaceDefaultName), ws.name)
            assertEquals(ws.id, wsState.activeWorkspaceId)
            assertEquals(1, wsState.workspaces.size)
        }
    }

    @Test
    fun `create rename delete and active fallback`() {
        open().use { repo ->
            val wsState = newWs(repo)
            val w1 = wsState.createWorkspace("W1")
            val w2 = wsState.createWorkspace(null)
            assertFalse(w1.autoNamed)
            assertTrue(w2.autoNamed)
            wsState.setActive(w1.id)
            assertEquals(w1.id, wsState.activeWorkspaceId)

            wsState.renameWorkspace(w2.id, "Renamed")
            assertFalse(wsState.workspaces.first { it.id == w2.id }.autoNamed)
            assertEquals("Renamed", wsState.workspaces.first { it.id == w2.id }.name)

            assertTrue(wsState.deleteWorkspace(w1.id))
            assertNull(wsState.activeWorkspaceId)
            assertEquals(listOf(w2.id), wsState.workspaces.map { it.id })
        }
    }

    @Test
    fun `membership is idempotent and removable everywhere`() {
        open().use { repo ->
            val pid = repo.createConnection(profile())
            val c1 = repo.createConsole(pid, "c1")
            val c2 = repo.createConsole(pid, "c2")
            val wsState = newWs(repo)
            val w1 = wsState.createWorkspace("W1")
            val w2 = wsState.createWorkspace("W2")

            assertTrue(wsState.addMember(w1.id, c1.id))
            assertFalse(wsState.addMember(w1.id, c1.id))
            wsState.addMember(w2.id, c1.id)
            wsState.addMember(w1.id, c2.id)
            assertEquals(listOf(c1.id, c2.id), wsState.memberIds(w1.id))
            assertTrue(wsState.contains(w2.id, c1.id))

            wsState.removeConsoleEverywhere(c1.id)
            assertFalse(wsState.contains(w1.id, c1.id))
            assertFalse(wsState.contains(w2.id, c1.id))
            assertEquals(listOf(c2.id), wsState.memberIds(w1.id))
        }
    }

    @Test
    fun `mru order is isolated per workspace`() {
        open().use { repo ->
            val pid = repo.createConnection(profile())
            val c1 = repo.createConsole(pid, "c1")
            val c2 = repo.createConsole(pid, "c2")
            val c3 = repo.createConsole(pid, "c3")
            val wsState = newWs(repo)
            val w1 = wsState.createWorkspace("W1")
            val w2 = wsState.createWorkspace("W2")
            wsState.addMember(w1.id, c1.id)
            wsState.addMember(w1.id, c2.id)
            wsState.addMember(w2.id, c3.id)

            // w1 里 c1 最新，然后 c2；w2 的 MRU 不影响 w1
            wsState.touch(w1.id, c1.id)
            assertEquals(listOf(c1.id, c2.id), wsState.orderedMembers(w1.id))
            wsState.touch(w2.id, c3.id)
            assertEquals(listOf(c1.id, c2.id), wsState.orderedMembers(w1.id))
            assertEquals(listOf(c3.id), wsState.orderedMembers(w2.id))
        }
    }

    @Test
    fun `last active console persists and stale active workspace falls back to first`() {
        open().use { repo ->
            val pid = repo.createConnection(profile())
            val c = repo.createConsole(pid, "c")
            val wsState = newWs(repo)
            val w1 = wsState.createWorkspace("W1")
            val w2 = wsState.createWorkspace("W2")
            wsState.addMember(w1.id, c.id)
            wsState.setActive(w1.id)
            wsState.setLastActive(w1.id, c.id)

            // 新实例（模拟重启）：activeWorkspace 从注入的存档恢复
            val store = savedActive
            val reloaded = WorkspaceState(repo, loadActive = { store }, saveActive = { savedActive = it })
            reloaded.ensureLoaded()
            assertEquals(w1.id, reloaded.activeWorkspaceId)
            assertEquals(c.id, reloaded.lastActiveConsoleOf(w1.id))

            // 存档指向已删除的工作区 → 回落第一个
            val afterGhost = WorkspaceState(repo, loadActive = { "ghost" }, saveActive = {})
            afterGhost.ensureLoaded()
            assertEquals(w1.id, afterGhost.activeWorkspaceId)
        }
    }
}
