package app.ui

import engine.model.QueryResult

/**
 * 结果网格「客户端视图层」：排序 / 筛选（N1）。
 *
 * 纯逻辑、无 compose 依赖，便于单测。**不重跑 SQL**：只在已读出的行上做视图变换，
 * 输出「视图行 → 原始行下标」映射；编辑 overlay 通过该映射回到原始坐标写回。
 */

/** 单列排序键（按 [ResultViewSpec.sorts] 的顺序逐级比较，最后用稳定排序保证确定性）。 */
data class SortSpec(val column: Int, val ascending: Boolean = true)

/** 每列筛选操作符。 */
enum class FilterOp { CONTAINS, EQUALS, NOT_EQUALS, GREATER, GREATER_EQ, LESS, LESS_EQ, REGEX }

/** 解析后的列筛选条件（比较/正则在字符串值上按数值优先比较）。 */
data class ColumnFilter(val op: FilterOp, val operand: String) {
    fun matches(value: String?): Boolean = when (op) {
        FilterOp.CONTAINS -> value != null && value.contains(operand, ignoreCase = true)
        FilterOp.EQUALS -> value?.equals(operand, ignoreCase = false) == true
        FilterOp.NOT_EQUALS -> value == null || !value.equals(operand, ignoreCase = false)
        FilterOp.GREATER -> value != null && compareCells(value, operand) > 0
        FilterOp.GREATER_EQ -> value != null && compareCells(value, operand) >= 0
        FilterOp.LESS -> value != null && compareCells(value, operand) < 0
        FilterOp.LESS_EQ -> value != null && compareCells(value, operand) <= 0
        FilterOp.REGEX -> value != null &&
            runCatching { Regex(operand, RegexOption.IGNORE_CASE) }.getOrNull()?.containsMatchIn(value) == true
    }
}

/**
 * 解析列筛选输入：
 * `=x` 精确、`!=x` 不等、`>x` `>=x` `<x` `<=x` 比较、`~x` 正则，其余为**包含**（不区分大小写）。
 * 空白输入返回 null（= 无筛选）。
 */
fun parseColumnFilter(text: String): ColumnFilter? {
    if (text.isEmpty()) return null
    return when {
        text.startsWith("!=") -> ColumnFilter(FilterOp.NOT_EQUALS, text.substring(2))
        text.startsWith(">=") -> ColumnFilter(FilterOp.GREATER_EQ, text.substring(2))
        text.startsWith("<=") -> ColumnFilter(FilterOp.LESS_EQ, text.substring(2))
        text.startsWith("=") -> ColumnFilter(FilterOp.EQUALS, text.substring(1))
        text.startsWith(">") -> ColumnFilter(FilterOp.GREATER, text.substring(1))
        text.startsWith("<") -> ColumnFilter(FilterOp.LESS, text.substring(1))
        text.startsWith("~") -> ColumnFilter(FilterOp.REGEX, text.substring(1))
        else -> ColumnFilter(FilterOp.CONTAINS, text)
    }
}

/** 单元格比较：双方均可解析为数值时按数值比，否则不区分大小写字典序（同值再区分大小写）。 */
internal fun compareCells(a: String, b: String): Int {
    val na = a.toBigDecimalOrNull()
    val nb = b.toBigDecimalOrNull()
    if (na != null && nb != null) return na.compareTo(nb)
    val ci = a.compareTo(b, ignoreCase = true)
    return if (ci != 0) ci else a.compareTo(b)
}

/** 结果视图规格（UI 持有）：排序 + 每列筛选（列 → 原始输入文本）+ 顶部快速过滤。 */
data class ResultViewSpec(
    val sorts: List<SortSpec> = emptyList(),
    /** 列下标 → 筛选输入原文（惰性解析，便于 UI 回显）。 */
    val filters: Map<Int, String> = emptyMap(),
    /** 顶部快速过滤：对**所有列**做包含匹配（不区分大小写）。 */
    val quickFilter: String = "",
) {
    val active: Boolean
        get() = sorts.isNotEmpty() || filters.values.any { it.isNotEmpty() } || quickFilter.isNotBlank()

    /** 生效的筛选条件数量（快速过滤算一条）。 */
    val filterCount: Int
        get() = filters.values.count { it.isNotEmpty() } + if (quickFilter.isBlank()) 0 else 1
}

