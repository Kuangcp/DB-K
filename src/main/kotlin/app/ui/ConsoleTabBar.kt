package app.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.i18n.t
import app.state.ExternalFileIssue
import db.ConsoleRecord
import db.ConnectionProfile
import db.WorkspaceRecord
import i18n.Str
import tree.TypeBadge

/**
 * 控制台标签条（跨数据源）：固定显示全部档案的控制台，每个标签带数据源徽章（含 ● 未保存）；
 * 右侧「+」下拉选择在哪个数据源下新建。
 */
@Composable
internal fun ConsoleTabBar(
    consoles: List<ConsoleRecord>,
    activeConsole: ConsoleRecord?,
    dirtyConsoleIds: Set<String>,
    pendingEditCounts: Map<String, Int>,
    profilesById: Map<String, ConnectionProfile>,
    onSelectConsole: (ConsoleRecord) -> Unit,
    onCreateConsoleAt: (String) -> Unit,
    onRenameConsole: (ConsoleRecord) -> Unit,
    onDeleteConsole: (ConsoleRecord) -> Unit,
    onCloseConsole: (ConsoleRecord) -> Unit,
    externalIssues: Map<String, ExternalFileIssue> = emptyMap(),
    onOpenSqlFile: () -> Unit = {},
    onCopyFilePath: (ConsoleRecord) -> Unit = {},
    onRevealFile: (ConsoleRecord) -> Unit = {},
    onReloadFromDisk: (ConsoleRecord) -> Unit = {},
    workspaces: List<WorkspaceRecord> = emptyList(),
    activeWorkspace: WorkspaceRecord? = null,
    workspaceCountOf: (String) -> Int = { 0 },
    workspaceHasDirty: (String) -> Boolean = { false },
    onSelectWorkspace: (String) -> Unit = {},
    onCreateWorkspace: () -> Unit = {},
    onRenameWorkspace: (WorkspaceRecord) -> Unit = {},
    onDeleteWorkspace: (WorkspaceRecord) -> Unit = {},
    /** 标签多行换行（自动换行）；false = 单行横向滚动。 */
    multiRowTabs: Boolean = false,
) {
    var createMenuOpen by remember { mutableStateOf(false) }
    // 条内出现多个数据源时，标签额外显示所属数据源名，避免同类型两个库分不清
    val multiSource = consoles.map { it.connectionId }.distinct().size > 1

    // 两种布局共用同一份标签渲染（仅外层容器不同：换行 vs 横滚）
    val renderChip: @Composable (ConsoleRecord, Modifier) -> Unit = { c, chipModifier ->
        ConsoleChip(
            console = c,
            profile = profilesById[c.connectionId],
            showSourceTag = multiSource,
            active = c.id == activeConsole?.id,
            dirty = c.id in dirtyConsoleIds,
            pendingEdits = pendingEditCounts[c.id] ?: 0,
            issue = externalIssues[c.id],
            modifier = chipModifier,
            onSelect = { onSelectConsole(c) },
            onRename = { onRenameConsole(c) },
            onDelete = { onDeleteConsole(c) },
            onClose = { onCloseConsole(c) },
            onCopyFilePath = { onCopyFilePath(c) },
            onRevealFile = { onRevealFile(c) },
            onReloadFromDisk = { onReloadFromDisk(c) },
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 38.dp).padding(start = 8.dp, end = 6.dp),
    ) {
        if (multiRowTabs) {
            // 多行：自动换行，不设行数上限（标签越多条越高）
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.weight(1f, fill = false).padding(vertical = 4.dp),
            ) {
                consoles.forEach { c -> renderChip(c, Modifier) }
            }
        } else {
            // 单行：横向滚动（滚轮由 Compose 原生处理），激活 tab 自动滚入可见范围
            val scrollState = rememberScrollState()
            val spacingPx = with(LocalDensity.current) { 5.dp.roundToPx() }
            val chipWidths = remember { mutableStateMapOf<String, Int>() }
            var viewportPx by remember { mutableIntStateOf(0) }
            val activeId = activeConsole?.id
            LaunchedEffect(activeId, consoles.map { it.id }, chipWidths.size, viewportPx) {
                if (activeId == null || viewportPx <= 0) return@LaunchedEffect
                var offset = 0
                var width = 0
                for (c in consoles) {
                    if (c.id == activeId) {
                        width = chipWidths[c.id] ?: 0
                        break
                    }
                    offset += (chipWidths[c.id] ?: 0) + spacingPx
                }
                if (width <= 0) return@LaunchedEffect
                val start = scrollState.value
                val end = start + viewportPx
                val target = when {
                    offset < start -> offset
                    offset + width > end -> offset + width - viewportPx
                    else -> return@LaunchedEffect
                }
                scrollState.animateScrollTo(target.coerceIn(0, scrollState.maxValue))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .horizontalScroll(scrollState)
                    .onSizeChanged { viewportPx = it.width },
            ) {
                consoles.forEach { c ->
                    renderChip(c, Modifier.onSizeChanged { chipWidths[c.id] = it.width })
                    Spacer(Modifier.width(5.dp))
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        // 同上：IconButton 与 DropdownMenu 必须在同一 Box 内，否则弹层按整行定位。
        Box {
            IconButton(
                onClick = { createMenuOpen = true },
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    Icons.Filled.Add, t(Str.TreeMenuNewConsole),
                    tint = MaterialTheme.colors.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
            DropdownMenu(expanded = createMenuOpen, onDismissRequest = { createMenuOpen = false }) {
                DropdownMenuItem(onClick = { createMenuOpen = false; onOpenSqlFile() }) {
                    Text(t(Str.ExternalOpenSqlFile), fontSize = 13.sp, color = MaterialTheme.colors.onSurface)
                }
                Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.1f))
                profilesById.values.forEach { p ->
                    DropdownMenuItem(onClick = {
                        createMenuOpen = false
                        onCreateConsoleAt(p.id)
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TypeBadge(p.dbType)
                            Text(
                                t(Str.EditorNewConsoleIn, p.name),
                                fontSize = 13.sp,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.85f),
                                modifier = Modifier.padding(start = 8.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        WorkspaceSwitcher(
            workspaces = workspaces,
            active = activeWorkspace,
            countOf = workspaceCountOf,
            hasDirty = workspaceHasDirty,
            onSelect = onSelectWorkspace,
            onCreate = onCreateWorkspace,
            onRename = onRenameWorkspace,
            onDelete = onDeleteWorkspace,
        )
    }
}

@Composable
private fun ConsoleChip(
    console: ConsoleRecord,
    profile: ConnectionProfile?,
    showSourceTag: Boolean,
    active: Boolean,
    dirty: Boolean,
    pendingEdits: Int,
    issue: ExternalFileIssue?,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    onCopyFilePath: () -> Unit,
    onRevealFile: () -> Unit,
    onReloadFromDisk: () -> Unit,
) {
    val menu = buildList {
        add(ContextMenuItem(t(Str.ConsoleRenameTitle)) { onRename() })
        if (console.external) {
            add(ContextMenuItem(t(Str.ExternalCopyPath)) { onCopyFilePath() })
            add(ContextMenuItem(t(Str.ExternalReveal)) { onRevealFile() })
            add(ContextMenuItem(t(Str.ExternalReloadDisk)) { onReloadFromDisk() })
        }
        add(ContextMenuItem(t(Str.EditorCloseConsole)) { onClose() })
        add(ContextMenuItem(t(Str.ConfirmDeleteConsoleTitle)) { onDelete() })
    }
    ContextMenuArea(items = { menu }) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .widthIn(max = 260.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (active) MaterialTheme.colors.primary.copy(alpha = 0.16f)
                    else MaterialTheme.colors.onSurface.copy(alpha = 0.045f),
                )
                .clickable(onClick = onSelect)
                .padding(start = if (profile != null) 5.dp else 9.dp, end = 9.dp, top = 4.dp, bottom = 4.dp),
        ) {
            if (profile != null) {
                TypeBadge(profile.dbType)
                Spacer(Modifier.width(6.dp))
            }
            if (dirty) {
                Text(
                    "●",
                    color = Color(0xFFFFB300), // 语义色：未保存（与状态点同源）
                    fontSize = 9.sp,
                    modifier = Modifier.padding(end = 3.dp),
                )
            }
            if (pendingEdits > 0) {
                // 语义色：该控制台有未提交的结果修改（数量）
                Text(
                    "✦$pendingEdits",
                    color = Color(0xFFFFB300),
                    fontSize = 9.sp,
                    modifier = Modifier.padding(end = 3.dp),
                )
            }
            if (console.external) {
                Icon(
                    DbIcons.Link,
                    contentDescription = t(Str.ExternalLinkTip, console.filePath),
                    tint = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.size(12.dp).padding(end = 3.dp),
                )
            }
            when (issue) {
                ExternalFileIssue.MISSING -> Text(
                    "!",
                    color = Color(0xFFFFB300),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(end = 3.dp),
                )
                ExternalFileIssue.CONFLICT -> Text(
                    "◆",
                    color = Color(0xFFFFB300),
                    fontSize = 8.sp,
                    modifier = Modifier.padding(end = 3.dp),
                )
                null -> Unit
            }
            if (showSourceTag && profile != null) {
                Text(
                    "${profile.name} · ",
                    fontSize = 11.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                )
            }
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

/** 引导区：还没有激活控制台时的空白态 —— 提示打开上方标签/在左侧树点数据源，并给出按数据源新建入口。 */
@Composable
internal fun StarterPane(
    profiles: List<ConnectionProfile>,
    onCreateConsoleAt: (String) -> Unit,
    hasWorkspace: Boolean = true,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            t(Str.EditorNoOpenConsole),
            style = MaterialTheme.typography.subtitle1,
            color = MaterialTheme.colors.onSurface,
        )
        Text(
            t(Str.EditorNoOpenConsoleHint),
            fontSize = 12.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
        )
        if (!hasWorkspace) {
            Text(
                t(Str.StarterNoWorkspaceHint),
                fontSize = 12.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
            )
        }
        if (profiles.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            profiles.forEach { p ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onCreateConsoleAt(p.id) }
                        .background(MaterialTheme.colors.onSurface.copy(alpha = 0.045f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    TypeBadge(p.dbType)
                    Text(
                        t(Str.EditorNewConsoleIn, p.name),
                        fontSize = 12.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.8f),
                        modifier = Modifier.padding(start = 7.dp),
                    )
                }
            }
        }
    }
}
