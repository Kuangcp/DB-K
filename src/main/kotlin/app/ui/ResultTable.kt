package app.ui

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.core.export.ExportText
import app.settings.ShortcutCommand
import app.dialog.CellViewerDialog
import app.dialog.EditCellDialog
import app.i18n.LocalLang
import app.i18n.t
import engine.model.QueryResult
import i18n.I18n
import i18n.Str
import jdbc.CellValue
import jdbc.QueryExecutor

/** 每个字符的估算宽度（dp）：ASCII/半角 ≈ 7dp，CJK/全角 ≈ 12dp（11~12sp 默认字体）。 */
private fun charWidthDp(c: Char): Int = if (c.code >= 0x2E80) 12 else 7

/** 文本渲染宽度的估算值（dp）；CJK 按双宽计，避免列名/数据被过早省略。 */
internal fun textWidthDp(s: String): Int = s.sumOf { charWidthDp(it) }

/** 单元格左右内边距合计（dp，见 DataCell 的 padding(horizontal = 8.dp)）。 */
private const val CELL_H_PADDING_DP = 16

/** 表头除列名外的固定占位：内边距(8+10) + 间距(4) + 漏斗图标(16)（dp）。 */
internal const val HEADER_CHROME_DP = 38

/** 表头列名完整展示的字符上限：≤ 该长度不省略，超过则截到该长度加省略号。 */
internal const val HEADER_NAME_MAX_CHARS = 12

/** 自动列宽下限 / 上限（dp）；上限避免超长内容把列撑成巨宽。 */
private const val AUTO_COL_WIDTH_MIN = 64

private const val AUTO_COL_WIDTH_MAX = 320

/** 估算列宽时每格最多采样多少字符（已超过 [AUTO_COL_WIDTH_MAX] 所需，够用即可）。 */
private const val WIDTH_SAMPLE_CHARS = 64

/** NULL 占位文本（与 DataCell 默认 nullText 一致）。 */
private const val NULL_CELL_TEXT = "(NULL)"

/** 列名（含表头固定占位）所需的最小列宽；超过 [HEADER_NAME_MAX_CHARS] 省略为「前 N 字符…」。 */
internal fun headerMinWidthDp(name: String): Int {
    val shown = if (name.length > HEADER_NAME_MAX_CHARS) {
        name.take(HEADER_NAME_MAX_CHARS) + "…"
    } else {
        name
    }
    return textWidthDp(shown) + HEADER_CHROME_DP
}

/** 手动拖动列宽的上下限（dp）。 */
private const val MIN_COL_WIDTH = 40

private const val MAX_COL_WIDTH = 1600

/** 结果表行号 gutter 宽（dp，表头与数据行共用）。 */
private const val RESULT_GUTTER_DP = 44

/** 单列结果 / 转置后只剩一个值列时，值列自适应加宽的上限（dp，避免超长字段把列撑成巨宽）。 */
private const val MAX_FLEX_COL_WIDTH = 600

/** 结果网格：列宽按表头与最多前 300 行采样估算；表头与数据共用横向滚动。
 * 交互：右键单元格可「复制单元格值 / 复制本行 → INSERT」（不做单击复制，避免动不动污染剪贴板）。
 * [transposed]=true 时仅展示行列转制视图（复制交互随之作用于转置后的网格；
 * “本行 → INSERT”在转置视图下无意义故隐藏）。
 */
/** 结果表内单元格坐标（行列均基于当前展示视图：转置后为转置坐标）。 */
private data class CellSel(val row: Int, val col: Int)

/** 行内编辑目标：已有结果格 / 待插入行的一格。 */
private sealed interface EditTarget {
    data class Cell(val row: Int, val col: Int) : EditTarget
    data class Insert(val id: Long, val col: Int) : EditTarget
}

/** 对话框编辑请求（长值/多行/NULL）：定位 + 标题 + 初始值。 */
private sealed interface EditRequest {
    val title: String
    val value: String?

