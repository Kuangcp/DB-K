package db

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

/** [AppPaths] 纯路径解析测试（不触碰真实数据目录）。 */
class AppPathsTest {

    @Test
    fun `resolveLogsDirectory defaults to dataDir slash logs`() {
        val base = Paths.get("/tmp/data")
        assertEquals(base.resolve("logs"), AppPaths.resolveLogsDirectory(null, base))
        // 空 / 全空白 override 视为未设置
        assertEquals(base.resolve("logs"), AppPaths.resolveLogsDirectory("", base))
        assertEquals(base.resolve("logs"), AppPaths.resolveLogsDirectory("   ", base))
    }

    @Test
    fun `resolveLogsDirectory honors override`() {
        assertEquals(
            Paths.get("/tmp/dbk-logs"),
            AppPaths.resolveLogsDirectory("/tmp/dbk-logs", Paths.get("/tmp/data")),
        )
    }
}
