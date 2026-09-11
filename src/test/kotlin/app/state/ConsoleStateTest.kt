package app.state

import db.ColumnCache
import db.ConnectionProfile
import db.ConnectionsRepository
import db.DbType
import db.MetaCache
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ConsoleState 确定性单元测试：注入虚拟时间调度器，不碰真实网络/连接。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsoleStateTest {

    @TempDir
    lateinit var dir: Path

    private val dbPath: Path get() = dir.resolve("app.db")

    private fun repo() = ConnectionsRepository(dbPath, dir.resolve("consoles"))

    private fun profile(id: String = "p1") = ConnectionProfile(
        id = id, name = "conn", dbType = DbType.SQLITE, database = "x.db",
    )

    /** 用 TestScope 作为协程作用域、Unconfined 调度器作为 IO 调度器，保证防抖可确定性推进。 */
    private fun TestScope.newState(repo: ConnectionsRepository) = ConsoleState(
        repository = repo,
        connectionsState = ConnectionsState(MetaCache(dbPath), ColumnCache(dbPath)),
        scope = this,
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
    )

    @Test
    fun `createConsole creates activates and starts empty`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val c = state.createConsole(pid, "控制台 1")
            assertEquals(c.id, state.activeConsoleId)
            assertEquals("", state.textOf(c.id))
            assertEquals(1, state.profileConsoles(pid).size)
            assertTrue(Files.isRegularFile(Path.of(c.filePath)))
        }
    }

    @Test
    fun `setText debounces autosave`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val c = state.createConsole(pid, "c")
            state.setText(c.id, "SELECT 1;")
            assertTrue(state.isDirty(c.id))
            assertEquals("", repo.readConsoleContent(c.id)) // 防抖窗口内未落盘

            advanceTimeBy(2999)
            assertTrue(state.isDirty(c.id))

            advanceTimeBy(2)
            assertFalse(state.isDirty(c.id))
            assertEquals("SELECT 1;", repo.readConsoleContent(c.id))
        }
    }

    @Test
    fun `flushAllSync writes all dirty consoles immediately`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val c1 = state.createConsole(pid, "c1")
            val c2 = state.createConsole(pid, "c2")
            state.setText(c1.id, "A")
            state.setText(c2.id, "B")
            state.flushAllSync()
            assertEquals("A", repo.readConsoleContent(c1.id))
            assertEquals("B", repo.readConsoleContent(c2.id))
            assertFalse(state.isDirty(c1.id))
            assertFalse(state.isDirty(c2.id))
            advanceUntilIdle() // 清掉残留防抖任务
        }
    }

    @Test
    fun `activate flushes previous console before switching`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val c1 = state.createConsole(pid, "c1")
            val c2 = state.createConsole(pid, "c2")
            state.activate(c1) // 切回 c1 使其为当前
            state.setText(c1.id, "dirty text")
            assertTrue(state.isDirty(c1.id))

            state.activate(c2) // 切走 → 强制落盘
            assertFalse(state.isDirty(c1.id))
            assertEquals("dirty text", repo.readConsoleContent(c1.id))
        }
    }

    @Test
    fun `setCaret debounces and does not touch updatedAt`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val c = state.createConsole(pid, "c")
            val before = repo.getConsole(c.id)!!.updatedAt

            state.setCaret(c.id, 10, 15)
            assertEquals(10 to 15, state.caretOf(c.id)) // 内存即时
            assertEquals(0, repo.getConsole(c.id)!!.caretStart) // 防抖窗口内未落库

            advanceTimeBy(1501)
            assertEquals(10, repo.getConsole(c.id)!!.caretStart)
            assertEquals(15, repo.getConsole(c.id)!!.caretEnd)
            assertEquals(before, repo.getConsole(c.id)!!.updatedAt) // 不污染 updated_at
        }
    }

    @Test
    fun `activateForProfile prefers last active console`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val c1 = state.createConsole(pid, "c1")
            val c2 = state.createConsole(pid, "c2")
            repo.renameConsole(c2.id, "c2-new") // c2 updated_at 更新
            // 会话内最后激活的是 c2 → 优先它
            assertEquals(c2.id, state.activateForProfile(pid)!!.id)
            // 切到 c1 后，last active 变为 c1
            state.activate(c1)
            assertEquals(c1.id, state.activateForProfile(pid)!!.id)
        }
    }

    @Test
    fun `activateMostRecent picks max updatedAt across profiles`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            // 直接在仓库建控制台，让 ConsoleState 无激活状态（activeConsoleId 为 null）
            val c1 = repo.createConsole(pid, "c1")
            repo.createConsole(pid, "c2")
            repo.renameConsole(c1.id, "c1-renamed") // c1 变成最新改动
            val state = newState(repo)
            val rec = state.activateMostRecent(listOf(pid))
            assertNotNull(rec)
            assertEquals(c1.id, rec.id)
            assertEquals(c1.id, state.activeConsoleId)
        }
    }

    @Test
    fun `deleteConsole removes state and switches to sibling`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val c1 = state.createConsole(pid, "c1")
            val c2 = state.createConsole(pid, "c2")
            state.activate(c1)
            state.setText(c1.id, "x")

            state.deleteConsole(c1.id)
            assertEquals(c2.id, state.activeConsoleId)
            assertTrue(state.profileConsoles(pid).none { it.id == c1.id })
            assertFalse(state.isDirty(c1.id))
            assertEquals("", state.textOf(c1.id)) // 缓冲区被清
            assertNull(repo.getConsole(c1.id))
            assertFalse(Files.exists(Path.of(c1.filePath)))
        }
    }

    @Test
    fun `onConnectionDeleted clears in-memory state and resets active`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val c = state.createConsole(pid, "c")
            state.setText(c.id, "x")

            state.onConnectionDeleted(pid)
            assertNull(state.activeConsoleId)
            assertNull(state.consolesByConnection[pid])
            assertEquals("", state.textOf(c.id)) // 缓冲区被清
            assertFalse(state.isDirty(c.id))
        }
    }

    @Test
    fun `sessionContextSqlFor returns null when schemas not loaded`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val c = state.createConsole(pid, "c")
            state.setTarget(c.id, "main")
            // 未连接的 ConnectionsState 无 schema → 不切换
            assertNull(state.sessionContextSqlFor(state.activeConsole()!!, profile()))
        }
    }
}
