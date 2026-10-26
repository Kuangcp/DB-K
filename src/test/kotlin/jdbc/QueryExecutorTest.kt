package jdbc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueryExecutorTest {

    @Test
    fun `isQueryLike recognizes query heads with whitespace comments parens`() {
        assertTrue(QueryExecutor.isQueryLike("SELECT\n    table, count() AS n FROM system.parts"))
        assertTrue(QueryExecutor.isQueryLike("select\n\t* from t"))
        assertTrue(QueryExecutor.isQueryLike("-- 注释\nselect 1"))
        assertTrue(QueryExecutor.isQueryLike("/* c */ SELECT 1"))
        assertTrue(QueryExecutor.isQueryLike("(select 1) union all (select 2)"))
        assertTrue(QueryExecutor.isQueryLike("VALUES (1)"))
        assertTrue(QueryExecutor.isQueryLike("WITH x AS (SELECT 1) SELECT * FROM x"))
        assertTrue(QueryExecutor.isQueryLike("SHOW TABLES"))
        assertTrue(QueryExecutor.isQueryLike("explain select 1"))
        assertTrue(QueryExecutor.isQueryLike("desc users"))
        assertTrue(QueryExecutor.isQueryLike("TABLE t"))
    }

    @Test
    fun `isQueryLike rejects updates and blank input`() {
        assertFalse(QueryExecutor.isQueryLike("INSERT INTO users(name) VALUES ('x')"))
        assertFalse(QueryExecutor.isQueryLike("update users set name = 'x'"))
        assertFalse(QueryExecutor.isQueryLike("DELETE FROM users"))
        assertFalse(QueryExecutor.isQueryLike(""))
        assertFalse(QueryExecutor.isQueryLike("   "))
        assertFalse(QueryExecutor.isQueryLike("/* only comment */"))
    }

    @Test
    fun `cellToString renders cells consistently`() {
        assertNull(QueryExecutor.cellToString(null))
        assertEquals("true", QueryExecutor.cellToString(true))
        assertEquals("false", QueryExecutor.cellToString(false))
        assertEquals("abc", QueryExecutor.cellToString("abc"))
        assertEquals("42", QueryExecutor.cellToString(42))
        assertEquals("[3 bytes]", QueryExecutor.cellToString(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `isTruncatedCell detects the truncation marker`() {
        assertFalse(QueryExecutor.isTruncatedCell(null))
        assertFalse(QueryExecutor.isTruncatedCell("正常内容"))
        assertFalse(QueryExecutor.isTruncatedCell("普通文本…"))
        assertTrue(QueryExecutor.isTruncatedCell("开头" + QueryExecutor.CELL_TRUNCATION_MARKER))
    }

    @Test
    fun `execute passes configured timeout to statement`() {
        val rec = TimeoutRecorder()

        QueryExecutor.execute(rec.connection(), "UPDATE t SET x = 1", timeoutSeconds = 45)
        assertEquals(45, rec.statementTimeout)

        QueryExecutor.execute(rec.connection(), "UPDATE t SET x = 1", timeoutSeconds = 0)
        assertEquals(0, rec.statementTimeout)

        QueryExecutor.applyContext(rec.connection(), "USE db1", timeoutSeconds = 7)
        assertEquals(7, rec.statementTimeout)
    }

    @Test
    fun `execute keeps the documented default when no timeout is given`() {
        val rec = TimeoutRecorder()
        QueryExecutor.execute(rec.connection(), "UPDATE t SET x = 1")
        assertEquals(engine.QueryTimeout.DEFAULT_SECONDS, rec.statementTimeout)
    }

    @Test
    fun `write batch accepts a timeout and skips statements for an empty batch`() {
        val rec = TimeoutRecorder()
        assertEquals(0, RowUpdater.executeWriteBatch(rec.connection(), emptyList(), SQLiteDialect, timeoutSeconds = 45))
        assertEquals(-1, rec.preparedTimeout)
    }

    @Test
    fun `sqlstate 57014 without a local cancel is reported as a timeout`() {
        val cancelled = java.sql.SQLException("ERROR: canceling statement due to user request", "57014")
        val normalized = QueryExecutor.asTimeout(cancelled, cancelRequested = false)
        assertTrue(normalized is java.sql.SQLTimeoutException, "57014 应归一成 SQLTimeoutException: $normalized")
    }

    @Test
    fun `sqlstate 57014 after a local cancel keeps the driver message`() {
        val cancelled = java.sql.SQLException("ERROR: canceling statement due to user request", "57014")
        assertTrue(QueryExecutor.asTimeout(cancelled, cancelRequested = true) === cancelled)
    }

    @Test
    fun `non cancellation errors pass through untouched`() {
        val plain = java.sql.SQLException("relation does not exist", "42P01")
        assertTrue(QueryExecutor.asTimeout(plain, cancelRequested = false) === plain)
        // 被别的异常包住也要认出来
        val wrapped = RuntimeException(java.sql.SQLException("canceling statement due to statement timeout", "57014"))
        assertTrue(QueryExecutor.asTimeout(wrapped, cancelRequested = false) is java.sql.SQLTimeoutException)
    }
}
