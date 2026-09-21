package app.core

import com.sun.jna.Library
import com.sun.jna.Native
import org.tinylog.Logger
import java.io.File

/**
 * glibc 原生内存治理。
 *
 * 背景：JVM 应用的原生内存（RSS）常因 glibc 的两个行为「只涨不降」：
 * 1. 动态 mmap 阈值：一次大块 mmap 分配被 free 后，阈值会抬高，之后的大块改从 arena(sbrk)
 *    分配，free 后不还给 OS；峰值即长期 RSS。
 * 2. 多线程 arena：每个分配线程一个 64MB 对齐的 arena，free 的内存留在各自 arena，跨线程不复用。
 *
 * 这里在启动时把 mmap/trim 阈值压回 128KB、限制 arena 数量，并提供 [trim] 在重活后主动
 * `malloc_trim(0)` 把空闲页还给 OS。非 glibc 平台静默降级为 no-op。
 */
object NativeMemory {

    private const val M_TRIM_THRESHOLD = -1
    private const val M_MMAP_THRESHOLD = -3
    private const val M_ARENA_MAX = -8
    private const val THRESHOLD_BYTES = 128 * 1024

    private interface LibC : Library {
        fun malloc_trim(pad: Int): Int
        fun mallopt(param: Int, value: Int): Int
    }

    private val libc: LibC? = runCatching { Native.load("c", LibC::class.java) }.getOrNull()

    /** 非 Linux（Windows/macOS）没有 glibc 的 mallopt/malloc_trim，也无 /proc/self/statm，整体降级为 no-op。 */
    private val isLinux: Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("linux")

    /** 启动早期调用：压低 mmap/trim 阈值并限制 arena 数，避免峰值变成常驻。 */
    fun configure() {
        if (!isLinux) {
            Logger.debug("native mem: not Linux (os.name={}), skipping glibc tuning", System.getProperty("os.name"))
            return
        }
        val ok = runCatching {
            val c = libc ?: return@runCatching false
            c.mallopt(M_MMAP_THRESHOLD, THRESHOLD_BYTES)
            c.mallopt(M_TRIM_THRESHOLD, THRESHOLD_BYTES)
            c.mallopt(M_ARENA_MAX, 2)
            true
        }.getOrDefault(false)
        Logger.info("native mem: configure mallopt={} rss={} kB", ok, rssKb())
    }

    /** 重活（查询完成 / 导出 / 关闭大图）后调用，把 glibc 空闲页归还 OS。 */
    fun trim(tag: String) {
        if (!isLinux) return
        val before = rssKb()
        val trimmed = runCatching { libc?.malloc_trim(0) == 1 }.getOrDefault(false)
        val after = rssKb()
        val freed = before - after
        // 只在确实回收了可观内存时落 info，避免每条查询/每次关图都刷日志
        if (freed >= 4096) {
            Logger.info("native mem [{}]: rss {} → {} kB (trim={})", tag, before, after, trimmed)
        } else {
            Logger.debug("native mem [{}]: rss {} → {} kB (trim={})", tag, before, after, trimmed)
        }
    }

    /** 当前进程 RSS（kB）；非 Linux 返回 -1。 */
    fun rssKb(): Long {
        if (!isLinux) return -1
        val f = File("/proc/self/statm")
        if (!f.canRead()) return -1
        return runCatching {
            val pages = f.readText().trim().split(' ')[1].toLong()
            pages * 4096 / 1024
        }.getOrDefault(-1)
    }
}
