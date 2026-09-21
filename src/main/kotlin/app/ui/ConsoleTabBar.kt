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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.i18n.t
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
    workspaces: List<WorkspaceRecord> = emptyList(),
    activeWorkspace: WorkspaceRecord? = null,
    workspaceCountOf: (String) -> Int = { 0 },
    workspaceHasDirty: (String) -> Boolean = { false },
    onSelectWorkspace: (String) -> Unit = {},
    onCreateWorkspace: () -> Unit = {},
    onRenameWorkspace: (WorkspaceRecord) -> Unit = {},
    onDeleteWorkspace: (WorkspaceRecord) -> Unit = {},
) {
    var createMenuOpen by remember { mutableStateOf(false) }
    // 条内出现多个数据源时，标签额外显示所属数据源名，避免同类型两个库分不清
    val multiSource = consoles.map { it.connectionId }.distinct().size > 1
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(38.dp).padding(start = 8.dp, end = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
        ) {
            consoles.forEach { c ->
                ConsoleChip(
                    console = c,
                    profile = profilesById[c.connectionId],
                    showSourceTag = multiSource,
                    active = c.id == activeConsole?.id,
                    dirty = c.id in dirtyConsoleIds,
                    pendingEdits = pendingEditCounts[c.id] ?: 0,
                    onSelect = { onSelectConsole(c) },
                    onRename = { onRenameConsole(c) },
                    onDelete = { onDeleteConsole(c) },
                    onClose = { onCloseConsole(c) },
                )
                Spacer(Modifier.width(5.dp))
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
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val menu = listOf(
        ContextMenuItem(t(Str.ConsoleRenameTitle)) { onRename() },
        ContextMenuItem(t(Str.EditorCloseConsole)) { onClose() },
        ContextMenuItem(t(Str.ConfirmDeleteConsoleTitle)) { onDelete() },
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
