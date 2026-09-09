package app.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.state.ConsoleRunUi
import db.ConsoleRecord
import db.ConnectionProfile
import jdbc.QueryResult
import tree.ConnUiStatus
import tree.TypeBadge

/** 每个单元格最窄 64dp / 最宽 320dp（字符数 → dp 估算，12sp monospace 约 0.6em/字符）。 */
private fun estWidth(chars: Int): Int = (chars * 7 + 20).coerceIn(64, 320)

/**
 * 右侧 SQL 工作台（M4：多控制台）。
 * 结构：顶部「数据源切换 + 控制台标签」→ 编辑器（当前控制台缓冲区）→ 执行条 → 结果区。
 * 纯展示组件；全部编排/持久化在 Main/ConsoleState。
 */
@Composable
fun SqlWorkspace(
    profile: ConnectionProfile?,
    status: ConnUiStatus,
    statusMessage: String?,
    profiles: List<ConnectionProfile>,
    onSelectProfile: (String) -> Unit,
    consoles: List<ConsoleRecord>,
    activeConsole: ConsoleRecord?,
    onSelectConsole: (ConsoleRecord) -> Unit,
    onCreateConsole: () -> Unit,
    onRenameConsole: (ConsoleRecord) -> Unit,
    onDeleteConsole: (ConsoleRecord) -> Unit,
    editorText: String,
    editorDirty: Boolean,
    onTextChange: (String) -> Unit,
    run: ConsoleRunUi,
    onRun: () -> Unit,
    onClear: () -> Unit,
    onExportCsv: () -> Unit,
    onDisconnect: () -> Unit,
    isDark: Boolean,
    onToggleTheme: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
        HeaderBar(isDark, onToggleTheme)
        Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.08f))
        if (profile == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "还没有数据源。请在左侧新建连接档案，单击连接即可创建控制台开始写 SQL。",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            return
        }
        ConnectionNavBar(profile, status, statusMessage, profiles, onSelectProfile, onDisconnect)
        ConsoleTabBar(
            consoles = consoles,
            activeConsole = activeConsole,
            onSelectConsole = onSelectConsole,
            onCreateConsole = onCreateConsole,
            onRenameConsole = onRenameConsole,
            onDeleteConsole = onDeleteConsole,
        )
        Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.08f))
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EditorPane(
                queryText = editorText,
                dirty = editorDirty,
                onQueryTextChange = onTextChange,
                onRun = onRun,
                modifier = Modifier.weight(0.44f).fillMaxWidth(),
            )
            ExecBar(
                executing = run.executing,
                result = run.result,
                error = run.error,
                onRun = onRun,
                onClear = onClear,
                onExportCsv = onExportCsv,
                exportEnabled = exportEnabledFor(run),
                enabled = status != ConnUiStatus.CONNECTING,
            )
            ResultPane(
                result = run.result,
                error = run.error,
                modifier = Modifier.weight(0.56f).fillMaxWidth(),
            )
        }
    }
}

private fun exportEnabledFor(run: ConsoleRunUi): Boolean {
    val res = run.result ?: return false
    return !run.executing && run.error == null && res.isQuery && res.rowCount > 0
}

