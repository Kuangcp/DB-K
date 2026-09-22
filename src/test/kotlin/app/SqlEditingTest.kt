package app.ui

import engine.model.QueryColumn
import engine.model.QueryResult
import engine.model.SchemaMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    // ---------- 列补全上下文 ----------

    @Test
    fun `statementRangeAt isolates caret statement ignoring separators in literals`() {
        val sql = "SELECT 1; -- not;a;sep\nSELECT a FROM t; SELECT 2"
        val caret = sql.indexOf("FROM t") + 3
        val range = statementRangeAt(sql, caret)!!
        val slice = sql.substring(range.first, range.last + 1)
        check(slice.contains("SELECT a FROM t"))
        check(!slice.contains("SELECT 1"))
        // caret 落在末尾分号之后（后续仅空白）→ 无语句
        assertNull(statementRangeAt("SELECT 1;   ", 11))
        // 末尾仍有语句时正常返回
        val last = statementRangeAt(sql, sql.length)!!
        assertEquals("SELECT 2", sql.substring(last.first, last.last + 1).trim())
    }

    @Test
    fun `parseTableRefs reads from join aliases and comma lists`() {
        val scope = parseTableRefs("SELECT u.id FROM public.users u JOIN orders AS o ON o.uid = u.id")
        assertEquals(2, scope.tables.size)
        assertEquals("users", scope.tables[0].table)
        assertEquals("public", scope.tables[0].schema)
        assertEquals("u", scope.tables[0].alias)
        assertEquals("orders", scope.tables[1].table)
        assertEquals("o", scope.tables[1].alias)
        check(!scope.complex)

        val comma = parseTableRefs("SELECT * FROM a, b")
        assertEquals(listOf("a", "b"), comma.tables.map { it.table })
        assertNull(comma.tables[0].alias)
    }

    @Test
    fun `parseTableRefs handles derived tables scoping and string literals`() {
        // 有别名派生表：记录为 derived，不再整体置 complex
        val derived = parseTableRefs("SELECT * FROM (SELECT 1 AS a) s")
        check(!derived.complex)
        assertEquals(listOf("s"), derived.tables.map { it.table })
        check(derived.tables.single().derived)
        // 无别名派生表：无法引用 → complex
        check(parseTableRefs("SELECT * FROM (SELECT 1)").complex)
        // 内层子查询的 FROM 不计入（只看深度 0）
        val nested = parseTableRefs("SELECT (SELECT max(x) FROM t2) AS m FROM t1")
        assertEquals(listOf("t1"), nested.tables.map { it.table })
        // 字符串字面量里的 from 不应被当作表
        val scope = parseTableRefs("SELECT 'from x' AS v FROM real_t")
        assertEquals(listOf("real_t"), scope.tables.map { it.table })
    }

    @Test
    fun `parseTableRefs reads cte names and explicit columns`() {
        val scope = parseTableRefs("WITH c(a, b) AS (SELECT 1, 2) SELECT c.a FROM c")
        assertEquals(listOf("a", "b"), scope.cteColumns["c"])
        check(scope.tables.single().cte)
        assertEquals("c", scope.tables.single().table)
        // 无显式列的 CTE 仍被识别（列未知）
        val plain = parseTableRefs("WITH t AS (SELECT 1 AS x) SELECT * FROM t")
        check(plain.tables.single().cte)
        assertEquals(emptyList(), plain.cteColumns["t"])
    }

    @Test
    fun `selectStarBeforeCaret detects select list star only`() {
        assertEquals(7, selectStarBeforeCaret("SELECT *", 8))
        assertEquals(10, selectStarBeforeCaret("SELECT a, *", 11))
        assertEquals(16, selectStarBeforeCaret("SELECT DISTINCT *", 17))
        assertNull(selectStarBeforeCaret("SELECT count(*", 14))
        assertNull(selectStarBeforeCaret("SELECT a *", 9)) // 乘号
        assertNull(selectStarBeforeCaret("SELECT *", 7)) // caret 不在星号后
    }

    @Test
    fun `completionItems ranks functions before tables and carries insertText`() {
        val items = completionItems(
            word = "",
            expands = listOf(CompletionItem("*", "展开", CompletionKind.EXPAND, insertText = "id, name")),
            columns = listOf(CompletionItem("id", "INTEGER", CompletionKind.COLUMN)),
            functions = listOf(CompletionItem("count", "函数", CompletionKind.FUNCTION)),
            objects = listOf(CompletionItem("users", "public", CompletionKind.TABLE)),
            includeKeywords = false,
        )
        assertEquals(listOf("*", "id", "count", "users"), items.map { it.text })
        assertEquals("id, name", items.first().insertText)
    }

    @Test
    fun `buildPreparedScope returns statement and caret offset`() {
        val sql = "SELECT 1; SELECT a FROM t"
        val p = buildPreparedScope(sql, sql.length)!!
        assertEquals("SELECT a FROM t", p.stmt.trim())
        assertEquals(listOf("t"), p.scope.tables.map { it.table })
        assertEquals(p.stmt.length, p.caretInStmt)
        assertNull(buildPreparedScope("SELECT 1;  ", 11))
    }

    @Test
    fun `sqlQualifiedPrefix detects alias and schema qualifiers`() {
        val q1 = sqlQualifiedPrefix("SELECT u. FROM users u", 9)!!
        assertEquals("u", q1.qualifier)
        assertEquals("", q1.wordText)
        val q2 = sqlQualifiedPrefix("SELECT u.na FROM users u", 11)!!
        assertEquals("u", q2.qualifier)
        assertEquals("na", q2.wordText)
        val q3 = sqlQualifiedPrefix("SELECT public.users.", 20)!!
        assertEquals("public.users", q3.qualifier)
        assertNull(sqlQualifiedPrefix("SELECT 1.5", 10)) // 数字限定符
        assertNull(sqlQualifiedPrefix("SELECT * FROM t", 10)) // 无点
    }

    @Test
    fun `resolveTableRef prefers target schema on ambiguity`() {
        val s1 = SchemaMeta(catalog = null, schema = "public")
        val s2 = SchemaMeta(catalog = null, schema = "archive")
        val known = listOf(CompletionTable("users", s1), CompletionTable("users", s2))
        val ref = TableRef(qualifier = "users", schema = null, table = "users", alias = null)
        assertEquals(s2, resolveTableRef(ref, known, listOf(s1, s2), defaultSchema = s2))
        // 唯一表名 → 直接命中
        assertEquals(s1, resolveTableRef(ref.copy(table = "orders"), listOf(CompletionTable("orders", s1)), listOf(s1), null))
        // 限定名按 schema 解析
        assertEquals(s2, resolveTableRef(ref.copy(schema = "archive"), known, listOf(s1, s2), null))
    }

    @Test
    fun `completionCandidates ranks columns first and supports qualified empty prefix`() {
        assertEquals(
            listOf("name"),
            completionCandidates("na", listOf("users"), listOf("name", "nick"), includeKeywords = false),
        )
        // 限定符后空前缀：列出该表全部列，且不出关键字/表名
        assertEquals(
            listOf("id", "name"),
            completionCandidates("", emptyList(), listOf("id", "name"), includeKeywords = false),
        )
        // 空前缀且无上下文 → 空
        assertEquals(emptyList(), completionCandidates("", emptyList(), emptyList(), includeKeywords = false))
        // 空前缀带对象（Ctrl+Space 显式唤起）→ 列出对象
        assertEquals(listOf("users"), completionCandidates("", listOf("users"), includeKeywords = false))
    }

    @Test
    fun `tableRef completeness excludes name being typed`() {
        val typing = "SELECT * FROM use"
        check(!parseTableRefs(typing).tables.single().isComplete(typing.length, typing.length))
        val done = "SELECT * FROM users WHERE x = 1"
        check(parseTableRefs(done).tables.single().isComplete(done.length, 20))
        // 表名在语句末尾但 caret 已移到 SELECT 列表 → 视为写完（可预取/补列）
        val stmt = "SELECT  FROM users"
        check(parseTableRefs(stmt).tables.single().isComplete(stmt.length, 8))
    }

    @Test
    fun `completionItems carries detail and ranks columns then aliases then tables`() {
        val items = completionItems(
            word = "",
            columns = listOf(CompletionItem("id", "INTEGER", CompletionKind.COLUMN)),
            aliases = listOf(CompletionItem("u", "users", CompletionKind.ALIAS)),
            objects = listOf(CompletionItem("users", "public", CompletionKind.TABLE)),
            includeKeywords = false,
        )
        assertEquals(listOf("id", "u", "users"), items.map { it.text })
        assertEquals("INTEGER", items.first().detail)
        assertEquals(CompletionKind.ALIAS, items[1].kind)
        // 文本去重：列优先于同名对象
        assertEquals(
            listOf(CompletionKind.COLUMN),
            completionItems(
                word = "",
                columns = listOf(CompletionItem("id", kind = CompletionKind.COLUMN)),
                objects = listOf(CompletionItem("id", kind = CompletionKind.TABLE)),
                includeKeywords = false,
            ).map { it.kind },
        )
    }

    @Test
    fun `sqlCompletionAllowed blocks strings and comments`() {
        check(sqlCompletionAllowed("SELECT a", 8))
        check(!sqlCompletionAllowed("SELECT 'ab", 10))
        check(!sqlCompletionAllowed("SELECT -- x", 11))
    }

    // ---- duplicateLineText（Ctrl+Y 复制当前行） ----

    @Test
    fun `duplicateLineText copies current line and keeps column`() {
        // 光标在 "bb" 第二个字符（索引 4）：复制后光标落在副本行同一列
        assertEquals(
            ("aa\nbb\nbb\ncc" to 7),
            duplicateLineText("aa\nbb\ncc", 4, 4),
        )
        // 最后一行（无尾随换行）
        assertEquals(
            ("aa\nbb\nbb" to 6),
            duplicateLineText("aa\nbb", 3, 3),
        )
        // 空行也能复制
        assertEquals(
            ("aa\n\n\nbb" to 4),
            duplicateLineText("aa\n\nbb", 3, 3),
        )
    }

    @Test
    fun `duplicateLineText copies selection as whole lines`() {
        // 选区横跨三段：整块行复制，光标停在副本块首
        assertEquals(
            ("aa\nbb\ncc\naa\nbb\ncc\n" to 9),
            duplicateLineText("aa\nbb\ncc\n", 1, 7),
        )
        // 选区终点正好落在行首（前一个字符是换行）→ 只复制上一行，不误带下一行
        assertEquals(
            ("aa\naa\nbb\ncc" to 3),
            duplicateLineText("aa\nbb\ncc", 0, 3),
        )
    }

    @Test
    fun `duplicateLineText returns null for empty text`() {
        assertNull(duplicateLineText("", 0, 0))
    }

    @Test
    fun `matchesQualifier accepts alias table and schema table`() {
        val ref = TableRef(qualifier = "public.users", schema = "public", table = "users", alias = "u")
        check(ref.matchesQualifier("u"))
        check(ref.matchesQualifier("USERS"))
        check(ref.matchesQualifier("public.users"))
        check(!ref.matchesQualifier("o"))
    }

    // ---- tableAtCaret（Ctrl+Q 定位光标下的表） ----

    private val publicS = SchemaMeta(catalog = null, schema = "public")
    private val archiveS = SchemaMeta(catalog = null, schema = "archive")
    private val known = listOf(
        CompletionTable("users", publicS),
        CompletionTable("orders", publicS),
        CompletionTable("users", archiveS),
    )

    @Test
    fun `tableAtCaret resolves bare table and alias`() {
        // 光标在 `users` 中间（FROM users）
        val sql = "SELECT * FROM users WHERE id = 1"
        assertEquals(TableAtCaret("users", publicS), tableAtCaret(sql, 17, known, listOf(publicS, archiveS), publicS))
        // 别名 u → 其表
        val aliased = "SELECT u.id FROM users u"
        assertEquals(TableAtCaret("users", publicS), tableAtCaret(aliased, 23, known, listOf(publicS, archiveS), publicS))
        // `u.id` 的列 id：限定符 u → users
        assertEquals(TableAtCaret("users", publicS), tableAtCaret(aliased, 10, known, listOf(publicS, archiveS), publicS))
    }

    @Test
    fun `tableAtCaret resolves schema qualified and honors default schema`() {
        val sql = "SELECT * FROM archive.users"
        assertEquals(
            TableAtCaret("users", archiveS),
            tableAtCaret(sql, 22, known, listOf(publicS, archiveS), publicS),
        )
        // 同名跨 schema：裸名优先 defaultSchema
        val bare = "SELECT * FROM users"
        assertEquals(
            TableAtCaret("users", archiveS),
            tableAtCaret(bare, 17, known, listOf(publicS, archiveS), archiveS),
        )
    }

    @Test
    fun `tableAtCaret returns null for columns keywords and cte`() {
        val sql = "SELECT name FROM orders"
        assertNull(tableAtCaret(sql, 9, known, listOf(publicS), publicS)) // name 不是表
        assertNull(tableAtCaret("SELECT * FROM t", 5, known, listOf(publicS), publicS)) // 关键字 SELECT
        val cte = "WITH c AS (SELECT 1) SELECT * FROM c"
        assertNull(tableAtCaret(cte, cte.length - 1, known, listOf(publicS), publicS)) // CTE 无 DDL
        assertNull(tableAtCaret("SELECT 'users'", 11, known, listOf(publicS), publicS)) // 字符串内
    }

    @Test
    fun `sqlHasOrderBy and pagination clause detection`() {
        assertTrue(sqlHasOrderBy("SELECT * FROM t ORDER BY id"))
        assertTrue(sqlHasOrderBy("select * from t order\nby id"))
        assertFalse(sqlHasOrderBy("SELECT * FROM t"))
        assertTrue(sqlHasPaginationClause("SELECT * FROM t LIMIT 100"))
        assertTrue(sqlHasPaginationClause("SELECT * FROM t OFFSET 5 ROWS"))
        assertTrue(sqlHasPaginationClause("SELECT TOP 10 * FROM t"))
        assertTrue(sqlHasPaginationClause("SELECT * FROM t WHERE ROWNUM <= 5"))
        assertFalse(sqlHasPaginationClause("SELECT * FROM t ORDER BY id"))
    }
}
