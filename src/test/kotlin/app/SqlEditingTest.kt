package app.ui

import jdbc.QueryColumn
import jdbc.QueryResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SqlEditingTest {

    @Test
    fun `sqlCompletionWord extracts identifier prefix`() {
        val w = sqlCompletionWord("SELECT * FROM acc\nWHERE id = 1", 17)
        assertEquals("acc", w!!.text)
        assertEquals(14, w.start)
        assertEquals(17, w.end)
    }

    @Test
    fun `sqlCompletionWord returns null in string comment or number`() {
        assertNull(sqlCompletionWord("SELECT 'ab' x", 9)) // 引号内
        assertNull(sqlCompletionWord("SELECT -- ab\nc", 11)) // 行注释内
        assertNull(sqlCompletionWord("SELECT 1", 8)) // 纯数字
        assertNull(sqlCompletionWord("SELECT *", 0)) // caret 在最前
    }

    @Test
    fun `completionCandidates prefers identifiers then keywords`() {
        assertEquals(
            listOf("account", "accounts"),
            completionCandidates("acc", listOf("account", "accounts", "address", "acc")),
        )
        assertEquals("SELECT", completionCandidates("sele", emptyList()).first())
        // 长度不短于前缀的不出现
        assertEquals(emptyList(), completionCandidates("acc", listOf("acc")))
    }

    @Test
    fun `transposeResult swaps columns to rows`() {
        val base = QueryResult(
            sql = "SELECT * FROM t",
            columns = listOf(QueryColumn("a"), QueryColumn("b")),
            rows = listOf(listOf("1", "x"), listOf("2", "y")),
        )
        val tr = transposeResult(base)
        assertEquals(listOf("列名", "行 1", "行 2"), tr.columns.map { it.name })
        assertEquals(
            listOf(listOf("a", "1", "2"), listOf("b", "x", "y")),
            tr.rows,
        )
    }

    @Test
    fun `rowToInsertSql quotes values and writes null`() {
        val ins = rowToInsertSql(
            "SELECT * FROM users WHERE id = 1",
            listOf("id", "name", "note"),
            listOf("1", "O'Reilly", null),
        )
        assertEquals("INSERT INTO users (id, name, note) VALUES ('1', 'O''Reilly', NULL);", ins)
    }

    @Test
    fun `rowToInsertSql returns null when table unknown`() {
        assertNull(rowToInsertSql("SELECT a FROM (SELECT 1 AS a) sub", listOf("a"), listOf("1")))
    }

    @Test
    fun `rowToInsertSql quotes non-simple column names`() {
        val ins = rowToInsertSql(
            "SELECT * FROM users",
            listOf("first name", "select"),
            listOf("a", "b"),
        )
        assertEquals(
            "INSERT INTO users (\"first name\", select) VALUES ('a', 'b');",
            ins,
        )
    }

    @Test
    fun `extractTableName handles from into update and quotes`() {
        assertEquals("users", extractTableName("SELECT * FROM users WHERE 1=1"))
        assertEquals("t", extractTableName("INSERT INTO t (a) VALUES (1)"))
        assertEquals("t", extractTableName("UPDATE t SET x = 1"))
        assertEquals("\"PUBLIC\".\"account\"", extractTableName("SELECT * FROM \"PUBLIC\".\"account\" WHERE 1=1"))
        assertNull(extractTableName("SELECT a FROM (SELECT 1 AS a) sub"))
    }
}
