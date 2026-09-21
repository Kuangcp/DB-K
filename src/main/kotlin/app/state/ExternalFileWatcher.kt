package app.state

import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 基于 WatchService 的外部文件监听：监听父目录（覆盖「临时文件 + rename」原子保存），
 * 目录注册引用计数，事件防抖，另有 mtime+size 安全轮询兜底（kqueue/网络盘漏事件、目录被替换）。
 * 单守护线程；watch/unwatch 可在 UI 线程调用。
 */
class ExternalFileWatcher(
    private val onChange: (Path) -> Unit,
    private val debounceMs: Long = 300,
    private val pollMs: Long = 2000,
) : FileWatcher, AutoCloseable {

    private data class DirReg(val key: WatchKey, var refs: Int)
    private data class Snapshot(var modified: Long, var size: Long)

    private val service: WatchService = FileSystems.getDefault().newWatchService()
    private val lock = Any()
    private val dirs = LinkedHashMap<Path, DirReg>()
    private val keys = HashMap<WatchKey, Path>()
    private val watched = LinkedHashMap<Path, Snapshot>()
    private val pending = ConcurrentHashMap<Path, Long>()
    private val running = AtomicBoolean(true)

    private val thread = Thread({ loop() }, "dbk-file-watcher").apply {
        isDaemon = true
        start()
    }

    override fun watch(path: Path) {
        val p = path.toAbsolutePath().normalize()
        val parent = p.parent ?: return
        synchronized(lock) {
            if (watched.containsKey(p)) return
            watched[p] = snapshot(p)
            val reg = dirs[parent]
            if (reg != null) {
                reg.refs += 1
                return
            }
            val key = register(parent) ?: return
            dirs[parent] = DirReg(key, 1)
            keys[key] = parent
        }
    }

    override fun unwatch(path: Path) {
        val p = path.toAbsolutePath().normalize()
        val parent = p.parent ?: return
        synchronized(lock) {
            if (watched.remove(p) == null) return
            val reg = dirs[parent] ?: return
            reg.refs -= 1
            if (reg.refs <= 0) {
                reg.key.cancel()
                keys.remove(reg.key)
                dirs.remove(parent)
            }
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { service.close() }
        runCatching { thread.interrupt() }
    }

    private fun loop() {
        var lastPoll = 0L
        while (running.get()) {
            val key = try {
                service.poll(100, TimeUnit.MILLISECONDS)
            } catch (_: ClosedWatchServiceException) {
                return
            } catch (_: InterruptedException) {
                return
            }
            if (key != null) {
                val dir = synchronized(lock) { keys[key] }
                if (dir != null) {
                    for (ev in key.pollEvents()) handleEvent(dir, ev)
                }
                key.reset()
            }
            emitDue()
            val now = System.currentTimeMillis()
            if (now - lastPoll >= pollMs) {
                lastPoll = now
                safetyPoll()
            }
        }
    }

    private fun handleEvent(dir: Path, ev: WatchEvent<*>) {
        if (ev.kind() == StandardWatchEventKinds.OVERFLOW) {
            synchronized(lock) { watched.keys.forEach { markPending(it) } }
            return
        }
        val name = ev.context() as? Path ?: return
        val full = dir.resolve(name).toAbsolutePath().normalize()
        synchronized(lock) { if (watched.containsKey(full)) markPending(full) }
    }

    /** mtime+size 有变化即入 pending；同时尝试重新注册丢失的父目录。 */
    private fun safetyPoll() {
        val toCheck: List<Path>
        synchronized(lock) { toCheck = watched.keys.toList() }
        for (p in toCheck) {
            val snap = snapshot(p)
            synchronized(lock) {
                val old = watched[p] ?: continue
                if (snap.modified != old.modified || snap.size != old.size) {
                    old.modified = snap.modified
                    old.size = snap.size
                    markPending(p)
                }
                val parent = p.parent ?: continue
                if (parent !in dirs) {
                    register(parent)?.let { k ->
                        val refs = watched.keys.count { it.parent == parent }
                        dirs[parent] = DirReg(k, refs)
                        keys[k] = parent
                    }
                }
            }
        }
    }

    private fun emitDue() {
        val now = System.currentTimeMillis()
        for ((p, first) in pending.entries) {
            if (now - first < debounceMs) continue
            if (pending.remove(p, first)) {
                synchronized(lock) {
                    watched[p]?.let { it.modified = snapshot(p).modified; it.size = snapshot(p).size }
                }
                runCatching { onChange(p) }
            }
        }
    }

    private fun register(dir: Path): WatchKey? = runCatching {
        dir.register(
            service,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE,
        )
    }.getOrNull()

    private fun markPending(p: Path) {
        pending.putIfAbsent(p, System.currentTimeMillis())
    }

    private fun snapshot(p: Path): Snapshot = runCatching {
        val a = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes::class.java)
        Snapshot(a.lastModifiedTime().toMillis(), a.size())
    }.getOrElse { Snapshot(-1L, -1L) }
}
