package app.core.export

import engine.model.QueryColumn
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.xssf.streaming.SXSSFSheet
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import java.io.OutputStream
import java.math.BigDecimal

/**
 * Excel (.xlsx) 流式写出：POI `SXSSFWorkbook` 只保留 [windowRows] 行在内存，其余溢写到临时文件，
 * 百万行导出堆占用仍为常量级；[finish] 后 [SXSSFWorkbook.dispose] 清理临时文件。
 *
 * 数值列写数值单元格（Excel 本身以 double 存储，超过 15 位有效数字的整型回退为文本以保精度）。
 */
class XlsxSink(
    private val out: OutputStream,
    private val windowRows: Int = 100,
) : RowSink {

    private var workbook: SXSSFWorkbook? = null
    private var sheet: SXSSFSheet? = null
    private var columns: List<QueryColumn> = emptyList()
    private var headerStyle: CellStyle? = null
    private var rowIndex = 0
    private var count = 0L
    private var disposed = false

    override fun begin(columns: List<QueryColumn>) {
        this.columns = columns
        val wb = SXSSFWorkbook(windowRows)
        wb.setCompressTempFiles(true)
        workbook = wb
        val sh = wb.createSheet("result")
        // 冻结首行，滚动时表头常驻
        sh.createFreezePane(0, 1)
        headerStyle = wb.createCellStyle().apply {
            val font = wb.createFont().apply { bold = true }
            setFont(font)
        }
        sheet = sh
        val header = sh.createRow(rowIndex++)
        columns.forEachIndexed { i, column ->
            header.createCell(i).apply {
                setCellValue(column.name)
                headerStyle?.let { cellStyle = it }
            }
        }
    }

    override fun row(values: List<String?>) {
        val sh = sheet ?: return
        val row = sh.createRow(rowIndex++)
        values.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val cell = row.createCell(i)
            val numeric = if (i < columns.size && ExportText.isNumericType(columns[i].sqlType)) excelNumber(v) else null
            if (numeric != null) cell.setCellValue(numeric.toDouble()) else cell.setCellValue(v)
        }
        count++
    }

    override fun finish(): Long {
        val wb = workbook ?: return count
        wb.write(out)
        out.flush()
        dispose(wb)
        return count
    }

    override fun close() {
        workbook?.let { dispose(it) }
        runCatching { out.close() }
    }

    private fun dispose(wb: SXSSFWorkbook) {
        if (disposed) return
        disposed = true
        workbook = null
        sheet = null
        // SXSSFWorkbook.close() 关闭底层 XSSFWorkbook 并删除滑动窗口的临时文件（不关 OutputStream）
        runCatching { wb.close() }
    }

    /** 可安全写入 Excel 数值单元格的数值；精度 > 15 位或不可解析 → null（回退文本保精度）。 */
    private fun excelNumber(v: String): BigDecimal? {
        val bd = v.trim().toBigDecimalOrNull() ?: return null
        return if (bd.precision() > 15) null else bd
    }
}
