package app.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.state.ConfirmRequest
import app.state.ConsoleRenameRequest
import app.state.FolderDialogRequest
import db.FolderRow

/** 控制台新建/重命名弹窗（新控制台名可任意起，内容绑定独立 .sql 文件）。 */
@Composable
fun ConsoleNameDialog(
    initial: String,
    isCreate: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isCreate) "新建控制台" else "重命名控制台") },
        text = {
            Column {
                Text(
                    if (isCreate) "控制台绑定一个 SQL 文件，同一数据源可建多个控制台分别工作。"
                    else "控制台对应一个 SQL 文件，改名不影响内容。",
                    style = MaterialTheme.typography.body2,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 文件夹新建/重命名弹窗。 */
@Composable
fun FolderNameDialog(
    request: FolderDialogRequest,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val isCreate = request is FolderDialogRequest.Create
    val initial = (request as? FolderDialogRequest.Rename)?.folder?.name ?: ""
    var name by remember(request) { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isCreate) "新建文件夹" else "重命名文件夹") },
        text = {
            Column {
                Text(
                    if (isCreate) "文件夹用于分组管理连接档案，可再拖入任意数量连接。" else "输入新的文件夹名称：",
                    style = MaterialTheme.typography.body2,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 通用危险操作确认弹窗。 */
@Composable
fun ConfirmDialog(
    request: ConfirmRequest,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val (title, message) = when (request) {
        is ConfirmRequest.DeleteFolder -> {
            val note = if (request.movingConnections > 0) {
                "\n\n其中 ${request.movingConnections} 个连接将移至「未分组」（不会删除连接档案）。"
            } else "\n\n该文件夹下没有连接。"
            "删除文件夹" to "确定删除文件夹「${request.name}」吗？$note"
        }
        is ConfirmRequest.DeleteConnection -> {
            "删除连接" to "确定删除连接「${request.name}」吗？\n\n仅删除本机保存的连接档案，不影响远端数据库。"
        }
        is ConfirmRequest.DeleteConsole -> {
            "删除控制台" to "确定删除控制台「${request.name}」（数据源：${request.connectionName}）吗？\n\n其绑定的 SQL 文件将一并删除，不可恢复。"
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colors.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
