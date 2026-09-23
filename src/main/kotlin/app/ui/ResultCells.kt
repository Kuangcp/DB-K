package app.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.i18n.t
import i18n.Str

/** 结果网格单元格渲染预览上限：单元格本身可能很大（大到 1M 字符），直接交给 Compose 排版会拖垮内存。 */
private const val RESULT_CELL_PREVIEW_CHARS = 512

/** 结果区筛选条：左侧顶部快速过滤（所有列包含），右侧「命中 N / 共 M」与清除全部。 */
@Composable
internal fun ResultFilterBar(
    spec: ResultViewSpec,
    onSpecChange: (ResultViewSpec) -> Unit,
    shownRows: Int,
    totalRows: Int,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.surface)
            .padding(start = 8.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .width(240.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colors.onSurface.copy(alpha = 0.05f))
                .padding(horizontal = 7.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = spec.quickFilter,
                onValueChange = { onSpecChange(spec.copy(quickFilter = it)) },
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colors.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colors.primary),
                modifier = Modifier.fillMaxWidth(),
            )
            if (spec.quickFilter.isEmpty()) {
                Text(
                    t(Str.ResultQuickFilter),
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.35f),
                )
            }
        }
        if (spec.quickFilter.isNotEmpty()) {
            Spacer(Modifier.width(2.dp))
            IconButton(
                onClick = { onSpecChange(spec.copy(quickFilter = "")) },
                modifier = Modifier.size(20.dp),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = t(Str.ResultClearQuickFilter),
                    tint = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            if (shownRows != totalRows) t(Str.ResultMatchRows, shownRows, totalRows) else t(Str.ResultTotalRows, totalRows),
            fontSize = 11.sp,
            color = if (shownRows != totalRows) MaterialTheme.colors.primary
            else MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
        )
        Spacer(Modifier.weight(1f))
        if (spec.filterCount > 0) {
            Text(
                t(Str.ResultFilterCount, spec.filterCount),
                fontSize = 11.sp,
                color = MaterialTheme.colors.primary,
            )
            Spacer(Modifier.width(4.dp))
            TextButton(
                onClick = { onSpecChange(ResultViewSpec()) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            ) {
                Text(t(Str.ResultClearAll), fontSize = 11.sp)
            }
        }
    }
    Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.06f))
}

/**
 * 可排序/筛选的表头单元格：点击切换排序（升→降→无），右侧漏斗图标弹出列筛选输入框。
 * 列筛选输入语法同 [parseColumnFilter]（`=x` 精确、`!=x`、`>x`、`~regex`，默认包含）。
 */
