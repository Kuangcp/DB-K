package app.dialog

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.AlertDialog
import androidx.compose.material.Checkbox
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jdbc.CellValue

/**
 * 长值 / 多行单元格编辑弹窗（`doc/EDITABLE_RESULT.md` §8）。
 *
 * - NULL 与空串**严格区分**：勾选「设为 NULL」写入 SQL NULL；否则写入文本框内容（可为空串）。
 * - `Ctrl+Enter` 提交、`Esc` 取消；多行文本中 `Enter` 换行。
 * - 颜色全走主题（`onSurface`），无硬编码文字色。
 */
@Composable
fun EditCellDialog(
    title: String,
    initial: String?,
    onDismiss: () -> Unit,
    onConfirm: (CellValue) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial.orEmpty()) }
    var isNull by remember(initial) { mutableStateOf(initial == null) }
    val commit = { onConfirm(CellValue(if (isNull) null else text)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 1) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isNull, onCheckedChange = { isNull = it })
                    Text(
                        "设为 NULL（与空串不同）",
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.75f),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .padding(top = 4.dp)
                        .border(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.25f))
                        .padding(6.dp)
                        .onPreviewKeyEvent { e ->
                            when {
                                e.type == KeyEventType.KeyDown && e.isCtrlPressed &&
                                    (e.key == Key.Enter || e.key == Key.NumPadEnter) -> {
                                    commit()
                                    true
                                }
                                e.type == KeyEventType.KeyDown && e.key == Key.Escape -> {
                                    onDismiss()
                                    true
                                }
                                else -> false
                            }
                        },
                ) {
                    if (isNull) {
                        Text(
                            "(NULL)",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                        )
                    } else {
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            textStyle = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = MaterialTheme.colors.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colors.primary),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Text(
                    "Ctrl+Enter 提交 · Esc 取消 · Enter 换行",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = commit) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
