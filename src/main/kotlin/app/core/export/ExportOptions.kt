package app.core.export

/**
 * 导出参数。
 * @property tableName SQL INSERT 的目标表（可含 schema 限定，如 `public.users`）。
 * @property batchSize SQL INSERT 每条语句合并的值组数（1 = 每行一条 INSERT）。
 * @property prettyJson JSON 是否换行缩进（便于阅读；大文件可关掉省体积）。
 */
data class ExportOptions(
    val tableName: String = "exported_data",
    val batchSize: Int = 100,
    val prettyJson: Boolean = true,
)
