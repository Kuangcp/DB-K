package app.ui

import engine.model.QueryResult
import jdbc.CellValue
import jdbc.ColumnValue
import jdbc.DeletePlan
import jdbc.InsertPlan
import jdbc.WriteOp

/**
 * 结果行级写操作（N3：增行 / 删行）的纯逻辑模型与计划生成，无 compose 依赖，便于单测。
 *
 * 与「改格」共用同一提交管线（`RowUpdater.executeWriteBatch`，单事务 + 影响行数=1 校验）：
 * - [ResultEdits] 是每个控制台的本地 overlay（纯内存，不落盘）；
 * - [buildWriteOps] 把 overlay 编译成按序执行的 [WriteOp]：DELETE → UPDATE → INSERT
 *   （先删可释放唯一键，再改已有行，最后插新行）。
 *
 * 覆盖编辑仍需 [EditPlan]（基表 + 行定位键）；[EditPlan] 为 null 时整表只读，增删同样禁用。
 */

/** 一个待插入的行：临时 id（会话内自增，不落盘）+ 结果列下标 → 已填值（缺席 = 未填，走库默认）。 */
data class PendingInsert(val id: Long, val values: Map<Int, CellValue> = emptyMap())

/** 某控制台结果网格的本地暂存写操作集合（单元格修改 / 插入行 / 删除行）。 */
data class ResultEdits(
    val cells: Map<CellKey, CellValue> = emptyMap(),
    val inserts: List<PendingInsert> = emptyList(),
    val deletes: Set<Int> = emptySet(),
) {
    /** 未提交「处」数（一格 / 一行 / 一删各算一处）。 */
    val count: Int get() = cells.size + inserts.size + deletes.size

    val isEmpty: Boolean get() = cells.isEmpty() && inserts.isEmpty() && deletes.isEmpty()

    /** 待插入行是否已填写至少一个可编辑列（决定能否生成 INSERT）。 */
    fun insertHasValues(plan: EditPlan, insert: PendingInsert): Boolean =
        plan.columns.any { it.index in insert.values }

    companion object {
        val EMPTY = ResultEdits()
    }
}

/**
 * 生成 DELETE 计划：按原始行下标 + 行定位键（WHERE 用原始值）。无法定位的行被忽略。
 */
fun buildDeletePlans(result: QueryResult, plan: EditPlan, rows: Set<Int>): List<DeletePlan> =
    rows.sorted().mapNotNull { rowIndex ->
        if (rowIndex !in result.rows.indices) return@mapNotNull null
        val original = result.rows[rowIndex]
        val keys = plan.keyColumns.mapNotNull { idx ->
            val col = result.columns.getOrNull(idx) ?: return@mapNotNull null
            val base = col.baseColumn ?: return@mapNotNull null
            ColumnValue(base, CellValue(original.getOrNull(idx)), col.sqlType)
        }
        if (keys.size != plan.keyColumns.size) null else DeletePlan(plan.schemaMeta, plan.table, keys)
    }

/**
 * 生成 INSERT 计划：只包含用户填写过的可编辑列（未填列交给数据库默认值/NULL）。
 * 完全未填写的待插入行被忽略（提交前由 `ConsoleState` 拦截并提示）。
 */
fun buildInsertPlans(plan: EditPlan, inserts: List<PendingInsert>): List<InsertPlan> =
    inserts.mapNotNull { insert ->
        val columns = plan.columns
            .filter { it.index in insert.values }
            .map { ColumnValue(it.baseColumn, insert.values.getValue(it.index), it.sqlType) }
        if (columns.isEmpty()) null else InsertPlan(plan.schemaMeta, plan.table, columns)
    }

/**
 * 把本地 overlay 编译成按序执行的写操作：DELETE → UPDATE → INSERT。
 * [ResultEdits.cells] 中属于待删除行的修改应已在标记删除时清除，这里不再过滤。
 */
fun buildWriteOps(result: QueryResult, plan: EditPlan, edits: ResultEdits): List<WriteOp> {
    if (edits.isEmpty) return emptyList()
    val deletes = buildDeletePlans(result, plan, edits.deletes).map { WriteOp.Delete(it) }
    val updates = buildUpdatePlans(result, plan, edits.cells).map { WriteOp.Update(it) }
    val inserts = buildInsertPlans(plan, edits.inserts).map { WriteOp.Insert(it) }
    return deletes + updates + inserts
}
