package app.core

import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path

/**
 * 用系统文件管理器打开目录（P9 诊断入口：「打开日志 / 数据目录」）。
 *
 * 无桌面环境、JDK 不支持 `Desktop.OPEN`、或被系统拒绝时返回 false，
 * 由调用方回落「复制路径 + 提示」，不让失败冒泡成异常。
 * 打开前确保目录存在（首次启动可能尚无日志文件）。
 */
fun openDirectory(dir: Path): Boolean {
    val desktop = runCatching {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
    }.getOrNull() ?: return false
    if (!runCatching { desktop.isSupported(Desktop.Action.OPEN) }.getOrDefault(false)) return false
    return runCatching {
        Files.createDirectories(dir)
        desktop.open(dir.toFile())
    }.isSuccess
}
