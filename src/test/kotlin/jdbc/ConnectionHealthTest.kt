package jdbc

import db.ConnectionProfile
import db.DbType
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLRecoverableException
import java.sql.SQLSyntaxErrorException
import java.sql.SQLTransientConnectionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 连接健康策略与断连判据（见 `doc/CONNECTION_HEALTH.md`）。纯逻辑，不连库。 */
class ConnectionHealthTest {

    private fun profile(type: DbType, host: String = "db.example.com", port: Int = 0) = ConnectionProfile(
        id = "p", name = "conn", dbType = type, host = host, port = port, database = "x",
    )

    @Test
    fun `network dialects enable health check`() {
        listOf(DbType.MYSQL, DbType.MARIADB, DbType.POSTGRES, DbType.SQLSERVER)
            .forEach { assertEquals(ConnectionHealth(), DialectRegistry.forType(it).healthFor(profile(it))) }
    }

    @Test
    fun `clickhouse validates with explicit select`() {
        val health = DialectRegistry.forType(DbType.CLICKHOUSE).healthFor(profile(DbType.CLICKHOUSE))
        assertEquals("SELECT 1", health?.validationQuery)
    }

    @Test
    fun `oracle uses select from dual`() {
        val health = DialectRegistry.forType(DbType.ORACLE).healthFor(profile(DbType.ORACLE))
        assertEquals("SELECT 1 FROM DUAL", health?.validationQuery)
    }

    @Test
    fun `sqlite skips health check`() {
        assertNull(DialectRegistry.forType(DbType.SQLITE).healthFor(profile(DbType.SQLITE)))
    }

    @Test
    fun `h2 only checks over tcp`() {
        val embedded = profile(DbType.H2, host = "")
        assertNull(DialectRegistry.forType(DbType.H2).healthFor(embedded))
        assertNotNull(DialectRegistry.forType(DbType.H2).healthFor(profile(DbType.H2, host = "localhost", port = 9092)))
        // H2LOCAL 恒为嵌入式本地文件，即使 host 非空也不探活
        assertNull(DialectRegistry.forType(DbType.H2LOCAL).healthFor(profile(DbType.H2LOCAL, host = "localhost")))
    }

    @Test
    fun `connection loss detected for standard jdbc connection exceptions`() {
        assertTrue(isConnectionLost(SQLNonTransientConnectionException("closed")))
        assertTrue(isConnectionLost(SQLRecoverableException("recoverable")))
        assertTrue(isConnectionLost(SQLTransientConnectionException("transient")))
    }

    @Test
    fun `connection loss detected by sqlstate 08 and nested cause`() {
        assertTrue(isConnectionLost(SQLException("io", "08006")))
        // 外层非连接异常，但 root cause 是连接丢失
        assertTrue(isConnectionLost(SQLException("wrap", "42000", RuntimeException(SQLException("io", "08S01")))))
    }

    @Test
    fun `non connection errors are not treated as loss`() {
        assertFalse(isConnectionLost(SQLSyntaxErrorException("bad sql", "42000")))
        assertFalse(isConnectionLost(IllegalStateException("nope")))
        assertFalse(isConnectionLost(null))
    }
}
