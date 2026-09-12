package db

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

/**
 * 应用数据目录（连接档案、文件夹、SQL 历史等本地元数据存放处），与目标数据库无关。
 * 平台默认目录 + `debugHome` 重定向逻辑照搬 api-x：主目录下 `app-settings.properties`
 * 可写 `debugHome=/path` 把全部数据重定向到沙箱目录，便于开发调试不污染正式数据。
 */
object AppPaths {

    private const val KEY_DEBUG_HOME = "debugHome"

    private fun platformDefaultDataRoot(): Path {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> {
                val appData = System.getenv("APPDATA")
                if (!appData.isNullOrBlank()) Paths.get(appData, "db-k")
                else Paths.get(System.getProperty("user.home"), "AppData", "Roaming", "db-k")
            }
            os.contains("mac") -> Paths.get(
                System.getProperty("user.home"), "Library", "Application Support", "db-k",
            )
            else -> {
                val xdg = System.getenv("XDG_DATA_HOME")
                if (!xdg.isNullOrBlank()) Paths.get(xdg, "db-k")
                else Paths.get(System.getProperty("user.home"), ".local", "share", "db-k")
            }
        }
    }

    private val appSettingsBootstrapFile: Path
        get() = platformDefaultDataRoot().resolve("app-settings.properties")

    @Volatile
    private var cachedDataDirectory: Path? = null
    private val dataDirectoryLock = Any()

    fun dataDirectory(): Path {
        cachedDataDirectory?.let { return it }
        return synchronized(dataDirectoryLock) {
            cachedDataDirectory?.let { return it }
            val def = platformDefaultDataRoot()
            val chosen = if (Files.isRegularFile(appSettingsBootstrapFile)) {
                runCatching {
                    val props = Properties()
                    Files.newInputStream(appSettingsBootstrapFile).use { props.load(it) }
                    val raw = props.getProperty(KEY_DEBUG_HOME)?.trim().orEmpty()
                    if (raw.isEmpty()) def
                    else Paths.get(raw).toAbsolutePath().normalize().also { Files.createDirectories(it) }
                }.getOrNull() ?: def
            } else def
            Files.createDirectories(chosen)
            cachedDataDirectory = chosen
            chosen
        }
    }

    /** 应用元数据 SQLite 库文件。 */
    fun appDatabasePath(): Path = dataDirectory().resolve("app.db")

    /** 控制台 .sql 文件根目录：<dataDir>/consoles/。 */
    fun consolesDir(): Path = dataDirectory().resolve("consoles")

    /**
     * 会话日志根目录：默认 <dataDir>/logs/，可用 `-Ddbk.logDir=<dir>` 覆盖。
     * 与 `SessionLogWriter` 共用同一解析逻辑（设置窗口「打开日志目录」需展示同一路径）。
     */
    fun logsDirectory(): Path = resolveLogsDirectory(System.getProperty("dbk.logDir"), dataDirectory())

    /** [logsDirectory] 的纯解析（便于单测，不触碰真实数据目录）。 */
    internal fun resolveLogsDirectory(override: String?, dataDir: Path): Path =
        override?.takeIf { it.isNotBlank() }?.let { Paths.get(it) } ?: dataDir.resolve("logs")

    /** 某控制台绑定的 .sql 文件（id 稳定，重命名不换文件）。 */
    fun consoleFile(consoleId: String): Path = consolesDir().resolve("$consoleId.sql")
}
