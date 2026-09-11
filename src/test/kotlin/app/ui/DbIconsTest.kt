package app.ui

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 守卫自绘图标：`addPathNodes` 对非法 pathData 会在首次访问时抛异常，
 * 这里逐个 touch 一遍，防止新增图标时把路径写坏（编译期查不出）。
 */
class DbIconsTest {

    @Test
    fun allIconsParseValidPathData() {
        val icons = listOf(
            "Folder" to DbIcons.Folder,
            "Database" to DbIcons.Database,
            "Moon" to DbIcons.Moon,
            "Sun" to DbIcons.Sun,
            "History" to DbIcons.History,
            "Transpose" to DbIcons.Transpose,
            "Download" to DbIcons.Download,
            "Stop" to DbIcons.Stop,
        )
        icons.forEach { (name, icon) ->
            assertTrue(icon.defaultWidth.value > 0f, "图标 $name 未能解析出有效尺寸")
            assertTrue(icon.defaultHeight.value > 0f, "图标 $name 未能解析出有效尺寸")
        }
    }
}
