package app.ui

import jdbc.CellValue
import jdbc.ColumnValue
import engine.model.QueryColumn
import engine.model.QueryResult
import jdbc.UpdatePlan
import jdbc.isBinarySqlType
import engine.model.SchemaMeta

/**
 * 查询结果可编辑性的纯逻辑（无 compose 依赖，便于单测）。对应 `doc/EDITABLE_RESULT.md` §2。
 *
 * 判定完全基于驱动上报的结果列元数据（[QueryColumn.table]/[baseColumn]）+ 目标表主键：
 * 只有"来源明确是同一张基表、且主键列都在结果里"时才给出 [EditPlan]；否则返回 null（只读）。
 */

/** 结果单元格的**原始**（非转置）坐标：行下标 / 列下标。 */
data class CellKey(val row: Int, val col: Int)

/** 一个可编辑的结果列：结果列下标 → 基表真实列 + 目标 JDBC 类型。 */
data class EditableColumn(val index: Int, val baseColumn: String, val sqlType: Int)

/**
 * 结果集编辑计划：把结果列映射回一张基表的真实列，并给出定位键列。
 * [keyColumns] 为结果列下标，可能包含只读列（如自增主键——不可改但可用于 WHERE）。
 */
data class EditPlan(
    val catalog: String?,
    val schema: String?,
    val table: String,
    val columns: List<EditableColumn>,
    val keyColumns: List<Int>,
) {
    val schemaMeta: SchemaMeta get() = SchemaMeta(catalog, schema)

    fun columnAt(index: Int): EditableColumn? = columns.firstOrNull { it.index == index }
}

private data class BaseRef(
    val index: Int,
    val query: QueryColumn,
    val table: String,
    val column: String,
)

private fun tableIdentity(table: String, schema: String?, catalog: String?): String =
    "${catalog.orEmpty().lowercase()}\u0000${schema.orEmpty().lowercase()}\u0000${table.lowercase()}"

/**
 * 从结果元数据构建编辑计划；不可编辑时返回 null。
 *
 * @param primaryKeys 目标表主键列名（大小写不敏感；空集 = 无主键 = 只读）。
 * @param knownColumns 目标表已知列名（来自 `ColumnCatalog`）；非 null 时二次校验结果列确为
 *   该表真实列（挡住驱动把表达式/别名当列名上报）。
 * @param isBaseTable 来源对象是否为基表（视图/物化视图一律只读）。
 */
fun buildEditPlan(
    result: QueryResult,
    primaryKeys: Set<String>,
    knownColumns: Set<String>? = null,
    isBaseTable: Boolean = true,
): EditPlan? {
    if (!result.isQuery || result.columns.isEmpty()) return null
    if (!isBaseTable || primaryKeys.isEmpty()) return null

    // 1) 收集所有有基表来源的列，并要求同属一张表
    val base = result.columns.withIndex().mapNotNull { (i, c) ->
        val table = c.table?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val col = c.baseColumn?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        BaseRef(i, c, table, col)
    }
    if (base.isEmpty()) return null
    if (base.map { tableIdentity(it.table, it.query.schema, it.query.catalog) }.distinct().size != 1) return null

    val ref = base.first()
    val known = knownColumns?.map { it.lowercase() }?.toSet()
    val valid = base.filter { known == null || it.column.lowercase() in known }
    if (valid.isEmpty()) return null

    // 2) 键列：主键列必须都能在结果里找到（不要求可编辑，例如自增主键）
    val keyColumns = primaryKeys.map { pk ->
        valid.firstOrNull { it.column.equals(pk, ignoreCase = true) && !isBinarySqlType(it.query.sqlType) }?.index
            ?: return null
    }

    // 3) 可编辑列：排除只读/自增/二进制
    val editable = valid
        .filter { !it.query.readOnly && !it.query.autoIncrement && !isBinarySqlType(it.query.sqlType) }
        .map { EditableColumn(it.index, it.column, it.query.sqlType) }
    if (editable.isEmpty()) return null

    return EditPlan(ref.query.catalog, ref.query.schema, ref.table, editable, keyColumns)
}

/**
 * 展示坐标 → 原始坐标；越界或落在转置后的标签列（第 0 列）返回 null。非转置为恒等映射。
 */
fun viewToOriginal(result: QueryResult, transposed: Boolean, viewRow: Int, viewCol: Int): CellKey? {
    if (!transposed) {
        return if (viewRow in result.rows.indices && viewCol in result.columns.indices) CellKey(viewRow, viewCol) else null
    }
    // 转置后：viewRow = 原列下标，viewCol-1 = 原行下标；viewCol == 0 是原列名标签
    val origRow = viewCol - 1
    val origCol = viewRow
    return if (origRow in result.rows.indices && origCol in result.columns.indices) CellKey(origRow, origCol) else null
}

/** 把本地暂存修改应用到结果，返回新的 [QueryResult]（越界坐标忽略）。 */
fun applyEdits(result: QueryResult, edits: Map<CellKey, CellValue>): QueryResult {
    if (edits.isEmpty()) return result
    val rows = result.rows.toMutableList()
    for ((key, value) in edits) {
        if (key.row !in rows.indices || key.col !in result.columns.indices) continue
        val row = rows[key.row].toMutableList()
        row[key.col] = value.raw
        rows[key.row] = row
    }
    return result.copy(rows = rows)
}

/**
 * 把暂存修改转成 UPDATE 计划（按行分组）：每行 sets = 该行改动列，keys = **原始行**的键列值。
 * WHERE 用改动前的值，保证即便用户改了主键列也能定位到原行。
 */
fun buildUpdatePlans(
    result: QueryResult,
    plan: EditPlan,
    edits: Map<CellKey, CellValue>,
): List<UpdatePlan> {
    if (edits.isEmpty()) return emptyList()
    return edits.entries.groupBy({ it.key.row }, { it.key to it.value }).mapNotNull { (rowIndex, entries) ->
        if (rowIndex !in result.rows.indices) return@mapNotNull null
        val original = result.rows[rowIndex]
        val sets = entries.mapNotNull { (key, value) ->
            val col = plan.columnAt(key.col) ?: return@mapNotNull null
            if (key.col !in original.indices) return@mapNotNull null
            ColumnValue(col.baseColumn, value, col.sqlType)
        }
        if (sets.isEmpty()) return@mapNotNull null
        val keyValues = plan.keyColumns.map { idx ->
            val col = result.columns[idx]
            val base = col.baseColumn ?: return@mapNotNull null
            ColumnValue(base, CellValue(original.getOrNull(idx)), col.sqlType)
        }
        if (keyValues.size != plan.keyColumns.size) return@mapNotNull null
        UpdatePlan(plan.schemaMeta, plan.table, sets, keyValues)
    }
}
