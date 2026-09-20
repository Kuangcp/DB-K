package app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.i18n.t
import i18n.Str

/** 补全候选弹层：主题化小面板，左侧类别色点 + 右侧详情（列类型/别名指向）；键盘选中高亮 + 鼠标点击上屏。 */
@Composable
internal fun CompletionPopup(
    items: List<CompletionItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismissSignal = LocalCompletionDismiss.current
    Column(
        modifier = modifier
            .shadow(6.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colors.surface)
            .border(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
            // 标记「这一下按在弹层内」：根布局在 Final pass 据此判定是否属于“点弹层外”。
            // Initial pass 先于根布局的 Final，命中/不命中两种情况都不会消费事件。
            .pointerInput(dismissSignal) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.type == PointerEventType.Press) dismissSignal.markPressInside()
                    }
                }
            },
    ) {
        Text(
            t(Str.EditorCompletionHint, items.size),
            fontSize = 10.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        )
        Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.08f))
        val listState = rememberLazyListState()
        // 键盘选择越出可视区时跟随滚动
        LaunchedEffect(selectedIndex) {
            if (items.isNotEmpty() && selectedIndex in items.indices) {
                listState.animateScrollToItem(selectedIndex)
            }
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(items) { index, item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(index) }
                        .background(
                            if (index == selectedIndex) MaterialTheme.colors.primary.copy(alpha = 0.16f)
                            else Color.Transparent,
                        )
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(completionKindColor(item.kind)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        item.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.9f),
                        modifier = Modifier.weight(1f),
                    )
                    if (item.detail != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            item.detail,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
                        )
                    }
                }
            }
        }
    }
}

/** 补全类别语义点色（只做色点，文字仍走主题色）。 */
private fun completionKindColor(kind: CompletionKind): Color = when (kind) {
    CompletionKind.COLUMN -> Color(0xFF5586E4)
    CompletionKind.ALIAS -> Color(0xFF9E9E9E)
    CompletionKind.FUNCTION -> Color(0xFF7E57C2)
    CompletionKind.TABLE -> Color(0xFF26A69A)
    CompletionKind.KEYWORD -> Color(0xFFFFB300)
    CompletionKind.EXPAND -> Color(0xFF43A047)
}
