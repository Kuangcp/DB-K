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
