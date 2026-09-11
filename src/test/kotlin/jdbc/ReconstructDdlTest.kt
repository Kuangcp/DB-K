package jdbc

import jdbc.model.ColumnMeta
import jdbc.model.SchemaMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [reconstructDdl]（方言 tableDdl 的默认/回落路径）：schema 前缀、引号、NOT NULL、缺失类型。 */
class ReconstructDdlTest {

    private val columns = listOf(
        ColumnMeta("id", "INTEGER", nullable = false, ordinal = 1),
        ColumnMeta("name", "TEXT", nullable = true, ordinal = 2),
    )

    @Test
    fun `no schema uses plain qualified name`() {
        val ddl = reconstructDdl(null, "users", columns) { "\"$it\"" }
        assertEquals(
            "CREATE TABLE \"users\" (\n  \"id\" INTEGER NOT NULL,\n  \"name\" TEXT\n);",
            ddl,
        )
    }

    @Test
    fun `schema prefix skips main and falls back to catalog`() {
        assertTrue(reconstructDdl(SchemaMeta(null, "public"), "t", columns) { it }.startsWith("CREATE TABLE public.t ("))
        assertTrue(reconstructDdl(SchemaMeta(null, "main"), "t", columns) { it }.startsWith("CREATE TABLE t ("))
        assertTrue(reconstructDdl(SchemaMeta("db1", null), "t", columns) { it }.startsWith("CREATE TABLE db1.t ("))
    }

    @Test
    fun `missing type renders question mark`() {
        val ddl = reconstructDdl(null, "t", listOf(ColumnMeta("c", null))) { it }
        assertTrue(ddl.contains("  c ?"))
    }
}
