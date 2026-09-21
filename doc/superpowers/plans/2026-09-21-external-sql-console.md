# 外部 SQL 文件控制台 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让用户把项目里的 `.sql` 文件（选择或拖入）当普通控制台用：绑定数据源、选中执行、切目标库、编辑自动写回原文件，并与磁盘外部修改双向同步。

**Architecture:** 外部控制台就是一条 `external=1` 的普通 `consoles` 行（路径唯一、跨工作区共享），复用整条控制台生命周期；新增一个基于 `WatchService` 的 `ExternalFileWatcher` 监听父目录，事件经 UI 线程回灌 `ConsoleState`，按「干净自动重载 / 脏则提示」处理冲突；缺文件时暂停自动保存并在退出时保护未保存内容。

**Tech Stack:** Kotlin/JVM 25、Compose Desktop 1.12（`TextFieldState`、`Modifier.dragAndDropTarget`、`DragData.FilesList`）、SQLite（`org.xerial:sqlite-jdbc`）、`java.nio.file.WatchService`、kotlinx-coroutines、kotlin.test + JUnit5。

**Spec:** `doc/superpowers/specs/2026-09-21-external-sql-console-design.md`

## Global Constraints

- JDK：`JAVA_HOME=/home/zk/.sdkman/candidates/java/25.0.3-jbr`；Gradle：`/home/zk/.sdkman/candidates/gradle/9.4.1/bin`（不引入 wrapper）。所有命令前先 `export JAVA_HOME=... PATH=...`。
- 编译检查：`gradle compileKotlin`；单测：`gradle test`；驱动冒烟：`gradle smokeJdbc`。
- 分层：`engine` 纯叶层；`jdbc`/`redis`/`es` 禁止 import compose/coroutines；`i18n` 只依赖 JDK；`app/state` 依赖 `db`/`engine`/`jdbc`。`FileWatcher` 放 `app/state`（避免 `app/state → app/core` 反向依赖）。
- 编译：`allWarningsAsErrors = true`（`build.gradle.kts`），未使用/缺失 import 都会让 `gradle compileKotlin` 失败；每个代码步骤务必让 import 与用量精确一致。
- i18n：所有用户可见文案走 `Str` key，zh + en 同时补；`I18nCatalogTest` 校验占位符一致与非空；派生/状态模型只存 key + 参数。
- 主题：文字/图标前景必须来自主题；语义色只做色块/点/徽章背景；新增界面需人工切深色与中英各检查一遍。
- 外部文件绝不改名、绝不删除；删除控制台只删 `external=0` 的文件。
- UI 行为（拖拽、横幅、弹层、主题可读性）**不做自动化验证**，只给人工步骤；程序化可验部分（迁移、仓库、watcher、ConsoleState、i18n）跑 `gradle test`。
- 提交信息用 Conventional Commits（`feat(...)` / `fix(...)` / `test(...)` / `docs(...)`）。

## Review Focus

1. **同一文件用不同写法路径（相对路径 / 符号链接）重复拖入** → 预期：只产生一个控制台，第二次聚焦已有，绝不两份缓冲写同一文件。测试：Task 1 的 `getConsoleByPath` + 路径规范化，Task 4 的 `openExternalFile reuses existing by normalized path`。
2. **运行时文件被外部删除后继续编辑再退出** → 预期：绝不静默重建、绝不静默丢失，退出弹框处理。测试：Task 4 的 `missing pauses autosave` + `missingDirtyExternal`；人工：Task 9 退出流程。
3. **外部保存与 DB-K 自动保存并发** → 预期：不读到半截内容、不把外部内容误判成自身写入。测试：Task 2 原子写往返、Task 4 `self write is ignored` / `external change reloads clean buffer`。
4. **IDE 原子保存（临时文件 + rename 覆盖）** → 预期：能触发重载。测试：Task 3 watcher（父目录监听）+ Task 9 人工用真实 IDE 保存验证。
5. **删除外部控制台 / 删除其数据源连接** → 预期：用户的 `.sql` 文件原封不动。测试：Task 1 `deleteConsole keeps external file` + `deleteConnection keeps external file`。

---

### Task 1: 数据层——v12 迁移、`external` 字段、外部控制台仓库方法

**Files:**
- Modify: `src/main/kotlin/db/AppDatabase.kt`（`CURRENT_VERSION`、迁移接线、`migrateToV12`）
- Modify: `src/main/kotlin/db/ConsoleModels.kt`
- Modify: `src/main/kotlin/db/ConnectionsRepository.kt`（SELECT/映射/新方法/删除分支）
- Test: `src/test/kotlin/db/AppDatabaseMigrationTest.kt`、`src/test/kotlin/db/ConnectionsRepositoryTest.kt`

**Interfaces:**
- Consumes: 无（本任务是最底层）
- Produces（后续任务依赖的精确签名）：
  - `ConsoleRecord.external: Boolean`
  - `ConnectionsRepository.getConsoleByPath(path: String): ConsoleRecord?`
  - `ConnectionsRepository.createExternalConsole(connectionId: String, name: String, filePath: String): ConsoleRecord`
  - `ConnectionsRepository.rebindConsoleFile(id: String, newPath: String)`

- [ ] **Step 1: 写失败测试（迁移）**

在 `AppDatabaseMigrationTest.kt` 的 `fresh migrate applies all versions` 里把版本列表补到 12：

```kotlin
assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), versions)
```

在同文件的 `legacy v2 migrates preserving data and defaults` 末尾（`v9` 断言之后）补两条断言：

```kotlin
// v12 旧控制台默认 external=0
st.executeQuery("SELECT external FROM consoles WHERE id='cc1'").use { rs ->
    rs.next()
    assertEquals(0, rs.getInt("external"))
}
```

并新增一个独立测试：

```kotlin
@Test
fun `v12 file_path is unique`() {
    val db = dir.resolve("v12.db")
    raw(db).use { AppDatabase.migrate(it) }
    raw(db).use { c ->
        c.createStatement().use { st ->
            st.execute(
                "INSERT INTO consoles(id, connection_id, name, file_path, created_at, updated_at) " +
                    "VALUES ('a','c1','a','/tmp/x.sql',1,1)",
            )
        }
        val dup = runCatching {
            c.createStatement().use { st ->
                st.execute(
                    "INSERT INTO consoles(id, connection_id, name, file_path, created_at, updated_at) " +
                        "VALUES ('b','c1','b','/tmp/x.sql',1,1)",
                )
            }
        }
        assertTrue(dup.isFailure, "file_path 唯一索引应拒绝重复路径")
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `gradle test --tests 'db.AppDatabaseMigrationTest' --console=plain`
Expected: FAIL（版本列表缺少 12 / 无 `external` 列 / 重复路径未被拒绝）

- [ ] **Step 3: 写失败测试（仓库）**

在 `ConnectionsRepositoryTest.kt` 末尾追加：

```kotlin
@Test
fun `external console round-trips and never deletes its file`() {
    open().use { repo ->
        val pid = repo.createConnection(newProfile(id = "c1"))
        val ext = dir.resolve("project").also { java.nio.file.Files.createDirectories(it) }.resolve("iter.sql")
        java.nio.file.Files.writeString(ext, "SELECT 1")

        val rec = repo.createExternalConsole(pid, "iter.sql", ext.toString())
        assertTrue(rec.external)
        assertEquals(rec.id, repo.getConsoleByPath(ext.toString())?.id)
        assertEquals("SELECT 1", repo.readConsoleContent(rec.id))
        assertTrue(java.nio.file.Files.isRegularFile(ext), "createExternalConsole 不应覆盖/删除已有文件")

        repo.deleteConsole(rec.id)
        assertTrue(java.nio.file.Files.isRegularFile(ext), "删除外部控制台不得删除文件")
        assertNull(repo.getConsole(rec.id))
    }
}

@Test
fun `deleteConnection keeps external files`() {
    open().use { repo ->
        val pid = repo.createConnection(newProfile(id = "c1"))
        val managed = repo.createConsole(pid, "控制台 1")
        val ext = dir.resolve("keep.sql")
        java.nio.file.Files.writeString(ext, "SELECT 1")
        repo.createExternalConsole(pid, "keep.sql", ext.toString())

        repo.deleteConnection(pid)
        assertFalse(java.nio.file.Files.exists(java.nio.file.Path.of(managed.filePath)), "普通控制台文件应删除")
        assertTrue(java.nio.file.Files.isRegularFile(ext), "外部文件应保留")
    }
}

@Test
fun `rebindConsoleFile points console at a new path`() {
    open().use { repo ->
        val pid = repo.createConnection(newProfile(id = "c1"))
        val a = dir.resolve("a.sql"); val b = dir.resolve("b.sql")
        java.nio.file.Files.writeString(a, "SELECT 1")
        val rec = repo.createExternalConsole(pid, "a.sql", a.toString())

        repo.rebindConsoleFile(rec.id, b.toString())
        assertEquals(b.toString(), repo.getConsole(rec.id)!!.filePath)
        assertTrue(repo.getConsole(rec.id)!!.external)
    }
}
```

- [ ] **Step 4: 运行测试确认失败**

Run: `gradle test --tests 'db.ConnectionsRepositoryTest' --console=plain`
Expected: FAIL（`createExternalConsole` / `getConsoleByPath` / `rebindConsoleFile` 未定义；`ConsoleRecord.external` 不存在）

- [ ] **Step 5: 实现数据层**

`db/ConsoleModels.kt`：`ConsoleRecord` 加字段（放在 `filePath` 之后）：

```kotlin
    val filePath: String,
    /** true = 外部文件控制台：file_path 为用户指定路径，DB-K 不改名、不删除它。 */
    val external: Boolean = false,
```

`db/AppDatabase.kt`：`CURRENT_VERSION = 12`；在 `migrate()` 的 v11 接线之后加：

```kotlin
        if (!applied.contains(12)) {
            conn.createStatement().use { st -> migrateToV12(st) }
            conn.prepareStatement("INSERT INTO schema_migrations(version) VALUES (12)").use { it.executeUpdate() }
        }
