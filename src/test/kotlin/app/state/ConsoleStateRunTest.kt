package app.state

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
        val cs = ConnectionsState(MetaCache(dbPath))
        val state = ConsoleState(
            repository = repo,
            connectionsState = cs,
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
}
