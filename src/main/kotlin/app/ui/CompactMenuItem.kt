package app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 紧凑下拉项：Material2 默认 `DropdownMenuItem` 最小高 48dp，条目多时弹层接近半屏；
 * 这里自绘固定行高（默认 26dp）替代。选中/hover 等语义由调用方在内容里表达。
 *
 * @param height 行高；标题栏工具菜单用 26dp，工作区菜单用 30dp（内容稍多）。
 * @param modifier 附加修饰（如 hover 监听）；会先于内部行高/点击/内边距应用。
 */
@Composable
internal fun CompactMenuItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 26.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        content = content,
    )
}