    data class Cell(val key: CellKey, override val title: String, override val value: String?) : EditRequest
    data class Insert(val id: Long, val col: Int, override val title: String, override val value: String?) : EditRequest
}

@Composable
internal fun ResultTable(
    result: QueryResult,
    transposed: Boolean,
    resultEdits: ResultEdits,
    editPlan: EditPlan?,
    canModifyRows: Boolean,
    selectedRow: Int?,
    onSelectedRowChange: (Int?) -> Unit,
    viewSpec: ResultViewSpec,
    onViewSpecChange: (ResultViewSpec) -> Unit,
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
    // 当前快捷键表：结果网格的复制单元格走注册表（基础键，固定），方向键等仍为模态导航。
    val keymap = LocalKeymap.current
    // 订阅语言变化：下面非 composable 的 lambda（buildList / onClick）里用 I18n.t(lang, ...) 取词，
    // 读 LocalLang 使本组件在语言切换时重组，菜单文案随即刷新。
    val lang = LocalLang.current
    // 窗口级 Ctrl 状态（AWT dispatcher 维护）；结果表格 Ctrl+双击=编辑用
    val ctrlHeld = LocalCtrlHeld.current
    // 单元格大段文本查看器（双击 / 右键「查看完整内容」）
    var viewer by remember { mutableStateOf<CellView?>(null) }
    // 正在行内编辑的目标 + 草稿
    var editing by remember(result.sql, result.rows.size, transposed) { mutableStateOf<EditTarget?>(null) }
    var editDraft by remember { mutableStateOf("") }
    // 长值/多行/NULL → 对话框编辑
    var dialogEdit by remember { mutableStateOf<EditRequest?>(null) }
    // 暂存修改叠到展示值上（仅值变化，尺寸不变）
    val edited = remember(result, resultEdits.cells) { applyEdits(result, resultEdits.cells) }
    // 视图层（N1）：排序/筛选只改行顺序与可见行，不重跑 SQL；rowView 保留回原始行的映射
    val rowView = remember(edited, viewSpec) { buildResultView(edited, viewSpec) }
    val displayedRows = remember(edited, rowView) { rowView.rowOrder.map { edited.rows[it] } }
    val view = if (transposed) transposeResult(edited.copy(rows = displayedRows)) else edited.copy(rows = displayedRows)
    val cols = view.columns
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // 横向滚动用共享 ScrollState + horizontalScroll（表头与每一行都读同一偏移）——
    // 不能用多个 LazyRow 共享 LazyListState：虚拟化列表各自测量，滚动条驱动时只有
    // 最近测量那一个响应，会出现“只有内容滚、表头不动”的错位。
    val hScroll = rememberScrollState()
    val vScroll = rememberLazyListState()
    // 结果表格滚动条：列多/行多时可见可拖，横向条与表头/各行同步
    val scrollbarStyle = dbScrollbarStyle()
    // 列宽：默认按内容采样估算；用户拖表头分隔线可覆盖（列数/内容/布局变化时重建）。
    // 用轻量键避免每次重组合都对整表行做深比较（拖动时会高频重组合）。
    // 列宽：默认按内容采样估算；用户拖表头分隔线可覆盖。
    // 非转置：列数不随排序/筛选变化，列宽保持稳定；转置：列数 = 展示行数 + 1，故随筛选重建。
    val transposedRows = if (transposed) view.rows.size else 0
    val baseWidths = remember(result.sql, result.columns, transposed, transposedRows) {
        List(cols.size) { c ->
            // 表头列名（≤12 字符完整展示）是列宽下限：否则短 CJK 列名会被省略成「当…」
            var w = headerMinWidthDp(cols[c].name)
            val sample = minOf(view.rows.size, 300)
            for (r in 0 until sample) {
                val v = view.rows[r][c]
                val cw = if (v == null) {
                    textWidthDp(NULL_CELL_TEXT) + CELL_H_PADDING_DP
                } else {
                    textWidthDp(v.take(WIDTH_SAMPLE_CHARS)) + CELL_H_PADDING_DP
                }
                if (cw > w) w = cw
            }
            w.coerceIn(AUTO_COL_WIDTH_MIN, AUTO_COL_WIDTH_MAX)
        }
    }
    val widths = remember(result.sql, result.columns, transposed, transposedRows) {
        mutableStateListOf<Int>().apply { addAll(baseWidths) }
    }
    // 单列结果 / 转置后只剩一个值列（列名 + 行 1）时，让该值列自适应吃掉右侧空白（有上限）。
    val widenCol = when {
        transposed && cols.size == 2 -> 1
        !transposed && cols.size == 1 -> 0
        else -> -1
    }
    // 用户手动拖过列宽后不再自动加宽（按列记忆）
    val manualCols = remember(result.sql, result.columns, transposed, transposedRows) {
        mutableStateListOf<Boolean>().apply { repeat(cols.size) { add(false) } }
    }
    var tableWidthPx by remember { mutableStateOf(0) }
    val scrollbarDp = scrollbarStyle.thickness.value
    val availDp = with(density) { tableWidthPx.toDp().value }
    LaunchedEffect(availDp, widenCol, result.sql, transposed) {
        if (widenCol in cols.indices && availDp > 0f && !manualCols[widenCol]) {
            val others = cols.indices.filter { it != widenCol }.sumOf { widths[it] }
            val slack = availDp - RESULT_GUTTER_DP - scrollbarDp - others
            widths[widenCol] = slack
                .coerceIn(baseWidths[widenCol].toFloat(), MAX_FLEX_COL_WIDTH.toFloat())
                .toInt()
        }
    }
    // 点选 / 方向键选中的单元格（换结果或转置即清空）
    val sel = remember(result.sql, result.rows.size, transposed) { mutableStateOf<CellSel?>(null) }
    val focusRequester = remember { FocusRequester() }
    val onMove = { dr: Int, dc: Int ->
        if (view.rows.isNotEmpty() && cols.isNotEmpty()) {
            val cur = sel.value ?: CellSel(0, 0)
            val nr = (cur.row + dr).coerceIn(0, view.rows.size - 1)
            val nc = (cur.col + dc).coerceIn(0, cols.size - 1)
            sel.value = CellSel(nr, nc)
            scope.launch {
                // 垂直：目标行不在可视区才滚（避免每次移动都跳回顶部）
                if (vScroll.layoutInfo.visibleItemsInfo.none { it.index == nr }) {
                    vScroll.animateScrollToItem(nr)
                }
                // 水平：把目标列滚进视口
                val x0 = with(density) { (RESULT_GUTTER_DP + widths.take(nc).sum()).dp.toPx() }
                val x1 = x0 + with(density) { widths[nc].dp.toPx() }
                val vp = hScroll.viewportSize
                when {
                    x0 < hScroll.value -> hScroll.animateScrollTo(x0.toInt())
                    x1 > hScroll.value + vp -> hScroll.animateScrollTo((x1 - vp).toInt())
                }
            }
        }
    }
    fun cellText(r: Int, c: Int): String? = view.rows.getOrNull(r)?.getOrNull(c)

    fun editableAt(r: Int, c: Int): Boolean {
        val plan = editPlan ?: return false
        val orig = displayToOriginal(rowView.rowOrder, result.columns.size, transposed, r, c) ?: return false
        if (orig.row in resultEdits.deletes) return false // 待删除行不可再改格
        if (plan.columnAt(orig.col) == null) return false
        // 展示值被截断的单元格禁止编辑（否则会把截断内容写回库）
        return !QueryExecutor.isTruncatedCell(result.rows.getOrNull(orig.row)?.getOrNull(orig.col))
    }

    fun beginEdit(r: Int, c: Int) {
        if (!editableAt(r, c)) return
        editDraft = cellText(r, c).orEmpty()
        sel.value = CellSel(r, c)
        editing = EditTarget.Cell(r, c)
    }

    /** 待插入行一格：无历史值可查看，双击/右键直接进入编辑。 */
    fun beginInsertEdit(id: Long, c: Int) {
        if (editPlan?.columnAt(c) == null) return
        val ins = resultEdits.inserts.firstOrNull { it.id == id } ?: return
        editDraft = ins.values[c]?.raw.orEmpty()
        editing = EditTarget.Insert(id, c)
    }

    fun commitCellDraft(row: Int, c: Int) {
        val orig = displayToOriginal(rowView.rowOrder, result.columns.size, transposed, row, c) ?: return
        val text = editDraft
        val shown = cellText(row, c)
        // 与当前显示值一致（含 NULL/空串语义）→ 不产生修改
        if (text == shown.orEmpty() && !(text.isEmpty() && shown == null)) return
        val original = result.rows.getOrNull(orig.row)?.getOrNull(orig.col)
        // 改回原始值 → 撤销暂存
        if (text == original.orEmpty() && !(text.isEmpty() && original == null)) {
            onClearCellEdit(orig)
            return
        }
        onCellEdit(orig, CellValue(text))
    }

    fun commitInsertDraft(id: Long, c: Int) {
        val ins = resultEdits.inserts.firstOrNull { it.id == id } ?: return
        if (editPlan?.columnAt(c) == null) return
        // 未填过的格子提交空串 → 保持未填（走库默认值）；已填过则可清成空串
        if (editDraft.isEmpty() && c !in ins.values) return
        onInsertCellEdit(id, c, CellValue(editDraft))
    }

    fun commitEditDraft() {
        val e = editing ?: return
        editing = null
        when (e) {
            is EditTarget.Cell -> commitCellDraft(e.row, e.col)
            is EditTarget.Insert -> commitInsertDraft(e.id, e.col)
        }
    }

    fun cancelEditDraft() {
        editing = null
    }

    // 把当前选中行（原始坐标）上报给工具条，供行级操作（删除/撤销）定位
    val selSnapshot = sel.value
    LaunchedEffect(selSnapshot, transposed, rowView.rowOrder) {
        onSelectedRowChange(
            selSnapshot?.let {
                displayToOriginal(rowView.rowOrder, result.columns.size, transposed, it.row, it.col)?.row
            },
        )
    }

    val onKey: (KeyEvent) -> Boolean = { e ->
        if (e.type != KeyEventType.KeyDown) {
            false
        } else {
            when {
                editing != null && e.key == Key.Escape -> { cancelEditDraft(); true }
                // 编辑中：其余按键（含方向键/Ctrl+C）交给文本框，不要劫持光标移动与复制
                editing != null -> false
                keymap.matches(ShortcutCommand.COPY_CELL, e) -> {
                    sel.value
                        ?.takeIf { it.row in view.rows.indices && it.col in cols.indices }
                        ?.let { s -> copyCellValue(onCopyText, view.rows[s.row][s.col], cols[s.col].name) }
                    true
                }
                e.key == Key.DirectionUp -> { onMove(-1, 0); true }
                e.key == Key.DirectionDown -> { onMove(1, 0); true }
                e.key == Key.DirectionLeft -> { onMove(0, -1); true }
                e.key == Key.DirectionRight -> { onMove(0, 1); true }
                else -> false
            }
        }
    }
    // 每行可生成的 INSERT（仅原布局；复杂查询/无法定表时 null）。
    // 不预生成 SQL：大字段下逐行拼 INSERT 会翻倍内存；点击时按需生成。
    val tableName = extractTableName(result.sql)
    val canInsertRow = !transposed && tableName != null
    // 含截断单元格的原始行：不能据此复制 INSERT（否则会把截断内容写回库）
    val truncatedRows: Set<Int> = buildSet {
        result.rows.forEachIndexed { idx, r -> if (r.any { QueryExecutor.isTruncatedCell(it) }) add(idx) }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { tableWidthPx = it.width }
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent(onKey),
    ) {
        ResultFilterBar(
            spec = viewSpec,
            onSpecChange = onViewSpecChange,
            shownRows = rowView.rowCount,
            totalRows = rowView.totalRows,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colors.onSurface.copy(alpha = 0.06f))
                .horizontalScroll(hScroll),
        ) {
            RowHeaderCell("", RESULT_GUTTER_DP)
            cols.forEachIndexed { c, col ->
                // 表头单元格 + 右缘拖拽把手（覆盖式，不占布局宽，保证与数据行水平对齐）
                Box {
                    ResultHeaderCell(
                        name = col.name,
                        width = widths[c],
                        highlighted = sel.value?.col == c,
                        sort = viewSpec.sorts.firstOrNull { it.column == c },
                        sortRank = viewSpec.sorts.indexOfFirst { it.column == c }.takeIf { it > 0 },
                        filterText = viewSpec.filters[c],
                        onToggleSort = {
                            onViewSpecChange(viewSpec.toggleSort(c))
                        },
                        onFilterChange = { text ->
                            val next = viewSpec.filters.toMutableMap()
                            if (text.isEmpty()) next.remove(c) else next[c] = text
                            onViewSpecChange(viewSpec.copy(filters = next))
                        },
                    )
                    ColumnResizeHandle(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        onDragStart = { manualCols[c] = true },
                        onDrag = { deltaDp ->
                            widths[c] = (widths[c] + deltaDp.toInt()).coerceIn(MIN_COL_WIDTH, MAX_COL_WIDTH)
                        },
                    )
                }
            }
        }
        Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.1f))
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // 右侧/底部预留滚动条厚度，避免遮挡最后一列与最后一行
            Column(modifier = Modifier.fillMaxSize().padding(end = scrollbarStyle.thickness, bottom = scrollbarStyle.thickness)) {
                LazyColumn(state = vScroll, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(view.rows) { index, row ->
                        val rowSelected = sel.value?.row == index
                        val origRow = rowView.rowOrder.getOrNull(index)
                        val deleted = origRow != null && origRow in resultEdits.deletes
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    when {
                                        deleted -> Color(0xFFE53935).copy(alpha = 0.08f)
                                        rowSelected -> MaterialTheme.colors.primary.copy(alpha = 0.10f)
                                        index % 2 == 1 -> MaterialTheme.colors.onSurface.copy(alpha = 0.025f)
                                        else -> Color.Transparent
                                    },
                                )
                                .horizontalScroll(hScroll),
                        ) {
                            RowHeaderCell(
                                if (deleted) "✕" else "${index + 1}",
                                RESULT_GUTTER_DP,
                                highlighted = rowSelected,
                            )
                            row.forEachIndexed { c, v ->
                                val colName = view.columns[c].name
                                val cellView = v?.let { CellView(I18n.t(lang, Str.ResultCellTitle, colName, index + 1), it) }
                                val origKey = displayToOriginal(rowView.rowOrder, result.columns.size, transposed, index, c)
                                val isEditable = editableAt(index, c)
                                val pending = origKey != null && resultEdits.cells.containsKey(origKey)
                                val isEditing = editing == EditTarget.Cell(index, c)
                                DataCell(
                                    value = v,
                                    width = widths[c],
                                    selected = sel.value == CellSel(index, c),
                                    pending = pending,
                                    deleted = deleted,
                                    onSelect = {
                                        val cur = editing
                                        if (cur != null && cur != EditTarget.Cell(index, c)) commitEditDraft()
                                        sel.value = CellSel(index, c)
                                        focusRequester.requestFocus()
                                    },
                                    editor = if (isEditing) {
                                        {
                                            CellEditor(
                                                value = editDraft,
                                                onValueChange = { editDraft = it },
                                                onCommit = { commitEditDraft() },
                                                onCancel = { cancelEditDraft() },
                                            )
                                        }
                                    } else null,
                                    onDoubleClick = when {
                                        isEditable -> {
                                            {
                                                if (ctrlHeld) {
                                                    // 长值/多行/NULL → 对话框；短值 → 行内编辑
                                                    if (v == null || v.length > 60 || v.contains('\n')) {
                                                        origKey?.let {
                                                            dialogEdit = EditRequest.Cell(
                                                                it,
                                                                I18n.t(lang, Str.ResultCellTitle, result.columns[it.col].name, it.row + 1),
                                                                v,
                                                            )
                                                        }
                                                    } else {
                                                        beginEdit(index, c)
                                                    }
                                                } else {
                                                    cellView?.let { cv -> viewer = cv }
                                                }
                                            }
                                        }
                                        else -> cellView?.let { cv -> { viewer = cv } }
                                    },
                                    menuItems = buildList {
                                        add(
                                            ContextMenuItem(I18n.t(lang, Str.ResultCopyCell)) {
                                                copyCellValue(onCopyText, v, colName)
                                            },
                                        )
                                        if (cellView != null) {
                                            add(ContextMenuItem(I18n.t(lang, Str.ResultViewFull)) { viewer = cellView })
                                        }
                                        if (canInsertRow && origRow != null && origRow !in truncatedRows) {
                                            add(
                                                ContextMenuItem(I18n.t(lang, Str.ResultCopyRowInsert)) {
                                                    val insertSql = rowToInsertSql(
                                                        result.sql,
                                                        result.columns.map { it.name },
                                                        result.rows[origRow],
                                                    )
                                                    if (insertSql != null) {
                                                        onCopyText(insertSql, I18n.t(lang, Str.ResultCopiedRowInsert, tableName))
                                                    }
                                                },
                                            )
                                        }
                                        // CSV 不需要表名，只需要列名 + 原始行；与 INSERT 一样跳过截断行。
                                        if (!transposed && origRow != null && origRow !in truncatedRows) {
                                            add(
                                                ContextMenuItem(I18n.t(lang, Str.ResultCopyRowCsv)) {
                                                    onCopyText(
                                                        ExportText.csvHeaderAndRow(
                                                            result.columns.map { it.name },
                                                            result.rows[origRow],
                                                        ),
                                                        I18n.t(lang, Str.ResultCopiedRowCsv),
                                                    )
                                                },
                                            )
                                            // TSV（含表头）：与 CSV 同守卫（转置/截断行不提供）
                                            add(
                                                ContextMenuItem(I18n.t(lang, Str.ResultCopyRowTsv)) {
                                                    onCopyText(
                                                        ExportText.tsvHeaderAndRow(
                                                            result.columns.map { it.name },
                                                            result.rows[origRow],
                                                        ),
                                                        I18n.t(lang, Str.ResultCopiedRowTsv),
                                                    )
                                                },
                                            )
                                        }
                                        if (isEditable && !deleted) {
                                            add(ContextMenuItem(I18n.t(lang, Str.ResultEditCell)) { beginEdit(index, c) })
                                            add(
                                                ContextMenuItem(I18n.t(lang, Str.ResultEditInDialog)) {
                                                    origKey?.let {
                                                        dialogEdit = EditRequest.Cell(
                                                            it,
                                                            I18n.t(lang, Str.ResultCellTitle, result.columns[it.col].name, it.row + 1),
                                                            v,
                                                        )
                                                    }
                                                },
                                            )
                                            add(
                                                ContextMenuItem(I18n.t(lang, Str.ResultSetNull)) {
                                                    origKey?.let { onCellEdit(it, CellValue(null)) }
                                                },
                                            )
                                        }
                                        if (pending) {
                                            add(
                                                ContextMenuItem(I18n.t(lang, Str.ResultUndoCellEdit)) {
                                                    onClearCellEdit(origKey)
                                                },
                                            )
                                        }
                                        if (canModifyRows && origRow != null) {
                                            add(
                                                ContextMenuItem(if (deleted) I18n.t(lang, Str.ResultUndoDeleteRow) else I18n.t(lang, Str.ResultMarkDeleteRowCtx)) {
                                                    onToggleRowDelete(origRow)
                                                },
                                            )
                                        }
                                    },
                                )
                            }
                        }
                        Divider(
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.05f),
                            modifier = Modifier.padding(start = RESULT_GUTTER_DP.dp),
                        )
                    }
                    // 待插入行（仅非转置视图）：直接编辑，提交时生成 INSERT（未填列走库默认值）
                    if (!transposed) {
                        items(resultEdits.inserts, key = { it.id }) { ins ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colors.primary.copy(alpha = 0.06f))
                                    .horizontalScroll(hScroll),
                            ) {
                                RowHeaderCell("＋", RESULT_GUTTER_DP, highlighted = false)
                                cols.forEachIndexed { c, _ ->
                                    val filled = ins.values[c]
                                    val isEditable = editPlan?.columnAt(c) != null
                                    val isEditing = editing == EditTarget.Insert(ins.id, c)
                                    DataCell(
                                        value = filled?.raw,
                                        width = widths[c],
                                        pending = filled != null,
                                        nullText = if (c in ins.values) "(NULL)" else I18n.t(lang, Str.ResultPendingUnset),
                                        onSelect = {
                                            val cur = editing
                                            if (cur != null && cur != EditTarget.Insert(ins.id, c)) commitEditDraft()
                                        },
                                        editor = if (isEditing) {
                                            {
                                                CellEditor(
                                                    value = editDraft,
                                                    onValueChange = { editDraft = it },
                                                    onCommit = { commitEditDraft() },
                                                    onCancel = { cancelEditDraft() },
                                                )
                                            }
                                        } else null,
                                        onDoubleClick = if (isEditable) {
                                            { beginInsertEdit(ins.id, c) }
                                        } else null,
                                        menuItems = buildList {
                                            if (isEditable) {
                                                add(ContextMenuItem(I18n.t(lang, Str.ResultEditPendingCell)) { beginInsertEdit(ins.id, c) })
                                                add(
                                                    ContextMenuItem(I18n.t(lang, Str.ResultEditInDialog)) {
                                                        val pendingName = result.columns.getOrNull(c)?.name ?: I18n.t(lang, Str.ResultColumnFallback)
                                                        dialogEdit = EditRequest.Insert(
                                                            ins.id, c,
                                                            I18n.t(lang, Str.ResultPendingRowTitle, pendingName),
                                                            filled?.raw,
                                                        )
                                                    },
                                                )
                                                add(
                                                    ContextMenuItem(I18n.t(lang, Str.ResultSetNull)) {
                                                        onInsertCellEdit(ins.id, c, CellValue(null))
                                                    },
                                                )
                                                if (c in ins.values) {
                                                    add(
                                                        ContextMenuItem(I18n.t(lang, Str.ResultClearPendingCell)) {
                                                            onClearInsertCell(ins.id, c)
                                                        },
                                                    )
                                                }
                                            }
                                            add(ContextMenuItem(I18n.t(lang, Str.ResultRemovePendingRow)) { onRemoveInsertRow(ins.id) })
                                        },
                                    )
                                }
                            }
                            Divider(
                                color = MaterialTheme.colors.primary.copy(alpha = 0.15f),
                                modifier = Modifier.padding(start = RESULT_GUTTER_DP.dp),
                            )
                        }
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(vScroll),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(bottom = scrollbarStyle.thickness),
                style = scrollbarStyle,
            )
            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(hScroll),
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(end = scrollbarStyle.thickness),
                style = scrollbarStyle,
            )
        }
    }
    dialogEdit?.let { request ->
        EditCellDialog(
            title = t(Str.ResultEditCellTitle, request.title),
            initial = request.value,
            onDismiss = { dialogEdit = null },
            onConfirm = { value ->
                dialogEdit = null
                when (request) {
                    is EditRequest.Cell -> {
                        val original = result.rows.getOrNull(request.key.row)?.getOrNull(request.key.col)
                        val raw = value.raw
                        // 改回原始值 → 撤销暂存；其余（含空串）写入 overlay
                        if (raw == original.orEmpty() && !(raw.isNullOrEmpty() && original == null)) {
                            onClearCellEdit(request.key)
                        } else {
                            onCellEdit(request.key, value)
                        }
                    }
                    is EditRequest.Insert -> onInsertCellEdit(request.id, request.col, value)
                }
            },
        )
    }
    viewer?.let { v ->
        CellViewerDialog(
            title = v.title,
            content = v.content,
            onDismiss = { viewer = null },
            onCopy = onCopyText,
        )
    }
}

