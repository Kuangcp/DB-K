package app.core.export

import db.ConnectionProfile
import db.DbType
import jdbc.LiveConnection
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.io.TempDir
import java.io.FileInputStream
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ResultExport 端到端：真实 JDBC 连接 + 游标流式 → 落盘 → 回读（H2 内嵌）。 */
class ResultExportIntegrationTest {

    @TempDir
    lateinit var dir: Path

    private fun h2(): LiveConnection {
        val profile = ConnectionProfile(
            id = "p", name = "p", dbType = DbType.H2, host = "",
            database = dir.resolve("e.db").toString(), user = "sa", password = "",
        )
        return LiveConnection(profile).also { it.open() }
    }

    private fun LiveConnection.seed(sql: String) {
        onConnection { conn -> conn.createStatement().use { it.execute(sql) } }
    }

    @Test
    fun `streamed json export writes every row`() {
        h2().use { live ->
            live.seed("CREATE TABLE t AS SELECT X AS id FROM SYSTEM_RANGE(1, 2500)")
            val file = dir.resolve("out.json").toFile()
            val n = ResultExport.writeStreamed(
                file, ExportFormat.JSON, live,
                "SELECT id FROM t ORDER BY id", null, ExportOptions(), live::quoteIdent,
            )
            assertEquals(2500, n)
            val text = file.readText()
            assertTrue(text.startsWith("[\n  {\"ID\": 1}"), "unexpected head: ${text.take(40)}")
            assertTrue(text.trimEnd().endsWith("}\n]"), "unexpected tail: ${text.takeLast(40)}")
        }
    }

    @Test
    fun `streamed xlsx export is readable back`() {
        h2().use { live ->
            live.seed("CREATE TABLE t AS SELECT X AS id, CONCAT('v', X) AS name FROM SYSTEM_RANGE(1, 500)")
            val file = dir.resolve("out.xlsx").toFile()
            val n = ResultExport.writeStreamed(
                file, ExportFormat.EXCEL, live,
                "SELECT id, name FROM t ORDER BY id", null, ExportOptions(), live::quoteIdent,
            )
            assertEquals(500, n)
            XSSFWorkbook(FileInputStream(file)).use { wb ->
                val sheet = wb.getSheetAt(0)
                assertEquals("ID", sheet.getRow(0).getCell(0).stringCellValue)
                assertEquals(1.0, sheet.getRow(1).getCell(0).numericCellValue)
                assertEquals("v1", sheet.getRow(1).getCell(1).stringCellValue)
                assertEquals(500.0, sheet.getRow(500).getCell(0).numericCellValue)
            }
        }
    }

    @Test
    fun `streamed csv export round trips through sqlite`() {
        h2().use { live ->
            live.seed("CREATE TABLE t AS SELECT X AS id, IFNULL(NULL, 'x') AS name FROM SYSTEM_RANGE(1, 10)")
            val file = dir.resolve("out.csv").toFile()
            val n = ResultExport.writeStreamed(
                file, ExportFormat.CSV, live,
                "SELECT id, name FROM t ORDER BY id", null, ExportOptions(), live::quoteIdent,
            )
            assertEquals(10, n)
            val lines = file.readLines()
            assertEquals("ID,NAME", lines.first())
            assertEquals(11, lines.size)
            assertEquals("1,x", lines[1])
        }
    }
}
