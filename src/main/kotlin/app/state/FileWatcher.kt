package app.state

import java.nio.file.Path

/** 文件变更监听抽象：生产实现为 [ExternalFileWatcher]，测试注入 fake。 */
interface FileWatcher {
    fun watch(path: Path)
    fun unwatch(path: Path)
    fun close()
}
