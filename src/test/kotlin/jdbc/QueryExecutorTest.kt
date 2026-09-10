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
}
