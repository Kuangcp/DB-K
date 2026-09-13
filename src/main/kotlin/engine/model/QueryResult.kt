package engine.model

import java.sql.Types

/**
 * 结果集列：展示名 + 结果编辑定位所需的来源元数据。
 * 仅当 [table]/[baseColumn] 非空（真实基表列）且同属一张基表时，该列才可能可编辑。
 * [sqlType] 为 JDBC `java.sql.Types`，用于写入时按类型绑定；[autoIncrement]/[readOnly]
 * 标记自动/只读列（不允许修改）。元数据取不到时全部降级为不可编辑（安全失败）。
 */
data class QueryColumn(
    val name: String,
    val catalog: String? = null,
    val schema: String? = null,
    val table: String? = null,
    val baseColumn: String? = null,
    val sqlType: Int = Types.OTHER,
    val nullable: Boolean = true,
    val autoIncrement: Boolean = false,
    val readOnly: Boolean = false,
)

/** 查询结果：结果集模式（列+行）或更新模式（影响行数）。行内值为字符串化的单元格。 */
data class QueryResult(
    val sql: String,
    val columns: List<QueryColumn>,
    val rows: List<List<String?>>,
    val affectedRows: Int? = null,
    val truncated: Boolean = false,
    val durationMs: Long = 0,
) {
    val isQuery: Boolean get() = affectedRows == null
    val rowCount: Int get() = rows.size
}
