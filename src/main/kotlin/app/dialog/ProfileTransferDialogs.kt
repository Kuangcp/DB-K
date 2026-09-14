package app.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.state.ImportConflictRequest
import app.state.PassphraseRequest

/**
 * 导出加密 / 导入解密的口令弹窗。
 * 导出时带二次确认（防打错导致文件无法再打开）；[PassphraseRequest.onSubmit] 返回错误则保持打开。
 */
@Composable
fun PassphraseDialog(
    request: PassphraseRequest,
    onDismiss: () -> Unit,
) {
    var passphrase by remember(request) { mutableStateOf("") }
    var confirm by remember(request) { mutableStateOf("") }
    var error by remember(request) { mutableStateOf<String?>(null) }
    val mismatch = request.requireConfirmation && confirm.isNotEmpty() && passphrase != confirm
    val canSubmit = passphrase.isNotBlank() && (!request.requireConfirmation || passphrase == confirm)
    val submit: () -> Unit = {
        if (canSubmit) {
            val err = request.onSubmit(passphrase)
            if (err == null) onDismiss() else error = err
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(request.title) },
        text = {
            Column {
                Text(request.message, style = MaterialTheme.typography.body2)
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it; error = null },
                    label = { Text("口令") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .onPreviewKeyEvent(submitOnEnter(canSubmit, submit)),
                )
                if (request.requireConfirmation) {
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it; error = null },
                        label = { Text("确认口令") },
                        singleLine = true,
                        isError = mismatch,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .onPreviewKeyEvent(submitOnEnter(canSubmit, submit)),
                    )
                    if (mismatch) {
                        Text(
                            "两次输入的口令不一致",
                            color = MaterialTheme.colors.error,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colors.error,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canSubmit, onClick = submit) { Text(request.confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 导入同名冲突弹窗：逐项选择「跳过」或「导入为新档案」，另提供全部快捷选择。
 * 确认后经 [ImportConflictRequest.onSubmit] 回传「导入连接 id → 是否新建」。
 */
@Composable
fun ImportConflictDialog(
    request: ImportConflictRequest,
    onDismiss: () -> Unit,
) {
    // true = 导入为新档案（默认），false = 跳过
    var choices by remember(request) {
        mutableStateOf(request.conflicts.associate { it.id to true })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("存在同名数据源") },
        text = {
            Column {
                Text(
                    "导入的 ${request.conflicts.size} 个数据源与现有数据源同名。请逐项选择处理方式。",
                    style = MaterialTheme.typography.body2,
                )
                Row(modifier = Modifier.padding(top = 6.dp)) {
                    TextButton(
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        onClick = { choices = choices.mapValues { false } },
                    ) { Text("全部跳过", fontSize = 12.sp) }
                    TextButton(
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        onClick = { choices = choices.mapValues { true } },
                    ) { Text("全部新建", fontSize = 12.sp) }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    request.conflicts.forEach { c ->
                        val asNew = choices[c.id] ?: true
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        ) {
                            Text(
                                c.name,
                                style = MaterialTheme.typography.body2,
                                color = MaterialTheme.colors.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            ToggleText("跳过", selected = !asNew) { choices = choices + (c.id to false) }
                            Spacer(Modifier.width(4.dp))
                            ToggleText("新建", selected = asNew) { choices = choices + (c.id to true) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { request.onSubmit(choices); onDismiss() }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 轻量二选一文本开关（选中走主题主色，未选中走次要文字色）。 */
@Composable
private fun ToggleText(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = if (selected) MaterialTheme.colors.primary
            else MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
        )
    }
}
