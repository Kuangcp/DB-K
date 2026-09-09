package app.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.state.ConnectionEditorRequest
import db.ConnectionProfile
import db.DbType
import db.FolderRow

/**
 * 连接档案编辑弹窗：名称 / 类型 / 文件夹 / 主机 / 端口 / 库 / 账号 / 附加参数，
 * 底部实时预览 JDBC URL。SQLite 类型时隐藏服务端相关字段，database 视为文件路径。
 */
@Composable
fun ConnectionEditorDialog(
    request: ConnectionEditorRequest,
    folders: List<FolderRow>,
    onDismiss: () -> Unit,
    onSubmit: (ConnectionProfile) -> Unit,
) {
    val isEdit = request is ConnectionEditorRequest.Edit
    val initial = (request as? ConnectionEditorRequest.Edit)?.profile
    val createFolderId = (request as? ConnectionEditorRequest.Create)?.folderId

    var name by remember(request) { mutableStateOf(initial?.name ?: "") }
    var dbType by remember(request) { mutableStateOf(initial?.dbType ?: DbType.POSTGRES) }
    var folderId by remember(request) { mutableStateOf(initial?.folderId ?: createFolderId) }
    var host by remember(request) { mutableStateOf(initial?.host ?: "localhost") }
    var portText by remember(request) { mutableStateOf((initial?.port ?: dbType.defaultPort).toString()) }
    var database by remember(request) { mutableStateOf(initial?.database ?: "") }
    var user by remember(request) { mutableStateOf(initial?.user ?: "") }
    var password by remember(request) { mutableStateOf(initial?.password ?: "") }
    var extra by remember(request) { mutableStateOf(initial?.extraParams ?: "") }

    val isEmbedded = dbType == DbType.SQLITE
    val port = portText.toIntOrNull()

    fun build(): ConnectionProfile = ConnectionProfile(
        id = initial?.id ?: "",
        name = name.trim(),
        folderId = folderId,
        dbType = dbType,
        host = if (isEmbedded) "" else host.trim(),
        port = if (isEmbedded || port == null) dbType.defaultPort else port,
        database = database.trim(),
        user = if (isEmbedded) null else user.trim().ifEmpty { null },
        password = if (isEmbedded) null else password.takeIf { it.isNotEmpty() },
        extraParams = if (isEmbedded) "" else extra.trim(),
    )

    val canSubmit = name.isNotBlank() && database.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "编辑连接" else "新建连接") },
        text = {
            Column(
                modifier = Modifier
                    .width(460.dp)
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FormRow("名称") {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
                FormRow("类型") {
                    DropdownField(
                        label = dbType.label,
                        options = DbType.entries.map { it.label },
                        onSelect = { idx ->
                            val next = DbType.entries[idx]
                            // 切换类型时若端口还是旧默认值则跟随新默认
                            if (port == null || port == dbType.defaultPort) {
                                portText = next.defaultPort.toString()
                            }
                            dbType = next
                        },
                    )
                }
                FormRow("文件夹") {
                    DropdownField(
                        label = folderId?.let { fid -> folders.firstOrNull { it.id == fid }?.name } ?: "未分组",
                        options = listOf("未分组") + folders.map { it.name },
                        onSelect = { idx ->
                            folderId = if (idx == 0) null else folders[idx - 1].id
                        },
                    )
                }
                if (!isEmbedded) {
                    FormRow("主机") {
                        OutlinedTextField(
                            value = host, onValueChange = { host = it },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    FormRow("端口") {
                        OutlinedTextField(
                            value = portText, onValueChange = { portText = it.filter { c -> c.isDigit() } },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                FormRow(if (isEmbedded) "文件路径" else "数据库") {
                    OutlinedTextField(
                        value = database, onValueChange = { database = it },
                        singleLine = true,
                        placeholder = { Text(if (isEmbedded) "/path/to/demo.db" else dbType.label.lowercase()) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (!isEmbedded) {
                    FormRow("用户名") {
                        OutlinedTextField(
                            value = user, onValueChange = { user = it },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    FormRow("密码") {
                        OutlinedTextField(
                            value = password, onValueChange = { password = it },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    FormRow("附加参数") {
                        OutlinedTextField(
                            value = extra, onValueChange = { extra = it },
                            singleLine = true,
                            placeholder = { Text("sslMode=require&connectTimeout=5") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (!isEmbedded && database.isNotBlank()) {
                    Text(
                        "JDBC URL：${build().urlPreview()}",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canSubmit, onClick = { onSubmit(build()) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun FormRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.75f),
            modifier = Modifier.width(76.dp),
        )
        Row(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/** 简易下拉框（Material2 无 ExposedDropdownMenu，自行组合）。 */
@Composable
private fun DropdownField(
    label: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "选择")
                }
            },
            modifier = Modifier.fillMaxWidth().clickable { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(onClick = { onSelect(index); expanded = false }) {
                    Text(option)
                }
            }
        }
    }
}
