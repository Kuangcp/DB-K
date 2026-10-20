package app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * 通用级联菜单项：普通动作 / 向右级联子菜单 / 分隔线。
 * 与 tree 的右键菜单同构；结果区与树各持渲染入口。
 */
sealed interface MenuEntry {
    class Action(
        val label: String,
        val enabled: Boolean = true,
        val onClick: () -> Unit,
    ) : MenuEntry

    class Submenu(val label: String, val items: List<MenuEntry>) : MenuEntry

    object Separator : MenuEntry
}

/** 菜单列宽（dp）。 */
private val MENU_COL_W = 208.dp

/**
 * 自绘级联上下文菜单：单个 [Popup]，根列 + 向右展开的子列共用同一弹层。
 * 不用嵌套 DropdownMenu —— 避免子弹层夺焦时父弹层被 dismiss。
 * 需放在「右键命中节点」的 [Box] 内：Popup 以该 Box 为锚点，[clickOffset] 为节点局部坐标。
 */
@Composable
internal fun CascadingContextMenu(
    items: List<MenuEntry>,
    clickOffset: Offset,
    onDismiss: () -> Unit,
) {
    var openSub by remember { mutableStateOf<Int?>(null) }
    val sub = openSub?.let { items.getOrNull(it) as? MenuEntry.Submenu }
    val lineColor = MaterialTheme.colors.onSurface.copy(alpha = 0.1f)
    Popup(
        onDismissRequest = onDismiss,
        popupPositionProvider = remember(clickOffset) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    // 菜单左上角落在点击点；越界时贴窗口边
                    val x = (anchorBounds.left + clickOffset.x.toInt())
                        .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                    val y = (anchorBounds.top + clickOffset.y.toInt())
                        .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
                    return IntOffset(x, y)
                }
            }
        },
        properties = PopupProperties(focusable = true),
    ) {
        Row(
            modifier = Modifier
                .shadow(8.dp, RoundedCornerShape(7.dp))
                .clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colors.surface)
                .border(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.22f), RoundedCornerShape(7.dp)),
        ) {
            MenuColumn(items, openSub, { openSub = it }, onDismiss)
            if (sub != null) {
                MenuColumn(
                    items = sub.items,
                    openSub = null,
                    onOpenSub = {},
                    onDismiss = onDismiss,
                    modifier = Modifier.drawBehind {
                        drawLine(lineColor, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 1f)
                    },
                )
            }
        }
    }
}

@Composable
private fun MenuColumn(
    items: List<MenuEntry>,
    openSub: Int?,
    onOpenSub: (Int?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.width(MENU_COL_W).padding(vertical = 4.dp)) {
        items.forEachIndexed { i, item ->
            when (item) {
                is MenuEntry.Action -> MenuRow(
                    label = item.label,
                    enabled = item.enabled,
                    arrow = false,
                    highlight = false,
                    onHover = { onOpenSub(null) },
                    onClick = {
                        onDismiss()
                        if (item.enabled) item.onClick()
                    },
                )
                is MenuEntry.Submenu -> MenuRow(
                    label = item.label,
                    enabled = true,
                    arrow = true,
                    highlight = openSub == i,
                    // hover 即展开子菜单（点击也能展开，兼容键盘/无 hover）
                    onHover = { onOpenSub(i) },
                    onClick = { onOpenSub(if (openSub == i) null else i) },
                )
                MenuEntry.Separator -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .height(1.dp)
                        .background(MaterialTheme.colors.onSurface.copy(alpha = 0.1f)),
                )
            }
        }
    }
}

@Composable
private fun MenuRow(
    label: String,
    enabled: Boolean,
    arrow: Boolean,
    highlight: Boolean,
    onHover: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (highlight) MaterialTheme.colors.primary.copy(alpha = 0.16f) else Color.Transparent)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type == PointerEventType.Enter) onHover()
                    }
                }
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Normal,
            color = if (enabled) MaterialTheme.colors.onSurface.copy(alpha = 0.85f)
            else MaterialTheme.colors.onSurface.copy(alpha = 0.35f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (arrow) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.width(15.dp).height(15.dp),
            )
        }
    }
}