@Composable
private fun HeaderBar(isDark: Boolean, onToggleTheme: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp),
    ) {
        Text("SQL 控制台", style = MaterialTheme.typography.subtitle2, color = MaterialTheme.colors.onSurface)
        Text(
            "每个数据源可建多个控制台，各绑定一个 .sql 文件",
            fontSize = 11.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.padding(start = 10.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onToggleTheme, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = if (isDark) DbIcons.Sun else DbIcons.Moon,
                contentDescription = if (isDark) "切换浅色主题" else "切换深色主题",
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

/** 绑定数据源导航行：点击连接名可切换到其它数据源；含连接状态与断开。 */
@Composable
private fun ConnectionNavBar(
    profile: ConnectionProfile,
    status: ConnUiStatus,
    statusMessage: String?,
    profiles: List<ConnectionProfile>,
    onSelectProfile: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(38.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(5.dp))
                .clickable(enabled = profiles.size > 1) { menuOpen = true }
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            TypeBadge(profile.dbType)
            Text(
                profile.name,
                style = MaterialTheme.typography.body2,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 7.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (profiles.size > 1) {
                Icon(
                    Icons.Filled.ArrowDropDown, "切换数据源",
                    tint = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                profiles.forEach { p ->
                    DropdownMenuItem(onClick = {
                        menuOpen = false
                        if (p.id != profile.id) onSelectProfile(p.id)
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TypeBadge(p.dbType)
                            Text(
                                p.name,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(start = 8.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        StatusDot(status)
        Text(
            statusLabel(status),
            fontSize = 11.sp,
            color = statusColor(status),
            modifier = Modifier.padding(start = 4.dp),
        )
        if (status == ConnUiStatus.ERROR && statusMessage != null) {
            Text(
                statusMessage,
                fontSize = 11.sp,
                color = MaterialTheme.colors.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false),
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            "SQL 文件自动保存",
            fontSize = 10.5.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.35f),
        )
        if (status == ConnUiStatus.CONNECTED) {
            TextButton(onClick = onDisconnect) { Text("断开", fontSize = 12.sp) }
        }
    }
}

/** 控制台标签条：当前数据源下所有控制台 + 「新建」。 */
@Composable
private fun ConsoleTabBar(
    consoles: List<ConsoleRecord>,
    activeConsole: ConsoleRecord?,
    onSelectConsole: (ConsoleRecord) -> Unit,
    onCreateConsole: () -> Unit,
    onRenameConsole: (ConsoleRecord) -> Unit,
    onDeleteConsole: (ConsoleRecord) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(36.dp).padding(start = 12.dp, end = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
        ) {
            consoles.forEach { c ->
                ConsoleChip(
                    console = c,
                    active = c.id == activeConsole?.id,
                    onSelect = { onSelectConsole(c) },
                    onRename = { onRenameConsole(c) },
                    onDelete = { onDeleteConsole(c) },
                )
                Spacer(Modifier.width(5.dp))
            }
        }
        Spacer(Modifier.width(6.dp))
        IconButton(
            onClick = onCreateConsole,
            modifier = Modifier.size(24.dp),
        ) {
            Icon(
                Icons.Filled.Add, "新建控制台",
                tint = MaterialTheme.colors.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun ConsoleChip(
    console: ConsoleRecord,
    active: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val menu = listOf(
        ContextMenuItem("重命名控制台") { onRename() },
        ContextMenuItem("删除控制台") { onDelete() },
    )
    ContextMenuArea(items = { menu }) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (active) MaterialTheme.colors.primary.copy(alpha = 0.16f)
                    else MaterialTheme.colors.onSurface.copy(alpha = 0.045f),
                )
                .clickable(onClick = onSelect)
                .padding(horizontal = 9.dp, vertical = 4.dp),
        ) {
            Text(
                console.name,
                fontSize = 12.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) MaterialTheme.colors.primary
                else MaterialTheme.colors.onSurface.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EditorPane(
    queryText: String,
    dirty: Boolean,
    onQueryTextChange: (String) -> Unit,
    onRun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        OutlinedTextField(
            value = queryText,
            onValueChange = onQueryTextChange,
            modifier = Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && e.isCtrlPressed) {
                    onRun()
                    true
                } else {
                    false
                }
            },
            singleLine = false,
            placeholder = {
                Text(
                    "输入 SQL，Ctrl+Enter 执行，例如：\nSELECT * FROM users",
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.35f),
                )
            },
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colors.onSurface,
            ),
            keyboardOptions = KeyboardOptions.Default,
        )
        // 编辑状态提示（● = 有未落盘改动，自动保存中）
        Text(
            if (dirty) "● 未保存" else "已保存到 .sql 文件",
            fontSize = 10.sp,
            color = if (dirty) MaterialTheme.colors.primary.copy(alpha = 0.75f)
            else MaterialTheme.colors.onSurface.copy(alpha = 0.3f),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 6.dp),
        )
    }
}

@Composable
private fun ExecBar(
    executing: Boolean,
    result: QueryResult?,
    error: String?,
    onRun: () -> Unit,
    onClear: () -> Unit,
    onExportCsv: () -> Unit,
    exportEnabled: Boolean,
    enabled: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Button(onClick = onRun, enabled = enabled && !executing) {
            Text(if (executing) "执行中…" else "执行 (Ctrl+Enter)", fontSize = 13.sp)
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onClear, enabled = !executing) { Text("清空", fontSize = 12.sp) }
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onExportCsv, enabled = exportEnabled) {
            Text("导出 CSV", fontSize = 12.sp)
        }
        Spacer(Modifier.weight(1f))
        if (executing) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(
                " 执行中…",
                fontSize = 12.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
            )
        } else if (error != null) {
            Text("执行出错", fontSize = 12.sp, color = MaterialTheme.colors.error)
        } else if (result != null) {
            Text(
                metaText(result),
                fontSize = 12.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
            )
        }
    }
}

private fun metaText(result: QueryResult): String {
    val ms = "${result.durationMs} ms"
    return when {
        result.affectedRows != null -> "已更新 ${result.affectedRows} 行 · $ms"
        result.rowCount == 0 -> "查询完成 · 0 行 · $ms"
        else -> {
            val truncated = if (result.truncated) "（截断）" else ""
            "${result.columns.size} 列 × ${result.rowCount} 行$truncated · $ms"
        }
    }
}

@Composable
private fun ResultPane(result: QueryResult?, error: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colors.surface),
    ) {
        when {
            error != null -> CenteredHint(error, isError = true)
            result == null -> CenteredHint("执行 SELECT 后在此查看结果表格；可导出 CSV", isError = false)
            result.isQuery && result.rowCount == 0 -> CenteredHint("查询完成：0 行", isError = false)
            result.isQuery -> ResultTable(result)
            else -> CenteredHint("语句执行成功（非查询，未产生结果集）", isError = false)
        }
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

/** 结果网格：列宽按表头与最多前 300 行采样估算；表头与数据共用横向滚动。 */
@Composable
private fun ResultTable(result: QueryResult) {
    val cols = result.columns
    val widths = IntArray(cols.size) { c ->
        var w = cols[c].name.length
        val sample = minOf(result.rows.size, 300)
        for (r in 0 until sample) {
            val cell = result.rows[r][c]
            val len = cell?.length ?: 5 // (NULL)
            if (len > w) w = len
        }
        estWidth(w)
    }
    val hScroll = rememberLazyListState()
    Column(modifier = Modifier.fillMaxSize()) {
        LazyRow(state = hScroll, modifier = Modifier.background(MaterialTheme.colors.onSurface.copy(alpha = 0.06f))) {
            item {
                Row {
                    RowHeaderCell("", 44)
                    cols.forEachIndexed { c, col ->
                        RowHeaderCell(col.name, widths[c])
                    }
                }
            }
        }
        Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.1f))
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(result.rows) { index, row ->
                LazyRow(state = hScroll, modifier = Modifier.fillMaxWidth()) {
                    item {
                        Row(modifier = Modifier.background(
                            if (index % 2 == 1) MaterialTheme.colors.onSurface.copy(alpha = 0.025f)
                            else Color.Transparent,
                        )) {
                            DataCell("${index + 1}", 44, mono = false, muted = true)
                            row.forEachIndexed { c, v -> DataCell(v, widths[c]) }
                        }
                    }
                }
                Divider(
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.05f),
                    modifier = Modifier.padding(start = 44.dp),
                )
            }
        }
    }
}

