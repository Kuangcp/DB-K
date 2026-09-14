package app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlFormatterTest {

    /** 格式化只允许改变空白与关键字大小写：词元签名必须一致（语义不变的核心断言）。 */
    private fun assertSameTokens(sql: String) {
        val before = formatTokenSignature(tokenizeForFormat(sql))
        val formatted = formatSql(sql)
        val after = formatTokenSignature(tokenizeForFormat(formatted))
        assertEquals(before, after, "token signature changed for:\n$sql\n--- formatted:\n$formatted")
    }

    private val samples = listOf(
        "select id, name from users where id = 1",
        "select a,b,c from t where x=1 and y=2 or z<>3",
        "SELECT u.id, count(*) AS n FROM users u LEFT JOIN orders o ON o.uid = u.id GROUP BY u.id HAVING count(*) > 3 ORDER BY n DESC LIMIT 10",
        "insert into t (a, b) values (1, 'x,y;z')",
        "update t set a = a + 1, b = -2 where id in (1,2,3)",
        "delete from t where created < now() - interval '1 day'",
        "select 'it''s ok', \"quoted, col\", `back``tick` from t -- trailing comment",
        "/* block\n comment */ select 1; select 2;",
        "select array[1,2,3], x::int, a->>'k' from t",
        "with cte as (select 1 as x) select x from cte union all select 2",
        "select * from (select a from t) sub",
        "select case when a is null then 0 else a end as v from t",
        "create function f() returns int as $$ begin; return 1; end; $$ language plpgsql",
    )

    @Test
    fun `formatting preserves token sequence for samples`() {
        samples.forEach { assertSameTokens(it) }
    }

    @Test
    fun `formatting is idempotent`() {
        samples.forEach { sql ->
            val once = formatSql(sql)
            assertEquals(once, formatSql(once), "not idempotent for:\n$sql")
        }
    }

    @Test
    fun `formats a simple select with clauses on their own lines`() {
        val out = formatSql("select id, name from users where id = 1 and name = 'a'")
        assertEquals(
            """
            SELECT
                id,
                name
            FROM users
            WHERE id = 1
                AND name = 'a'
            """.trimIndent() + "\n",
            out,
        )
    }

    @Test
    fun `keyword case lower and indent width respected`() {
        val out = formatSql(
            "SELECT a, b FROM t",
            SqlFormatOptions(indentWidth = 2, keywordCase = KeywordCase.LOWER),
        )
        assertEquals("select\n  a,\n  b\nfrom t\n", out)
    }

    @Test
    fun `comments and string semicolons are not split`() {
        assertSameTokens("select '--not comment' from t")
        assertSameTokens("select 'a;b' from t")
        val out = formatSql("select 1 -- note\nfrom t")
        assertTrue(out.contains("-- note"), out)
        assertTrue(out.contains("FROM t"), out)
    }

    @Test
    fun `operators and unary minus keep meaning`() {
        val out = formatSql("select -1, a-b, a*b, x::int from t")
        assertTrue(out.contains("-1"), out)
        assertTrue(out.contains("a - b"), out)
        assertTrue(out.contains("a * b"), out)
        assertTrue(out.contains("x::int"), out)
    }

    @Test
    fun `dollar quoted body is preserved verbatim`() {
        val d = "\$tag\$"
        val sql = "select f($d a; b $d) from t"
        val out = formatSql(sql)
        assertTrue(out.contains("$d a; b $d"), out)
    }

    @Test
    fun `empty input yields empty output`() {
        assertEquals("", formatSql("   \n  "))
    }
}
