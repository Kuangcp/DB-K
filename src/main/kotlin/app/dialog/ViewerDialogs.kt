package app.dialog

import androidx.compose.foundation.Image
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import app.state.TableDdlRequest
import app.ui.applySqlHighlightRules
import app.ui.decodeBase64Bytes
import app.ui.humanSize
import app.ui.imageFormatName
import app.ui.md5Hex
import app.ui.sqlHighlightKeywords
import app.ui.sqlSyntaxPalette
import com.neoutils.highlight.compose.remember.rememberAnnotatedString
import com.neoutils.highlight.compose.remember.rememberHighlight
import db.ConnectionProfile
import jdbc.model.SchemaMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 查看器窗口的初始尺寸（显式指定，绝不能留 Unspecified——见文件末注释）。 */
private val VIEWER_WINDOW_SIZE = DpSize(780.dp, 580.dp)

/** 单元格内容按 Base64 图片解码的字节上限（防超大图撑爆内存）。 */
private const val MAX_IMAGE_BYTES = 32 * 1024 * 1024

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

/**
 * 只读文本视图的共用外框：圆角底色 + 纵向滚动 + 右侧滚动条。
 * [content] 放进内部可滚动列（自动换行）。
 */
@Composable
private fun ViewerFrame(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
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
            content()
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(vScroll),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            style = viewerScrollbarStyle(),
        )
    }
}

/** 传统只读文本视图：自动换行、等宽字体、纵向滚动 + 右侧滚动条（不做语法着色）。 */
@Composable
private fun ViewerText(content: String, modifier: Modifier = Modifier) {
    ViewerFrame(modifier) {
        Text(
            content,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.9f),
        )
    }
}

/**
 * 只读 SQL 代码视图：与编辑器共用 [applySqlHighlightRules] 语法高亮，等宽 + 主题色。
 * 仅用于 DDL（表/视图等数据库对象定义）；任意单元格文本仍用 [ViewerText]，不按 SQL 误染。
 */