@Composable
private fun RowHeaderCell(text: String, width: Int) {
    Box(
        modifier = Modifier.width(width.dp).height(30.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.75f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun DataCell(value: String?, width: Int, mono: Boolean = true, muted: Boolean = false) {
    Box(
        modifier = Modifier.width(width.dp).height(26.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value == null) {
            Text(
                "(NULL)",
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                fontSize = 12.sp,
                fontStyle = null,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.3f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        } else {
            Text(
                value,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                fontSize = 12.sp,
                color = if (muted) MaterialTheme.colors.onSurface.copy(alpha = 0.45f)
                else MaterialTheme.colors.onSurface.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

internal fun statusLabel(status: ConnUiStatus): String = when (status) {
    ConnUiStatus.DISCONNECTED -> "未连接"
    ConnUiStatus.CONNECTING -> "连接中…"
    ConnUiStatus.CONNECTED -> "已连接"
    ConnUiStatus.ERROR -> "连接失败"
}

internal fun statusColor(status: ConnUiStatus): Color = when (status) {
    ConnUiStatus.DISCONNECTED -> Color(0xFF9E9E9E)
    ConnUiStatus.CONNECTING -> Color(0xFFFFB300)
    ConnUiStatus.CONNECTED -> Color(0xFF43A047)
    ConnUiStatus.ERROR -> Color(0xFFE53935)
}

@Composable
internal fun StatusDot(status: ConnUiStatus) {
    Box(
        modifier = Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(statusColor(status)),
    )
}
