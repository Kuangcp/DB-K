package app.core

import engine.model.QueryColumn
import engine.model.QueryResult
import kotlin.test.Test
import kotlin.test.assertEquals

class CsvExportTest {

    private fun result(columns: List<String>, rows: List<List<String?>>) = QueryResult(
        sql = "SELECT * FROM t",
        columns = columns.map { QueryColumn(it) },
        rows = rows,
    )

    @Test
    fun `toCsv writes header and rows`() {
        val csv = CsvExport.toCsv(
            result(listOf("a", "b"), listOf(listOf("1", "2"), listOf("3", "4"))),
        )
        assertEquals("a,b\n1,2\n3,4\n", csv)
    }

    @Test
    fun `toCsv escapes comma quote and newline`() {
        val csv = CsvExport.toCsv(
            result(
                listOf("col"),
                listOf(
                    listOf("a,b"),
                    listOf("he said \"hi\""),
                    listOf("line1\nline2"),
                ),
            ),
        )
        assertEquals(
            "col\n\"a,b\"\n\"he said \"\"hi\"\"\"\n\"line1\nline2\"\n",
            csv,
        )
    }

    @Test
    fun `toCsv renders null as empty`() {
        val csv = CsvExport.toCsv(result(listOf("a", "b"), listOf(listOf(null, "x"))))
        assertEquals("a,b\n,x\n", csv)
    }

    @Test
    fun `toCsv escapes header column names`() {
        val csv = CsvExport.toCsv(result(listOf("a,b"), listOf(listOf("1"))))
        assertEquals("\"a,b\"\n1\n", csv)
    }
}
