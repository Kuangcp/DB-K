package app.state

import db.ColumnCache
import db.ConnectionProfile
import db.ConnectionsRepository
import db.DbType
import db.MetaCache
import jdbc.H2Dialect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ConsoleState.run 端到端集成测试：真实 H2 嵌入式库 + 真实 ConnectionsState/MetaCache，
 * 验证执行、历史落库、错误处理、目标库切换（不依赖外部服务）。
 */
class ConsoleStateRunTest {

    @TempDir
    lateinit var dir: Path

    /** 建一个含 account 表的 H2 文件库，返回可入库的档案（id 由仓库生成）。 */
    private fun seedH2Profile(): ConnectionProfile {
        val db = dir.resolve("demo-h2").toString()
        H2Dialect.openConnection(
            ConnectionProfile(id = "seed", name = "seed", dbType = DbType.H2, host = "", database = db, user = "sa", password = ""),
        ).use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE account (id INT PRIMARY KEY, name VARCHAR)")
                st.execute("INSERT INTO account VALUES (1, 'a'), (2, 'b')")
            }
        }
        return ConnectionProfile(id = "", name = "h2", dbType = DbType.H2, host = "", database = db, user = "sa", password = "")
    }

    private fun newState(repo: ConnectionsRepository, dbPath: Path): Pair<ConnectionsState, ConsoleState> {
        val cs = ConnectionsState(MetaCache(dbPath), ColumnCache(dbPath))
        val state = ConsoleState(
            repository = repo,
            connectionsState = cs,
            workspaces = WorkspaceState(repo, loadActive = { null }, saveActive = {}),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
        return cs to state
    }

    @Test
    fun `run executes query records history and fills slot`() = runBlocking {
        val dbPath = dir.resolve("app.db")
        val repo = ConnectionsRepository(dbPath, dir.resolve("consoles"))
        repo.use {
            val pid = it.createConnection(seedH2Profile())
            val stored = it.getConnection(pid)!!
            val (_, state) = newState(it, dbPath)
            val console = state.createConsole(pid, "c")

            state.run(console, stored, "SELECT id, name FROM account ORDER BY id")

            val slot = state.runStateOf(console.id)
            assertFalse(slot.executing)
            assertNull(slot.error)
            val result = slot.result!!
            assertTrue(result.isQuery)
            assertEquals(listOf(listOf("1", "a"), listOf("2", "b")), result.rows)

            val history = it.listHistoryByProfile(pid)
            assertEquals(1, history.size)
            assertTrue(history.single().ok)
            assertEquals(2, history.single().rowCount)
        }
    }

    @Test
    fun `fetchMore appends next page until exhausted`() = runBlocking {
        val dbPath = dir.resolve("app.db")
        val repo = ConnectionsRepository(dbPath, dir.resolve("consoles"))
        repo.use {
            val pid = it.createConnection(seedH2Profile())
            val stored = it.getConnection(pid)!!
            val (_, state) = newState(it, dbPath)
            val console = state.createConsole(pid, "c")

            // 1200 行 > QueryExecutor.MAX_ROWS(1000) → 首屏截断
            state.run(console, stored, "SELECT X AS n FROM SYSTEM_RANGE(1, 1200) ORDER BY X")
            val first = state.runStateOf(console.id).result!!
            assertTrue(first.truncated)
            assertEquals(1000, first.rows.size)
            assertTrue(state.canFetchMore(console.id))

            // 取下一页（offset=1000，page=500）→ 追加剩余 200 行
            val added = state.fetchMore(console, stored).getOrThrow()
            assertEquals(200, added)
            val after = state.runStateOf(console.id).result!!
            assertEquals(1200, after.rows.size)
            assertFalse(after.truncated)
            assertFalse(state.canFetchMore(console.id))
            // 追加不替换：首尾均保留且顺序稳定
            assertEquals("1", after.rows.first().single())
            assertEquals("1200", after.rows.last().single())
        }
    }

    @Test
    fun `run records failed history on error`() = runBlocking {
        val dbPath = dir.resolve("app.db")
        val repo = ConnectionsRepository(dbPath, dir.resolve("consoles"))
        repo.use {
            val pid = it.createConnection(seedH2Profile())
            val stored = it.getConnection(pid)!!
            val (_, state) = newState(it, dbPath)
            val console = state.createConsole(pid, "c")

            state.run(console, stored, "SELECT * FROM no_such_table")

            val slot = state.runStateOf(console.id)
            assertFalse(slot.executing)
            assertNotNull(slot.error)
            assertNull(slot.result)

            val history = it.listHistoryByProfile(pid)
            assertEquals(1, history.size)
            assertFalse(history.single().ok)
            assertNotNull(history.single().errorMessage)
        }
    }

    @Test
    fun `run reports error for empty sql`() = runBlocking {
        val dbPath = dir.resolve("app.db")
        val repo = ConnectionsRepository(dbPath, dir.resolve("consoles"))
        repo.use {
            val pid = it.createConnection(seedH2Profile())
            val stored = it.getConnection(pid)!!
            val (_, state) = newState(it, dbPath)
            val console = state.createConsole(pid, "c")

            state.run(console, stored, "   ")
            assertEquals("请输入要执行的 SQL", state.runStateOf(console.id).error)
            assertTrue(it.listHistoryByProfile(pid).isEmpty()) // 空 SQL 不记历史
        }
    }

    @Test
    fun `sessionContextSqlFor resolves target against loaded schemas`() = runBlocking {
        val dbPath = dir.resolve("app.db")
        val repo = ConnectionsRepository(dbPath, dir.resolve("consoles"))
        repo.use {
            val pid = it.createConnection(seedH2Profile())
            val stored = it.getConnection(pid)!!
            val (cs, state) = newState(it, dbPath)
            cs.ensureConnectionReady(stored) // 连接 + 加载 schema

            val console = state.createConsole(pid, "c")
            state.setTarget(console.id, "PUBLIC")
            assertEquals(
                "SET SCHEMA \"PUBLIC\"",
                state.sessionContextSqlFor(state.activeConsole()!!, stored),
            )

            // 目标不存在的库 → 不切换
            state.setTarget(console.id, "NOPE")
            assertNull(state.sessionContextSqlFor(state.activeConsole()!!, stored))
        }
    }

    @Test
    fun `run executes multiple statements into multiple outcomes`() = runBlocking {
        val dbPath = dir.resolve("app.db")
        val repo = ConnectionsRepository(dbPath, dir.resolve("consoles"))
        repo.use {
            val pid = it.createConnection(seedH2Profile())
            val stored = it.getConnection(pid)!!
            val (_, state) = newState(it, dbPath)
            val console = state.createConsole(pid, "c")

            state.run(
                console, stored,
                "SELECT id, name FROM account ORDER BY id;\n-- 中间注释\nSELECT COUNT(*) AS n FROM account;",
            )

            val slot = state.runStateOf(console.id)
            assertFalse(slot.executing)
            assertEquals(2, slot.outcomes.size) // 注释片段被忽略
            assertTrue(slot.outcomes[0].isQuery)
            assertEquals(listOf(listOf("1", "a"), listOf("2", "b")), slot.outcomes[0].result!!.rows)
            assertEquals(listOf(listOf("2")), slot.outcomes[1].result!!.rows)
            assertEquals(0, slot.activeIndex) // 全部成功时展示第一条

            // 每条语句独立写历史
            assertEquals(2, it.listHistoryByProfile(pid).size)
        }
    }

    @Test
    fun `run stops at first failing statement`() = runBlocking {
        val dbPath = dir.resolve("app.db")
        val repo = ConnectionsRepository(dbPath, dir.resolve("consoles"))
        repo.use {
            val pid = it.createConnection(seedH2Profile())
            val stored = it.getConnection(pid)!!
            val (_, state) = newState(it, dbPath)
            val console = state.createConsole(pid, "c")

            state.run(
                console, stored,
                "SELECT 1;\nSELECT * FROM no_such_table;\nSELECT 2;",
            )

            val slot = state.runStateOf(console.id)
            assertFalse(slot.executing)
            assertEquals(2, slot.outcomes.size) // 第一条成功 + 第二条失败即停
            assertTrue(slot.outcomes[0].ok)
            assertFalse(slot.outcomes[1].ok)
            assertNotNull(slot.outcomes[1].error)
            assertEquals(1, slot.activeIndex) // 自动跳到出错语句

            val history = it.listHistoryByProfile(pid)
            assertEquals(2, history.size) // 每条执行过的语句各一条
            assertFalse(history.first().ok) // 最新的是失败那条
            assertTrue(history.last().ok)
        }
    }
}
