package app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.i18n.t
import i18n.Str

/**
 * N2 查找替换栏：浮在编辑器右上的紧凑面板。
 * 查找框 Enter=下一个、Shift+Enter=上一个、Esc=关闭；`Aa`=区分大小写、`.*`=正则。
 */
@Composable
internal fun FindReplaceBar(
    findText: String,
    onFindTextChange: (String) -> Unit,
    replaceText: String,
    onReplaceTextChange: (String) -> Unit,
    regex: Boolean,
    onRegexChange: (Boolean) -> Unit,
    caseSensitive: Boolean,
    onCaseSensitiveChange: (Boolean) -> Unit,
    matchCount: Int,
    activeIndex: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val findFocus = remember { FocusRequester() }
    // 打开即聚焦查找框（面板仅 findOpen 时进入组合，故每次打开都会重新聚焦）
    LaunchedEffect(Unit) { runCatching { findFocus.requestFocus() } }
    val iconTint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
    Column(
        modifier = modifier
            .width(432.dp)
            .shadow(8.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colors.surface)
            .border(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FindInputField(
                value = findText,
                onValueChange = onFindTextChange,
                placeholder = t(Str.FindPlaceholder),
                focus = findFocus,
                onKey = { e ->
                    when {
                        e.key == Key.Enter -> {
                            if (e.isShiftPressed) onPrev() else onNext()
                            true
                        }
                        e.key == Key.Escape -> { onClose(); true }
                        else -> false
                    }
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            FindToggleChip("Aa", caseSensitive) { onCaseSensitiveChange(!caseSensitive) }
            Spacer(Modifier.width(4.dp))
            FindToggleChip(".*", regex) { onRegexChange(!regex) }
            Spacer(Modifier.width(6.dp))
            Text(
                if (matchCount == 0) "0/0" else "${activeIndex + 1}/$matchCount",
                fontSize = 11.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.widthIn(min = 34.dp),
            )
            IconButton(onClick = onPrev, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Filled.KeyboardArrowUp, t(Str.FindPrev), tint = iconTint, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onNext, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Filled.KeyboardArrowDown, t(Str.FindNext), tint = iconTint, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onClose, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Filled.Close, t(Str.FindClose), tint = iconTint, modifier = Modifier.size(16.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FindInputField(
                value = replaceText,
                onValueChange = onReplaceTextChange,
                placeholder = t(Str.FindReplacePlaceholder),
                focus = null,
                onKey = null,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            FindSmallButton(t(Str.FindReplace)) { onReplace() }
            Spacer(Modifier.width(4.dp))
            FindSmallButton(t(Str.FindReplaceAll)) { onReplaceAll() }
        }
    }
}

@Composable
private fun FindInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    focus: FocusRequester?,
    onKey: ((KeyEvent) -> Boolean)?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(26.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colors.background)
            .border(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.22f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colors.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colors.primary),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
                .then(
                    if (onKey != null) {
                        Modifier.onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown) onKey(e) else false
                        }
                    } else {
                        Modifier
                    },
                ),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            fontSize = 12.sp,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.35f),
                        )
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
private fun FindToggleChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(28.dp)
            .height(26.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) MaterialTheme.colors.primary.copy(alpha = 0.22f) else Color.Transparent)
            .border(
                1.dp,
                if (active) MaterialTheme.colors.primary.copy(alpha = 0.55f)
                else MaterialTheme.colors.onSurface.copy(alpha = 0.2f),
                RoundedCornerShape(4.dp),
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.onSurface.copy(alpha = if (active) 0.95f else 0.55f),
        )
    }
}

@Composable
private fun FindSmallButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(26.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colors.primary.copy(alpha = 0.12f))
            .clickable { onClick() }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colors.onSurface.copy(alpha = 0.85f))
    }
}
