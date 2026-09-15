package app.core.export

import engine.model.QueryColumn
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertEquals

class ExportSinkTest {

    private fun col(name: String, sqlType: Int = Types.VARCHAR) = QueryColumn(name = name, sqlType = sqlType)

    private fun write(sink: RowSink, columns: List<QueryColumn>, rows: List<List<String?>>): Long =
        sink.use { s ->
            s.begin(columns)
            rows.forEach { r -> s.row(r) }
            s.finish()
        }

    @Test
    fun `csv sink writes header and escapes`() {
        val w = StringWriter()
        val n = write(
            CsvSink(w),
            listOf(col("a"), col("b")),
            listOf(listOf("1", "x,y"), listOf(null, "z")),
        )
        assertEquals(2, n)
        assertEquals("a,b\n1,\"x,y\"\n,z\n", w.toString())
    }

    @Test
    fun `json sink emits array with typed scalars and unique keys`() {
        val w = StringWriter()
        val n = write(
            JsonSink(w, pretty = true),
            listOf(col("id", Types.INTEGER), col("name"), col("id", Types.INTEGER)),
            listOf(listOf("1", "a", "9"), listOf("2", null, "8")),
        )
        assertEquals(2, n)
        assertEquals(
            "[\n  {\"id\": 1, \"name\": \"a\", \"id#2\": 9},\n  {\"id\": 2, \"name\": null, \"id#2\": 8}\n]\n",
            w.toString(),
        )
    }

    @Test
    fun `sql insert sink batches rows and escapes`() {
        val w = StringWriter()
        val n = write(
            SqlInsertSink(w, tableName = "t", batchSize = 2),
            listOf(col("a", Types.INTEGER), col("b")),
            listOf(listOf("1", "x"), listOf("2", "y"), listOf("3", null)),
        )
        assertEquals(3, n)
        assertEquals(
            "INSERT INTO t (\"a\", \"b\") VALUES\n" +
                "  (1, 'x'),\n" +
                "  (2, 'y');\n" +
                "INSERT INTO t (\"a\", \"b\") VALUES\n" +
                "  (3, NULL);\n",
            w.toString(),
        )
    }

    @Test
    fun `sql insert sink honors mysql backtick quoting`() {
        val w = StringWriter()
        write(
            SqlInsertSink(w, tableName = "t", batchSize = 100, quoteIdent = { "`$it`" }),
            listOf(col("a")),
            listOf(listOf("1")),
        )
        assertEquals("INSERT INTO t (`a`) VALUES\n  ('1');\n", w.toString())
    }

    @Test
    fun `xlsx sink writes typed cells readable by third-party`() {
        val bytes = ByteArrayOutputStream()
        val n = write(
            XlsxSink(bytes, windowRows = 10),
            listOf(col("id", Types.INTEGER), col("name")),
            listOf(listOf("42", "hello"), listOf("7", null)),
        )
        assertEquals(2, n)
        XSSFWorkbook(ByteArrayInputStream(bytes.toByteArray())).use { wb ->
            val sheet = wb.getSheetAt(0)
            assertEquals("id", sheet.getRow(0).getCell(0).stringCellValue)
            assertEquals(42.0, sheet.getRow(1).getCell(0).numericCellValue)
            assertEquals("hello", sheet.getRow(1).getCell(1).stringCellValue)
            assertEquals("7", sheet.getRow(2).getCell(0).numericCellValue.toInt().toString())
        }
    }
}
