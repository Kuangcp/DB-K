package jdbc

import db.ConnectionProfile
import db.DbType
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** QueryExecutor 针对 H2 嵌入式库的真实执行冒烟。 */
class QueryExecutorIntegrationTest {

    @TempDir
    lateinit var dir: Path

    private fun withH2(block: (Connection) -> Unit) {
        val profile = ConnectionProfile(
            id = "p", name = "p", dbType = DbType.H2, host = "",
            database = dir.resolve("q.db").toString(), user = "sa", password = "",
        )
        H2Dialect.openConnection(profile).use { block(it) }
    }

    @Test
    fun `select reads rows as strings`() {
        withH2 { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR)")
                st.execute("INSERT INTO t VALUES (1, 'a'), (2, 'b')")
            }
            val r = QueryExecutor.execute(conn, "SELECT id, name FROM t ORDER BY id")
            assertTrue(r.isQuery)
            assertEquals(2, r.columns.size)
            assertEquals(listOf(listOf("1", "a"), listOf("2", "b")), r.rows)
        }
    }

    @Test
    fun `update reports affected rows`() {
        withH2 { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR)")
                st.execute("INSERT INTO t VALUES (1, 'a'), (2, 'b')")
            }
            val r = QueryExecutor.execute(conn, "UPDATE t SET name = 'c' WHERE id = 1")
            assertFalse(r.isQuery)
            assertEquals(1, r.affectedRows)
        }
    }

    @Test
    fun `result truncated at MAX_ROWS`() {
        withH2 { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE big AS SELECT X AS id FROM SYSTEM_RANGE(1, 1005)")
            }
            val r = QueryExecutor.execute(conn, "SELECT * FROM big")
            assertTrue(r.isQuery)
            assertEquals(QueryExecutor.MAX_ROWS, r.rows.size)
            assertTrue(r.truncated)
        }
    }
}
