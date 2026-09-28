package app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.DialogWindowScope
import androidx.compose.ui.window.rememberDialogState
import app.i18n.ProvideI18n
import app.i18n.t
import app.state.UiScaleState
import i18n.Lang
import i18n.Str
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import org.tinylog.Logger

/**
 * Linux 上 transparent + undecorated 窗口在部分 WM/合成器下会导致重绘卡死（已知 Compose Desktop 问题）；
 * 默认 Linux 关闭窗口透明（回落方形圆角为 0），可用 `-Ddbk.transparentWindow=true` 强制开启。
 */
private val transparentWindowsSupported: Boolean by lazy {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    System.getProperty("dbk.transparentWindow")?.toBooleanStrictOrNull() ?: !os.contains("linux")
}

/**
 * 自绘无边框弹窗（可拖动标题栏 + 边缘缩放 + 圆角统一边框），替代系统原生标题栏窗口。
 *
 * 后续把设置、查询结果详情等弹窗统一改成这个：调用方只关心 `content`，窗口外壳/主题/i18n/缩放/拖拽
 * 全部封装在这里。关键约束见 `AGENTS.md` 的「自绘弹窗」一节：
 * - 显式尺寸（避开 AlertDialog 的 pack-to-content 撑高问题）；
 * - 独立窗口是独立 composition，这里自行提供 theme / i18n / ui-scale；
 * - 拖动/缩放全程用 AWT 屏幕像素坐标（MouseInfo + window.location/bounds），别用 Compose 逻辑坐标。
 *
 * [content] 在标题栏与分隔线之下的 `Column` 内渲染，可用 `ColumnScope` 的 `Modifier.weight(1f)` 撑满剩余高度。
 */
@Composable
fun FramelessDialog(
    title: String,
    width: Dp,
    height: Dp,
    colors: ThemeColors,
    language: Lang,
    onCloseRequest: () -> Unit,
    resizable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val transparent = transparentWindowsSupported
    Logger.info("FramelessDialog open: title={} size={}x{} transparent={} os={}", title, width, height, transparent, System.getProperty("os.name"))
    val dialogState = rememberDialogState(size = scaledSize(width, height))
    // 界面缩放变化时同步窗口尺寸（与设置窗一致）
    LaunchedEffect(UiScaleState.scale) {
        dialogState.size = scaledSize(width, height, UiScaleState.scale)
    }
    DialogWindow(
        onCloseRequest = onCloseRequest,
        state = dialogState,
        title = title,
        undecorated = true,
        transparent = transparent,
        resizable = false,
        onPreviewKeyEvent = { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                onCloseRequest()
                true
            } else {
                false
            }
        },
    ) {
        ProvideUiScale {
            ProvideI18n(language) {
                MaterialTheme(colors = colors.toMaterialColors()) {
                    CompositionLocalProvider(
                        LocalContentColor provides MaterialTheme.colors.onSurface,
                        LocalThemeColors provides colors,
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                shape = if (transparent) RoundedCornerShape(10.dp) else RoundedCornerShape(0.dp),
                                color = MaterialTheme.colors.background,
                                border = BorderStroke(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.35f)),
                            ) {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    WindowTitleBar(title = title, onClose = onCloseRequest)
                                    Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.18f))
                                    content()
                                }
                            }
                            if (resizable) {
                                ResizeHandle(
                                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp),
                                    resizeWidth = true,
                                    resizeHeight = false,
                                )
                                ResizeHandle(
                                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(6.dp),
                                    resizeWidth = false,
                                    resizeHeight = true,
                                )
                                ResizeHandle(
                                    modifier = Modifier.align(Alignment.BottomEnd).size(20.dp),
                                    resizeWidth = true,
                                    resizeHeight = true,
                                    grip = true,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 自绘标题栏：整条可拖动窗口（拖动标题栏 = 移动窗口），右侧关闭按钮。 */
@Composable
private fun DialogWindowScope.WindowTitleBar(title: String, onClose: () -> Unit) {
    var startWindow by remember { mutableStateOf(Point(0, 0)) }
    var startMouse by remember { mutableStateOf(Point(0, 0)) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        startWindow = window.location
                        startMouse = MouseInfo.getPointerInfo().location
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        // 全程用 AWT 屏幕像素坐标（避免 Compose dp 与 window.location px 混用 + 坐标反馈）
                        val mouse = MouseInfo.getPointerInfo().location
                        window.location = Point(
                            startWindow.x + (mouse.x - startMouse.x),
                            startWindow.y + (mouse.y - startMouse.y),
                        )
                    },
                )
            },
    ) {
        Text(
            title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 14.dp).weight(1f),
        )
        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = t(Str.CommonCancel),
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 窗口边缘/角落缩放把手：拖动按 [resizeWidth]/[resizeHeight] 改变窗口尺寸。
 *  全程用 AWT 屏幕像素坐标（window.bounds + MouseInfo），避开 Compose dp 与 px 混用导致的坐标反馈。 */
@Composable
private fun DialogWindowScope.ResizeHandle(
    modifier: Modifier,
    resizeWidth: Boolean,
    resizeHeight: Boolean,
    grip: Boolean = false,
) {
    var startBounds by remember { mutableStateOf(Rectangle(0, 0, 0, 0)) }
    var startMouse by remember { mutableStateOf(Point(0, 0)) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = {
                    startBounds = window.bounds
                    startMouse = MouseInfo.getPointerInfo().location
                },
                onDrag = { change, _ ->
                    change.consume()
                    val mouse = MouseInfo.getPointerInfo().location
                    val dx = mouse.x - startMouse.x
                    val dy = mouse.y - startMouse.y
                    val w = if (resizeWidth) (startBounds.width + dx).coerceAtLeast(560) else startBounds.width
                    val h = if (resizeHeight) (startBounds.height + dy).coerceAtLeast(360) else startBounds.height
                    window.setBounds(startBounds.x, startBounds.y, w, h)
                },
            )
        },
    ) {
        if (grip) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) { i ->
                    Box(
                        Modifier
                            .width((13 - i * 4).dp)
                            .height(2.dp)
                            .background(MaterialTheme.colors.onSurface.copy(alpha = 0.35f)),
                    )
                }
            }
        }
    }
}
