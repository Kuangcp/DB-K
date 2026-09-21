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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import app.i18n.t
import app.i18n.tn
import app.state.ConfirmRequest
import app.state.ConsoleRenameRequest
import app.state.FolderDialogRequest
import app.state.WorkspaceDialogRequest
import db.FolderRow
import i18n.Str

/**
 * 单行输入弹窗的 Enter 提交处理：Enter / 数字键盘 Enter 等同于点「确定」（空白时忽略）。
 * 挂在输入框的 Modifier 上（onPreviewKeyEvent），保证单行 TextField 不吞掉回车。
 */
internal fun submitOnEnter(enabled: Boolean, onConfirm: () -> Unit): (KeyEvent) -> Boolean = { e ->
    if (enabled && e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) {
        onConfirm()
        true
    } else {
        false
    }
}

/** 控制台新建/重命名弹窗（新控制台名可任意起，内容绑定独立 .sql 文件）。 */
@Composable
fun ConsoleNameDialog(
    initial: String,
    isCreate: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    val confirm = { onConfirm(name.trim()) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(if (isCreate) Str.ConsoleCreateTitle else Str.ConsoleRenameTitle)) },
        text = {
            Column {
                Text(
                    t(if (isCreate) Str.ConsoleCreateHint else Str.ConsoleRenameHint),
                    style = MaterialTheme.typography.body2,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(t(Str.CommonName)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent(submitOnEnter(name.isNotBlank(), confirm)),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = confirm) { Text(t(Str.CommonOk)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t(Str.CommonCancel)) }
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
    val confirm = { onConfirm(name.trim()) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(request) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(if (isCreate) Str.FolderCreateTitle else Str.FolderRenameTitle)) },
        text = {
            Column {
                Text(
                    t(if (isCreate) Str.FolderCreateHint else Str.FolderRenameHint),
                    style = MaterialTheme.typography.body2,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(t(Str.CommonName)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent(submitOnEnter(name.isNotBlank(), confirm)),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = confirm) { Text(t(Str.CommonOk)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t(Str.CommonCancel)) }
        },
    )
}

/** 工作区新建/重命名弹窗（与其它单字段弹窗一致：Enter 提交、空输入忽略）。 */
@Composable
fun WorkspaceNameDialog(
    request: WorkspaceDialogRequest,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val isCreate = request is WorkspaceDialogRequest.Create
    val initial = (request as? WorkspaceDialogRequest.Rename)?.workspace?.name ?: ""
    var name by remember(request) { mutableStateOf(initial) }
    val confirm = { onConfirm(name.trim()) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(request) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(if (isCreate) Str.WorkspaceNewTitle else Str.WorkspaceRenameTitle)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(t(Str.CommonName)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent(submitOnEnter(name.isNotBlank(), confirm)),
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = confirm) { Text(t(Str.CommonOk)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(Str.CommonCancel)) } },
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
            val parts = mutableListOf<String>()
            if (request.movingConnections > 0) {
                parts += tn(
                    Str.ConfirmFolderMovedConnectionsOne,
                    Str.ConfirmFolderMovedConnectionsOther,
                    request.movingConnections,
                )
            }
            if (request.childFolders > 0) {
                parts += tn(
                    Str.ConfirmFolderMovedFoldersOne,
                    Str.ConfirmFolderMovedFoldersOther,
                    request.childFolders,
                )
            }
            val note = if (parts.isNotEmpty()) {
                t(Str.ConfirmFolderNoteKeep, parts.joinToString(t(Str.CommonListSeparator)))
            } else {
                t(Str.ConfirmFolderEmpty)
            }
            t(Str.ConfirmDeleteFolderTitle) to t(Str.ConfirmDeleteFolderMessage, request.name, note)
        }
        is ConfirmRequest.DeleteConnection -> {
            t(Str.ConfirmDeleteConnectionTitle) to t(Str.ConfirmDeleteConnectionMessage, request.name)
        }
        is ConfirmRequest.DeleteConsole -> {
            t(Str.ConfirmDeleteConsoleTitle) to
                t(Str.ConfirmDeleteConsoleMessage, request.name, request.connectionName)
        }
        is ConfirmRequest.DeleteWorkspace -> {
            t(Str.WorkspaceDeleteTitle) to t(Str.WorkspaceDeleteConfirm, request.name)
        }
        is ConfirmRequest.DiscardResultEdits -> {
            t(Str.ConfirmDiscardTitle) to t(Str.ConfirmDiscardMessage, request.count, t(request.action))
        }
        is ConfirmRequest.DeleteRows -> {
            t(Str.ConfirmDeleteRowsTitle) to t(Str.ConfirmDeleteRowsMessage, request.count)
        }
        is ConfirmRequest.DangerConfirm -> {
            t(Str.ConfirmDangerTitle) to t(Str.ConfirmDangerMessage, request.command)
        }
    }
    val confirmLabel = when (request) {
        is ConfirmRequest.DiscardResultEdits -> t(Str.CommonContinue)
        is ConfirmRequest.DeleteRows -> t(Str.CommonContinue)
        is ConfirmRequest.DangerConfirm -> t(Str.CommonExecute)
        else -> t(Str.CommonDelete)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colors.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t(Str.CommonCancel)) }
        },
    )
}
