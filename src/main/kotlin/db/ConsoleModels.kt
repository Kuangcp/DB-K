package db

/**
 * SQL 控制台元数据行：一个数据源（connection_id）可有多个命名控制台，
 * 每个控制台最终绑定一个 .sql 文件（filePath），编辑器内容即该文件内容。
 */
data class ConsoleRecord(
    val id: String,
    val connectionId: String,
    val name: String,
    val filePath: String,
    val sortOrder: Int = 0,
    val updatedAt: Long = 0,
)
