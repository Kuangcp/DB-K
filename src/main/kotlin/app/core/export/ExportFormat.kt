package app.core.export

/** 结果导出格式（N8）：标签用于 UI，扩展名用于文件对话框默认名。 */
enum class ExportFormat(val label: String, val extension: String) {
    CSV("CSV", "csv"),
    JSON("JSON", "json"),
    SQL_INSERT("SQL INSERT", "sql"),
    EXCEL("Excel 工作簿 (.xlsx)", "xlsx"),
}
