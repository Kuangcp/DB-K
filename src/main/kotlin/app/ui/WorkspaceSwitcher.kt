package app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.i18n.t
import db.WorkspaceRecord
import i18n.Str

/** 工作区下拉项行高：比标题栏工具菜单（26dp）稍高，容下名称 + 计数 + hover 图标。 */
private val WORKSPACE_ITEM_H = 30.dp

/**
 * 标签条右侧的工作区切换器：当前工作区名 + 下拉。
 * 下拉项 = 工作区列表（`名字 · N`，有未保存带 ●，当前项 ✓）；悬停行尾浮现「重命名 / 删除」；
 * 底部固定「新建工作区…」。零工作区时按钮显示「无工作区」，下拉只剩新建项。
 */
@Composable
internal fun WorkspaceSwitcher(
    workspaces: List<WorkspaceRecord>,
    active: WorkspaceRecord?,
    countOf: (String) -> Int,
    hasDirty: (String) -> Boolean,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onRename: (WorkspaceRecord) -> Unit,
    onDelete: (WorkspaceRecord) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colors.onSurface.copy(alpha = 0.045f))
                .clickable { open = true }
                .padding(start = 8.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        ) {
            Text(
                active?.let { displayName(it) } ?: t(Str.WorkspaceNoWorkspace),
                fontSize = 12.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = if (active != null) 0.85f else 0.4f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(120.dp),
            )
            Icon(
                Icons.Filled.ArrowDropDown,
                t(Str.WorkspaceMenuTooltip),
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            workspaces.forEach { ws ->
                WorkspaceRow(
                    ws = ws,
                    current = ws.id == active?.id,
                    count = countOf(ws.id),
                    dirty = hasDirty(ws.id),
                    onSelect = { open = false; onSelect(ws.id) },
                    onRename = { open = false; onRename(ws) },
                    onDelete = { open = false; onDelete(ws) },
                )
            }
            if (workspaces.isNotEmpty()) Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.08f))
            CompactMenuItem(onClick = { open = false; onCreate() }, height = WORKSPACE_ITEM_H) {
                Text(t(Str.WorkspaceNew), fontSize = 12.5.sp, color = MaterialTheme.colors.onSurface.copy(alpha = 0.85f))
            }
        }
    }
}

/** 工作区名渲染：auto_named 跟随语言，用户改名后用存储值。 */
@Composable
internal fun displayName(ws: WorkspaceRecord): String =
    if (ws.autoNamed) t(Str.WorkspaceDefaultName) else ws.name

@Composable
private fun WorkspaceRow(
    ws: WorkspaceRecord,
    current: Boolean,
    count: Int,
    dirty: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var hovering by remember { mutableStateOf(false) }
    CompactMenuItem(
        onClick = onSelect,
        height = WORKSPACE_ITEM_H,
        modifier = Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    when (awaitPointerEvent().type) {
                        PointerEventType.Enter -> hovering = true
                        PointerEventType.Exit -> hovering = false
                        else -> Unit
                    }
                }
            }
        },
    ) {
        Icon(
            Icons.Filled.Check,
            null,
            tint = if (current) MaterialTheme.colors.primary else Color.Transparent,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        if (dirty) {
            Text("●", color = Color(0xFFFFB300), fontSize = 9.sp, modifier = Modifier.padding(end = 3.dp))
        }
        Text(
            t(Str.WorkspaceWithCount, displayName(ws), count),
            fontSize = 12.5.sp,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            color = if (current) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(alpha = 0.85f),
        )
        if (hovering) {
            Spacer(Modifier.width(10.dp))
            Icon(
                Icons.Filled.Edit,
                t(Str.WorkspaceRenameTitle),
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp).clickable(onClick = onRename),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Filled.Delete,
                t(Str.WorkspaceDeleteTitle),
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp).clickable(onClick = onDelete),
            )
        }
    }
}
