package jdbc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqlSplitterTest {

    @Test
    fun `splits on semicolons`() {
        assertEquals(
            listOf("SELECT 1", "SELECT 2"),
            splitSqlStatements("SELECT 1; SELECT 2"),
        )
        assertEquals(listOf("SELECT 1"), splitSqlStatements("SELECT 1"))
        assertEquals(listOf("SELECT 1", "SELECT 2"), splitSqlStatements("SELECT 1;\nSELECT 2;"))
    }

    @Test
    fun `semicolon inside string or identifiers is not a separator`() {
        assertEquals(listOf("SELECT ';' FROM t"), splitSqlStatements("SELECT ';' FROM t;"))
        assertEquals(listOf("SELECT \"a;b\" FROM t"), splitSqlStatements("SELECT \"a;b\" FROM t;"))
        assertEquals(listOf("SELECT `a;b` FROM t"), splitSqlStatements("SELECT `a;b` FROM t;"))
        assertEquals(listOf("SELECT 'it''s;' AS v"), splitSqlStatements("SELECT 'it''s;' AS v;"))
    }

    @Test
    fun `semicolon inside comments is not a separator`() {
        assertEquals(listOf("SELECT 1"), splitSqlStatements("SELECT 1; -- trailing ; comment"))
        assertEquals(listOf("SELECT 1", "/* ; */ SELECT 2"), splitSqlStatements("SELECT 1; /* ; */ SELECT 2;"))
    }

    @Test
    fun `comment-only statements are ignored`() {
        assertEquals(
            listOf("SELECT 1", "SELECT 2"),
            splitSqlStatements("SELECT 1;\n/* block 注释 */\n;SELECT 2;"),
        )
        assertEquals(
            listOf("SELECT 1"),
            splitSqlStatements("SELECT 1;\n-- 末尾注释"),
        )
        // 全注释输入
        assertEquals(emptyList(), splitSqlStatements("-- 只有注释"))
        assertEquals(emptyList(), splitSqlStatements("/* block */"))
    }

    @Test
    fun `mid-batch comment attaches to following statement`() {
        assertEquals(
            listOf("SELECT 1", "-- 中间注释\nSELECT 2"),
            splitSqlStatements("SELECT 1;\n-- 中间注释\nSELECT 2;"),
        )
    }

    @Test
    fun `leading comment is kept with its statement`() {
        assertEquals(
            listOf("-- 前导注释\nSELECT 1"),
            splitSqlStatements("-- 前导注释\nSELECT 1;"),
        )
    }

    @Test
    fun `statement ranges keep offsets`() {
        val sql = "SELECT 1; SELECT 2"
        assertEquals(listOf(0..7, 10..17), splitSqlStatementRanges(sql))
        // ranges 映射回与 splitSqlStatements 完全一致的语句文本
        assertEquals(
            splitSqlStatements(sql),
            splitSqlStatementRanges(sql).map { sql.substring(it.first, it.last + 1) },
        )
    }

    @Test
    fun `statement ranges trim surrounding whitespace`() {
        val sql = "  SELECT 1 ;\n\n  SELECT 2  ;"
        val ranges = splitSqlStatementRanges(sql)
        assertEquals("SELECT 1", sql.substring(ranges[0].first, ranges[0].last + 1))
        assertEquals("SELECT 2", sql.substring(ranges[1].first, ranges[1].last + 1))
    }

    @Test
    fun `statement ranges cover multi-line and one-line many statements`() {
        val sql = "SELECT 1; SELECT 2; SELECT 3"
        assertEquals(3, splitSqlStatementRanges(sql).size)
        val multi = "SELECT a,\n  b\nFROM t; SELECT 2"
        val ranges = splitSqlStatementRanges(multi)
        assertEquals("SELECT a,\n  b\nFROM t", multi.substring(ranges[0].first, ranges[0].last + 1))
        assertEquals("SELECT 2", multi.substring(ranges[1].first, ranges[1].last + 1))
    }

    @Test
    fun `statement ranges agree with split on tricky input`() {
        val sql = "SELECT ';' FROM t; -- c;\n/* x; */ SELECT 2;"
        assertEquals(
            splitSqlStatements(sql),
            splitSqlStatementRanges(sql).map { sql.substring(it.first, it.last + 1) },
        )
    }

    @Test
    fun `statement ranges empty for blank or comment-only`() {
        assertEquals(emptyList(), splitSqlStatementRanges(""))
        assertEquals(emptyList(), splitSqlStatementRanges("   \n\t"))
        assertEquals(emptyList(), splitSqlStatementRanges("-- only comment"))
        assertEquals(emptyList(), splitSqlStatementRanges("/* block */"))
    }

    @Test
    fun `isCommentOnlySql detects comment-only content`() {
        assertTrue("-- foo".isCommentOnlySql())
        assertTrue("/* foo */".isCommentOnlySql())
        assertTrue("-- foo\n/* bar */".isCommentOnlySql())
        assertTrue("   \n\t ".isCommentOnlySql())
        assertFalse("SELECT 1".isCommentOnlySql())
        assertFalse("-- foo\nSELECT 1".isCommentOnlySql())
        assertFalse("'a string'".isCommentOnlySql())
        assertFalse("\"ident\"".isCommentOnlySql())
    }
}