@Composable
private fun SqlCodeText(content: String, modifier: Modifier = Modifier) {
    val isDark = MaterialTheme.colors.isLight.not()
    val keywords = remember { sqlHighlightKeywords().distinct() }
    // isDark 作 key：主题切换时重建 Highlight（与 EditorPane 一致，否则旧色板不刷新）。
    val highlight = rememberHighlight(isDark) {
        applySqlHighlightRules(sqlSyntaxPalette(isDark), keywords)
    }
    val annotated = highlight.rememberAnnotatedString(content)
    ViewerFrame(modifier) {
        Text(
            annotated,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.9f),
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
                ddl != null -> SqlCodeText(ddl.orEmpty(), Modifier.weight(1f).fillMaxWidth())
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
 * 大段文本查看器：结果单元格双击 / 右键「查看完整内容」、执行历史双击均用它。
 * 顶部显示副标题（默认字符/行数），正文定宽滚动 + 「复制全部」。[highlightSql] 为 true 时
 * 与 Ctrl+Q 的 DDL 弹窗同款 SQL 语法高亮（历史 SQL）。同样用显式尺寸的 [DialogWindow]。
 */
@Composable
fun TextViewerDialog(
    title: String,
    content: String,
    onDismiss: () -> Unit,
    onCopy: (String, String) -> Unit,
    /** true = 按 SQL 语法高亮，false = 纯文本（任意单元格文本不该按 SQL 误染）。 */
    highlightSql: Boolean = false,
    /** 副标题；null 时回退为「N 字符 · M 行」。 */
    subtitle: String? = null,
    /** 复制成功后的 Toast 文案。 */
    copyToast: String = "已复制全部内容",
    /** 可选的额外动作按钮（如历史 SQL 的「插入到当前控制台」）；null 则不显示。 */
    extraAction: Pair<String, () -> Unit>? = null,
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
                subtitle ?: "${content.length} 字符 · $lineCount 行",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(bottom = 6.dp),
            )
            if (highlightSql) {
                SqlCodeText(content, Modifier.weight(1f).fillMaxWidth())
            } else {
                ViewerText(content, Modifier.weight(1f).fillMaxWidth())
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                extraAction?.let { (label, onClick) ->
                    TextButton(onClick = onClick) { Text(label) }
                }
                TextButton(onClick = { onCopy(content, copyToast) }) { Text("复制全部") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}

/**
 * 单元格内容查看器：在通用文本查看器基础上加「单元格工具」——
 * - MD5：算内容摘要，结果显示在正文上方的条里（等宽 hex + 复制 + 收起），不遮挡正文；
 * - 按图片查看：把内容当 Base64 图片解码，正文切成适应窗口的图片预览（带格式/尺寸/体积信息）。
 *
 * 仅在结果单元格双击时使用；历史 SQL 等纯文本走 [TextViewerDialog]。
 * 全部状态以 [content] 为 key：同一弹窗切到别的单元格时自动复位。
 */
@Composable
fun CellViewerDialog(
    title: String,
    content: String,
    onDismiss: () -> Unit,
    onCopy: (String, String) -> Unit,
) {
    // 大内容时 count 也是 O(n)，按 content 缓存，避免忙碌态重组成倍扫描
    val lineCount = remember(content) { content.count { it == '\n' } + 1 }
    val scope = rememberCoroutineScope()

    var showImage by remember(content) { mutableStateOf(false) }
    var md5 by remember(content) { mutableStateOf<String?>(null) }
    var md5Busy by remember(content) { mutableStateOf(false) }
    var image by remember(content) { mutableStateOf<ImageBitmap?>(null) }
    var imageMeta by remember(content) { mutableStateOf<String?>(null) }
    var imageBusy by remember(content) { mutableStateOf(false) }
    var imageError by remember(content) { mutableStateOf<String?>(null) }

    fun computeMd5() {
        md5Busy = true
        scope.launch {
            md5 = withContext(Dispatchers.Default) { md5Hex(content) }
            md5Busy = false
        }
    }

    fun decodeImage() {
        imageBusy = true
        imageError = null
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val bytes = decodeBase64Bytes(content)
                    require(bytes.size <= MAX_IMAGE_BYTES) { "图片过大（${humanSize(bytes.size)}）" }
                    val bmp = bytes.decodeToImageBitmap()
                    Triple(bmp, imageFormatName(bytes) ?: "图片", bytes.size)
                }
            }
            result.onSuccess { (bmp, fmt, size) ->
                image = bmp
                imageMeta = "$fmt · ${bmp.width}×${bmp.height} · ${humanSize(size)}"
                showImage = true
            }.onFailure {
                imageError = it.message ?: "无法识别为图片"
                showImage = false
            }
            imageBusy = false
        }
    }

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
            // 副标题（左）+ 工具（右）
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${content.length} 字符 · $lineCount 行",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = !md5Busy,
                    onClick = { if (md5 != null) md5 = null else computeMd5() },
                ) { Text(if (md5Busy) "MD5…" else "MD5", fontSize = 12.sp) }
                TextButton(
                    enabled = !imageBusy,
                    onClick = {
                        when {
                            showImage -> showImage = false
                            image != null -> showImage = true
                            else -> decodeImage()
                        }
                    },
                ) {
                    Text(
                        when {
                            imageBusy -> "解码中…"
                            showImage -> "返回文本"
                            else -> "按图片查看"
                        },
                        fontSize = 12.sp,
                    )
                }
            }
            // MD5 结果条（不遮挡正文）
            md5?.let { hash ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "MD5",
                        fontSize = 11.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                    )
                    Text(
                        hash,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.9f),
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                    TextButton(onClick = { onCopy(hash, "已复制 MD5") }) { Text("复制", fontSize = 12.sp) }
                    TextButton(onClick = { md5 = null }) { Text("×", fontSize = 12.sp) }
                }
            }
            // 图片解码失败提示（保持文本视图）
            imageError?.let { msg ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "无法按图片查看：$msg",
                        fontSize = 11.sp,
                        color = MaterialTheme.colors.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { imageError = null }) { Text("×", fontSize = 12.sp) }
                }
            }
            // 正文：图片预览 / 文本
            val bitmap = image
            if (showImage && bitmap != null) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colors.onSurface.copy(alpha = 0.04f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                        contentScale = ContentScale.Fit,
                    )
                    imageMeta?.let { meta ->
                        Text(
                            meta,
                            fontSize = 10.sp,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
                            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                        )
                    }
                }
            } else {
                ViewerText(content, Modifier.weight(1f).fillMaxWidth())
            }
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
