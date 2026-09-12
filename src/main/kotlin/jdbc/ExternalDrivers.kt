package jdbc

import db.AppPaths
import db.DbType
import org.tinylog.Logger
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Driver
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Collectors

/**
 * 外部 JDBC 驱动加载：把 `<dataDir>/drivers` 下的 jar 用**独立 classloader** 加载，
 * 供 SQL Server / Oracle 等「驱动不应进内置 classpath」的数据源使用（Oracle 驱动有 license 限制）。
 *
 * 设计要点：
 * - classloader 的 parent 取 `java.sql.Driver` 的 classloader（平台层）：保证与内置驱动共享
 *   `java.sql` 接口类，同时与内置驱动**隔离**（同名类互不干扰；驱动自带依赖放同一目录即可）。
 * - **不走 DriverManager 注册**：[DriverManager.getConnection] 会以 caller classloader 校验驱动可见性
 *   （`isDriverAllowed`），子 classloader 加载的驱动会被静默跳过。这里持有驱动实例，直接
 *   `driver.connect(url, props)`；内置驱动仍走原 `Class.forName + DriverManager` 路径。
 * - 目录不存在自动创建；单个 jar 读取/实例化失败只记日志，不影响其它驱动与启动。
 * - 幂等：同一目录只扫描一次。`scanDirectory` 供自检/测试指定目录；`reset` 供测试卸载。
 *
 * 加载时机：应用启动时 `ensureLoaded()` 一次；新增 jar 后需**重启**应用（首版语义）。
 */
object ExternalDrivers {

    const val DIR_NAME = "drivers"

    /** 测试/运维覆盖驱动目录，等价于 logDir 的覆盖习惯。 */
    private const val OVERRIDE_PROP = "dbk.driversDir"

    private val driversByClass = ConcurrentHashMap<String, Driver>()
    private val loaders = mutableListOf<URLClassLoader>()
    private val loadedDirs = mutableSetOf<String>()

    private val driverParent: ClassLoader =
        Driver::class.java.classLoader ?: ClassLoader.getPlatformClassLoader()

    /** 外部驱动目录：默认 `<dataDir>/drivers`，可用 `-Ddbk.driversDir=<dir>` 覆盖。 */
    fun driversDir(): Path =
        System.getProperty(OVERRIDE_PROP)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { Paths.get(it).toAbsolutePath().normalize() }
            ?: AppPaths.dataDirectory().resolve(DIR_NAME)

    /** 幂等加载默认目录，返回已加载的驱动类名（排序）。 */
    fun ensureLoaded(): List<String> {
        loadDir(driversDir())
        return loadedNames()
    }

    /** 扫描指定目录（自检/测试用）；同一目录重复调用幂等。返回已加载驱动类名。 */
    fun scanDirectory(dir: Path): List<String> {
        loadDir(dir)
        return loadedNames()
    }

    /** 按驱动类名取外部驱动实例（大小写不敏感）；调用前会确保默认目录已加载。 */
    fun driverFor(className: String): Driver? {
        loadDir(driversDir())
        return driversByClass[className.lowercase()]
    }

    fun isAvailable(className: String): Boolean = driverFor(className) != null

    /** 已加载的外部驱动类名（排序）。 */
    fun loadedNames(): List<String> = driversByClass.values.map { it.javaClass.name }.sorted()

    /** 卸载全部外部驱动（仅测试使用；运行中的应用不需要）。 */
    @Synchronized
    fun reset() {
        loaders.forEach { runCatching { it.close() } }
        loaders.clear()
        driversByClass.clear()
        loadedDirs.clear()
    }

    @Synchronized
    private fun loadDir(dir: Path) {
        val key = dir.toAbsolutePath().normalize().toString()
        if (key in loadedDirs) return
        loadedDirs += key
        if (!Files.isDirectory(dir)) {
            runCatching { Files.createDirectories(dir) }
                .onFailure { Logger.warn(it, "创建外部驱动目录失败：{}", dir) }
            Logger.info("外部驱动目录：{}（尚无 jar）", dir)
            return
        }
        val jars: List<Path> = runCatching {
            Files.list(dir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".jar", ignoreCase = true) }
                    .sorted()
                    .collect(Collectors.toList())
            }
        }.onFailure { Logger.warn(it, "扫描外部驱动目录失败：{}", dir) }.getOrDefault(emptyList())
        if (jars.isEmpty()) {
            Logger.info("外部驱动目录：{}（尚无 jar）", dir)
            return
        }
        val loader = URLClassLoader(jars.map { it.toUri().toURL() }.toTypedArray(), driverParent)
        loaders += loader

        // 候选驱动类：jar 内 META-INF/services/java.sql.Driver + 内置声明为 external 的类型
        val candidates = linkedSetOf<String>()
        jars.forEach { jar -> candidates += readServiceEntries(jar) }
        DbType.entries.filter { it.externalDriver }.forEach { candidates += it.driverClass }

        var loaded = 0
        candidates.forEach { name ->
            val cls = runCatching { Class.forName(name, false, loader) }.getOrNull() ?: return@forEach
            if (!Driver::class.java.isAssignableFrom(cls)) return@forEach
            val driver = runCatching { cls.getDeclaredConstructor().newInstance() as Driver }
                .onFailure { Logger.warn(it, "实例化外部驱动失败：{}", name) }
                .getOrNull() ?: return@forEach
            if (driversByClass.putIfAbsent(name.lowercase(), driver) == null) {
                loaded++
                Logger.info("已加载外部驱动：{}", name)
            }
        }
        Logger.info("外部驱动目录：{}，jar={} 个，新增驱动={} 个", dir, jars.size, loaded)
    }

    /** 读 jar 的 `META-INF/services/java.sql.Driver`（每行一个驱动类名，忽略注释）。 */
    private fun readServiceEntries(jar: Path): List<String> = runCatching {
        java.util.jar.JarFile(jar.toFile()).use { jf ->
            jf.getEntry("META-INF/services/java.sql.Driver")?.let { entry ->
                jf.getInputStream(entry).bufferedReader().useLines { lines ->
                    lines.map { it.substringBefore('#').trim() }
                        .filter { it.isNotEmpty() }
                        .toList()
                }
            }.orEmpty()
        }
    }.onFailure { Logger.warn(it, "读取驱动 service 声明失败：{}", jar) }.getOrDefault(emptyList())
}
