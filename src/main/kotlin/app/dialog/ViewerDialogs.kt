package app.dialog

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import app.state.TableDdlRequest
import db.ConnectionProfile
import jdbc.model.SchemaMeta

/** 查看器窗口的初始尺寸（显式指定，绝不能留 Unspecified——见文件末注释）。 */
private val VIEWER_WINDOW_SIZE = DpSize(780.dp, 580.dp)

/** 与结果表格一致的滚动条样式（主题色半透明，深浅色下都可见）。 */
@Composable
private fun viewerScrollbarStyle(): ScrollbarStyle = ScrollbarStyle(
    minimalHeight = 24.dp,
    thickness = 10.dp,
    shape = RoundedCornerShape(5.dp),
    hoverDurationMillis = 300,
    unhoverColor = MaterialTheme.colors.onSurface.copy(alpha = 0.20f),
    hoverColor = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
)

/** 传统只读文本视图：自动换行、等宽字体、纵向滚动 + 右侧滚动条。 */
@Composable
private fun ViewerText(content: String, modifier: Modifier = Modifier) {
    val vScroll = rememberScrollState()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colors.onSurface.copy(alpha = 0.04f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp)
                .verticalScroll(vScroll),
        ) {
            Text(
                content,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.9f),
            )
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(vScroll),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            style = viewerScrollbarStyle(),
        )
    }
}

/** Esc / 关闭按钮统一的关窗键盘处理。 */
private fun escapeCloses(onClose: () -> Unit): (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { e ->
    if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
        onClose()
        true
    } else {
        false
    }
}

/**
 * 对象定义（DDL）浮窗：异步取定义 → 加载态 / 正文 / 失败原因。
 * 用**显式尺寸**的 [DialogWindow]（不是 AlertDialog）：AlertDialog 的窗口会 pack-to-content，
 * 滚动节点在 pack 测量时把窗口撑成内容全高，滚动后内容整体移出窗口（表现为大片留白/内容消失）。
 */
@Composable
fun DdlDialog(
    request: TableDdlRequest,
    loadDdl: suspend (ConnectionProfile, SchemaMeta?, String) -> Result<String>,
    onDismiss: () -> Unit,
    onCopy: (String, String) -> Unit,
) {
    var loading by remember(request) { mutableStateOf(true) }
    var ddl by remember(request) { mutableStateOf<String?>(null) }
    var error by remember(request) { mutableStateOf<String?>(null) }

    LaunchedEffect(request) {
        loading = true
        error = null
        ddl = null
        loadDdl(request.profile, request.schema, request.objectName).fold(
            onSuccess = { ddl = it },
            onFailure = { error = it.message ?: "读取失败" },
        )
        loading = false
    }

    val title = "${request.noun}定义 · ${request.objectName}"
    DialogWindow(
        onCloseRequest = onDismiss,
        state = rememberDialogState(size = VIEWER_WINDOW_SIZE),
        title = title,
        resizable = true,
        onPreviewKeyEvent = escapeCloses(onDismiss),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background)
                .padding(12.dp),
        ) {
            when {
                loading -> Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(" 正在读取定义…", style = MaterialTheme.typography.body2)
                }
                ddl != null -> ViewerText(ddl.orEmpty(), Modifier.weight(1f).fillMaxWidth())
                else -> Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Text(
                        error ?: "未获取到定义。",
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.error,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    enabled = !ddl.isNullOrBlank(),
                    onClick = { ddl?.let { onCopy(it, "已复制「${request.objectName}」定义") } },
                ) { Text("复制全部") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}

/**
 * 单元格大段文本查看器：结果表格单元格双击 / 右键「查看完整内容」打开。
 * 顶部显示字符/行数，正文定宽滚动 + 「复制全部」。同样用显式尺寸的 [DialogWindow]。
 */
@Composable
fun CellViewerDialog(
    title: String,
    content: String,
    onDismiss: () -> Unit,
    onCopy: (String, String) -> Unit,
) {
    val lineCount = content.count { it == '\n' } + 1
    DialogWindow(
        onCloseRequest = onDismiss,
        state = rememberDialogState(size = VIEWER_WINDOW_SIZE),
        title = title,
        resizable = true,
        onPreviewKeyEvent = escapeCloses(onDismiss),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background)
                .padding(12.dp),
        ) {
            Text(
                "${content.length} 字符 · $lineCount 行",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(bottom = 6.dp),
            )
            ViewerText(content, Modifier.weight(1f).fillMaxWidth())
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onCopy(content, "已复制单元格内容") }) { Text("复制全部") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}
