package app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 自绘矢量图标（material-icons-core 不包含 folder/database 等管理类图标，
 * 为保持轻量不引入 icons-extended，需要的手绘在此集中）。
 * pathData 与 Material Design Icons 同源。
 */
object DbIcons {

    private fun vector(name: String, path: String): ImageVector {
        val nodes = addPathNodes(path)
        return ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(pathData = nodes, fill = SolidColor(Color.Black)).build()
    }

    val Folder: ImageVector by lazy {
        vector("folder", "M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z")
    }

    /** 数据库圆柱图标（连接行/徽章替代用，M2 树内 schema 节点亦可复用）。 */
    val Database: ImageVector by lazy {
        vector(
            "database",
            "M12 3C7.58 3 2 4.79 2 7s5.58 4 10 4 10-1.79 10-4S16.42 3 12 3zM12 11c-4.42 0-10 1.79-10 4s5.58 4 10 4 10-1.79 10-4-5.58-4-10-4zM12 19c-4.42 0-10 1.79-10 4s5.58 4 10 4 10-1.79 10-4-5.58-4-10-4z",
        )
    }

    /** 月亮图标（深色主题时显示，点击切回浅色）。 */
    val Moon: ImageVector by lazy {
        vector(
            "moon",
            "M12 3c-4.97 0-9 4.03-9 9s4.03 9 9 9 9-4.03 9-9c0-.46-.04-.92-.1-1.36-.98 1.37-2.58 2.26-4.4 2.26-2.98 0-5.4-2.42-5.4-5.4 0-1.81.89-3.42 2.26-4.4-.44-.06-.9-.1-1.36-.1z",
        )
    }

    /** 太阳图标（浅色主题时显示，点击切深色）。 */
    val Sun: ImageVector by lazy {
        vector(
            "sun",
            "M12 7c-2.76 0-5 2.24-5 5s2.24 5 5 5 5-2.24 5-5-2.24-5-5-5zM2 13h2c.55 0 1-.45 1-1s-.45-1-1-1H2c-.55 0-1 .45-1 1s.45 1 1 1zm18 0h2c.55 0 1-.45 1-1s-.45-1-1-1h-2c-.55 0-1 .45-1 1s.45 1 1 1zM11 2v2c0 .55.45 1 1 1s1-.45 1-1V2c0-.55-.45-1-1-1s-1 .45-1 1zm0 18v2c0 .55.45 1 1 1s1-.45 1-1v-2c0-.55-.45-1-1-1s-1 .45-1 1zM5.99 4.58c-.39-.39-1.03-.39-1.42 0-.39.39-.39 1.03 0 1.42l1.06 1.06c.39.39 1.03.39 1.42 0 .38-.39.39-1.03 0-1.42L5.99 4.58zm12.03 12.02c-.39-.39-1.03-.39-1.42 0-.39.39-.39 1.03 0 1.42l1.06 1.06c.39.39 1.03.39 1.42 0 .39-.39.39-1.03 0-1.42l-1.06-1.06zm1.06-10.96c.39-.39.39-1.03 0-1.42-.39-.39-1.03-.39-1.42 0l-1.06 1.06c-.39.39-.39 1.03 0 1.42.39.38 1.03.39 1.42 0l1.06-1.06zM7.05 18.36c.39-.39.39-1.03 0-1.42-.39-.39-1.03-.39-1.42 0l-1.06 1.06c-.39.39-.39 1.03 0 1.42.39.39 1.03.39 1.42 0l1.06-1.06z",
        )
    }
}
