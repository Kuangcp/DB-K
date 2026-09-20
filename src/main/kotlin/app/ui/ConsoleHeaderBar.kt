package app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import app.i18n.t
import db.ConnectionProfile
import engine.model.SchemaMeta
import i18n.I18n
import i18n.Str
import tree.ConnUiStatus
import tree.TypeBadge

@Composable
internal fun WindowScope.HeaderBar(
    isDark: Boolean,
    onToggleTheme: () -> Unit,
    showHistoryButton: Boolean,
    historyOpen: Boolean,
    onToggleHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    mainWindowState: WindowState,
    onWindowCloseRequest: () -> Unit,
    /** 有激活控制台时显示编辑器动作（格式化 / 查找替换）。 */
    showEditorActions: Boolean = false,
    findOpen: Boolean = false,
    onFormatSql: () -> Unit = {},
    onOpenFindReplace: () -> Unit = {},
) {
    val topBarIconTint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
    val isWindowMaximized = mainWindowState.placement == WindowPlacement.Maximized ||
            mainWindowState.placement == WindowPlacement.Fullscreen

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(40.dp),
    ) {
        // 左侧整块可拖拽（等效系统标题栏）；窗口控制按钮在右侧、不可拖拽。
        WindowDraggableArea(
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            ) {
                if (showEditorActions) {
                    IconButton(onClick = onFormatSql, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = DbIcons.Format,
                            contentDescription = t(Str.EditorFormatSql),
                            tint = topBarIconTint,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                    IconButton(onClick = onOpenFindReplace, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = DbIcons.FindReplace,
                            contentDescription = t(Str.EditorFindReplace),
                            tint = if (findOpen) MaterialTheme.colors.primary
                            else topBarIconTint,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
                // 左侧留白：后续在此放更多工具 icon（标题文字已去掉）
                Spacer(Modifier.weight(1f))
                if (showHistoryButton) {
                    IconButton(onClick = onToggleHistory, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = DbIcons.History,
                            contentDescription = if (historyOpen) t(Str.EditorCollapseHistory) else t(Str.EditorOpenHistory),
                            tint = if (historyOpen) MaterialTheme.colors.primary
                            else topBarIconTint,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
                IconButton(onClick = onToggleTheme, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = if (isDark) DbIcons.Sun else DbIcons.Moon,
                        contentDescription = if (isDark) t(Str.EditorSwitchToLight) else t(Str.EditorSwitchToDark),
                        tint = topBarIconTint,
                        modifier = Modifier.size(17.dp),
                    )
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = t(Str.SettingsTitle),
                        tint = topBarIconTint,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
        IconButton(
            onClick = { mainWindowState.isMinimized = true },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = DbIcons.WindowMinimize,
                contentDescription = t(Str.WindowMinimize),
                tint = topBarIconTint,
                modifier = Modifier.size(16.dp),
            )
        }
        IconButton(
            onClick = {
                mainWindowState.placement = if (isWindowMaximized) {
                    WindowPlacement.Floating
                } else {
                    WindowPlacement.Maximized
                }
            },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = if (isWindowMaximized) DbIcons.WindowRestore else DbIcons.WindowMaximize,
                contentDescription = if (isWindowMaximized) t(Str.WindowRestore) else t(Str.WindowMaximize),
                tint = topBarIconTint,
                modifier = Modifier.size(16.dp),
            )
        }
        IconButton(
            onClick = onWindowCloseRequest,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = t(Str.ViewerClose),
                tint = topBarIconTint,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 绑定数据源导航行：展示当前控制台的数据源（点击可切到其它数据源），右侧是执行目标切换与断开。 */
@Composable
internal fun ConnectionNavBar(
    profile: ConnectionProfile,
    status: ConnUiStatus,
    statusMessage: String?,
    profiles: List<ConnectionProfile>,
    onSelectProfile: (String) -> Unit,
    onDisconnect: () -> Unit,
    supportsTargetSwitch: Boolean,
    /** 执行目标切换器标签（SQL = 「目标」；Redis DB = 「DB」）。 */
    targetLabel: String,
    /** 是否显示「默认（连接库）」选项。 */
    targetAllowDefault: Boolean,
    /** 该数据源的库/schema 列表（null = 未连接/未加载完成）。 */
    schemas: List<SchemaMeta>?,
    /** 当前控制台已选目标库/schema（"" = 连接默认）。 */
    target: String,
    onSelectTarget: (String) -> Unit,
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
                    Icons.Filled.ArrowDropDown, t(Str.EditorSwitchSource),
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
        if (supportsTargetSwitch) {
            TargetSwitcher(
                enabled = status == ConnUiStatus.CONNECTED && schemas != null,
                loading = status == ConnUiStatus.CONNECTED && schemas == null,
                title = targetLabel,
                allowDefault = targetAllowDefault,
                schemas = schemas.orEmpty(),
                target = target,
                onSelectTarget = onSelectTarget,
            )
        }
        if (status == ConnUiStatus.CONNECTED) {
            TextButton(onClick = onDisconnect) { Text(t(Str.EditorDisconnect), fontSize = 12.sp) }
        }
    }
}

/**
 * 执行目标切换：每个控制台可选其数据源下的库/schema（PG 即 schema、MySQL 即库），
 * 下次执行前自动发出 USE / SET search_path 前导。"" = 默认（连接库/连接默认）。
 */
@Composable
private fun TargetSwitcher(
    enabled: Boolean,
    loading: Boolean,
    title: String,
    allowDefault: Boolean,
    schemas: List<SchemaMeta>,
    target: String,
    onSelectTarget: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val label = if (target.isBlank()) t(Str.EditorTargetDefault) else target
    // DropdownMenu 的定位锤点是「与它同一父布局」的节点；本组件自身不产生布局节点，
    // 若把锤点 Row 与 DropdownMenu 直接放进父 Row，弹层会按整行（全宽）换算 → 跑到左上角。
    // 用 Box 把锤点与弹层包在一起，弹层即贴着「目标」按钮弹出。
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(5.dp))
                .clickable(enabled = enabled) { open = true }
                .padding(horizontal = 6.dp, vertical = 3.dp),
        ) {
            Text(
                title,
                fontSize = 10.5.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.35f),
            )
            Text(
                if (loading) t(Str.EditorTargetLoading) else label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colors.onSurface.copy(alpha = 0.75f)
                else MaterialTheme.colors.onSurface.copy(alpha = 0.35f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 5.dp).widthIn(max = 150.dp),
            )
            Icon(
                Icons.Filled.ArrowDropDown, t(Str.EditorSwitchTarget),
                tint = MaterialTheme.colors.onSurface.copy(alpha = if (enabled) 0.5f else 0.25f),
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (allowDefault) {
                DropdownMenuItem(onClick = {
                    open = false
                    if (target != "") onSelectTarget("")
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (target.isBlank()) "✓ " else "  ",
                            fontSize = 12.sp,
                            color = MaterialTheme.colors.primary,
                        )
                        Text(
                            t(Str.EditorTargetDefaultHint),
                            fontSize = 13.sp,
                            color = if (target.isBlank()) MaterialTheme.colors.primary
                            else MaterialTheme.colors.onSurface.copy(alpha = 0.75f),
                        )
                    }
                }
            }
            schemas.forEach { s ->
                val name = s.displayName
                val selected = name.equals(target, ignoreCase = true)
                DropdownMenuItem(onClick = {
                    open = false
                    if (!selected) onSelectTarget(name)
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (selected) "✓ " else "  ",
                            fontSize = 12.sp,
                            color = MaterialTheme.colors.primary,
                        )
                        Text(
                            name,
                            fontSize = 13.sp,
                            color = if (selected) MaterialTheme.colors.primary
                            else MaterialTheme.colors.onSurface.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

internal fun statusLabel(status: ConnUiStatus): String = when (status) {
    ConnUiStatus.DISCONNECTED -> I18n.t(Str.TreeDisconnected)
    ConnUiStatus.CONNECTING -> I18n.t(Str.TreeMenuConnecting)
    ConnUiStatus.CONNECTED -> I18n.t(Str.ConnStatusConnected)
    ConnUiStatus.ERROR -> I18n.t(Str.TreeConnectFailed)
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