@Composable
internal fun ResultHeaderCell(
    name: String,
    width: Int,
    highlighted: Boolean,
    sort: SortSpec?,
    sortRank: Int?,
    filterText: String?,
    onToggleSort: () -> Unit,
    onFilterChange: (String) -> Unit,
) {
    var filterOpen by remember { mutableStateOf(false) }
    var draft by remember(filterText, filterOpen) { mutableStateOf(filterText.orEmpty()) }
    val filterFocus = remember { FocusRequester() }
    LaunchedEffect(filterOpen) { if (filterOpen) runCatching { filterFocus.requestFocus() } }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .width(width.dp)
            .height(30.dp)
            .background(if (highlighted) MaterialTheme.colors.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onToggleSort)
            .padding(start = 8.dp, end = 10.dp),
    ) {
        Text(
            name,
            fontSize = 11.sp,
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.SemiBold,
            color = if (highlighted) MaterialTheme.colors.primary
            else MaterialTheme.colors.onSurface.copy(alpha = 0.75f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (sort != null) {
            Text(
                if (sort.ascending) "▲" else "▼",
                fontSize = 8.sp,
                color = MaterialTheme.colors.primary,
                modifier = Modifier.padding(start = 3.dp),
            )
            if (sortRank != null) {
                Text(
                    "${sortRank + 1}",
                    fontSize = 7.5.sp,
                    color = MaterialTheme.colors.primary.copy(alpha = 0.8f),
                    modifier = Modifier.padding(start = 1.dp),
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Box(contentAlignment = Alignment.Center) {
            IconButton(
                onClick = { filterOpen = true },
                modifier = Modifier.size(16.dp),
            ) {
                Icon(
                    DbIcons.Filter,
                    contentDescription = t(Str.ResultFilterLabel, name),
                    tint = if (!filterText.isNullOrEmpty()) MaterialTheme.colors.primary
                    else MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(12.dp),
                )
            }
            DropdownMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }) {
                Column(modifier = Modifier.width(228.dp).padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(
                        t(Str.ResultFilterLabel, name),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colors.onSurface,
                    )
                    Text(
                        t(Str.ResultFilterOpsHint),
                        fontSize = 10.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .height(26.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colors.onSurface.copy(alpha = 0.06f))
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        BasicTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colors.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colors.primary),
                            modifier = Modifier.fillMaxWidth().focusRequester(filterFocus),
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(
                            onClick = { onFilterChange(""); filterOpen = false },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) { Text(t(Str.ResultClear), fontSize = 12.sp) }
                        TextButton(
                            onClick = { onFilterChange(draft); filterOpen = false },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) { Text(t(Str.ResultApply), fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun RowHeaderCell(text: String, width: Int, highlighted: Boolean = false) {
    Box(
        modifier = Modifier
            .width(width.dp)
            .height(30.dp)
            .background(if (highlighted) MaterialTheme.colors.primary.copy(alpha = 0.12f) else Color.Transparent),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text,
            fontSize = 11.sp,
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.SemiBold,
            color = if (highlighted) MaterialTheme.colors.primary
            else MaterialTheme.colors.onSurface.copy(alpha = 0.75f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
internal fun DataCell(
    value: String?,
    width: Int,
    selected: Boolean = false,
    rangeSelected: Boolean = false,
    pending: Boolean = false,
    deleted: Boolean = false,
    /** value == null 时的占位文本（待插入行未填列用「未填」，区别于显式 NULL）。 */
    nullText: String = "(NULL)",
    onSelect: () -> Unit = {},
    mono: Boolean = true,
    muted: Boolean = false,
    onDoubleClick: (() -> Unit)? = null,
    editor: (@Composable () -> Unit)? = null,
    menuItems: List<ContextMenuItem> = emptyList(),
) {
    val content: @Composable () -> Unit = {
        if (value == null) {
            Text(
                nullText,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                fontSize = 12.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.3f),
                textDecoration = if (deleted) TextDecoration.LineThrough else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        } else {
            // 只渲染前缀预览：完整值仍在模型中（查看/复制走原字符串），避免 Compose 按巨大段落排版
            val shown = if (value.length > RESULT_CELL_PREVIEW_CHARS) {
                value.take(RESULT_CELL_PREVIEW_CHARS) + "…"
            } else {
                value
            }
            Text(
                shown,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                fontSize = 12.sp,
                color = when {
                    deleted -> MaterialTheme.colors.onSurface.copy(alpha = 0.35f)
                    muted -> MaterialTheme.colors.onSurface.copy(alpha = 0.45f)
                    else -> MaterialTheme.colors.onSurface.copy(alpha = 0.9f)
                },
                textDecoration = if (deleted) TextDecoration.LineThrough else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
    // 手势块只捕获一次 lambda：经 rememberUpdatedState 取最新回调，避免每次重组合重启手势
    val currentSelect = rememberUpdatedState(onSelect)
    val currentDoubleClick = rememberUpdatedState(onDoubleClick)
    val background = when {
        selected -> MaterialTheme.colors.primary.copy(alpha = 0.18f)
        rangeSelected -> MaterialTheme.colors.primary.copy(alpha = 0.10f)
        pending -> Color(0xFFFFB300).copy(alpha = 0.20f)
        else -> Color.Transparent
    }
    // 行内编辑时不要挂选中/双击手势，避免与文本框抢指针
    val gesture = if (editor == null) {
        Modifier.pointerInput(Unit) {
            detectTapGestures(
                onPress = { currentSelect.value() },
                onDoubleTap = { currentDoubleClick.value?.invoke() },
            )
        }
    } else {
        Modifier
    }
    val base = Modifier
        .width(width.dp)
        .height(26.dp)
        .background(background)
        .then(
            if (selected) Modifier.border(1.dp, MaterialTheme.colors.primary.copy(alpha = 0.85f))
            else Modifier,
        )
        .then(gesture)
    when {
        editor != null -> Box(modifier = base, contentAlignment = Alignment.CenterStart) { editor() }
        menuItems.isEmpty() -> Box(modifier = base, contentAlignment = Alignment.CenterStart) { content() }
        else -> {
            // 仅右键菜单可复制（不做单击复制）
            ContextMenuArea(items = { menuItems }) {
                Box(modifier = base, contentAlignment = Alignment.CenterStart) {
                    content()
                }
            }
        }
    }
}

/**
 * 结果单元格行内编辑器：Enter 提交、Esc 取消、失焦提交（点其他单元格/工具条按钮同样生效）。
 * 受控：草稿由 [ResultTable] 持有，便于“点其它格先提交当前格”。
 */
@Composable
internal fun CellEditor(
    value: String,
    onValueChange: (String) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 2.dp)
            .border(1.dp, MaterialTheme.colors.primary.copy(alpha = 0.9f)),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colors.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colors.primary),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 5.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { st ->
                    if (st.isFocused) {
                        hadFocus = true
                    } else if (hadFocus) {
                        hadFocus = false
                        onCommit()
                    }
                }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) {
                        false
                    } else {
                        when (e.key) {
                            Key.Enter, Key.NumPadEnter -> { onCommit(); true }
                            Key.Escape -> { onCancel(); true }
                            else -> false
                        }
                    }
                },
        )
    }
}
