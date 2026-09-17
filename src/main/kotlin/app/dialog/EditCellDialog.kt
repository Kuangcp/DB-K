package app.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import jdbc.CellValue

/** 按初始内容估算弹窗尺寸：宽度按最长行，高度按行数；夹在合理区间内，超长时才出滚动条。 */
private fun dialogSizeFor(initial: String?): DpSize {
    val text = initial ?: ""
    val lineCount = text.count { it == '\n' } + 1
    val longest = text.lineSequence().maxOfOrNull { it.length } ?: 0
    // 等宽 13sp 粗略按 8dp/字符、20dp/行；再加标题/勾选/提示/按钮与内边距的固定 chrome。
    val w = (longest * 8f + 40f).coerceIn(480f, 980f)
    val h = (lineCount * 20f + 132f).coerceIn(480f, 760f)
    return DpSize(w.dp, h.dp)
}

/**
 * 长值 / 多行单元格编辑弹窗（`doc/EDITABLE_RESULT.md` §8）。
 *
 * - NULL 与空串**严格区分**：勾选「设为 NULL」写入 SQL NULL；否则写入文本框内容（可为空串）。
 * - `Ctrl+Enter` 提交、`Esc` 取消；多行文本中 `Enter` 换行。
 * - 颜色全走主题（`onSurface`），无硬编码文字色。
 * - 用**显式尺寸**的 [DialogWindow]（不是 AlertDialog）：AlertDialog 的窗口会 pack-to-content，
 *   滚动节点在 pack 测量时把窗口撑成内容全高，滚动后内容整体移出窗口（表现为大片留白）。
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
    // 尺寸只在打开时按 initial 算一次（可手动缩放）；打开后打字不再改窗口大小，避免跳变。
    val windowSize = remember(initial) { dialogSizeFor(initial) }

    DialogWindow(
        onCloseRequest = onDismiss,
        state = rememberDialogState(size = windowSize),
        title = title,
        resizable = true,
        onPreviewKeyEvent = { e ->
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background)
                .padding(12.dp),
        ) {
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
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .border(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.25f))
                    .padding(6.dp),
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = commit) { Text("确定") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    }
}
