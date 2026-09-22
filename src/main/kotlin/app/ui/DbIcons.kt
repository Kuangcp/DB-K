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

    /** 链接/外部文件图标（控制台标签区分外部文件）。 */
    val Link: ImageVector by lazy {
        vector(
            "link",
            "M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1s-1.39 3.1-3.1 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z",
        )
    }

    /** 调色板图标（主题下拉）。 */
    val Palette: ImageVector by lazy {
        vector(
            "palette",
            "M12 3c-4.97 0-9 4.03-9 9s4.03 9 9 9c.83 0 1.5-.67 1.5-1.5 0-.39-.15-.74-.39-1.01-.23-.26-.38-.61-.38-.99 0-.83.67-1.5 1.5-1.5H16c2.76 0 5-2.24 5-5 0-4.42-4.03-8-9-8zm-5.5 9c-.83 0-1.5-.67-1.5-1.5S5.67 9 6.5 9 8 9.67 8 10.5 7.33 12 6.5 12zm3-4C8.67 8 8 7.33 8 6.5S8.67 5 9.5 5s1.5.67 1.5 1.5S10.33 8 9.5 8zm5 0c-.83 0-1.5-.67-1.5-1.5S13.67 5 14.5 5s1.5.67 1.5 1.5S15.33 8 14.5 8zm3 4c-.83 0-1.5-.67-1.5-1.5S16.67 9 17.5 9s1.5.67 1.5 1.5S18.33 12 17.5 12z",
        )
    }

    /** 历史（时钟回退）：执行历史面板开关。 */
    val History: ImageVector by lazy {
        vector(
            "history",
            "M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21c4.97 0 9-4.03 9-9s-4.03-9-9-9zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z",
        )
    }

    /** 行列转置（双向箭头）：结果区「转置 (Ctrl+T)」。 */
    val Transpose: ImageVector by lazy {
        vector("transpose", "M6.99 11L3 15l3.99 4v-3H14v-2H6.99v-3zM21 9l-3.99-4v3H10v2h7.01v3L21 9z")
    }

    /** 下载（导出结果）：结果区导出当前结果（CSV / JSON / SQL INSERT / Excel）。 */
    val Download: ImageVector by lazy {
        vector("download", "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z")
    }

    /** 漏斗筛选：结果表头列筛选入口。 */
    val Filter: ImageVector by lazy {
        vector(
            "filter",
            "M14 12v7.88c.04.3-.12.62-.42.77-.28.15-.63.12-.88-.06L9.6 18.4c-.25-.19-.4-.49-.4-.8V12L3.5 5.2c-.45-.53-.07-1.2.6-1.2h15.8c.67 0 1.05.67.6 1.2L14 12z",
        )
    }

    /** 下箭头：结果区「取更多」（追加下一页）。 */
    val FetchMore: ImageVector by lazy {
        vector("fetch-more", "M11 4h2v12l5.5-5.5 1.42 1.42L12 20.84l-7.92-7.92L5.5 10.5 11 16V4z")
    }

    /** 停止（实心方块）：取消正在执行的查询。 */
    val Stop: ImageVector by lazy {
        vector("stop", "M6 6h12v12H6z")
    }

    /** 提交（对勾）：把未提交的单元格修改写回库。 */
    val Commit: ImageVector by lazy {
        vector("commit", "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z")
    }

    /** 刷新（环形箭头）：重新执行当前结果 Tab 的语句。 */
    val Refresh: ImageVector by lazy {
        vector(
            "refresh",
            "M17.65 6.35A7.958 7.958 0 0 0 12 4c-4.42 0-8 3.58-8 8s3.58 8 8 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z",
        )
    }

    /** 图钉：固定/取消固定结果 tab（新执行不覆盖）。 */
    val Pin: ImageVector by lazy {
        vector(
            "pin",
            "M16 9V4h1c.55 0 1-.45 1-1s-.45-1-1-1H7c-.55 0-1 .45-1 1s.45 1 1 1h1v5c0 1.66-1.34 3-3 3v2h5.97v7l1 1 1-1v-7H19v-2c-1.66 0-3-1.34-3-3z",
        )
    }

    /** 撤销（回退箭头）：丢弃全部未提交的单元格修改。 */
    val Undo: ImageVector by lazy {
        vector(
            "undo",
            "M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88 3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.02 11.03 17.1 8 12.5 8z",
        )
    }

    /** 插入行（加号）：结果网格新增待插入行。 */
    val AddRow: ImageVector by lazy {
        vector("add-row", "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")
    }

    /** 删除行（垃圾桶）：结果网格标记/撤销删除当前行。 */
    val DeleteRow: ImageVector by lazy {
        vector(
            "delete-row",
            "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z",
        )
    }

    /** 格式化（对齐线）：SQL 编辑器「格式化」入口。 */
    val Format: ImageVector by lazy {
        vector("format", "M15 15H3v2h12v-2zm0-8H3v2h12V7zM3 13h18v-2H3v2zm0 8h18v-2H3v2zM3 3v2h18V3H3z")
    }

    /** 窗口最小化（减号）：自定义标题栏。 */
    val WindowMinimize: ImageVector by lazy {
        vector("window-minimize", "M19 13H5v-2h14v2z")
    }

    /** 窗口最大化（空心方框，普通状态）：自定义标题栏，点击去最大化。 */
    val WindowMaximize: ImageVector by lazy {
        vector("window-maximize", "M5 5h14v14H5V5zm2 2v10h10V7H7z")
    }

    /** 窗口还原（双斜向重叠方框，已最大化状态）：点击还原为浮动窗口。 */
    val WindowRestore: ImageVector by lazy {
        vector("window-restore", "M9 5h10v10h-2V7H9V5zm-4 4h10v10H5V9zm2 2v6h6v-6H7z")
    }

    /** 查找替换（上下箭头 + 循环）。 */
    val FindReplace: ImageVector by lazy {
        vector(
            "find-replace",
            "M11 6h5l-3.5-3.5L14 1l6 6-6 6-1.5-1.5L16 8h-5c-1.1 0-2 .9-2 2v2H7v-2c0-2.21 1.79-4 4-4zm2 12H8l3.5 3.5L10 23l-6-6 6-6 1.5 1.5L8 16h5c1.1 0 2-.9 2-2v-2h2v2c0 2.21-1.79 4-4 4z",
        )
    }
}
