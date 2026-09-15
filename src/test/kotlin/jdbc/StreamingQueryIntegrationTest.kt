package jdbc

import db.ConnectionProfile
import db.DbType
import engine.model.QueryColumn
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** StreamingQuery 对嵌入式库的真实执行冒烟（N8 游标流式导出）。 */
class StreamingQueryIntegrationTest {

    @TempDir
    lateinit var dir: Path

    private fun withH2(block: (Connection) -> Unit) {
        val profile = ConnectionProfile(
            id = "p", name = "p", dbType = DbType.H2, host = "",
            database = dir.resolve("s.db").toString(), user = "sa", password = "",
        )
        H2Dialect.openConnection(profile).use { block(it) }
    }

    private fun withSqlite(block: (Connection) -> Unit) {
        val profile = ConnectionProfile(
            id = "p", name = "p", dbType = DbType.SQLITE,
            database = dir.resolve("s.sqlite").toString(),
        )
        SQLiteDialect.openConnection(profile).use { block(it) }
    }

    @Test
    fun `prefetch strategy streams all rows and preserves autocommit`() {
        withH2 { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE big AS SELECT X AS id, CONCAT('v', X) AS name FROM SYSTEM_RANGE(1, 5000)")
            }
            var meta: List<QueryColumn> = emptyList()
            val rows = mutableListOf<List<String?>>()
            val n = StreamingQuery.stream(
                conn, "SELECT id, name FROM big ORDER BY id",
                CursorStrategy.PREFETCH, 100,
                onMeta = { meta = it },
                onRow = { rows.add(it.toList()) },
            )
            assertEquals(5000, n)
            assertEquals(listOf("ID", "NAME"), meta.map { it.name })
            assertEquals(listOf("1", "v1"), rows.first())
            assertEquals(listOf("5000", "v5000"), rows.last())
            assertTrue(conn.autoCommit)
        }
    }

    @Test
    fun `transaction portal strategy restores autocommit`() {
        withH2 { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE t AS SELECT X AS id FROM SYSTEM_RANGE(1, 200)")
            }
            val n = StreamingQuery.stream(
                conn, "SELECT id FROM t ORDER BY id",
                CursorStrategy.TRANSACTION_PORTAL, 50,
                onMeta = {}, onRow = {},
            )
            assertEquals(200, n)
            // 导出结束后必须恢复 autoCommit（不留悬挂事务）
            assertTrue(conn.autoCommit)
        }
    }

    @Test
    fun `sqlite none strategy streams`() {
        withSqlite { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)")
                st.execute("INSERT INTO t VALUES (1, 'a'), (2, 'b'), (3, NULL)")
            }
            val rows = mutableListOf<List<String?>>()
            val n = StreamingQuery.stream(
                conn, "SELECT id, name FROM t ORDER BY id",
                CursorStrategy.NONE, 1000,
                onMeta = {}, onRow = { rows.add(it.toList()) },
            )
            assertEquals(3, n)
            assertEquals(listOf(listOf("1", "a"), listOf("2", "b"), listOf("3", null)), rows)
        }
    }
}
