package app.core

import org.tinylog.core.LogEntry
import org.tinylog.core.LogEntryValue
import org.tinylog.writers.AbstractWriter
import java.io.BufferedWriter
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * tinylog 2.x 自定义 writer（注册见 src/main/resources/tinylog.properties：writer2 = app.core.SessionLogWriter）。
 *
 * 每次进程启动新建一个日志文件，便于按「会话」排查问题：
 *   logs/2026-09/2026-09-04_0.log、logs/2026-09/2026-09-04_1.log（同一天多次启动，序号递增）
 *
 * 日志根目录默认相对工作目录的 logs/；可用 -Ddbk.logDir=<dir> 覆盖（M5 打包后指向用户目录）。
 */
class SessionLogWriter() : AbstractWriter(emptyMap()) {

    /** 兼容 tinylog 通过属性 Map 反射实例化（内容不使用，路径/格式自管）。 */
    constructor(properties: Map<String, String>) : this()

    private val lock = ReentrantLock()
    private val file: File
    private val out: BufferedWriter

    init {
        val root = File(System.getProperty("dbk.logDir") ?: "logs")
        val now = LocalDateTime.now()
        val monthDir = File(root, now.format(MONTH_DIR))
        monthDir.mkdirs()
        val prefix = now.format(DAY_PREFIX)
        var index = 0
        while (File(monthDir, "${prefix}_$index.log").exists()) index++
        file = File(monthDir, "${prefix}_$index.log")
        out = file.bufferedWriter(Charsets.UTF_8)
        out.write("# db-k session log started at ${now.format(TIMESTAMP)}")
        out.newLine()
    }

    override fun getRequiredLogEntryValues(): Set<LogEntryValue> =
        setOf(
            LogEntryValue.DATE,
            LogEntryValue.LEVEL,
            LogEntryValue.FILE,
            LogEntryValue.LINE,
            LogEntryValue.MESSAGE,
            LogEntryValue.EXCEPTION,
        )

    override fun write(entry: LogEntry) {
        lock.withLock {
            runCatching {
                val where = entry.fileName?.takeIf { it.isNotEmpty() }
                    ?.let { "$it:${entry.lineNumber}" } ?: "-"
                val line = buildString {
                    append(entry.timestamp.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime().format(TIMESTAMP))
                    append(" [").append(entry.level.toString()).append("] (")
                    append(where).append(") ").append(entry.message)
                }
                out.write(line)
                out.newLine()
                entry.exception?.let {
                    out.write(it.stackTraceToString())
                    out.newLine()
                }
                out.flush()
            }.onFailure { /* 日志写失败不向上抛，避免影响业务 */ }
        }
    }

    override fun flush() {
        lock.withLock { runCatching { out.flush() } }
    }

    override fun close() {
        lock.withLock { runCatching { out.close() } }
    }

    private companion object {
        val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        val MONTH_DIR: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
        val DAY_PREFIX: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