```

并新增（放在 `migrateToV11` 附近）：

```kotlin
    /**
     * v12：外部文件控制台（拖入/选择的 .sql 当普通控制台用）。
     * external=1 的文件由用户维护，DB-K 绝不删除；file_path 唯一索引落实「按路径全局唯一」。
     */
    private fun migrateToV12(st: Statement) {
        st.executeUpdate("ALTER TABLE consoles ADD COLUMN external INTEGER NOT NULL DEFAULT 0")
        st.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_consoles_file_path ON consoles(file_path)")
    }
```

`db/ConnectionsRepository.kt`：

1. 两处 `SELECT ... FROM consoles`（`listConsoles`、`getConsole`）列清单加 `external`：
   `SELECT id, connection_id, name, file_path, external, sort_order, updated_at, target, caret_start, caret_end FROM ...`
2. `mapConsole` 加一行：`external = rs.getInt("external") != 0,`
3. 在 `createConsole` 之后新增：

```kotlin
    /** 按文件路径查控制台（唯一索引支撑）；外部文件的路径唯一性由此落实。 */
    fun getConsoleByPath(path: String): ConsoleRecord? {
        return conn.prepareStatement(
            "SELECT id, connection_id, name, file_path, external, sort_order, updated_at, target, caret_start, caret_end FROM consoles WHERE file_path = ?",
        ).use { ps ->
            ps.setString(1, path)
            ps.executeQuery().use { rs -> if (rs.next()) mapConsole(rs) else null }
        }
    }

    /** 新建外部文件控制台：只插元数据行，不建文件、不写空内容（文件由用户维护）。 */
    fun createExternalConsole(connectionId: String, name: String, filePath: String): ConsoleRecord {
        val id = newId()
        val now = System.currentTimeMillis()
        val rec = ConsoleRecord(
            id = id, connectionId = connectionId, name = name,
            filePath = filePath, external = true,
            sortOrder = nextSortOrder("consoles"), updatedAt = now,
        )
        conn.prepareStatement(
            """
            INSERT INTO consoles(id, connection_id, name, file_path, external, sort_order, created_at, updated_at)
            VALUES (?, ?, ?, ?, 1, ?, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, id)
            ps.setString(2, connectionId)
            ps.setString(3, name)
            ps.setString(4, filePath)
            ps.setInt(5, rec.sortOrder)
            ps.setLong(6, now)
            ps.setLong(7, now)
            ps.executeUpdate()
        }
        return rec
    }

    /** 「另存为…」：把外部控制台重绑到新路径（external 保持 1）。 */
    fun rebindConsoleFile(id: String, newPath: String) {
        conn.prepareStatement("UPDATE consoles SET file_path = ?, updated_at = ? WHERE id = ?").use { ps ->
            ps.setString(1, newPath)
            ps.setLong(2, System.currentTimeMillis())
            ps.setString(3, id)
            ps.executeUpdate()
        }
    }
```

4. `deleteConsole`：仅当非外部才删文件：

```kotlin
        if (rec.filePath.isNotBlank() && !rec.external) {
            runCatching { ConsoleFiles.delete(java.nio.file.Paths.get(rec.filePath)) }
        }
```

5. `deleteConnection`：跳过外部文件：

```kotlin
            listConsoles(id).forEach { rec ->
                if (!rec.external && rec.filePath.isNotBlank()) {
                    runCatching { ConsoleFiles.delete(java.nio.file.Paths.get(rec.filePath)) }
                }
            }
```

- [ ] **Step 6: 运行测试确认通过**

Run: `gradle test --tests 'db.AppDatabaseMigrationTest' --tests 'db.ConnectionsRepositoryTest' --console=plain`
Expected: PASS

- [ ] **Step 7: 提交**

```bash
git add src/main/kotlin/db/AppDatabase.kt src/main/kotlin/db/ConsoleModels.kt \
  src/main/kotlin/db/ConnectionsRepository.kt \
  src/test/kotlin/db/AppDatabaseMigrationTest.kt src/test/kotlin/db/ConnectionsRepositoryTest.kt
git commit -m "feat(db): 外部文件控制台数据层（v12 external 列 + file_path 唯一索引 + 仓库方法）"
```

---

### Task 2: `ConsoleFiles` 原子写

**Files:**
- Modify: `src/main/kotlin/db/ConsoleFiles.kt`
- Test: `src/test/kotlin/db/ConsoleFilesTest.kt`（新建）

**Interfaces:**
- Consumes: 无
- Produces: `ConsoleFiles.write(path: Path, text: String)` 语义不变（原子化），签名不变

- [ ] **Step 1: 写失败测试**

新建 `src/test/kotlin/db/ConsoleFilesTest.kt`：

```kotlin
package db

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ConsoleFilesTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `write then read round-trips utf8`() {
        val f = dir.resolve("sub").resolve("a.sql")
        ConsoleFiles.write(f, "SELECT '中文';\n")
        assertEquals("SELECT '中文';\n", ConsoleFiles.read(f))
    }

    @Test
    fun `write leaves no temp file behind`() {
        val f = dir.resolve("a.sql")
        ConsoleFiles.write(f, "SELECT 1")
        Files.list(dir).use { s ->
            val names = s.map { it.fileName.toString() }.toList()
            assertEquals(listOf("a.sql"), names)
        }
    }

    @Test
    fun `read of missing file returns empty`() {
        assertFalse(Files.exists(dir.resolve("nope.sql")))
        assertEquals("", ConsoleFiles.read(dir.resolve("nope.sql")))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `gradle test --tests 'db.ConsoleFilesTest' --console=plain`
Expected: FAIL（`write leaves no temp file behind` 当前实现不该失败——见 Step 3 说明；若此时已 PASS，Step 3 仍必须改成原子写并复跑保证不回归）

- [ ] **Step 3: 实现原子写**

把 `ConsoleFiles.write` 改为：

```kotlin
    /**
     * 原子写：同目录临时文件 + ATOMIC_MOVE，避免外部工具/监听读到半截内容。
     * 跨文件系统等无法原子移动时回落普通写。
     */
    fun write(path: Path, text: String) {
        Files.createDirectories(path.parent)
        val tmp = runCatching { Files.createTempFile(path.parent, ".${path.fileName}", ".tmp") }.getOrNull()
        if (tmp == null) {
            Files.write(path, text.toByteArray(StandardCharsets.UTF_8))
            return
        }
        runCatching {
            Files.write(tmp, text.toByteArray(StandardCharsets.UTF_8))
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.onFailure {
            runCatching { Files.deleteIfExists(tmp) }
            Files.write(path, text.toByteArray(StandardCharsets.UTF_8))
        }
    }
```

新增 import：`import java.nio.file.StandardCopyOption`。

- [ ] **Step 4: 运行测试确认通过**

Run: `gradle test --tests 'db.ConsoleFilesTest' --console=plain`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/db/ConsoleFiles.kt src/test/kotlin/db/ConsoleFilesTest.kt
git commit -m "feat(db): ConsoleFiles 原子写（临时文件 + ATOMIC_MOVE，回落普通写）"
```

---

### Task 3: `FileWatcher` 接口 + `ExternalFileWatcher`

**Files:**
- Create: `src/main/kotlin/app/state/FileWatcher.kt`
- Create: `src/main/kotlin/app/state/ExternalFileWatcher.kt`
- Test: `src/test/kotlin/app/state/ExternalFileWatcherTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `interface FileWatcher { fun watch(path: Path); fun unwatch(path: Path); fun close() }`
  - `class ExternalFileWatcher(onChange: (Path) -> Unit, debounceMs: Long = 300, pollMs: Long = 2000) : FileWatcher, AutoCloseable`

- [ ] **Step 1: 写失败测试**

新建 `src/test/kotlin/app/state/ExternalFileWatcherTest.kt`：

```kotlin
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
            Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
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
```

- [ ] **Step 2: 运行测试确认失败**

Run: `gradle test --tests 'app.state.ExternalFileWatcherTest' --console=plain`
Expected: FAIL（`ExternalFileWatcher` 未定义）

- [ ] **Step 3: 实现接口与监听器**

`app/state/FileWatcher.kt`：

```kotlin
package app.state

import java.nio.file.Path

/** 文件变更监听抽象：生产实现为 [ExternalFileWatcher]，测试注入 fake。 */
interface FileWatcher {
    fun watch(path: Path)
    fun unwatch(path: Path)
    fun close()
}
```

`app/state/ExternalFileWatcher.kt`：

```kotlin
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
            val key = runCatching {
                parent.register(
                    service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE,
                )
            }.getOrNull() ?: return
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
                    runCatching {
                        parent.register(
                            service,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_DELETE,
                        )
                    }.getOrNull()?.let { k ->
                        dirs[parent] = DirReg(k, 1)
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
                synchronized(lock) { watched[p]?.let { it.modified = snapshot(p).modified; it.size = snapshot(p).size } }
                runCatching { onChange(p) }
            }
        }
    }

    private fun markPending(p: Path) {
        pending.putIfAbsent(p, System.currentTimeMillis())
    }

    private fun snapshot(p: Path): Snapshot = runCatching {
        val a = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes::class.java)
        Snapshot(a.lastModifiedTime().toMillis(), a.size())
    }.getOrElse { Snapshot(-1L, -1L) }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `gradle test --tests 'app.state.ExternalFileWatcherTest' --console=plain`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/app/state/FileWatcher.kt src/main/kotlin/app/state/ExternalFileWatcher.kt \
  src/test/kotlin/app/state/ExternalFileWatcherTest.kt
git commit -m "feat(state): ExternalFileWatcher（父目录监听 + 防抖 + mtime 兜底）"
```

---

### Task 4: `ConsoleState` 外部文件机制

**Files:**
- Modify: `src/main/kotlin/app/state/ConsoleState.kt`
- Test: `src/test/kotlin/app/state/ConsoleStateTest.kt`

**Interfaces:**
- Consumes: Task 1 仓库方法、Task 2 `ConsoleFiles`、Task 3 `FileWatcher`
- Produces：
  - `enum class ExternalFileIssue { CONFLICT, MISSING }`
  - `ConsoleState(..., fileWatcher: FileWatcher? = null)`
  - `fun consoleByPath(path: Path): ConsoleRecord?`
  - `fun openExternalFile(profileId: String, path: Path): ConsoleRecord`
  - `fun handleExternalChange(path: Path)`
  - `fun externalIssueOf(consoleId: String): ExternalFileIssue?`
  - `fun textRevisionOf(consoleId: String): Int`
  - `fun reloadFromDisk(consoleId: String)`
  - `fun keepLocal(consoleId: String)`
  - `fun recreateExternalFile(consoleId: String): Boolean`
  - `fun rebindExternalFile(consoleId: String, newPath: Path): Boolean`
  - `fun pruneMissingExternal(profileIds: List<String>)`
  - `fun missingDirtyExternal(profileIds: List<String>): List<ConsoleRecord>`
  - `fun discardDirty(consoleId: String)`
  - `fun closeExternalWatcher()`

- [ ] **Step 1: 写失败测试**

在 `ConsoleStateTest.kt` 顶部加测试用具：

```kotlin
    private class FakeFileWatcher : app.state.FileWatcher {
        val watched = mutableSetOf<Path>()
        val unwatched = mutableSetOf<Path>()
        var closed = false
        override fun watch(path: Path) { watched += path }
        override fun unwatch(path: Path) { unwatched += path }
        override fun close() { closed = true }
    }
```

把 `newState` 改为接收 watcher 并注入（默认 fake，避免真实守护线程）：

```kotlin
    private fun TestScope.newState(
        repo: ConnectionsRepository,
        watcher: app.state.FileWatcher = FakeFileWatcher(),
    ) = ConsoleState(
        repository = repo,
        connectionsState = ConnectionsState(MetaCache(dbPath), ColumnCache(dbPath)),
        workspaces = WorkspaceState(repo, loadActive = { null }, saveActive = {}),
        scope = this,
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        fileWatcher = watcher,
    )
```

追加测试：

```kotlin
    private fun extFile(name: String, text: String = "SELECT 1"): Path {
        val p = dir.resolve(name)
        Files.writeString(p, text)
        return p
    }

    @Test
    fun `openExternalFile creates reads watches and marks present`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val watcher = FakeFileWatcher()
            val state = newState(repo, watcher)
            val f = extFile("iter.sql", "SELECT 42")
            val rec = state.openExternalFile(pid, f)
            assertTrue(rec.external)
            assertEquals("SELECT 42", state.textOf(rec.id))
            assertNull(state.externalIssueOf(rec.id))
            assertTrue(f.toAbsolutePath().normalize() in watcher.watched)
        }
    }

    @Test
    fun `openExternalFile reuses existing by normalized path`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val f = dir.resolve("sub").also { Files.createDirectories(it) }.resolve("iter.sql")
            Files.writeString(f, "SELECT 1")
            val first = state.openExternalFile(pid, f)
            val second = state.openExternalFile(pid, f.toAbsolutePath().normalize())
            assertEquals(first.id, second.id)
            assertEquals(1, repo.listConsoles(pid).count { it.external })
        }
    }

    @Test
    fun `external change reloads clean buffer`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val f = extFile("a.sql", "SELECT 1")
            val rec = state.openExternalFile(pid, f)
            Files.writeString(f, "SELECT 2")
            state.handleExternalChange(f)
            assertEquals("SELECT 2", state.textOf(rec.id))
            assertEquals(1, state.textRevisionOf(rec.id))
            assertNull(state.externalIssueOf(rec.id))
        }
    }

    @Test
    fun `external change on dirty buffer flags conflict and keeps local`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val f = extFile("a.sql", "SELECT 1")
            val rec = state.openExternalFile(pid, f)
            state.setText(rec.id, "SELECT local")
            Files.writeString(f, "SELECT 2")
            state.handleExternalChange(f)
            assertEquals(ExternalFileIssue.CONFLICT, state.externalIssueOf(rec.id))
            assertEquals("SELECT local", state.textOf(rec.id))

            // 载入磁盘版本：丢本地、清脏、清冲突
            state.reloadFromDisk(rec.id)
            assertEquals("SELECT 2", state.textOf(rec.id))
            assertFalse(state.isDirty(rec.id))
            assertNull(state.externalIssueOf(rec.id))
        }
    }

    @Test
    fun `self write is ignored`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val f = extFile("a.sql", "SELECT 1")
            val rec = state.openExternalFile(pid, f)
            state.setText(rec.id, "SELECT mine")
            state.flushNow(rec.id)
            val before = state.textRevisionOf(rec.id)
            state.handleExternalChange(f)
            assertEquals(before, state.textRevisionOf(rec.id), "自身写入不应触发重载")
            assertEquals("SELECT mine", state.textOf(rec.id))
        }
    }

    @Test
    fun `external delete flags missing and pauses autosave`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val f = extFile("a.sql", "SELECT 1")
            val rec = state.openExternalFile(pid, f)
            state.setText(rec.id, "SELECT local")
            Files.delete(f)
            state.handleExternalChange(f)
            assertEquals(ExternalFileIssue.MISSING, state.externalIssueOf(rec.id))
            state.flushNow(rec.id)
            assertTrue(state.isDirty(rec.id), "缺文件时 flush 应保持脏、不写盘")
            assertFalse(Files.exists(f), "缺文件时绝不擅自重建")

            assertEquals(listOf(rec.id), state.missingDirtyExternal(listOf(pid)).map { it.id })
        }
    }

    @Test
    fun `recreate writes buffer back to original path`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val f = extFile("a.sql", "SELECT 1")
            val rec = state.openExternalFile(pid, f)
            state.setText(rec.id, "SELECT local")
            Files.delete(f)
            state.handleExternalChange(f)
            assertTrue(state.recreateExternalFile(rec.id))
            assertEquals("SELECT local", Files.readString(f))
            assertFalse(state.isDirty(rec.id))
            assertNull(state.externalIssueOf(rec.id))
        }
    }

    @Test
    fun `rebind writes buffer to new path and updates record`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val f = extFile("a.sql", "SELECT 1")
            val rec = state.openExternalFile(pid, f)
            val g = dir.resolve("b.sql")
            assertTrue(state.rebindExternalFile(rec.id, g))
            assertEquals(g.toAbsolutePath().normalize().toString(), repo.getConsole(rec.id)!!.filePath)
            assertEquals("SELECT 1", Files.readString(g))
        }
    }

    @Test
    fun `prune hides missing external console but keeps row`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val f = extFile("a.sql", "SELECT 1")
            val rec = state.openExternalFile(pid, f)
            Files.delete(f)
            state.pruneMissingExternal(listOf(pid))
            val ws = state.workspaces.activeWorkspace()!!
            assertFalse(state.workspaces.contains(ws.id, rec.id))
            assertNotNull(repo.getConsole(rec.id))
        }
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `gradle test --tests 'app.state.ConsoleStateTest' --console=plain`
Expected: FAIL（`fileWatcher` 参数、`openExternalFile`、`ExternalFileIssue` 等未定义）

- [ ] **Step 3: 实现 `ConsoleState`**

顶部加 import 与枚举：

```kotlin
import java.nio.file.Files
import java.nio.file.Path
import db.ConsoleFiles

/** 外部文件当前的问题态：冲突（磁盘变了且本地脏）/ 缺失（磁盘文件不存在）。 */
enum class ExternalFileIssue { CONFLICT, MISSING }
```

构造函数加参数：

```kotlin
class ConsoleState(
    private val repository: ConnectionsRepository,
    private val connectionsState: ConnectionsState,
    val workspaces: WorkspaceState,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val confirmDangerous: (suspend (String) -> Boolean)? = null,
    fileWatcher: FileWatcher? = null,
) {
```

新增字段与 watcher：

```kotlin
    /** 外部文件当前问题态（冲突/缺失）；无键 = 正常。 */
    val externalIssues = mutableStateMapOf<String, ExternalFileIssue>()

    /** 外部文件内容重载信号：EditorArea 据此把权威缓冲刷进 TextFieldState。 */
    private val textRevisions = mutableStateMapOf<String, Int>()

    /** 外部文件上次载入/写入的内容，用于忽略自身写入。 */
    private val lastDiskContent = mutableMapOf<String, String>()

    /** 规范化路径 → consoleId（监听反向表）。 */
    private val pathToConsole = mutableMapOf<String, String>()

    private val watcher: FileWatcher = fileWatcher ?: ExternalFileWatcher { path ->
        scope.launch { handleExternalChange(path) }
    }
```

新增私有规范化 + 公共查询：

```kotlin
    private fun normalizePath(path: Path): String =
        runCatching { path.toRealPath().toString() }
            .getOrElse { path.toAbsolutePath().normalize().toString() }

    /** 监听反向表用的键：绝对规范化（与 ExternalFileWatcher 回调路径同源）。 */
    private fun absKey(path: Path): String = path.toAbsolutePath().normalize().toString()

    private fun resolveConsoleId(path: Path): String? =
        pathToConsole[absKey(path)]
            ?: repository.getConsoleByPath(absKey(path))?.id
            ?: repository.getConsoleByPath(normalizePath(path))?.id

    fun consoleByPath(path: Path): ConsoleRecord? = repository.getConsoleByPath(normalizePath(path))

    fun externalIssueOf(consoleId: String): ExternalFileIssue? = externalIssues[consoleId]

    fun textRevisionOf(consoleId: String): Int = textRevisions[consoleId] ?: 0
```

修改 `activate`——外部控制台加载时记录磁盘内容、开始监听、判定缺失：

```kotlin
        if (console.id !in loaded) {
            val text = repository.readConsoleContent(console.id)
            buffers[console.id] = text
            loaded += console.id
            if (console.external) {
                val p = Path.of(console.filePath)
                lastDiskContent[console.id] = text
                pathToConsole[absKey(p)] = console.id
                watcher.watch(p)
                if (Files.isRegularFile(p)) externalIssues.remove(console.id)
                else externalIssues[console.id] = ExternalFileIssue.MISSING
            }
        }
```

`openExternalFile`（放在 `createConsole` 附近）：

```kotlin
    /**
     * 打开一个外部 .sql 文件为控制台（选择框 / 拖拽入口）：按规范化路径复用已有记录，
     * 未命中才新建（不覆盖文件内容）。加入当前工作区并激活、开始监听。
     */
    fun openExternalFile(profileId: String, path: Path): ConsoleRecord {
        val normalized = normalizePath(path)
        val existing = repository.getConsoleByPath(normalized)
        val rec = if (existing != null) {
            val list = profileConsoles(existing.connectionId)
            if (list.none { it.id == existing.id }) {
                consolesByConnection[existing.connectionId] = list + existing
            }
            existing
        } else {
            val created = repository.createExternalConsole(profileId, path.fileName.toString(), normalized)
            consolesByConnection[profileId] = consolesByConnection[profileId].orEmpty() + created
            created
        }
        val ws = workspaces.ensureActive()
        if (!workspaces.contains(ws.id, rec.id)) workspaces.addMember(ws.id, rec.id)
        activate(rec)
        return rec
    }
```

`handleExternalChange` + 动作：

```kotlin
    /** 监听回调（已在 UI 线程）：缺文件标记缺失；否则与上次磁盘内容比对，干净重载、脏则冲突。 */
    fun handleExternalChange(path: Path) {
        val consoleId = resolveConsoleId(path) ?: return
        val p = Path.of(findConsole(consoleId)?.filePath ?: path.toString())
        if (!Files.isRegularFile(p)) {
            externalIssues[consoleId] = ExternalFileIssue.MISSING
            return
        }
        val disk = ConsoleFiles.read(p)
        if (disk == lastDiskContent[consoleId]) return
        if (consoleId !in dirtyConsoleIds) {
            buffers[consoleId] = disk
            lastDiskContent[consoleId] = disk
            externalIssues.remove(consoleId)
            textRevisions[consoleId] = (textRevisions[consoleId] ?: 0) + 1
        } else {
            externalIssues[consoleId] = ExternalFileIssue.CONFLICT
        }
    }

    /** 冲突：载入磁盘版本（丢弃本地、清脏、取消防抖任务）。 */
    fun reloadFromDisk(consoleId: String) {
        val rec = findConsole(consoleId) ?: return
        saveJobs.remove(consoleId)?.cancel()
        val disk = ConsoleFiles.read(Path.of(rec.filePath))
        buffers[consoleId] = disk
        lastDiskContent[consoleId] = disk
        dirtyConsoleIds = dirtyConsoleIds - consoleId
        externalIssues.remove(consoleId)
        textRevisions[consoleId] = (textRevisions[consoleId] ?: 0) + 1
    }

    /** 冲突：保留我的（保持脏，下次落盘覆盖磁盘）。 */
    fun keepLocal(consoleId: String) {
        externalIssues.remove(consoleId)
    }

    /** 缺失：按原路径写回缓冲；父目录不存在返回 false。 */
    fun recreateExternalFile(consoleId: String): Boolean {
        val rec = findConsole(consoleId) ?: return false
        val p = Path.of(rec.filePath)
        if (p.parent == null || !Files.isDirectory(p.parent)) return false
        val text = buffers[consoleId] ?: ""
        return runCatching {
            ConsoleFiles.write(p, text)
            lastDiskContent[consoleId] = text
            dirtyConsoleIds = dirtyConsoleIds - consoleId
            externalIssues.remove(consoleId)
        }.isSuccess
    }

    /** 「另存为…」：写缓冲到新路径并重绑控制台；新路径被占用返回 false。 */
    fun rebindExternalFile(consoleId: String, newPath: Path): Boolean {
        val rec = findConsole(consoleId) ?: return false
        val normalized = normalizePath(newPath)
        if (repository.getConsoleByPath(normalized)?.id?.let { it != consoleId } == true) return false
        val text = buffers[consoleId] ?: ""
        val ok = runCatching { ConsoleFiles.write(Path.of(normalized), text) }.isSuccess
        if (!ok) return false
        repository.rebindConsoleFile(consoleId, normalized)
        val oldPath = Path.of(rec.filePath)
        watcher.unwatch(oldPath)
        pathToConsole.remove(absKey(oldPath))
        pathToConsole[absKey(Path.of(normalized))] = consoleId
        watcher.watch(Path.of(normalized))
        lastDiskContent[consoleId] = text
        dirtyConsoleIds = dirtyConsoleIds - consoleId
        externalIssues.remove(consoleId)
        val entry = consolesByConnection.entries.firstOrNull { (_, l) -> l.any { it.id == consoleId } }
        if (entry != null) {
            consolesByConnection[entry.key] = entry.value.map {
                if (it.id == consoleId) it.copy(filePath = normalized) else it
            }
        }
        return true
    }

    /** 启动：文件已丢失的外部控制台从所有工作区移除成员（等价关闭），保留行与关联。 */
    fun pruneMissingExternal(profileIds: List<String>) {
        allConsoles(profileIds).filter { it.external && !Files.isRegularFile(Path.of(it.filePath)) }
            .forEach { workspaces.removeConsoleEverywhere(it.id) }
    }

    /** 退出保护：external + missing + 脏的控制台。 */
    fun missingDirtyExternal(profileIds: List<String>): List<ConsoleRecord> =
        allConsoles(profileIds).filter {
            it.external && it.id in dirtyConsoleIds && externalIssues[it.id] == ExternalFileIssue.MISSING
        }

    fun closeExternalWatcher() {
        watcher.close()
    }

    /** 放弃某控制台的未保存改动（退出时选择「放弃」用）。 */
    fun discardDirty(consoleId: String) {
        saveJobs.remove(consoleId)?.cancel()
        dirtyConsoleIds = dirtyConsoleIds - consoleId
        externalIssues.remove(consoleId)
    }
```

修改 `flushNow`——缺文件跳过：

```kotlin
    fun flushNow(consoleId: String) {
        if (consoleId !in dirtyConsoleIds) return
        val text = buffers[consoleId] ?: return
        val rec = findConsole(consoleId)
        if (rec?.external == true && externalIssues[consoleId] == ExternalFileIssue.MISSING) return
        runCatching { repository.writeConsoleContent(consoleId, text) }
            .onSuccess { if (rec?.external == true) lastDiskContent[consoleId] = text }
            .onFailure { Logger.error(it, "console autosave failed {}", consoleId) }
        dirtyConsoleIds = dirtyConsoleIds - consoleId
    }
```

`deleteConsole` 清理（在现有清理块末尾追加）：

```kotlin
        if (rec.external) {
            watcher.unwatch(Path.of(rec.filePath))
            pathToConsole.remove(absKey(Path.of(rec.filePath)))
            externalIssues.remove(consoleId)
            lastDiskContent.remove(consoleId)
            textRevisions.remove(consoleId)
        }
```

`onConnectionDeleted` 循环内每个 `rec` 追加同样清理：

```kotlin
            if (rec.external) {
                watcher.unwatch(Path.of(rec.filePath))
                pathToConsole.remove(absKey(Path.of(rec.filePath)))
                externalIssues.remove(rec.id)
                lastDiskContent.remove(rec.id)
                textRevisions.remove(rec.id)
            }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `gradle test --tests 'app.state.ConsoleStateTest' --console=plain`
Expected: PASS（既有用例也不回归）

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/app/state/ConsoleState.kt src/test/kotlin/app/state/ConsoleStateTest.kt
git commit -m "feat(state): ConsoleState 外部文件机制（监听接线 + 冲突/缺失/重载 + prune）"
```

---

### Task 5: i18n key + `DialogState` 请求类型

**Files:**
- Modify: `src/main/kotlin/i18n/Str.kt`
- Modify: `src/main/kotlin/i18n/CatalogZh.kt`
- Modify: `src/main/kotlin/i18n/CatalogEn.kt`
- Modify: `src/main/kotlin/app/state/DialogState.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - i18n key 常量（供 Task 6/7/8）
  - `DialogState.externalMissingExit: ExternalMissingExitRequest?`
  - `DialogState.pickProfile: PickProfileRequest?`
  - `data class PickProfileRequest(names: List<String>, profiles: List<ConnectionProfile>, defaultProfileId: String?, onSubmit: (String) -> Unit)`
  - `data class ExternalMissingExitRequest(consoles: List<ConsoleRecord>, onRebuildAll: () -> Unit, onSaveAsEach: () -> Unit, onDiscard: () -> Unit, onCancel: () -> Unit)`

- [ ] **Step 1: 加 key（Str.kt）**

在「工作台：空态 / 工具栏 / 标题栏」段之后追加：

```kotlin
    // ── 外部 SQL 文件控制台 ──
    ExternalOpenSqlFile,
    ExternalPickSourceTitle,
    ExternalPickSourceHint,
    ExternalPickSourceConfirm,
    ExternalSaveAsTitle,
    ExternalLinkTip,
    ExternalMissingTip,
    ExternalConflictTip,
    ExternalConflictBanner,
    ExternalConflictReload,
    ExternalConflictKeep,
    ExternalMissingBanner,
    ExternalRecreateFile,
    ExternalSaveAs,
    ExternalCopyPath,
    ExternalReloadDisk,
    ExternalReveal,
    ExternalExitTitle,
    ExternalExitMessage,
    ExternalExitRebuildAll,
    ExternalExitSaveAsEach,
    ExternalExitDiscard,
    ExternalExitCancel,
    ExternalOpenedToast,
    ExternalIgnoredNonSql,
    ExternalRecreatedToast,
    ExternalSavedAsToast,
    ExternalRebindConflict,
    ExternalSaveFailed,
    ExternalDeleteNote,
    ExternalLoadFailed,
```

- [ ] **Step 2: 补 zh catalog**

在 `CatalogZh.kt` 对应位置追加（占位符 `{0}`/`{1}` 必须与 en 一致）：

```kotlin
    Str.ExternalOpenSqlFile -> "打开 SQL 文件…"
    Str.ExternalPickSourceTitle -> "选择数据源"
    Str.ExternalPickSourceHint -> "以下外部文件尚未关联数据源，请选择一个："
    Str.ExternalPickSourceConfirm -> "打开"
    Str.ExternalSaveAsTitle -> "另存为 SQL 文件"
    Str.ExternalLinkTip -> "外部文件：{0}"
    Str.ExternalMissingTip -> "文件已丢失"
    Str.ExternalConflictTip -> "磁盘文件已被外部修改"
    Str.ExternalConflictBanner -> "磁盘文件已被外部修改"
    Str.ExternalConflictReload -> "载入磁盘版本"
    Str.ExternalConflictKeep -> "保留我的"
    Str.ExternalMissingBanner -> "文件已丢失：{0}"
    Str.ExternalRecreateFile -> "重建文件"
    Str.ExternalSaveAs -> "另存为…"
    Str.ExternalCopyPath -> "复制文件路径"
    Str.ExternalReloadDisk -> "重新载入磁盘版本"
    Str.ExternalReveal -> "在文件管理器中显示"
    Str.ExternalExitTitle -> "外部文件不可写"
    Str.ExternalExitMessage -> "以下 {0} 个外部文件已丢失且含有未保存内容："
    Str.ExternalExitRebuildAll -> "重建全部"
    Str.ExternalExitSaveAsEach -> "逐个另存为…"
    Str.ExternalExitDiscard -> "放弃这些内容并退出"
    Str.ExternalExitCancel -> "取消退出"
    Str.ExternalOpenedToast -> "已打开外部文件：{0}"
    Str.ExternalIgnoredNonSql -> "已忽略非 .sql 文件：{0}"
    Str.ExternalRecreatedToast -> "已按原路径重建：{0}"
    Str.ExternalSavedAsToast -> "已另存为：{0}"
    Str.ExternalRebindConflict -> "目标文件已被另一个控制台使用：{0}"
    Str.ExternalSaveFailed -> "保存外部文件失败：{0}"
    Str.ExternalDeleteNote -> "仅移除控制台，不删除文件"
    Str.ExternalLoadFailed -> "读取外部文件失败，已按空内容打开：{0}"
```

- [ ] **Step 3: 补 en catalog**

在 `CatalogEn.kt` 对应位置追加：

```kotlin
    Str.ExternalOpenSqlFile -> "Open SQL File…"
    Str.ExternalPickSourceTitle -> "Choose a data source"
    Str.ExternalPickSourceHint -> "The following external files are not linked to a data source yet. Pick one:"
    Str.ExternalPickSourceConfirm -> "Open"
    Str.ExternalSaveAsTitle -> "Save As SQL File"
    Str.ExternalLinkTip -> "External file: {0}"
    Str.ExternalMissingTip -> "File missing"
    Str.ExternalConflictTip -> "File was modified externally"
    Str.ExternalConflictBanner -> "The file was modified externally"
    Str.ExternalConflictReload -> "Load disk version"
    Str.ExternalConflictKeep -> "Keep mine"
    Str.ExternalMissingBanner -> "File missing: {0}"
    Str.ExternalRecreateFile -> "Recreate file"
    Str.ExternalSaveAs -> "Save as…"
    Str.ExternalCopyPath -> "Copy file path"
    Str.ExternalReloadDisk -> "Reload from disk"
    Str.ExternalReveal -> "Show in file manager"
    Str.ExternalExitTitle -> "External files unavailable"
    Str.ExternalExitMessage -> "These {0} external files are missing and have unsaved changes:"
    Str.ExternalExitRebuildAll -> "Recreate all"
    Str.ExternalExitSaveAsEach -> "Save each as…"
    Str.ExternalExitDiscard -> "Discard and exit"
    Str.ExternalExitCancel -> "Cancel"
    Str.ExternalOpenedToast -> "Opened external file: {0}"
    Str.ExternalIgnoredNonSql -> "Ignored non-.sql file: {0}"
    Str.ExternalRecreatedToast -> "Recreated: {0}"
    Str.ExternalSavedAsToast -> "Saved as: {0}"
    Str.ExternalRebindConflict -> "Target file is already used by another console: {0}"
    Str.ExternalSaveFailed -> "Failed to save external file: {0}"
    Str.ExternalDeleteNote -> "Removes the console only; the file is kept"
    Str.ExternalLoadFailed -> "Failed to read external file; opened empty: {0}"
```

- [ ] **Step 4: `DialogState.kt` 加请求类型与字段**

加 import：`import db.ConsoleRecord`。在 `ExportRequest` 之后加：

```kotlin
/** 批量打开外部文件时，为未关联数据源的文件选择数据源。 */
data class PickProfileRequest(
    val names: List<String>,
    val profiles: List<ConnectionProfile>,
    val defaultProfileId: String?,
    val onSubmit: (String) -> Unit,
)

/** 退出时外部文件「已丢失且脏」的处理选择。 */
data class ExternalMissingExitRequest(
    val consoles: List<ConsoleRecord>,
    val onRebuildAll: () -> Unit,
    val onSaveAsEach: () -> Unit,
    val onDiscard: () -> Unit,
    val onCancel: () -> Unit,
)
```

`DialogState` 类内加字段：

```kotlin
    /** 外部文件数据源选择框。 */
    var pickProfile by mutableStateOf<PickProfileRequest?>(null)
    /** 退出时外部文件缺失处理框。 */
    var externalMissingExit by mutableStateOf<ExternalMissingExitRequest?>(null)
```

- [ ] **Step 5: 运行 i18n 测试确认通过**

Run: `gradle test --tests 'i18n.I18nCatalogTest' --console=plain`
Expected: PASS（若 zh/en 有遗漏或占位符不一致则 FAIL）

- [ ] **Step 6: 提交**

```bash
git add src/main/kotlin/i18n/Str.kt src/main/kotlin/i18n/CatalogZh.kt \
  src/main/kotlin/i18n/CatalogEn.kt src/main/kotlin/app/state/DialogState.kt
git commit -m "feat(i18n): 外部文件控制台文案（zh+en）+ DialogState 请求类型"
```

---

### Task 6: `EditorArea`——revision 同步 + 冲突/缺失横幅

**Files:**
- Modify: `src/main/kotlin/app/ui/EditorArea.kt`
- Modify: `src/main/kotlin/app/core/Main.kt`（传入新参数，最小接线）

**Interfaces:**
- Consumes: Task 4 `textRevisionOf` / `externalIssueOf`；Task 5 i18n key
- Produces: `EditorArea(...)` 新参数：
  - `textRevision: Int`
  - `externalIssue: ExternalFileIssue?`
  - `externalPath: String?`
  - `onReloadFromDisk: () -> Unit`
  - `onKeepLocal: () -> Unit`
  - `onRecreateExternal: () -> Unit`
  - `onSaveAsExternal: () -> Unit`
  - `onCopyExternalPath: () -> Unit`

- [ ] **Step 1: 加参数与 revision 同步**

`EditorArea` 形参列表末尾追加：

```kotlin
    /** 外部文件被重载的信号（变化时把权威缓冲刷进 TextFieldState）。 */
    textRevision: Int = 0,
    /** 当前控制台的外部文件问题态（冲突/缺失）。 */
    externalIssue: ExternalFileIssue? = null,
    /** 当前控制台外部文件路径（横幅显示用；非外部为 null）。 */
    externalPath: String? = null,
    onReloadFromDisk: () -> Unit = {},
    onKeepLocal: () -> Unit = {},
    onRecreateExternal: () -> Unit = {},
    onSaveAsExternal: () -> Unit = {},
    onCopyExternalPath: () -> Unit = {},
```

在 `val state = editorStates.getOrPut(consoleId) { ... }` 之后加：

```kotlin
        // 外部重载 / 隐藏后重开：把 ConsoleState 的权威缓冲刷进本控制台的 TextFieldState。
        // 正常输入时两者相等，不触发；只在确有差异时写入，避免与输入会话打架。
        LaunchedEffect(consoleId, textRevision) {
            val authoritative = editorText
            if (state.text.toString() != authoritative) {
                val caret = state.selection.start.coerceIn(0, authoritative.length)
                state.edit {
                    replace(0, length, authoritative)
                    selection = TextRange(caret)
                }
            }
        }
```

- [ ] **Step 2: 加横幅 composable**

在文件内新增（放在 `EditorArea` 之后或合适位置）：

```kotlin
/** 外部文件问题横幅（非阻塞）：冲突给「载入磁盘版本 / 保留我的」；缺失给重建/另存/关闭。 */
@Composable
private fun ExternalFileBanner(
    issue: ExternalFileIssue,
    path: String?,
    onReloadFromDisk: () -> Unit,
    onKeepLocal: () -> Unit,
    onRecreate: () -> Unit,
    onSaveAs: () -> Unit,
    onCopyPath: () -> Unit,
) {
    val warn = Color(0xFFFFB300) // 语义色：警告
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(warn.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            when (issue) {
                ExternalFileIssue.CONFLICT -> t(Str.ExternalConflictBanner)
                ExternalFileIssue.MISSING -> t(Str.ExternalMissingBanner, path.orEmpty())
            },
            fontSize = 12.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        when (issue) {
            ExternalFileIssue.CONFLICT -> {
                BannerAction(t(Str.ExternalConflictReload), onReloadFromDisk)
                BannerAction(t(Str.ExternalConflictKeep), onKeepLocal)
            }
            ExternalFileIssue.MISSING -> {
                BannerAction(t(Str.ExternalRecreateFile), onRecreate)
                BannerAction(t(Str.ExternalSaveAs), onSaveAs)
                BannerAction(t(Str.ExternalCopyPath), onCopyPath)
            }
        }
    }
}

@Composable
private fun BannerAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 12.sp,
        color = MaterialTheme.colors.primary,
        modifier = Modifier
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
```

新增 import：`app.state.ExternalFileIssue`、`androidx.compose.foundation.clickable`、`androidx.compose.foundation.shape.RoundedCornerShape`、`androidx.compose.foundation.layout.Spacer`（若缺）、`androidx.compose.ui.draw.clip`、`androidx.compose.ui.graphics.Color`。

- [ ] **Step 3: 在编辑器上方渲染横幅**

在激活控制台内容区、`key(consoleId) { EditorPane(...) }` 之前插入：

```kotlin
                    if (externalIssue != null) {
                        ExternalFileBanner(
                            issue = externalIssue,
                            path = externalPath,
                            onReloadFromDisk = onReloadFromDisk,
                            onKeepLocal = onKeepLocal,
                            onRecreate = onRecreateExternal,
                            onSaveAs = onSaveAsExternal,
                            onCopyPath = onCopyExternalPath,
                        )
                    }
```

- [ ] **Step 4: 最小接线（Main）**

在 `EditorArea(...)` 调用里、`editorText = ...` 附近加：

```kotlin
                        textRevision = activeConsole?.let { consoleState.textRevisionOf(it.id) } ?: 0,
                        externalIssue = activeConsole?.let { consoleState.externalIssueOf(it.id) },
                        externalPath = activeConsole?.takeIf { it.external }?.filePath,
                        onReloadFromDisk = { activeConsole?.let { consoleState.reloadFromDisk(it.id) } },
                        onKeepLocal = { activeConsole?.let { consoleState.keepLocal(it.id) } },
                        onRecreateExternal = {
                            activeConsole?.let {
                                val ok = consoleState.recreateExternalFile(it.id)
                                toastState.show(
                                    if (ok) I18n.t(Str.ExternalRecreatedToast, it.filePath)
                                    else I18n.t(Str.ExternalSaveFailed, it.filePath),
                                )
                            }
                        },
                        onSaveAsExternal = { activeConsole?.let { c -> saveExternalAs(c) } },
                        onCopyExternalPath = { activeConsole?.let { writeClipboardText(it.filePath) } },
```

（本步骤只接可独立编译的参数；`onSaveAsExternal` / `onCopyExternalPath` 留默认值，在 Task 8 接线。）

修正后的 Step 4 片段（只接可独立编译的项）：

```kotlin
                        textRevision = activeConsole?.let { consoleState.textRevisionOf(it.id) } ?: 0,
                        externalIssue = activeConsole?.let { consoleState.externalIssueOf(it.id) },
                        externalPath = activeConsole?.takeIf { it.external }?.filePath,
                        onReloadFromDisk = { activeConsole?.let { consoleState.reloadFromDisk(it.id) } },
                        onKeepLocal = { activeConsole?.let { consoleState.keepLocal(it.id) } },
                        onRecreateExternal = {
                            activeConsole?.let {
                                val ok = consoleState.recreateExternalFile(it.id)
                                toastState.show(
                                    if (ok) I18n.t(Str.ExternalRecreatedToast, it.filePath)
                                    else I18n.t(Str.ExternalSaveFailed, it.filePath),
                                )
                            }
                        },
```

- [ ] **Step 5: 编译与测试**

Run: `gradle compileKotlin test --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
git add src/main/kotlin/app/ui/EditorArea.kt src/main/kotlin/app/core/Main.kt
git commit -m "feat(ui): 编辑器外部文件 revision 同步 + 冲突/缺失横幅"
```

---

### Task 7: 标签条标识、右键菜单与「打开 SQL 文件…」入口

**Files:**
- Modify: `src/main/kotlin/app/ui/DbIcons.kt`（新增 `Link` 图标）
- Modify: `src/main/kotlin/app/ui/ConsoleTabBar.kt`
- Modify: `src/main/kotlin/app/core/Main.kt`（传入新回调）

**Interfaces:**
- Consumes: Task 5 i18n key；Task 4 `externalIssueOf`
- Produces: `ConsoleTabBar(...)` 新参数：
  - `externalIssues: Map<String, ExternalFileIssue>`
  - `onOpenSqlFile: () -> Unit`
  - `onCopyFilePath: (ConsoleRecord) -> Unit`
  - `onRevealFile: (ConsoleRecord) -> Unit`
  - `onReloadFromDisk: (ConsoleRecord) -> Unit`

- [ ] **Step 1: 加图标**

`DbIcons.kt` 内加：

```kotlin
    /** 链接/外部文件图标（控制台标签区分外部文件）。 */
    val Link: ImageVector by lazy {
        vector(
            "link",
            "M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1s-1.39 3.1-3.1 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z",
        )
    }
```

- [ ] **Step 2: 扩展 `ConsoleTabBar` 形参与「+」菜单**

形参加：

```kotlin
    externalIssues: Map<String, ExternalFileIssue> = emptyMap(),
    onOpenSqlFile: () -> Unit = {},
    onCopyFilePath: (ConsoleRecord) -> Unit = {},
    onRevealFile: (ConsoleRecord) -> Unit = {},
    onReloadFromDisk: (ConsoleRecord) -> Unit = {},
```

import：`import app.state.ExternalFileIssue`、`import app.ui.DbIcons`（同包可省）。

「+」下拉在数据源列表之前插入一条 + 分隔：

```kotlin
            DropdownMenu(expanded = createMenuOpen, onDismissRequest = { createMenuOpen = false }) {
                DropdownMenuItem(onClick = { createMenuOpen = false; onOpenSqlFile() }) {
                    Text(t(Str.ExternalOpenSqlFile), fontSize = 13.sp, color = MaterialTheme.colors.onSurface)
                }
                Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.1f))
                profilesById.values.forEach { p -> /* 既有内容不变 */ }
            }
```

（`Divider` 已在 material 包；如未 import 需补 `import androidx.compose.material.Divider`。）

`ConsoleChip` 调用处传 `issue`：

```kotlin
                ConsoleChip(
                    console = c,
                    profile = profilesById[c.connectionId],
                    showSourceTag = multiSource,
                    active = c.id == activeConsole?.id,
                    dirty = c.id in dirtyConsoleIds,
                    pendingEdits = pendingEditCounts[c.id] ?: 0,
                    issue = externalIssues[c.id],
                    onSelect = { onSelectConsole(c) },
                    onRename = { onRenameConsole(c) },
                    onDelete = { onDeleteConsole(c) },
                    onClose = { onCloseConsole(c) },
                    onCopyFilePath = { onCopyFilePath(c) },
                    onRevealFile = { onRevealFile(c) },
                    onReloadFromDisk = { onReloadFromDisk(c) },
                )
```

- [ ] **Step 3: 改 `ConsoleChip`**

形参加 `issue: ExternalFileIssue?` 与三个回调。在徽章/脏点之后插入链接图标与告警：

```kotlin
            if (console.external) {
                Icon(
                    DbIcons.Link,
                    contentDescription = t(Str.ExternalLinkTip, console.filePath),
                    tint = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.size(12.dp).padding(end = 3.dp),
                )
            }
            if (issue == ExternalFileIssue.MISSING) {
                Text("!", color = Color(0xFFFFB300), fontSize = 10.sp, modifier = Modifier.padding(end = 3.dp))
            } else if (issue == ExternalFileIssue.CONFLICT) {
                Text("◆", color = Color(0xFFFFB300), fontSize = 8.sp, modifier = Modifier.padding(end = 3.dp))
            }
```

`menu` 列表按 console 组合：

```kotlin
    val menu = buildList {
        add(ContextMenuItem(t(Str.ConsoleRenameTitle)) { onRename() })
        if (console.external) {
            add(ContextMenuItem(t(Str.ExternalCopyPath)) { onCopyFilePath() })
            add(ContextMenuItem(t(Str.ExternalReveal)) { onRevealFile() })
            add(ContextMenuItem(t(Str.ExternalReloadDisk)) { onReloadFromDisk() })
        }
        add(ContextMenuItem(t(Str.EditorCloseConsole)) { onClose() })
        add(ContextMenuItem(t(Str.ConfirmDeleteConsoleTitle)) { onDelete() })
    }
```

- [ ] **Step 4: Main 接线**

`EditorArea(...)` 调用里加：

```kotlin
                        externalIssues = consoleState.externalIssues,
                        onOpenSqlFile = { openSqlFileDialog() },
                        onCopyFilePath = { c -> writeClipboardText(c.filePath) },
                        onRevealFile = { c ->
                            val f = java.io.File(c.filePath)
                            if (!app.core.openDirectory(f.parentFile?.toPath() ?: f.toPath())) {
                                toastState.show(I18n.t(Str.ExternalCopyPath) + ": " + c.filePath)
                            }
                        },
                        onReloadFromDisk = { c -> consoleState.reloadFromDisk(c.id) },
```

`openSqlFileDialog` 在 Task 8 定义；本步骤先在 Main 中加一个最小可用定义（Task 8 会扩展成完整流程）：

```kotlin
    fun openSqlFileDialog() {
        val fd = FileDialog(null as java.awt.Frame?, I18n.t(Str.ExternalOpenSqlFile), FileDialog.LOAD)
        fd.file = "*.sql"
        fd.isVisible = true
        val name = fd.file ?: return
        openSqlFiles(listOf(File(fd.directory, name)))
    }
```

**本任务不做 Main 接线**：`ConsoleTabBar` 新参数均有默认值，未接线也能编译；Main 侧接线统一在 Task 8 完成。

修正：本任务 **Step 4 不执行**；`ConsoleTabBar` 新参数由 Task 8 的 Main 接线提供默认值即可编译（所有新参数已有默认值）。Task 7 提交只含图标 + ConsoleTabBar。

- [ ] **Step 5: 编译**

Run: `gradle compileKotlin --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
git add src/main/kotlin/app/ui/DbIcons.kt src/main/kotlin/app/ui/ConsoleTabBar.kt
git commit -m "feat(ui): 标签条外部文件标识与右键菜单（复制路径/显示/重载）"
```

---

### Task 8: Main 编排——打开入口、拖拽、数据源选择、退出保护、启动 prune

**Files:**
- Create: `src/main/kotlin/app/dialog/ExternalDialogs.kt`
- Modify: `src/main/kotlin/app/core/Main.kt`
- Modify: `src/main/kotlin/app/ui/ConsoleTabBar.kt`（由 Task 7 已加参数，本任务只需 Main 传入）

**Interfaces:**
- Consumes: Task 4 `openExternalFile`/`pruneMissingExternal`/`missingDirtyExternal`/`rebindExternalFile`；Task 5 `PickProfileRequest`/`ExternalMissingExitRequest`/i18n；Task 7 `ConsoleTabBar` 参数
- Produces: 可用的端到端入口与退出保护

- [ ] **Step 1: 新增对话框 composable**

`src/main/kotlin/app/dialog/ExternalDialogs.kt`：

```kotlin
package app.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.i18n.t
import app.state.ExternalMissingExitRequest
import app.state.PickProfileRequest
import db.ConnectionProfile
import i18n.Str
import tree.TypeBadge

/** 批量打开外部文件时选择数据源。 */
@Composable
fun PickProfileDialog(request: PickProfileRequest, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(request.defaultProfileId ?: request.profiles.firstOrNull()?.id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(Str.ExternalPickSourceTitle)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    t(Str.ExternalPickSourceHint),
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                )
                Text(
                    request.names.joinToString("，"),
                    fontSize = 11.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
                request.profiles.forEach { p ->
                    ProfileRow(p, p.id == selected) { selected = p.id }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != null,
                onClick = { selected?.let(request.onSubmit) },
            ) { Text(t(Str.ExternalPickSourceConfirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t(Str.CommonCancel)) }
        },
    )
}

@Composable
private fun ProfileRow(profile: ConnectionProfile, active: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) MaterialTheme.colors.primary.copy(alpha = 0.15f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        TypeBadge(profile.dbType)
        Text(
            profile.name,
            fontSize = 13.sp,
            color = MaterialTheme.colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** 退出时处理「外部文件已丢失且脏」。 */
@Composable
fun ExternalMissingExitDialog(request: ExternalMissingExitRequest, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(Str.ExternalExitTitle)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    t(Str.ExternalExitMessage, request.consoles.size),
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.8f),
                )
                request.consoles.forEach {
                    Text(
                        "• ${it.filePath}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.End) {
                TextButton(onClick = request.onCancel) { Text(t(Str.ExternalExitCancel)) }
                TextButton(onClick = request.onDiscard) { Text(t(Str.ExternalExitDiscard)) }
                TextButton(onClick = request.onSaveAsEach) { Text(t(Str.ExternalExitSaveAsEach)) }
                TextButton(onClick = request.onRebuildAll) { Text(t(Str.ExternalExitRebuildAll)) }
            }
        },
    )
}
```

（对话框用仓库既有形式 `AlertDialog`（见 `TextInputDialogs.kt`），无需额外基座；`Str.CommonCancel` 已存在。）

- [ ] **Step 2: Main 打开流程**

在 `AppBody` 内加：

```kotlin
    /** 打开一批外部 SQL 文件：有记忆直接用，未解析的合并成一次数据源选择。 */
    fun openSqlFiles(files: List<File>) {
        val sqlFiles = files.filter { it.extension.equals("sql", ignoreCase = true) }
        files.filter { !it.extension.equals("sql", ignoreCase = true) }
            .forEach { toastState.show(I18n.t(Str.ExternalIgnoredNonSql, it.name)) }
        if (sqlFiles.isEmpty()) return
        val resolved = mutableListOf<Pair<File, String>>()
        val unresolved = mutableListOf<File>()
        sqlFiles.forEach { f ->
            val existing = consoleState.consoleByPath(f.toPath())
            if (existing != null) resolved += f to existing.connectionId else unresolved += f
        }
        fun openAll(unresolvedProfileId: String?) {
            resolved.forEach { (f, pid) -> consoleState.openExternalFile(pid, f.toPath()) }
            unresolved.forEach { f ->
                val pid = unresolvedProfileId ?: activeProfile?.id ?: return@forEach
                consoleState.openExternalFile(pid, f.toPath())
            }
            val opened = resolved.map { it.first.name } + unresolved.map { it.name }
            opened.forEach { toastState.show(I18n.t(Str.ExternalOpenedToast, it)) }
        }
        if (unresolved.isEmpty()) {
            openAll(null)
        } else {
            dialogState.pickProfile = PickProfileRequest(
                names = unresolved.map { it.name },
                profiles = profiles,
                defaultProfileId = activeProfile?.id,
            ) { pid ->
                dialogState.pickProfile = null
                openAll(pid)
            }
        }
    }

    fun openSqlFileDialog() {
        val fd = FileDialog(null as java.awt.Frame?, I18n.t(Str.ExternalOpenSqlFile), FileDialog.LOAD)
        fd.file = "*.sql"
        fd.isVisible = true
        val name = fd.file ?: return
        openSqlFiles(listOf(File(fd.directory, name)))
    }

    /** 另存为：把控制台缓冲写到新路径并重绑。 */
    fun saveExternalAs(c: ConsoleRecord) {
        val fd = FileDialog(null as java.awt.Frame?, I18n.t(Str.ExternalSaveAsTitle), FileDialog.SAVE)
        fd.file = File(c.filePath).name
        fd.isVisible = true
        val name = fd.file ?: return
        val target = File(fd.directory, name).toPath()
        if (consoleState.rebindExternalFile(c.id, target)) {
            toastState.show(I18n.t(Str.ExternalSavedAsToast, target.toString()))
        } else {
            toastState.show(I18n.t(Str.ExternalRebindConflict, target.toString()))
        }
    }
```

- [ ] **Step 3: 拖拽**

在 `AppBody` 内、Box 之前：

```kotlin
    val openSqlFilesRef = rememberUpdatedState<(List<File>) -> Unit> { openSqlFiles(it) }
    val fileDropTarget = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val list = event.dragData() as? DragData.FilesList ?: return false
                val files = list.readFiles().map { File(it) }
                if (files.isEmpty()) return false
                openSqlFilesRef.value(files)
                return true
            }
        }
    }
```

Box 的 modifier 链加：

```kotlin
                    .dragAndDropTarget(
                        shouldStartDragAndDrop = { it.dragData() is DragData.FilesList },
                        target = fileDropTarget,
                    )
```

import：

```kotlin
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.runtime.rememberUpdatedState
```

- [ ] **Step 4: 退出保护**

`requestClose` 改为先处理外部缺失：

```kotlin
    // 退出流程：先处理「外部文件缺失且脏」，再走原有未提交结果修改确认。
    val performExit: () -> Unit = { /* 既有实现不变 */ }
    val requestClose: () -> Unit = {
        val pidList = profiles.map { it.id }
        val missing = consoleState.missingDirtyExternal(pidList)
        fun proceedResultEdits() {
            val pending = consoleState.totalEditCount()
            if (pending > 0) {
                dialogState.confirm = ConfirmRequest.DiscardResultEdits(pending, Str.ActionExitApp) { performExit() }
            } else {
                performExit()
            }
        }
        fun proceedExit() {
            val next = consoleState.missingDirtyExternal(pidList)
            if (next.isEmpty()) {
                dialogState.externalMissingExit = null
                proceedResultEdits()
            } else {
                dialogState.externalMissingExit = ExternalMissingExitRequest(
                    consoles = next,
                    onRebuildAll = {
                        next.forEach { consoleState.recreateExternalFile(it.id) }
                        dialogState.externalMissingExit = null
                        proceedExit()
                    },
                    onSaveAsEach = {
                        // 逐个另存为：写盘后重绑；取消任一文件则中止退出
                        var allHandled = true
                        next.forEach { c ->
                            val fd = FileDialog(null as java.awt.Frame?, I18n.t(Str.ExternalSaveAsTitle), FileDialog.SAVE)
                            fd.file = File(c.filePath).name
                            fd.isVisible = true
                            val name = fd.file ?: run { allHandled = false; return@forEach }
                            if (!consoleState.rebindExternalFile(c.id, File(fd.directory, name).toPath())) allHandled = false
                        }
                        if (allHandled) {
                            dialogState.externalMissingExit = null
                            proceedExit()
                        }
                    },
                    onDiscard = {
                        next.forEach { consoleState.keepLocal(it.id) }
                        dialogState.externalMissingExit = null
                        dialogState.confirm = ConfirmRequest.DiscardResultEdits(next.size, Str.ActionExitApp) { performExit() }
                    },
                    onCancel = { dialogState.externalMissingExit = null },
                )
            }
        }
        if (missing.isEmpty()) proceedResultEdits() else proceedExit()
    }
```

说明：`onDiscard` 里清掉 missing 标记后仍需 `flushAllSync` 才不会写盘吗？——`performExit` 内的 `flushAllSync` 会尝试 flush；为「放弃」语义，先 `keepLocal` 清 missing，再直接 `performExit()`（此时 dirty 仍在，flush 会把本地内容写到原路径——这与「放弃」矛盾）。因此 `onDiscard` 必须先把这些控制台从 `dirtyConsoleIds` 移出。在 Task 4 增加 `fun discardDirty(consoleId: String)`：

```kotlin
    /** 放弃某控制台的未保存改动（退出时选择「放弃」用）。 */
    fun discardDirty(consoleId: String) {
        saveJobs.remove(consoleId)?.cancel()
        dirtyConsoleIds = dirtyConsoleIds - consoleId
        externalIssues.remove(consoleId)
    }
```

`onDiscard` 改为：

```kotlin
                    onDiscard = {
                        next.forEach { consoleState.discardDirty(it.id) }
                        dialogState.externalMissingExit = null
                        performExit()
                    },
```

（`discardDirty` 归入 Task 4 的 Produces 列表与提交。）

- [ ] **Step 5: 渲染对话框 + 启动 prune + dispose**

在 `AppBody` 渲染区（其他 `dialogState.xxx?.let {}` 附近）加：

```kotlin
    dialogState.pickProfile?.let { request ->
        app.dialog.PickProfileDialog(request) { dialogState.pickProfile = null }
    }
    dialogState.externalMissingExit?.let { request ->
        app.dialog.ExternalMissingExitDialog(request) { request.onCancel() }
    }
```

`AppRoot` 的 `DisposableEffect` onDispose 里，`consoleState.flushAllSync()` 之后加：

```kotlin
            consoleState.closeExternalWatcher()
```

启动 prune：在 `AppRoot` 加载工作区并激活最近控制台之后加一次。`consoleState.activateMostRecent(...)` 调用点在 `AppRoot`（搜索现有调用），其后：

```kotlin
        consoleState.pruneMissingExternal(repository.listConnections().map { it.id })
```

（若 `listConnections()` 命名不同，用仓库现有的列出连接方法；执行时以实际方法名为准。）

- [ ] **Step 6: ConsoleTabBar 新参数接线**

在 `EditorArea(...)` 调用的 `ConsoleTabBar` 相关参数中（`ConsoleTabBar` 由 `EditorArea` 内部调用，因此把参数从 `EditorArea` 透传）。Task 7 已在 `ConsoleTabBar` 加默认值参数；此处需要 `EditorArea` 也加同名透传参数并在 `Main` 传入。补齐：

`EditorArea` 形参加：

```kotlin
    externalIssues: Map<String, ExternalFileIssue> = emptyMap(),
    onOpenSqlFile: () -> Unit = {},
    onCopyFilePath: (ConsoleRecord) -> Unit = {},
    onRevealFile: (ConsoleRecord) -> Unit = {},
    onReloadFromDisk: (ConsoleRecord) -> Unit = {},
```

`EditorArea` 内 `ConsoleTabBar(...)` 调用透传这 5 个参数。

`Main` 的 `EditorArea(...)` 调用加：

```kotlin
                        externalIssues = consoleState.externalIssues,
                        onOpenSqlFile = { openSqlFileDialog() },
                        onCopyFilePath = { c -> writeClipboardText(c.filePath) },
                        onRevealFile = { c ->
                            val f = File(c.filePath)
                            val dir = f.parentFile?.toPath()
                            if (dir == null || !app.core.openDirectory(dir)) {
                                toastState.show(I18n.t(Str.ExternalCopyPath) + ": " + c.filePath)
                            }
                        },
                        onReloadFromDisk = { c -> consoleState.reloadFromDisk(c.id) },
```

- [ ] **Step 7: 编译与测试**

Run: `gradle compileKotlin test --console=plain`
Expected: BUILD SUCCESSFUL（`I18nCatalogTest` 通过）

- [ ] **Step 8: 提交**

```bash
git add src/main/kotlin/app/dialog/ExternalDialogs.kt src/main/kotlin/app/core/Main.kt \
  src/main/kotlin/app/ui/EditorArea.kt src/main/kotlin/app/state/ConsoleState.kt
git commit -m "feat(app): 外部 SQL 文件打开入口/拖拽/数据源选择/退出保护/启动 prune"
```

---

### Task 9: 收尾——冒烟、TODO 更新、人工验证

**Files:**
- Modify: `TODO.md`
- Test: 全套 `gradle test` + `gradle smokeJdbc`

**Interfaces:**
- Consumes: 全部前序任务
- Produces: 可交付状态

- [ ] **Step 1: 全量验证**

Run:
```bash
export JAVA_HOME=/home/zk/.sdkman/candidates/java/25.0.3-jbr
export PATH=/home/zk/.sdkman/candidates/gradle/9.4.1/bin:$JAVA_HOME/bin:$PATH
gradle compileKotlin test smokeJdbc --console=plain
```
Expected: BUILD SUCCESSFUL；`smokeJdbc` 打印 PASS。

- [ ] **Step 2: 更新 TODO**

在 `TODO.md` 的「编辑区域」或「数据源支持」段落地记录已完成项（若 TODO 中无此项则不加）；在「结果编辑」段附近补一行「外部 SQL 文件控制台（N7）：拖入/选择打开 + 数据源关联 + 磁盘双向同步」并标记已完成（或按项目惯例删除待办条目）。按 AGENTS「完成一项即删除对应条目」，若 TODO 中无对应条目则本步骤只做验证记录，不改 TODO。

- [ ] **Step 3: 提交**

```bash
git add TODO.md
git commit -m "docs: 记录外部 SQL 文件控制台完成情况"
```

- [ ] **Step 4: 人工验证（由用户执行，不由 agent 跑 GUI）**

前置：准备一个项目里的 `iter.sql`（内容如 `SELECT 1;`），启动 `gradle run`（X11 下 `export DISPLAY=:0.0`）。

1. **入口**：标签条「+」→「打开 SQL 文件…」选 `iter.sql` → 弹数据源选择 → 选一个连接 → 出现带链接图标的标签，正文为文件内容，无「文件已丢失」标记。
2. **拖拽**：把 `iter.sql` 从文件管理器拖进窗口 → 聚焦已打开的同名控制台（不新增）。
3. **执行**：选中 `SELECT` 执行，结果正常；切换头部「目标」库后执行，语句按新目标运行。
4. **自动写回**：改内容，等 >3s 或切控制台 → 用外部编辑器打开 `iter.sql`，内容已更新。
5. **外部修改（干净）**：在外部编辑器改文件保存 → DB-K 编辑器内容在 ~1s 内自动刷新，无横幅。
6. **外部修改（脏）**：在 DB-K 改内容未落盘，立刻在外部编辑器改文件保存 → 出现黄色冲突横幅；点「载入磁盘版本」→ 编辑器变磁盘内容、脏点消失；重复一次点「保留我的」→ 横幅消失，随后 DB-K 内容覆盖磁盘。
7. **缺文件**：外部删除 `iter.sql` → 出现「文件已丢失」横幅；点「重建文件」→ 文件按原路径重建且内容为缓冲。再次删除，点「另存为…」→ 选新路径 → 标签重绑、新文件生成。
8. **重开缓存**：关闭标签 → 外部改文件 → 从数据源右键「打开控制台」重开 → 显示的是磁盘新内容（非旧缓存）。
9. **退出保护**：删除文件后继续在 DB-K 编辑（保持脏）→ 关闭窗口 → 弹退出处理框；测试「重建全部」「逐个另存为…」「放弃并退出」「取消退出」四条路径。
10. **删除不删文件**：删除该控制台标签记录 → 确认磁盘文件仍在。
11. **重启自动隐藏**：关闭 DB-K → 删除文件 → 重启 DB-K → 该标签不出现；重新把文件放回并从右键「打开控制台」可恢复。
12. **主题/i18n**：切深色检查横幅/横幅按钮/标签图标可读；切 English 检查全部外部文件文案无残留中文、无占位符错位。

- [ ] **Step 5: 反馈修复**

根据人工验证结果修改代码；每改一处补/跑对应 `gradle test`，重新提交。

---

## Self-Review

**1. Spec coverage（逐条对照）**
- 背景/动机 → Task 1/4/7/8 全流程 ✅
- 已确认决策 1（持久化）→ Task 1 external 行 + Task 4 复用生命周期 ✅
- 决策 2（冲突 A）→ Task 4 `handleExternalChange`/`reloadFromDisk`/`keepLocal` + Task 6 横幅 ✅
- 决策 3（数据源 B）→ Task 4 `openExternalFile` 复用 + Task 8 选择框 ✅
- 决策 4（缺文件 A+A2）→ Task 4 missing/`recreateExternalFile`/`missingDirtyExternal` + Task 6 横幅 + Task 8 退出框 + Task 4 `pruneMissingExternal` ✅
- 决策 5（入口 A+D）→ Task 8 `openSqlFileDialog` + 拖拽 `DragData.FilesList` + 多文件 ✅
- 决策 6（路径唯一 A）→ Task 1 唯一索引 + `getConsoleByPath` + Task 4 `normalizePath` ✅
- 数据模型/仓库 → Task 1 ✅
- 生命周期/自动保存/编辑器同步 → Task 4 + Task 6 ✅
- 监听器细节/原子写 → Task 3 + Task 2 ✅
- UI 入口/标识/横幅/对话框 → Task 6/7/8 ✅
- i18n → Task 5 ✅
- 持久化小结 → 无新增 properties，Task 1 覆盖 ✅
- 测试计划 → Task 1/2/3/4 + Task 9 ✅

**2. Placeholder scan**：无 TBD/TODO；UI 基座（`DialogWindow`、取消文案 key）在两处标注「按仓库实际命名替换」——这是对既有代码的引用不确定性，执行时以 `app/dialog` 现有基座为准，属正常适配，不是功能占位。

**3. Type consistency**：`FileWatcher`（`watch/unwatch/close`）在 Task 3 定义、Task 4 注入；`ExternalFileIssue` 在 Task 4 定义、Task 6/7 消费；`textRevisionOf`/`externalIssueOf`/`missingDirtyExternal`/`rebindExternalFile`/`discardDirty` 在 Task 4 定义并在 Task 6/8 使用；`PickProfileRequest`/`ExternalMissingExitRequest` 在 Task 5 定义、Task 8 使用；`ConsoleTabBar` 参数在 Task 7 定义、Task 8 透传。

**4. Review Focus**：5 条均已绑定到子任务的测试或人工步骤（见 Review Focus 段内标注）。
