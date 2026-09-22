package app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.state.ConsoleRunUi
import app.i18n.t
import engine.model.QueryResult
import i18n.Str
import jdbc.CellValue
import jdbc.QueryExecutor

/** 结果区占上下两块剩余高的比例下限/上限（拖动 clamp 用）。 */
internal const val MIN_RESULT_FRAC = 0.15f

internal const val MAX_RESULT_FRAC = 0.85f

/**
 * 编辑区/结果区分隔条：12dp 热区整条可上下拖动（悬停 N/S 双向箭头光标），
 * 中央一条 1dp 浅色线作视觉提示（悬停/拖动时加粗高亮）。拖动像素按内容区可用高换算成比例增量后由上层累加。
 */
@Composable
internal fun ResultSplitter(
    paneHeightPx: Int,
    onDragDeltaPx: (Float) -> Unit,
) {
    // 固定开销：上下 padding 8+8、本条高 5 —— 不算入比例换算基数
    val chromePx = LocalDensity.current.run { 21.dp.toPx() }
    val resizeCursor = remember {
        PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.N_RESIZE_CURSOR))
    }
    var hovering by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(5.dp)
            .pointerHoverIcon(resizeCursor)
            // 悬停高亮：Enter/Exit 由独立 pointerInput 观察，不与拖动手势冲突
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        when (awaitPointerEvent().type) {
                            PointerEventType.Enter -> hovering = true
                            PointerEventType.Exit -> hovering = false
                            else -> Unit
                        }
                    }
                }
            }
            .pointerInput(paneHeightPx) {
                detectVerticalDragGestures { _, dragAmount ->
                    val free = (paneHeightPx - chromePx).coerceAtLeast(1f)
                    // 屏幕 y 向下为正：向上拖（负值）要让结果区变大（上边界上移），故取反
                    onDragDeltaPx(-dragAmount / free)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (hovering) 3.dp else 1.dp)
                .background(
                    if (hovering) MaterialTheme.colors.primary.copy(alpha = 0.7f)
                    else MaterialTheme.colors.onSurface.copy(alpha = 0.12f),
                ),
        )
    }
}

