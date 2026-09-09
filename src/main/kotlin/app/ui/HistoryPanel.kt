package app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import db.SqlHistoryRow
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 执行历史侧栏：当前数据源最近执行的 SQL（成功/失败标记、时间、行数、耗时）。
 * 双击条目回填到当前控制台编辑器（只回填不自动执行）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryPanel(
    entries: List<SqlHistoryRow>,
    onFill: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colors.surface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.12f),
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp).padding(top = 6.dp),
        ) {
            Text(
                "执行历史",
                style = MaterialTheme.typography.subtitle2,
                fontSize = 13.sp,
                color = MaterialTheme.colors.onSurface,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClear, enabled = entries.isNotEmpty()) {
                Text("清空", fontSize = 11.sp)
            }
        }
        Text(
            "双击条目回填到当前控制台",
            fontSize = 10.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
        )
        Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.08f))
        if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(
                    "暂无历史。\n执行过的 SQL（含失败）会记录在这里。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries, key = { it.id }) { row ->
                    HistoryEntry(row) { sql -> onFill(sql) }
                    Divider(
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.05f),
                        modifier = Modifier.padding(start = 26.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryEntry(row: SqlHistoryRow, onFill: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onDoubleClick = { onFill(row.sqlText) }, onClick = {})
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        // 成功/失败语义色点（色块不算文字色）
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (row.ok) Color(0xFF43A047) else Color(0xFFE53935)),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.sqlText.replace('\n', ' ').trim().ifEmpty { "(空)" },
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                entryMeta(row),
                fontSize = 10.sp,
                color = if (row.ok) MaterialTheme.colors.onSurface.copy(alpha = 0.45f)
                else MaterialTheme.colors.error.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun entryMeta(row: SqlHistoryRow): String {
    val t = runCatching {
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").format(
            LocalDateTime.ofInstant(Instant.ofEpochMilli(row.executedAtMs), ZoneId.systemDefault()),
        )
    }.getOrDefault("")
    val head = when {
        !row.ok -> "失败"
        row.rowCount > 0 -> "${row.rowCount} 行"
        else -> "完成"
    }
    return "$t · $head · ${row.durationMs} ms" +
        if (row.errorMessage != null && row.errorMessage != "已取消执行") " · ${row.errorMessage}" else ""
}
