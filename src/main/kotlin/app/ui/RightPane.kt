package app.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import db.ConnectionProfile
import db.DbType
import db.FolderRow
import tree.ConnUiStatus
import tree.TypeBadge

/**
 * 右侧面板（M1）：数据源概览 + 选中连接详情。
 * M2 起此区替换为 SQL 编辑器与结果区。
 */
@Composable
fun RightPane(
    folders: List<FolderRow>,
    connections: List<ConnectionProfile>,
    selectedProfile: ConnectionProfile?,
    isDark: Boolean,
    onToggleTheme: () -> Unit,
    onSelectConnection: (String) -> Unit,
    onEdit: (ConnectionProfile) -> Unit,
    onDelete: (ConnectionProfile) -> Unit,
    onCopyUrl: (ConnectionProfile) -> Unit,
    /** M2：连接运行状态与动作。 */
    statusOf: (String) -> ConnUiStatus = { ConnUiStatus.DISCONNECTED },
    statusMessageOf: (String) -> String? = { null },
    onConnect: (ConnectionProfile) -> Unit = {},
    onDisconnect: (ConnectionProfile) -> Unit = {},
    onRefreshSchemas: (ConnectionProfile) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        ) {
            Text("数据源", style = MaterialTheme.typography.subtitle2)
            Text(
                "（${connections.size}）",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onToggleTheme) {
                Text(if (isDark) "浅色" else "深色", fontSize = 12.sp)
            }
        }
        Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.08f))
        if (connections.isEmpty()) {
            EmptyState()
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 选中连接的详情卡
                selectedProfile?.let { p ->
                    ConnectionDetailCard(
                        profile = p,
                        folderName = p.folderId?.let { fid -> folders.firstOrNull { it.id == fid }?.name },
                        status = statusOf(p.id),
                        statusMessage = statusMessageOf(p.id),
                        onEdit = { onEdit(p) },
                        onDelete = { onDelete(p) },
                        onCopyUrl = { onCopyUrl(p) },
                        onConnect = { onConnect(p) },
                        onDisconnect = { onDisconnect(p) },
                        onRefreshSchemas = { onRefreshSchemas(p) },
                    )
                }
                // 分组概览
                folders.sortedBy { it.sortOrder }.forEach { folder ->
                    val group = connections.filter { it.folderId == folder.id }.sortedBy { it.sortOrder }
                    if (group.isNotEmpty()) {
                        GroupHeader(folder.name, group.size)
                        group.forEach { conn ->
                            ConnectionRowCard(
                                conn,
                                selected = conn.id == selectedProfile?.id,
                                status = statusOf(conn.id),
                                onClick = { onSelectConnection(conn.id) },
                            )
                        }
                    }
                }
                val loose = connections.filter { it.folderId == null }.sortedBy { it.sortOrder }
                if (loose.isNotEmpty()) {
                    GroupHeader("未分组", loose.size)
                    loose.forEach { conn ->
                        ConnectionRowCard(
                            conn,
                            selected = conn.id == selectedProfile?.id,
                            status = statusOf(conn.id),
                            onClick = { onSelectConnection(conn.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(name: String, count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
    ) {
        Box(
            modifier = Modifier.size(width = 2.dp, height = 10.dp).clip(RoundedCornerShape(1.dp))
                .background(MaterialTheme.colors.primary.copy(alpha = 0.5f)),
        )
        Text(
            name,
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(start = 6.dp),
        )
        Text(
            " $count",
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.35f),
        )
    }
}

@Composable
private fun ConnectionRowCard(
    conn: ConnectionProfile,
    selected: Boolean,
    status: ConnUiStatus,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    selected -> MaterialTheme.colors.primary.copy(alpha = 0.10f)
                    else -> MaterialTheme.colors.surface
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        TypeBadge(conn.dbType)
        Column(modifier = Modifier.padding(start = 8.dp).weight(1f)) {
            Text(conn.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                connSubtitle(conn),
                fontSize = 11.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ConnStatusPoint(status)
    }
}

@Composable
private fun ConnectionDetailCard(
    profile: ConnectionProfile,
    folderName: String?,
    status: ConnUiStatus,
    statusMessage: String?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCopyUrl: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRefreshSchemas: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colors.surface)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TypeBadge(profile.dbType)
            Text(
                profile.name,
                style = MaterialTheme.typography.subtitle1,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            ConnStatusPoint(status)
            if (status == ConnUiStatus.CONNECTED) {
                Text(
                    "已连接",
                    fontSize = 10.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp),
                )
            }
            IconButton(onClick = onCopyUrl, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Filled.Share, "复制 JDBC URL", tint = MaterialTheme.colors.onSurface.copy(alpha = 0.6f), modifier = Modifier.size(15.dp))
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Filled.Edit, "编辑", tint = MaterialTheme.colors.onSurface.copy(alpha = 0.6f), modifier = Modifier.size(15.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Filled.Delete, "删除", tint = MaterialTheme.colors.error.copy(alpha = 0.8f), modifier = Modifier.size(15.dp))
            }
        }
        if (statusMessage != null && status == ConnUiStatus.ERROR) {
            Text(
                statusMessage,
                fontSize = 11.sp,
                color = Color(0xFFE53935),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            when (status) {
                ConnUiStatus.DISCONNECTED -> ActionButton("连接", primary = true) { onConnect() }
                ConnUiStatus.CONNECTING -> ActionButton("连接中…", primary = true, enabled = false) {}
                ConnUiStatus.ERROR -> ActionButton("重新连接", primary = true) { onConnect() }
                ConnUiStatus.CONNECTED -> {
                    ActionButton("断开", primary = false) { onDisconnect() }
                    Spacer(Modifier.width(8.dp))
                    ActionButton("刷新库列表", primary = false) { onRefreshSchemas() }
                }
            }
        }
        DetailLine("类型", profile.dbType.label)
        DetailLine("文件夹", folderName ?: "未分组")
        if (profile.dbType == DbType.SQLITE) {
            DetailLine("文件路径", profile.database)
        } else {
            DetailLine("主机", profile.host)
            DetailLine("端口", profile.port.toString())
            DetailLine("数据库", profile.database)
            DetailLine("用户名", profile.user ?: "(空)")
        }
        if (profile.extraParams.isNotBlank()) DetailLine("附加参数", profile.extraParams)
        DetailLine("JDBC URL", profile.urlPreview())
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.width(64.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.85f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ActionButton(label: String, primary: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(
            label,
            fontSize = 12.sp,
            color = if (!enabled) MaterialTheme.colors.onSurface.copy(alpha = 0.3f)
            else if (primary) MaterialTheme.colors.primary
            else MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun ConnStatusPoint(status: ConnUiStatus) {
    val color = when (status) {
        ConnUiStatus.DISCONNECTED -> Color(0xFF9E9E9E)
        ConnUiStatus.CONNECTING -> Color(0xFFFFB300)
        ConnUiStatus.CONNECTED -> Color(0xFF43A047)
        ConnUiStatus.ERROR -> Color(0xFFE53935)
    }
    Box(
        modifier = Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color),
    )
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "从左侧「新建连接」添加第一个数据源",
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
        )
    }
}

private fun connSubtitle(conn: ConnectionProfile): String = when (conn.dbType) {
    DbType.SQLITE -> conn.database
    DbType.H2 -> "${conn.host}:${conn.port}/${conn.database}"
    else -> "${conn.host}:${conn.port}/${conn.database}"
}
