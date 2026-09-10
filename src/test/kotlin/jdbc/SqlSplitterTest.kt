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
