package app.core.export

import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertEquals

class ExportTextTest {

    @Test
    fun `csv field escapes separators and quotes`() {
        assertEquals("plain", ExportText.csvField("plain"))
        assertEquals("\"a,b\"", ExportText.csvField("a,b"))
        assertEquals("\"he \"\"hi\"\"\"", ExportText.csvField("he \"hi\""))
        assertEquals("\"l1\nl2\"", ExportText.csvField("l1\nl2"))
        assertEquals("", ExportText.csvField(null))
    }

    @Test
    fun `csv header and row composes two escaped lines`() {
        assertEquals(
            "id,\"na,me\"\n1,\"he \"\"hi\"\"\"",
            ExportText.csvHeaderAndRow(listOf("id", "na,me"), listOf("1", "he \"hi\"")),
        )
        // NULL → 空串；表头同样转义
        assertEquals("a,b\n1,", ExportText.csvHeaderAndRow(listOf("a", "b"), listOf("1", null)))
    }

    @Test
    fun `tsv field is raw with null as empty`() {
        assertEquals("plain", ExportText.tsvField("plain"))
        assertEquals("", ExportText.tsvField(null))
        // B 方案：TSV 原义，不转义 Tab / 换行 / 引号
        assertEquals("a\tb", ExportText.tsvField("a\tb"))
        assertEquals("l1\nl2", ExportText.tsvField("l1\nl2"))
        assertEquals("he \"hi\"", ExportText.tsvField("he \"hi\""))
    }

    @Test
    fun `tsv header and rows compose tab separated lines`() {
        // 单列多行（PG EXPLAIN 形态）：首行表头，随后每行一条
        assertEquals(
            "QUERY PLAN\nSeq Scan on t\n  Filter: (id > 1)",
            ExportText.tsvHeaderAndRows(
                listOf("QUERY PLAN"),
                listOf(listOf("Seq Scan on t"), listOf("  Filter: (id > 1)")),
            ),
        )
    }

    @Test
    fun `tsv header and rows handles columns nulls and empty row set`() {
        assertEquals(
            "id\tname\n1\t\n2\tbob",
            ExportText.tsvHeaderAndRows(listOf("id", "name"), listOf(listOf("1", null), listOf("2", "bob"))),
        )
        // 无数据行 → 只有表头
        assertEquals("id\tname", ExportText.tsvHeaderAndRows(listOf("id", "name"), emptyList()))
    }

    @Test
    fun `tsv header and single row`() {
        assertEquals("a\tb\n1\t", ExportText.tsvHeaderAndRow(listOf("a", "b"), listOf("1", null)))
    }

    @Test
    fun `tsv rows without header`() {
        // 多选 Ctrl+C：无表头，行内 Tab 分行
        assertEquals(
            "1\ta\n2\tb",
            ExportText.tsvRows(listOf(listOf("1", "a"), listOf("2", "b"))),
        )
        assertEquals("x", ExportText.tsvRows(listOf(listOf("x"))))
        assertEquals("", ExportText.tsvRows(emptyList()))
        // NULL → 空串
        assertEquals("1\t", ExportText.tsvRows(listOf(listOf("1", null))))
    }

    @Test
    fun `csv header and rows compose escaped multi-line block`() {
        assertEquals(
            "id,name\n1,\"a,b\"\n2,\"x\ny\"",
            ExportText.csvHeaderAndRows(
                listOf("id", "name"),
                listOf(listOf("1", "a,b"), listOf("2", "x\ny")),
            ),
        )
        assertEquals("id", ExportText.csvHeaderAndRows(listOf("id"), emptyList()))
    }

    @Test
    fun `sql literal types null numeric boolean and string`() {
        assertEquals("NULL", ExportText.sqlLiteral(null, Types.VARCHAR))
        assertEquals("42", ExportText.sqlLiteral("42", Types.INTEGER))
        assertEquals("3.14", ExportText.sqlLiteral("3.14", Types.DECIMAL))
        assertEquals("TRUE", ExportText.sqlLiteral("true", Types.BOOLEAN))
        assertEquals("'true'", ExportText.sqlLiteral("true", Types.VARCHAR))
        assertEquals("'it''s'", ExportText.sqlLiteral("it's", Types.VARCHAR))
        // 声明为数值但值非数值 → 回退字符串（不产生非法 SQL）
        assertEquals("'abc'", ExportText.sqlLiteral("abc", Types.INTEGER))
    }

    @Test
    fun `json value emits bare numbers and quotes strings`() {
        assertEquals("null", ExportText.jsonValue(null, Types.VARCHAR))
        assertEquals("7", ExportText.jsonValue("7", Types.BIGINT))
        assertEquals("\"7\"", ExportText.jsonValue("7", Types.VARCHAR))
        assertEquals("false", ExportText.jsonValue("false", Types.BOOLEAN))
        assertEquals("\"false\"", ExportText.jsonValue("false", Types.CHAR))
    }

    @Test
    fun `json string escapes control chars and backslash`() {
        assertEquals("\"a\\\"b\"", ExportText.jsonString("a\"b"))
        assertEquals("\"a\\\\b\"", ExportText.jsonString("a\\b"))
        assertEquals("\"a\\nb\\tc\"", ExportText.jsonString("a\nb\tc"))
        assertEquals("\"\\u0001\"", ExportText.jsonString("\u0001"))
    }
}