/** 结果区：顶部一条 28dp 工具条（多语句 Tab + 状态 + 图标动作）+ 结果表格；无结果时不显示工具条。 */
@Composable
internal fun ResultTabs(
    run: ConsoleRunUi,
    transposed: Boolean,
    exportEnabled: Boolean,
    enabled: Boolean,
    onSelectOutcome: (Int) -> Unit,
    onToggleTranspose: () -> Unit,
    onCommitEdits: () -> Unit,
    onClearAllEdits: () -> Unit,
    onRefreshResult: () -> Unit,
    onTogglePin: () -> Unit,
    canFetchMore: Boolean,
    onFetchMore: () -> Unit,
    editCount: Int,
    canCommit: Boolean,
    resultBusy: Boolean,
    resultEdits: ResultEdits,
    editPlan: EditPlan?,
    canModifyRows: Boolean,
    onCellEdit: (CellKey, CellValue) -> Unit,
    onClearCellEdit: (CellKey) -> Unit,
    onInsertRow: () -> Unit,
    onRemoveInsertRow: (Long) -> Unit,
    onInsertCellEdit: (Long, Int, CellValue) -> Unit,
    onClearInsertCell: (Long, Int) -> Unit,
    onToggleRowDelete: (Int) -> Unit,
    onExport: () -> Unit,
    onCancelRun: () -> Unit,
    onCopyText: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 结果网格当前选中的原始行（行级操作按钮据此定位）；结果 / Tab 变化时清空
    var selectedRow by remember(run.activeIndex, run.result?.sql, run.result?.rows?.size) {
        mutableStateOf<Int?>(null)
    }
    Column(modifier = modifier) {
        if (run.outcomes.isNotEmpty() || run.executing) {
            ResultToolbar(
                run = run,
                transposed = transposed,
                exportEnabled = exportEnabled,
                enabled = enabled,
                onSelectOutcome = onSelectOutcome,
                onToggleTranspose = onToggleTranspose,
                onCommitEdits = onCommitEdits,
                onClearAllEdits = onClearAllEdits,
                onRefreshResult = onRefreshResult,
                onTogglePin = onTogglePin,
                canFetchMore = canFetchMore,
                onFetchMore = onFetchMore,
                editCount = editCount,
                canCommit = canCommit,
                resultBusy = resultBusy,
                canModifyRows = canModifyRows,
                selectedRow = selectedRow,
                selectedRowDeleted = selectedRow != null && selectedRow in resultEdits.deletes,
                onInsertRow = onInsertRow,
                onToggleRowDelete = { selectedRow?.let(onToggleRowDelete) },
                onExport = onExport,
                onCancelRun = onCancelRun,
            )
            Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.08f))
        }
        ResultPane(
            result = run.result,
            error = run.error,
            transposed = transposed,
            resultEdits = resultEdits,
            editPlan = editPlan,
            canModifyRows = canModifyRows,
            selectedRow = selectedRow,
            onSelectedRowChange = { selectedRow = it },
            onCellEdit = onCellEdit,
            onClearCellEdit = onClearCellEdit,
            onInsertRow = onInsertRow,
            onRemoveInsertRow = onRemoveInsertRow,
            onInsertCellEdit = onInsertCellEdit,
            onClearInsertCell = onClearInsertCell,
            onToggleRowDelete = onToggleRowDelete,
            onCopyText = onCopyText,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@Composable
internal fun ResultPane(
    result: QueryResult?,
    error: String?,
    transposed: Boolean,
    resultEdits: ResultEdits,
    editPlan: EditPlan?,
    canModifyRows: Boolean,
    selectedRow: Int?,
    onSelectedRowChange: (Int?) -> Unit,
    onCellEdit: (CellKey, CellValue) -> Unit,
    onClearCellEdit: (CellKey) -> Unit,
    onInsertRow: () -> Unit,
    onRemoveInsertRow: (Long) -> Unit,
    onInsertCellEdit: (Long, Int, CellValue) -> Unit,
    onClearInsertCell: (Long, Int) -> Unit,
    onToggleRowDelete: (Int) -> Unit,
    onCopyText: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colors.surface),
    ) {
        // 客户端排序/筛选视图（N1）：随结果 SQL 重置，不重跑 SQL
        var viewSpec by remember(result?.sql) { mutableStateOf(ResultViewSpec()) }
        when {
            error != null -> CenteredHint(error, isError = true)
            result == null -> CenteredHint(t(Str.ResultEmptyHint), isError = false)
            result.isQuery && result.rowCount == 0 && resultEdits.inserts.isEmpty() ->
                CenteredHint(t(Str.ResultQueryZeroHint), isError = false)
            result.isQuery -> Column(modifier = Modifier.fillMaxSize()) {
                if (result.truncated) TruncationBanner()
                ResultTable(
                    result = result,
                    transposed = transposed,
                    resultEdits = resultEdits,
                    editPlan = editPlan,
                    canModifyRows = canModifyRows,
                    selectedRow = selectedRow,
                    onSelectedRowChange = onSelectedRowChange,
                    viewSpec = viewSpec,
                    onViewSpecChange = { viewSpec = it },
                    onCellEdit = onCellEdit,
                    onClearCellEdit = onClearCellEdit,
                    onInsertRow = onInsertRow,
                    onRemoveInsertRow = onRemoveInsertRow,
                    onInsertCellEdit = onInsertCellEdit,
                    onClearInsertCell = onClearInsertCell,
                    onToggleRowDelete = onToggleRowDelete,
                    onCopyText = onCopyText,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
            else -> CenteredHint(
                if (result.affectedRows != null) t(Str.ResultUpdatedPlain, result.affectedRows)
                else t(Str.ResultNonQueryOk),
                isError = false,
            )
        }
    }
}

/** 截断醒目提示：结果被 QueryExecutor.MAX_ROWS 截断时显示在表格上方。 */
@Composable
private fun TruncationBanner() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFB300).copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFFFFB300)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            t(Str.ResultTruncatedHint, QueryExecutor.MAX_ROWS),
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun CenteredHint(text: String, isError: Boolean) {
    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            fontSize = 12.sp,
            color = if (isError) MaterialTheme.colors.error
            else MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
        )
    }
}
