package app.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Checkbox
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.core.export.ExportFormat
import app.core.export.ExportOptions
import app.state.ExportRequest

/**
 * 结果导出弹窗（N8）：选格式（CSV / JSON / SQL INSERT / Excel）与参数。
 * 结果被截断（>1000 行）时可勾选「全量流式」——重跑 SQL，按方言游标逐行导出，不受行数上限。
 * 确认后由 Main 打开系统文件对话框落盘。
 */
@Composable
fun ExportDialog(
    request: ExportRequest,
    onDismiss: () -> Unit,
    onConfirm: (ExportFormat, ExportOptions, Boolean) -> Unit,
) {
    var format by remember { mutableStateOf(ExportFormat.CSV) }
    var tableName by remember { mutableStateOf(request.defaultTableName) }
    var batchSizeText by remember { mutableStateOf("100") }
    var prettyJson by remember { mutableStateOf(true) }
    // 有单元格被截断时强制全量流式（重跑 SQL 取完整值，否则会导出截断标记）
    var fullStream by remember { mutableStateOf(request.cellsTruncated) }
    val forceStream = request.cellsTruncated

    val batchSize = batchSizeText.toIntOrNull()?.coerceIn(1, 10_000) ?: 100
    val canConfirm = format != ExportFormat.SQL_INSERT || tableName.isNotBlank()
    val secondary = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出结果", color = MaterialTheme.colors.onSurface) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    buildString {
                        append("当前 ${request.rowCount} 行")
                        when {
                            request.cellsTruncated -> append("（含超大字段，已截断展示；导出将重跑 SQL 取完整值）")
                            request.truncated -> append("（结果已截断，可勾选下方「全量流式」导出全部）")
                        }
                    },
                    fontSize = 12.sp,
                    color = secondary,
                )
                ExportFormat.entries.forEach { f ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { format = f }
                            .padding(vertical = 2.dp),
                    ) {
                        RadioButton(selected = format == f, onClick = { format = f })
                        Text(f.label, fontSize = 13.sp, color = MaterialTheme.colors.onSurface)
                    }
                }

                if (format == ExportFormat.SQL_INSERT) {
                    OutlinedTextField(
                        value = tableName,
                        onValueChange = { tableName = it },
                        label = { Text("目标表名（可含 schema，如 public.users）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    OutlinedTextField(
                        value = batchSizeText,
                        onValueChange = { batchSizeText = it.filter(Char::isDigit).take(5) },
                        label = { Text("每条 INSERT 合并行数（1~10000）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    Text(
                        "转义采用标准 SQL（单引号双写）；MySQL 默认把反斜杠当转义符，含反斜杠的数据可能受影响。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                if (format == ExportFormat.JSON) {
                    OptionRow(
                        checked = prettyJson,
                        label = "美化输出（缩进换行；大文件可关闭以减小体积）",
                        onCheckedChange = { prettyJson = it },
                    )
                }

                if (request.truncated || request.cellsTruncated) {
                    OptionRow(
                        checked = fullStream,
                        label = if (forceStream) {
                            "全量流式：存在超大字段，必须重跑 SQL 按游标取完整内容"
                        } else {
                            "全量流式：重跑 SQL，游标逐行导出（不受 ${request.rowCount} 行限制）"
                        },
                        onCheckedChange = { if (!forceStream) fullStream = it },
                        enabled = !forceStream,
                        bold = fullStream,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = {
                    onConfirm(
                        format,
                        ExportOptions(
                            tableName = tableName.trim().ifBlank { "exported_data" },
                            batchSize = batchSize,
                            prettyJson = prettyJson,
                        ),
                        fullStream,
                    )
                },
            ) { Text("导出…") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun OptionRow(
    checked: Boolean,
    label: String,
    onCheckedChange: (Boolean) -> Unit,
    bold: Boolean = false,
    enabled: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(top = 4.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colors.onSurface.copy(alpha = if (enabled) 1f else 0.6f),
            modifier = Modifier.width(360.dp),
        )
    }
}
