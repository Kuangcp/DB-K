package app.state

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExternalFileWatcherTest {

    @TempDir
    lateinit var dir: Path

    private fun await(latch: CountDownLatch): Boolean = latch.await(5, TimeUnit.SECONDS)

    @Test
    fun `notifies on write`() {
        val file = dir.resolve("a.sql")
        Files.writeString(file, "SELECT 1")
        val latch = CountDownLatch(1)
        ExternalFileWatcher(onChange = { latch.countDown() }).use { w ->
            w.watch(file)
            Files.writeString(file, "SELECT 2")
            assertTrue(await(latch), "写入后应收到回调")
        }
    }

    @Test
    fun `notifies on atomic replace`() {
        val file = dir.resolve("b.sql")
        Files.writeString(file, "SELECT 1")
        val latch = CountDownLatch(1)
        ExternalFileWatcher(onChange = { latch.countDown() }).use { w ->
            w.watch(file)
            val tmp = dir.resolve(".b.sql.tmp")
            Files.writeString(tmp, "SELECT 9")
            Files.move(
                tmp,
                file,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            assertTrue(await(latch), "原子替换应收到回调")
        }
    }

    @Test
    fun `unwatch stops notifications`() {
        val file = dir.resolve("c.sql")
        Files.writeString(file, "SELECT 1")
        val count = java.util.concurrent.atomic.AtomicInteger(0)
        ExternalFileWatcher(onChange = { count.incrementAndGet() }).use { w ->
            w.watch(file)
            w.unwatch(file)
            Files.writeString(file, "SELECT 2")
            Thread.sleep(800)
            assertEquals(0, count.get(), "unwatch 后不应再回调")
        }
    }

    @Test
    fun `close terminates thread`() {
        val file = dir.resolve("d.sql")
        Files.writeString(file, "SELECT 1")
        val w = ExternalFileWatcher(onChange = {})
        w.watch(file)
        w.close()
        // close 后再次 close 不抛异常
        w.close()
    }
}
