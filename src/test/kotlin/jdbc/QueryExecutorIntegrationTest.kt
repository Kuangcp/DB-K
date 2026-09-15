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

    @Test
    fun `oversized cell is truncated with marker`() {
        withH2 { conn ->
            conn.createStatement().use { st -> st.execute("CREATE TABLE t (txt CLOB)") }
            val big = "x".repeat(QueryExecutor.MAX_CELL_CHARS + 5_000)
            conn.prepareStatement("INSERT INTO t VALUES (?)").use { ps ->
                ps.setString(1, big)
                ps.executeUpdate()
            }
            val cell = QueryExecutor.execute(conn, "SELECT txt FROM t").rows[0][0]
            assertTrue(QueryExecutor.isTruncatedCell(cell))
            assertTrue(cell!!.length < big.length)
        }
    }

    @Test
    fun `result memory budget stops reading before MAX_ROWS`() {
        withH2 { conn ->
            conn.createStatement().use { st -> st.execute("CREATE TABLE wide (txt CLOB)") }
            val chunk = "y".repeat(100_000)
            conn.prepareStatement("INSERT INTO wide VALUES (?)").use { ps ->
                repeat(200) {
                    ps.setString(1, chunk)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            val r = QueryExecutor.execute(conn, "SELECT txt FROM wide")
            assertTrue(r.truncated)
            assertTrue(r.rows.size in 1 until 200)
        }
    }

    @Test
    fun `binary cell reports byte count`() {
        withH2 { conn ->
            conn.createStatement().use { st -> st.execute("CREATE TABLE b (data BLOB)") }
            conn.prepareStatement("INSERT INTO b VALUES (?)").use { ps ->
                ps.setBytes(1, ByteArray(10) { it.toByte() })
                ps.executeUpdate()
            }
            assertEquals("[10 bytes]", QueryExecutor.execute(conn, "SELECT data FROM b").rows[0][0])
        }
    }
}
