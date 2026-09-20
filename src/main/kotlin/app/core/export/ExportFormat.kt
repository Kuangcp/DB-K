package app.core.export
import i18n.Str

/** 结果导出格式（N8）：标签用于 UI，扩展名用于文件对话框默认名。 */
enum class ExportFormat(val labelKey: Str, val extension: String) {
    CSV(Str.ExportFormatCsv, "csv"),
    JSON(Str.ExportFormatJson, "json"),
    SQL_INSERT(Str.ExportFormatSqlInsert, "sql"),
    EXCEL(Str.ExportFormatExcel, "xlsx"),
}
