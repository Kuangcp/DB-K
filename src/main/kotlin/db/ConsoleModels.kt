package db

/**
 * SQL 控制台元数据行：一个数据源（connection_id）可有多个命名控制台，
 * 每个控制台最终绑定一个 .sql 文件（filePath），编辑器内容即该文件内容。
 * [target]：执行目标库/schema（该数据源下的 schema 展示名；"" = 连接默认，不切换）。
 * [caretStart]/[caretEnd]：上次离开时编辑器光标/选区偏移（重启后恢复焦点行；0 = 从头）。
 * [closed]：已关闭（从标签条隐藏但保留行与 .sql 文件，可从数据源右键重新打开）。
 */
data class ConsoleRecord(
    val id: String,
    val connectionId: String,
    val name: String,
    val filePath: String,
    val sortOrder: Int = 0,
    val updatedAt: Long = 0,
    val target: String = "",
    val caretStart: Int = 0,
    val caretEnd: Int = 0,
    val closed: Boolean = false,
)

/**
 * 工作区行：控制台的虚拟分组。
 * [autoNamed] = 名字跟随语言（渲染时取 `Str.WorkspaceDefaultName`）；用户改名后为 false。
 * [lastActiveConsoleId]：该工作区上次激活的控制台（重启回位）。
 */
data class WorkspaceRecord(
    val id: String,
    val name: String,
    val autoNamed: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long = 0,
    val lastActiveConsoleId: String? = null,
)
