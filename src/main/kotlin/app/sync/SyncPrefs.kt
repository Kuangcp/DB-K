package app.sync

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties
import org.tinylog.Logger

/**
 * 同步配置（应用级偏好，不落 SQLite）：`<dataDir>/sync.properties`。
 *
 * 含同步口令 → POSIX 下写入后收紧为 `rw-------`（非 POSIX 平台忽略）。
 * `localDir` 可以是相对 [dataDir] 的名字（默认 `sync`），也可以是绝对路径。
 */
data class SyncSettings(
    val remoteUrl: String = "",
    val localDir: String = "sync",
    val branch: String = "main",
    /** 明文口令（用户明确要求记住；只在本地配置目录，绝不进仓库）。 */
    val passphrase: String? = null,
    val autoPushAfterPull: Boolean = true,
    val autoPushOnExit: Boolean = false,
    /** 上次成功同步时间；0 = 本设备从未同步过（首次同步守卫用）。 */
    val lastSyncAtMs: Long = 0,
)

class SyncPrefs(private val file: Path) {

    fun load(): SyncSettings {
        if (!Files.isRegularFile(file)) return SyncSettings()
        val props = runCatching {
            Properties().apply { Files.newInputStream(file).use { load(it) } }
        }.onFailure { Logger.warn(it, "sync.properties unreadable; using defaults") }
            .getOrElse { return SyncSettings() }

        fun flag(key: String, def: Boolean): Boolean = when (props.getProperty(key)?.trim()?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> def
        }

        return SyncSettings(
            remoteUrl = props.getProperty("remoteUrl")?.trim().orEmpty(),
            localDir = props.getProperty("localDir")?.trim()?.takeIf { it.isNotEmpty() } ?: "sync",
            branch = props.getProperty("branch")?.trim()?.takeIf { it.isNotEmpty() } ?: "main",
            passphrase = props.getProperty("passphrase")?.takeIf { it.isNotEmpty() },
            autoPushAfterPull = flag("autoPushAfterPull", true),
            autoPushOnExit = flag("autoPushOnExit", false),
            lastSyncAtMs = props.getProperty("lastSyncAtMs")?.trim()?.toLongOrNull() ?: 0,
        )
    }

    fun save(settings: SyncSettings) {
        runCatching {
            val props = Properties()
            props.setProperty("remoteUrl", settings.remoteUrl)
            props.setProperty("localDir", settings.localDir)
            props.setProperty("branch", settings.branch)
            settings.passphrase?.let { props.setProperty("passphrase", it) }
            props.setProperty("autoPushAfterPull", settings.autoPushAfterPull.toString())
            props.setProperty("autoPushOnExit", settings.autoPushOnExit.toString())
            props.setProperty("lastSyncAtMs", settings.lastSyncAtMs.toString())

            Files.createDirectories(file.parent)
            val tmp = Files.createTempFile(file.parent, ".sync", ".properties")
            try {
                Files.newOutputStream(tmp).use { props.store(it, "db-k sync settings (passphrase stays on this device)") }
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                runCatching { Files.deleteIfExists(tmp) }
                Files.newOutputStream(file).use { props.store(it, "db-k sync settings") }
            }
            restrictToOwner(file)
        }.onFailure { Logger.warn(it, "failed to save sync.properties") }
    }

    /** POSIX 下收紧为 rw-------（600）；非 POSIX 平台静默忽略。 */
    private fun restrictToOwner(target: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                target,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }
}