/**
 * 表头点击的排序切换：未排序→升序；升序→降序；降序→移除。
 * [additive]（Shift/Ctrl 点击）时把新列追加为次级排序键，而不是替换。
 */
fun ResultViewSpec.toggleSort(column: Int, additive: Boolean = false): ResultViewSpec {
    val existing = sorts.firstOrNull { it.column == column }
    return when {
        // 非加性点击：若不是「唯一的排序列」，直接替换为该列升序；是唯一列则继续循环
        !additive && (sorts.size != 1 || existing == null) -> copy(sorts = listOf(SortSpec(column)))
        existing == null -> copy(sorts = sorts + SortSpec(column))
        existing.ascending -> copy(sorts = sorts.map { if (it.column == column) it.copy(ascending = false) else it })
        else -> copy(sorts = sorts.filterNot { it.column == column })
    }
}

/** 视图映射：[rowOrder] 为视图行下标 → 原始结果行下标。 */
data class ResultView(
    val rowOrder: List<Int>,
    val totalRows: Int,
) {
    val rowCount: Int get() = rowOrder.size
    val filtered: Boolean get() = rowCount != totalRows

    companion object {
        fun identity(total: Int) = ResultView((0 until total).toList(), total)
    }
}

/**
 * 由 [spec] 计算视图行顺序：先按每列筛选（AND）与快速过滤，再做多列稳定排序。
 * 无任何条件时返回恒等映射（不复制行）。
 */
fun buildResultView(result: QueryResult, spec: ResultViewSpec): ResultView {
    val total = result.rows.size
    if (!spec.active) return ResultView.identity(total)
    val colCount = result.columns.size
    val filters = spec.filters.entries.mapNotNull { (c, text) ->
        if (c !in 0 until colCount) null else parseColumnFilter(text)?.let { c to it }
    }
    val quick = spec.quickFilter.trim()
    val matches: (Int) -> Boolean = { rowIdx ->
        val row = result.rows[rowIdx]
        filters.all { (c, f) -> f.matches(row.getOrNull(c)) } &&
            (quick.isEmpty() || row.any { it != null && it.contains(quick, ignoreCase = true) })
    }
    var order = (0 until total).filter(matches)
    val sorts = spec.sorts.filter { it.column in 0 until colCount }
    if (sorts.isNotEmpty()) {
        order = order.sortedWith(Comparator { r1, r2 ->
            val row1 = result.rows[r1]
            val row2 = result.rows[r2]
            for (s in sorts) {
                val v1 = row1.getOrNull(s.column)
                val v2 = row2.getOrNull(s.column)
                // NULL 永远排在最后（升降序一致），避免方向反转后 null 跑到最前
                if (v1 == null || v2 == null) {
                    if (v1 == null && v2 == null) continue
                    return@Comparator if (v1 == null) 1 else -1
                }
                val c = compareCells(v1, v2)
                if (c != 0) return@Comparator if (s.ascending) c else -c
            }
            0
        })
    }
    return ResultView(order, total)
}

/**
 * 展示坐标（含转置 + 排序/筛选）→ 原始结果坐标；越界 / 转置标签列返回 null。
 * @param rowOrder 视图行 → 原始行下标（[buildResultView] 产物）。
 */
fun displayToOriginal(
    rowOrder: List<Int>,
    columnsCount: Int,
    transposed: Boolean,
    viewRow: Int,
    viewCol: Int,
): CellKey? {
    if (!transposed) {
        val origRow = rowOrder.getOrNull(viewRow) ?: return null
        if (viewCol !in 0 until columnsCount) return null
        return CellKey(origRow, viewCol)
    }
    // 转置后：viewRow = 原列下标；viewCol-1 = 视图行下标；viewCol==0 是原列名标签
    val origRow = rowOrder.getOrNull(viewCol - 1) ?: return null
    val origCol = viewRow
    if (origCol !in 0 until columnsCount) return null
    return CellKey(origRow, origCol)
}
