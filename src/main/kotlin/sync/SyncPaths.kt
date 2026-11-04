package sync

import java.nio.file.Path

/**
 * 同步仓库的路径约定：一个仓库可服务多个应用（按应用名分命名空间），
 * 每个应用内部再按实体拆文件 —— 冲突粒度因此等于「一条连接 / 一个控制台」。
 *
 * ```
 * <workTree>/db-k/
 *   salt.bin  secret.check  meta.json  README.md
 *   folders/<id>.json
 *   connections/<id>.enc
 *   workspaces/<id>.json
 *   consoles/<id>.meta.json   consoles/<id>.sql
 *   settings/live-templates.json  settings/keymap.json  settings/themes.json
 * ```
 */
object SyncPaths {

    const val SALT_FILE = "salt.bin"
    const val CHECK_FILE = "secret.check"
    const val META_FILE = "meta.json"
    const val README_FILE = "README.md"

    /** `settings/` 下受同步管理的文件名（新增设置项时在此登记）。 */
    val SETTING_NAMES: Set<String> = setOf("live-templates.json", "keymap.json", "themes.json")

    fun appRoot(workTree: Path, app: String): Path = workTree.resolve(app)

    fun saltFile(root: Path): Path = root.resolve(SALT_FILE)
    fun checkFile(root: Path): Path = root.resolve(CHECK_FILE)
    fun metaFile(root: Path): Path = root.resolve(META_FILE)

    fun folderFile(root: Path, id: String): Path = root.resolve("folders").resolve("$id.json")
    fun connectionFile(root: Path, id: String): Path = root.resolve("connections").resolve("$id.enc")
    fun workspaceFile(root: Path, id: String): Path = root.resolve("workspaces").resolve("$id.json")
    fun consoleMetaFile(root: Path, id: String): Path = root.resolve("consoles").resolve("$id.meta.json")
    fun consoleBodyFile(root: Path, id: String): Path = root.resolve("consoles").resolve("$id.sql")
    fun settingFile(root: Path, name: String): Path = root.resolve("settings").resolve(name)

    /**
     * 把仓库内文件解析成实体引用；`salt.bin` / `secret.check` / `meta.json` / README / 未知文件 → null。
     * 只认「目录 + 文件名」两层结构，避免把别处文件误当实体。
     */
    fun parse(root: Path, file: Path): SyncRef? {
        val rootAbs = root.toAbsolutePath().normalize()
        val target = file.toAbsolutePath().normalize()
        if (!target.startsWith(rootAbs)) return null
        val rel = rootAbs.relativize(target)
        if (rel.nameCount != 2) return null
        val dir = rel.getName(0).toString()
        val name = rel.getName(1).toString()
        return when (dir) {
            "folders" -> idOf(name, ".json")?.let(SyncRef::Folder)
            "connections" -> idOf(name, ".enc")?.let(SyncRef::Connection)
            "workspaces" -> idOf(name, ".json")?.let(SyncRef::Workspace)
            "consoles" -> idOf(name, ".meta.json")?.let(SyncRef::ConsoleMeta)
                ?: idOf(name, ".sql")?.let(SyncRef::ConsoleBody)
            "settings" -> name.takeIf { it in SETTING_NAMES }?.let(SyncRef::Setting)
            else -> null
        }
    }

    private fun idOf(fileName: String, suffix: String): String? =
        if (!fileName.endsWith(suffix)) {
            null
        } else {
            fileName.removeSuffix(suffix).takeIf { it.isNotEmpty() }
        }
}

/** 仓库中一个同步实体的引用（id 即文件名主干）。 */
sealed interface SyncRef {
    val id: String

    data class Folder(override val id: String) : SyncRef
    data class Connection(override val id: String) : SyncRef
    data class Workspace(override val id: String) : SyncRef
    data class ConsoleMeta(override val id: String) : SyncRef
    data class ConsoleBody(override val id: String) : SyncRef
    data class Setting(val name: String) : SyncRef {
        override val id: String get() = name
    }
}