/** 单元格大段文本查看器请求（"列名 · 第 N 行" + 原文）。 */
private data class CellView(val title: String, val content: String)

/**
 * 结果表头的列宽拖拽把手：覆盖在表头单元格右缘（不占布局宽，避免与数据行错位）。
 * 水平拖动改列宽（dp），拖动中握把加粗高亮；鼠标悬停显示水平缩放光标。
 */
@Composable
private fun ColumnResizeHandle(
    onDrag: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onDragStart: () -> Unit = {},
) {
    val resizeCursor = remember {
        PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.E_RESIZE_CURSOR))
    }
    val density = LocalDensity.current
    // 手势协程只捕获一次 lambda，需经 rememberUpdatedState 拿最新值
    // （结果/列变化时 widths 会被重建，旧引用会写到已废弃的列表上）
    val currentOnDrag = rememberUpdatedState(onDrag)
    val currentOnDragStart = rememberUpdatedState(onDragStart)
    var active by remember { mutableStateOf(false) }
    // 子 dp 拖拽累积：hidpi 下单次事件可能不足 1dp，先攒够整 dp 再上报，避免小拖无反应
    var acc by remember { mutableStateOf(0f) }
    Box(
        modifier = modifier
            .width(9.dp)
            .height(30.dp)
            .pointerHoverIcon(resizeCursor)
            .pointerInput(density) {
                detectDragGestures(
                    onDragStart = {
                        active = true
                        acc = 0f
                        currentOnDragStart.value()
                    },
                    onDragEnd = { active = false },
                    onDragCancel = { active = false },
                ) { change, dragAmount ->
                    change.consume()
                    acc += dragAmount.x / density.density
                    val whole = acc.toInt()
                    if (whole != 0) {
                        currentOnDrag.value(whole.toFloat())
                        acc -= whole
                    }
                }
            },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(if (active) 2.dp else 1.dp)
                .background(
                    if (active) MaterialTheme.colors.primary
                    else MaterialTheme.colors.onSurface.copy(alpha = 0.18f),
                ),
        )
    }
}

/** 复制单元格值：NULL 复制为空串（与 CSV 导出规则一致），Toast 文案带预览。 */
private fun copyCellValue(onCopyText: (String, String) -> Unit, v: String?, colName: String) {
    if (v == null) {
        onCopyText("", I18n.t(Str.ResultCopiedNull, colName))
    } else if (QueryExecutor.isTruncatedCell(v)) {
        onCopyText(v, I18n.t(Str.ResultCopiedTruncated, colName))
    } else {
        val preview = if (v.length > 28) v.take(28) + "…" else v
        onCopyText(v, I18n.t(Str.ResultCopiedCell, colName, preview))
    }
}
