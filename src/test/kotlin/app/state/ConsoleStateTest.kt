package app.state

import app.ui.CellKey
import app.ui.buildEditPlan
import db.ColumnCache
import db.ConnectionProfile
import db.ConnectionsRepository
import db.DbType
import db.MetaCache
import java.sql.Types
import jdbc.CellValue
import engine.model.QueryColumn
import engine.model.QueryResult
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
        workspaces = WorkspaceState(repo, loadActive = { null }, saveActive = {}),
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
    fun `close removes console from current workspace only`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val c1 = state.createConsole(pid, "控制台 1")
            val c2 = state.createConsole(pid, "控制台 2")
            assertEquals(listOf(c1.id, c2.id), state.openConsoles(pid).map { it.id })

            // 在另一个工作区也放入 c2
            val w1 = state.workspaces.workspaces.first().id
            val w2 = state.workspaces.createWorkspace("W2")
            state.workspaces.setActive(w2.id)
            state.reopenConsole(c2.id)
            assertTrue(state.workspaces.contains(w2.id, c2.id))

            // 回到默认工作区，关闭 c2：只影响当前工作区
            state.workspaces.setActive(w1)
            state.onWorkspaceSwitched()
            state.closeConsole(c2.id)
            assertFalse(state.workspaces.contains(w1, c2.id))
            assertTrue(state.workspaces.contains(w2.id, c2.id))
            assertEquals(listOf(c1.id), state.openConsoles(pid).map { it.id })
            // 成员移除不删正文
            assertTrue(Files.isRegularFile(Path.of(c2.filePath)))

            // 关闭最后一个 → 引导态
            state.closeConsole(c1.id)
            assertTrue(state.openConsoles(pid).isEmpty())
            assertNull(state.activeConsoleId)
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
    fun `activateForProfile reuses mru console of the workspace`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val c1 = state.createConsole(pid, "c1")
            val c2 = state.createConsole(pid, "c2")
            // MRU 最近的是 c2（createConsole 都 activate）
            assertEquals(c2.id, state.activateForProfile(pid)!!.id)
            state.activate(c1)
            assertEquals(c1.id, state.activateForProfile(pid)!!.id)

            // 新工作区里没有任何该源控制台 → 取 updated_at 最大者加入
            val w2 = state.workspaces.createWorkspace("W2")
            state.workspaces.setActive(w2.id)
            repo.renameConsole(c2.id, "c2-new") // c2 成为 updated_at 最大
            val picked = state.activateForProfile(pid)!!
            assertEquals(c2.id, picked.id)
            assertTrue(state.workspaces.contains(w2.id, c2.id))
        }
    }

    @Test
    fun `activateMostRecent restores last active of the active workspace`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val c1 = state.createConsole(pid, "c1")
            state.createConsole(pid, "c2")
            state.activate(c1)
            // 新实例模拟重启（同一 repo）：加载工作区/存档后回到 c1
            val restarted = newState(repo)
            val rec = restarted.activateMostRecent(listOf(pid))
            assertNotNull(rec)
            assertEquals(c1.id, rec.id)
            assertEquals(c1.id, restarted.activeConsoleId)
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

    @Test
    fun `previewCommit renders updates and leaves edits untouched`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val c = state.createConsole(pid, "c")

            val result = QueryResult(
                sql = "SELECT id, bal FROM acc",
                columns = listOf(
                    QueryColumn("id", table = "acc", baseColumn = "id", sqlType = Types.INTEGER),
                    QueryColumn("bal", table = "acc", baseColumn = "bal", sqlType = Types.INTEGER),
                ),
                rows = listOf(listOf("1", "100")),
            )
            state.runSlots[c.id] = ConsoleRunUi(
                outcomes = listOf(StatementOutcome("SELECT id, bal FROM acc", result, null)),
                activeIndex = 0,
            )
            state.editPlans[c.id] = buildEditPlan(result, primaryKeys = setOf("id"))!!
            state.setCellEdit(c.id, CellKey(0, 1), CellValue("200"))

            val preview = state.previewCommit(c, profile())
            assertEquals(1, preview.size)
            assertTrue(preview.single().startsWith("UPDATE"))
            assertTrue(preview.single().contains("200"))
            assertTrue(preview.single().contains("WHERE"))
            // 预览纯只读：暂存修改与编辑计划都不变
            assertEquals(1, state.editCount(c.id))
            assertNotNull(state.editPlanOf(c.id))
        }
    }

    @Test
    fun `previewCommit is empty without edits or edit plan`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val c = state.createConsole(pid, "c")
            // 无 run slot / 无编辑计划 → 空
            assertTrue(state.previewCommit(c, profile()).isEmpty())
        }
    }

    // ---------- N3：增行 / 删行 overlay ----------

    private fun seeded(r: ConnectionsRepository, state: ConsoleState, c: db.ConsoleRecord): QueryResult {
        val result = QueryResult(
            sql = "SELECT id, bal FROM acc",
            columns = listOf(
                QueryColumn("id", table = "acc", baseColumn = "id", sqlType = Types.INTEGER),
                QueryColumn("bal", table = "acc", baseColumn = "bal", sqlType = Types.INTEGER),
            ),
            rows = listOf(listOf("1", "100"), listOf("2", "200")),
        )
        state.runSlots[c.id] = ConsoleRunUi(
            outcomes = listOf(StatementOutcome("SELECT id, bal FROM acc", result, null)),
            activeIndex = 0,
        )
        state.editPlans[c.id] = buildEditPlan(result, primaryKeys = setOf("id"))!!
        return result
    }

    @Test
    fun `pending insert and delete flow into preview`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val c = state.createConsole(pid, "c")
            seeded(repo, state, c)

            val insId = state.addPendingInsert(c.id)
            state.setPendingInsertCell(c.id, insId, 0, CellValue("3"))
            state.setPendingInsertCell(c.id, insId, 1, CellValue("300"))
            state.markRowDeleted(c.id, 1)
            assertEquals(2, state.editCount(c.id))

            val preview = state.previewCommit(c, profile())
            assertEquals(2, preview.size, preview.toString())
            assertTrue(preview[0].startsWith("DELETE"), preview[0])
            assertTrue(preview[1].startsWith("INSERT"), preview[1])
            assertTrue(preview[1].contains("300"))
        }
    }

    @Test
    fun `mark row deleted drops its cell edits and is restorable`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val c = state.createConsole(pid, "c")
            seeded(repo, state, c)

            state.setCellEdit(c.id, CellKey(1, 1), CellValue("222"))
            state.markRowDeleted(c.id, 1)
            // 同行的改格被丢弃，只剩删除标记
            assertEquals(1, state.editCount(c.id))
            assertTrue(state.editsOf(c.id).cells.isEmpty())

            // 恢复行 → overlay 变空（编辑已被删）
            state.restoreRow(c.id, 1)
            assertTrue(state.editsOf(c.id).isEmpty)
        }
    }

    @Test
    fun `blank insert produces no preview entry and can be cleared`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val c = state.createConsole(pid, "c")
            seeded(repo, state, c)

            val id = state.addPendingInsert(c.id)
            assertEquals(1, state.editCount(c.id))
            // 未填任何值 → 不生成 INSERT，预览为空
            assertTrue(state.previewCommit(c, profile()).isEmpty())

            // 填了又清 → 回到未填
            state.setPendingInsertCell(c.id, id, 1, CellValue("x"))
            assertEquals(1, state.previewCommit(c, profile()).size)
            state.clearPendingInsertCell(c.id, id, 1)
            assertTrue(state.previewCommit(c, profile()).isEmpty())

            // 移除待插入行 → overlay 清空
            state.removePendingInsert(c.id, id)
            assertTrue(state.editsOf(c.id).isEmpty)
        }
    }

    @Test
    fun `clearEdits drops insert and delete overlays`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val c = state.createConsole(pid, "c")
            seeded(repo, state, c)

            state.addPendingInsert(c.id)
            state.markRowDeleted(c.id, 0)
            assertEquals(2, state.totalEditCount())
            state.clearEdits(c.id)
            assertEquals(0, state.totalEditCount())
            assertTrue(state.editsOf(c.id).isEmpty)
        }
    }

    @Test
    fun `switchConsoleByMru cycles all open consoles in recency order`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val c1 = state.createConsole(pid, "1")
            val c2 = state.createConsole(pid, "2")
            val c3 = state.createConsole(pid, "3")
            // 每次 createConsole 都会 activate：MRU = c3, c2, c1
            assertEquals(c3.id, state.activeConsoleId)

            state.switchConsoleByMru(1)
            assertEquals(c2.id, state.activeConsoleId)
            state.switchConsoleByMru(1)
            assertEquals(c1.id, state.activeConsoleId)
            state.switchConsoleByMru(1) // 回绕到最近使用的 c3
            assertEquals(c3.id, state.activeConsoleId)
        }
    }

    @Test
    fun `switchConsoleByMru reverse wraps to the last`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val c1 = state.createConsole(pid, "1")
            state.createConsole(pid, "2")
            state.createConsole(pid, "3")
            // MRU = c3, c2, c1；反向 → 快照最后一个 c1
            state.switchConsoleByMru(-1)
            assertEquals(c1.id, state.activeConsoleId)
        }
    }

    @Test
    fun `endMruCycle restarts snapshot from current recency order`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            state.createConsole(pid, "1")
            state.createConsole(pid, "2")
            val c3 = state.createConsole(pid, "3")
            state.switchConsoleByMru(1) // c3 -> c2
            val c2 = state.openConsoles(pid).first { it.name == "2" }
            assertEquals(c2.id, state.activeConsoleId)
            state.endMruCycle() // 等价于松开 Ctrl
            state.switchConsoleByMru(1) // 新会话：MRU = c2, c3, c1 → c3
            assertEquals(c3.id, state.activeConsoleId)
        }
    }

    @Test
    fun `mru switches only within the active workspace`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            state.createConsole(pid, "1")
            state.createConsole(pid, "2")
            val c3 = state.createConsole(pid, "3")
            // 新建工作区 W2 只含 c3 → 在该区无可切换
            val w2 = state.workspaces.createWorkspace("W2")
            state.workspaces.setActive(w2.id)
            state.reopenConsole(c3.id)
            assertNull(state.switchConsoleByMru(1))
            // 回默认工作区（含三个）→ 正常循环
            val w1 = state.workspaces.workspaces.first { it.id != w2.id }.id
            state.workspaces.setActive(w1)
            state.onWorkspaceSwitched()
            assertNotNull(state.switchConsoleByMru(1))
        }
    }

    @Test
    fun `switchConsoleByMru cycles forward and backward with wraparound`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            state.createConsole(pid, "1")
            val c2 = state.createConsole(pid, "2")
            val c3 = state.createConsole(pid, "3")
            // 创建即激活，MRU = c3, c2, c1
            assertEquals(c3.id, state.activeConsoleId)
            assertEquals(c2.id, state.switchConsoleByMru(1)?.id)
            val c1 = state.openConsoles(pid).first { it.name == "1" }
            assertEquals(c1.id, state.switchConsoleByMru(1)?.id)
            assertEquals(c3.id, state.switchConsoleByMru(1)?.id) // 回绕
            state.endMruCycle()
            // 反向：新快照 MRU = c3, c1, c2 → c3 的上一个是 c2
            assertEquals(c2.id, state.switchConsoleByMru(-1)?.id)
        }
    }

    @Test
    fun `switchConsoleByMru is a no-op with a single open console`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val only = state.createConsole(pid, "1")
            assertNull(state.switchConsoleByMru(1))
            assertEquals(only.id, state.activeConsoleId)
        }
    }
}
