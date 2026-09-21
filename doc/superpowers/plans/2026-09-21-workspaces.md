# 工作区（Workspace）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在控制台之上引入「工作区」——控制台的虚拟分组；关闭控制台 = 从当前工作区移出成员，允许零工作区，打开控制台时自动创建「默认」工作区。

**Architecture:** 新增 `workspaces` + `workspace_consoles` 两张 SQLite 表（成员关系即「是否显示在该工作区标签条」）；新增 `app/state/WorkspaceState` 承担工作区 CRUD/成员/MRU/激活记忆，`ConsoleState` 单向依赖它；`consoles.closed` 列退役；左树单击只选中，双击数据源 = 连接并打开控制台。

**Tech Stack:** Kotlin 2.4 / Compose Desktop（Material2）/ Gradle 9.4.1 / JDK 25.0.3-jbr / org.xerial:sqlite-jdbc / kotlinx.coroutines / JUnit5 + kotlin.test。

**Spec:** `doc/superpowers/specs/2026-09-21-workspaces-design.md`

## Global Constraints

- 工具链固定：`JAVA_HOME=/home/zk/.sdkman/candidates/java/25.0.3-jbr`，`PATH` 加 `/home/zk/.sdkman/candidates/gradle/9.4.1/bin`；命令用 `gradle`（无 wrapper）。
- 分层纪律：`i18n/` 只依赖 JDK；`db/` 依赖 `engine/model` + `i18n`；`engine` 纯叶层；`jdbc`/`redis`/`es` 禁 import compose/coroutines；`app → (engine, db, jdbc, redis, tree)` 单向。
- 状态即 snapshot：UI 可观察状态一律 `mutableStateOf`。
- i18n：所有用户可见文案走 `Str` key，新增必须 zh + en 同时补，`when` 穷尽；派生/缓存模型只存 key+参数，不存已翻译文本。
- 主题：`Text`/`Icon` 前景色必须来自主题（`onSurface` 系）；硬编码仅限语义色（绿/红/黄/灰）做色块/点/徽章背景。
- UI 验证：不跑 GUI 自动化（禁止 xdotool/截图分析/沙箱数据目录）；程序化逻辑跑 `gradle test`，UI 给人工步骤。
- 迁移只增不改：已写过的 `migrateToVn` 不再修改；`CURRENT_VERSION` 逐任务递增。
- 提交信息用 `feat(...)` / `refactor(...)` / `test(...)` 前缀；每任务结束跑 `gradle compileKotlin` 或 `gradle test` 通过后提交。

## Review Focus

以下五类输入/失败模式，spec 隐含但确定性测试最容易漏；每条都在其归属任务的测试里钉住：

1. **存量库升级/全新用户**：`consoles` 为空时迁移**不**建工作区（零工作区合法，不能崩、不能建空工作区）。
2. **失效的 `activeWorkspace`**：`app.properties` 里的 `activeWorkspace` 指向已删除的工作区 → 启动回落到第一个工作区，不崩、不复活旧 id。
3. **同一控制台跨两个工作区**：在 A 关闭它不改变 B 的成员关系；删连接/删控制台时所有工作区的成员都被清干净。
4. **语言切换**：auto_named 工作区显示当前语言的「默认 / Default」，用户改过名的不被翻译覆盖。
5. **MRU/标签范围**：Ctrl+Tab 与标签条只含**当前**工作区成员，不泄漏其它工作区的控制台。

---

## 文件结构

- 修改 `src/main/kotlin/db/AppDatabase.kt` —— v10（建表 + 回填）、v11（丢弃 `closed` 列）。
- 修改 `src/main/kotlin/db/ConsoleModels.kt` —— 新增 `WorkspaceRecord`；删除 `ConsoleRecord.closed`。
- 修改 `src/main/kotlin/db/ConnectionsRepository.kt` —— 工作区 CRUD/成员方法；移除 `closed` 读写。
- 新增 `src/main/kotlin/app/settings/WorkspacePrefs.kt` —— `app.properties` 的 `activeWorkspace`。
- 新增 `src/main/kotlin/app/state/WorkspaceState.kt` —— 工作区状态 + 成员 + MRU + 激活记忆。
- 修改 `src/main/kotlin/app/state/ConsoleState.kt` —— 控制台生命周期接入工作区。
- 修改 `src/main/kotlin/app/state/DialogState.kt` —— `WorkspaceDialogRequest` + `ConfirmRequest.DeleteWorkspace`。
- 修改 `src/main/kotlin/i18n/Str.kt` / `CatalogZh.kt` / `CatalogEn.kt` —— 新增 key。
- 修改 `src/main/kotlin/app/dialog/TextInputDialogs.kt` —— `WorkspaceNameDialog` + 确认弹窗分支。
- 新增 `src/main/kotlin/app/ui/WorkspaceSwitcher.kt` —— 标签条右侧工作区下拉。
- 修改 `src/main/kotlin/app/ui/ConsoleTabBar.kt` —— 集成 switcher。
- 修改 `src/main/kotlin/app/ui/SqlWorkspace.kt` —— 透传工作区参数。
- 修改 `src/main/kotlin/app/core/Main.kt` —— 构造/接线/动作/弹窗/启动回位。
- 修改 `src/main/kotlin/tree/DbTreeSidebar.kt` —— 单击只选中、双击连接行打开控制台。
- 测试：修改 `src/test/kotlin/db/AppDatabaseMigrationTest.kt`、`src/test/kotlin/db/ConnectionsRepositoryTest.kt`、`src/test/kotlin/app/state/ConsoleStateTest.kt`、`src/test/kotlin/app/state/ConsoleStateRunTest.kt`；新增 `src/test/kotlin/app/state/WorkspaceStateTest.kt`。

---

## Task 1: 工作区持久化（SQLite v10 + 仓库方法）

**Files:**
- Modify: `src/main/kotlin/db/AppDatabase.kt`（`CURRENT_VERSION` 9→10；新增 `migrateToV10` + 调用）
- Modify: `src/main/kotlin/db/ConsoleModels.kt`（新增 `WorkspaceRecord`）
- Modify: `src/main/kotlin/db/ConnectionsRepository.kt`（新增工作区方法）
- Test: `src/test/kotlin/db/AppDatabaseMigrationTest.kt`、`src/test/kotlin/db/ConnectionsRepositoryTest.kt`

**Interfaces:**
- Produces:
  - `data class db.WorkspaceRecord(val id: String, val name: String, val autoNamed: Boolean = false, val sortOrder: Int = 0, val createdAt: Long = 0, val lastActiveConsoleId: String? = null)`
  - `ConnectionsRepository.listWorkspaces(): List<WorkspaceRecord>`
  - `ConnectionsRepository.createWorkspace(name: String, autoNamed: Boolean): WorkspaceRecord`
  - `ConnectionsRepository.renameWorkspace(id: String, newName: String)`
  - `ConnectionsRepository.deleteWorkspace(id: String)`
  - `ConnectionsRepository.setWorkspaceLastActiveConsole(id: String, consoleId: String?)`
  - `ConnectionsRepository.listWorkspaceConsoleIds(): Map<String, List<String>>`
  - `ConnectionsRepository.addConsoleToWorkspace(workspaceId: String, consoleId: String): Boolean`（true = 新增；false = 已存在）
  - `ConnectionsRepository.removeConsoleFromWorkspace(workspaceId: String, consoleId: String)`

- [ ] **Step 1: 写失败测试（迁移回填 + 仓库 CRUD）**

在 `src/test/kotlin/db/AppDatabaseMigrationTest.kt` 末尾追加（类内）：

```kotlin
    @Test
    fun `v10 backfills only open consoles into one auto named workspace preserving order`() {
        val db = dir.resolve("v9.db")
        raw(db).use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY NOT NULL)")
                st.execute(
                    "CREATE TABLE consoles (id TEXT PRIMARY KEY NOT NULL, connection_id TEXT NOT NULL, " +
                        "name TEXT NOT NULL, file_path TEXT NOT NULL DEFAULT '', sort_order INTEGER NOT NULL DEFAULT 0, " +
                        "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, target TEXT NOT NULL DEFAULT '', " +
                        "caret_start INTEGER NOT NULL DEFAULT 0, caret_end INTEGER NOT NULL DEFAULT 0, " +
                        "closed INTEGER NOT NULL DEFAULT 0)",
                )
                st.executeUpdate("INSERT INTO schema_migrations(version) VALUES (1),(2),(3),(4),(5),(6),(7),(8),(9)")
                st.execute("INSERT INTO consoles(id, connection_id, name, sort_order, created_at, updated_at, closed) VALUES ('b','c1','b',1,3,3,0)")
                st.execute("INSERT INTO consoles(id, connection_id, name, sort_order, created_at, updated_at, closed) VALUES ('a','c1','a',0,2,2,0)")
                st.execute("INSERT INTO consoles(id, connection_id, name, sort_order, created_at, updated_at, closed) VALUES ('x','c1','x',0,1,1,1)")
            }
        }
        raw(db).use { AppDatabase.migrate(it) }
        raw(db).use { c ->
            c.createStatement().use { st ->
                // 只建了一个工作区，auto_named=1
                st.executeQuery("SELECT id, auto_named FROM workspaces").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(1, rs.getInt("auto_named"))
                    assertFalse(rs.next())
                }
                // 未关闭的两个入区，按 sort_order 排序；已关闭的不入区
                st.executeQuery("SELECT console_id FROM workspace_consoles ORDER BY sort_order").use { rs ->
                    val ids = buildList { while (rs.next()) add(rs.getString(1)) }
                    assertEquals(listOf("a", "b"), ids)
                }
            }
        }
    }

    @Test
    fun `v10 creates no workspace when there is no open console`() {
        val db = dir.resolve("empty.db")
        raw(db).use { AppDatabase.migrate(it) }
        raw(db).use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM workspaces").use { rs ->
                    rs.next()
                    assertEquals(0, rs.getInt(1))
                }
            }
        }
    }
```

在 `src/test/kotlin/db/ConnectionsRepositoryTest.kt` 末尾追加（类内）：

```kotlin
    @Test
    fun `workspace crud and membership`() {
        open().use { repo ->
            val pid = repo.createConnection(newProfile())
            val c1 = repo.createConsole(pid, "c1")
            val c2 = repo.createConsole(pid, "c2")

            val w1 = repo.createWorkspace("W1", autoNamed = false)
            val w2 = repo.createWorkspace("Default", autoNamed = true)
            assertEquals(listOf(w1.id, w2.id), repo.listWorkspaces().map { it.id })
            assertTrue(repo.listWorkspaces()[1].autoNamed)

            // 幂等：重复加入返回 false 且不重复
            assertTrue(repo.addConsoleToWorkspace(w1.id, c1.id))
            assertFalse(repo.addConsoleToWorkspace(w1.id, c1.id))
            assertTrue(repo.addConsoleToWorkspace(w1.id, c2.id))
            assertTrue(repo.addConsoleToWorkspace(w2.id, c1.id))
            assertEquals(listOf(c1.id, c2.id), repo.listWorkspaceConsoleIds()[w1.id])

            // 同一控制台可在两个工作区
            assertEquals(setOf(w1.id, w2.id), repo.listWorkspaceConsoleIds().filterValues { c1.id in it }.keys)

            repo.removeConsoleFromWorkspace(w1.id, c1.id)
            assertEquals(listOf(c2.id), repo.listWorkspaceConsoleIds()[w1.id])

            repo.renameWorkspace(w2.id, "Renamed")
            assertFalse(repo.listWorkspaces().first { it.id == w2.id }.autoNamed)

            repo.setWorkspaceLastActiveConsole(w1.id, c2.id)
            assertEquals(c2.id, repo.listWorkspaces().first { it.id == w1.id }.lastActiveConsoleId)

            repo.deleteWorkspace(w1.id)
            assertEquals(listOf(w2.id), repo.listWorkspaces().map { it.id })
            assertNull(repo.listWorkspaceConsoleIds()[w1.id])
        }
    }

    @Test
    fun `deleting a console cascades out of all workspaces`() {
        open().use { repo ->
            val pid = repo.createConnection(newProfile())
            val c = repo.createConsole(pid, "c")
            val w1 = repo.createWorkspace("W1", false)
            val w2 = repo.createWorkspace("W2", false)
            repo.addConsoleToWorkspace(w1.id, c.id)
            repo.addConsoleToWorkspace(w2.id, c.id)
            repo.deleteConsole(c.id)
            assertTrue(repo.listWorkspaceConsoleIds().values.all { c.id !in it })
        }
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run:
```bash
export JAVA_HOME=/home/zk/.sdkman/candidates/java/25.0.3-jbr
export PATH=/home/zk/.sdkman/candidates/gradle/9.4.1/bin:$JAVA_HOME/bin:$PATH
gradle test --tests 'db.AppDatabaseMigrationTest' --tests 'db.ConnectionsRepositoryTest'
```
Expected: 编译失败（`createWorkspace` / `WorkspaceRecord` 未定义）。

- [ ] **Step 3: 实现迁移 + 模型 + 仓库**

`src/main/kotlin/db/ConsoleModels.kt`：在文件末尾（`ConsoleRecord` 之后）新增：

```kotlin
/**
 * 工作区行：控制台的虚拟分组。
 * [autoNamed] = 名字跟随语言（渲染时取 `Str.WorkspaceDefaultName`）；用户改名后为 false。
 * [lastActiveConsoleId]：该工作区上次激活的控制台（重启回位）。
 */
data class WorkspaceRecord(
    val id: String,
    val name: String,
    val autoNamed: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long = 0,
    val lastActiveConsoleId: String? = null,
)
```

`src/main/kotlin/db/AppDatabase.kt`：
1. 顶部 import 增加（文件已无 import 段的自行加到最前）：
```kotlin
import i18n.I18n
import i18n.Str
import java.util.UUID
```
2. `CURRENT_VERSION` 改为 `10`。
3. `migrate` 里 v9 块之后追加：
```kotlin
        if (!applied.contains(10)) {
            conn.createStatement().use { st -> migrateToV10(st) }
            conn.prepareStatement("INSERT INTO schema_migrations(version) VALUES (10)").use { it.executeUpdate() }
        }
```
4. 新增方法（放在 `migrateToV9` 之后）：

```kotlin
    /**
     * v10：工作区（控制台的虚拟分组）。
     * `workspace_consoles` 的成员关系即「是否显示在该工作区标签条」；主键去重；
     * 删工作区/删控制台/删连接（→ 删控制台）时由 FK 级联清理。
     * 回填：把存量「未关闭」控制台划入一个 auto_named 的默认工作区（标签顺序保持）；
     * 没有任何未关闭控制台（全新用户）则不建工作区（零工作区合法）。
     */
    private fun migrateToV10(st: Statement) {
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS workspaces (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                auto_named INTEGER NOT NULL DEFAULT 0,
                sort_order INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                last_active_console_id TEXT NULL
            )
            """.trimIndent(),
        )
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS workspace_consoles (
                workspace_id TEXT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
                console_id TEXT NOT NULL REFERENCES consoles(id) ON DELETE CASCADE,
                sort_order INTEGER NOT NULL DEFAULT 0,
                added_at INTEGER NOT NULL,
                PRIMARY KEY (workspace_id, console_id)
            )
            """.trimIndent(),
        )
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ws_consoles_console ON workspace_consoles(console_id)")

        val openIds = st.executeQuery(
            "SELECT id FROM consoles WHERE closed = 0 ORDER BY connection_id, sort_order, created_at",
        ).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        if (openIds.isEmpty()) return

        val wsId = UUID.randomUUID().toString().replace("-", "").take(24)
        val now = System.currentTimeMillis()
        val defaultName = I18n.t(Str.WorkspaceDefaultName).replace("'", "''")
        st.executeUpdate(
            "INSERT INTO workspaces(id, name, auto_named, sort_order, created_at) " +
                "VALUES ('$wsId', '$defaultName', 1, 0, $now)",
        )
        openIds.forEachIndexed { i, cid ->
            st.executeUpdate(
                "INSERT INTO workspace_consoles(workspace_id, console_id, sort_order, added_at) " +
                    "VALUES ('$wsId', '$cid', $i, $now)",
            )
        }
    }
```

`src/main/kotlin/db/ConnectionsRepository.kt`：在 `// ---------- sql_history ----------` 之前插入：

```kotlin
    // ---------- workspaces（控制台的虚拟分组） ----------

    fun listWorkspaces(): List<WorkspaceRecord> =
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT id, name, auto_named, sort_order, created_at, last_active_console_id " +
                    "FROM workspaces ORDER BY sort_order, created_at",
            ).use { rs -> buildList { while (rs.next()) add(mapWorkspace(rs)) } }
        }

    fun createWorkspace(name: String, autoNamed: Boolean): WorkspaceRecord {
        val id = newId()
        val now = System.currentTimeMillis()
        val order = nextSortOrder("workspaces")
        conn.prepareStatement(
            "INSERT INTO workspaces(id, name, auto_named, sort_order, created_at) VALUES (?, ?, ?, ?, ?)",
        ).use { ps ->
            ps.setString(1, id)
            ps.setString(2, name)
            ps.setInt(3, if (autoNamed) 1 else 0)
            ps.setInt(4, order)
            ps.setLong(5, now)
            ps.executeUpdate()
        }
        return WorkspaceRecord(id = id, name = name, autoNamed = autoNamed, sortOrder = order, createdAt = now)
    }

    /** 重命名并清除 auto_named（此后名字不再跟随语言）。 */
    fun renameWorkspace(id: String, newName: String) {
        conn.prepareStatement("UPDATE workspaces SET name = ?, auto_named = 0 WHERE id = ?").use { ps ->
            ps.setString(1, newName)
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    fun deleteWorkspace(id: String) {
        conn.prepareStatement("DELETE FROM workspaces WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeUpdate()
        }
    }

    fun setWorkspaceLastActiveConsole(id: String, consoleId: String?) {
        conn.prepareStatement("UPDATE workspaces SET last_active_console_id = ? WHERE id = ?").use { ps ->
            if (consoleId == null) ps.setNull(1, java.sql.Types.VARCHAR) else ps.setString(1, consoleId)
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    /** 全部成员关系：workspaceId → 有序列 consoleId（标签顺序）。 */
    fun listWorkspaceConsoleIds(): Map<String, List<String>> =
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT workspace_id, console_id FROM workspace_consoles ORDER BY workspace_id, sort_order, added_at",
            ).use { rs ->
                val out = LinkedHashMap<String, MutableList<String>>()
                while (rs.next()) out.getOrPut(rs.getString(1)) { mutableListOf() }.add(rs.getString(2))
                out
            }
        }

    /** 加入工作区（幂等）：返回 true = 本次真正新增。 */
    fun addConsoleToWorkspace(workspaceId: String, consoleId: String): Boolean {
        val order = conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM workspace_consoles WHERE workspace_id = '$workspaceId'",
            ).use { rs -> rs.next(); rs.getInt(1) }
        }
        return conn.prepareStatement(
            "INSERT OR IGNORE INTO workspace_consoles(workspace_id, console_id, sort_order, added_at) VALUES (?, ?, ?, ?)",
        ).use { ps ->
            ps.setString(1, workspaceId)
            ps.setString(2, consoleId)
            ps.setInt(3, order)
            ps.setLong(4, System.currentTimeMillis())
            ps.executeUpdate() > 0
        }
    }

    fun removeConsoleFromWorkspace(workspaceId: String, consoleId: String) {
        conn.prepareStatement("DELETE FROM workspace_consoles WHERE workspace_id = ? AND console_id = ?").use { ps ->
            ps.setString(1, workspaceId)
            ps.setString(2, consoleId)
            ps.executeUpdate()
        }
    }

    private fun mapWorkspace(rs: java.sql.ResultSet): WorkspaceRecord = WorkspaceRecord(
        id = rs.getString("id"),
        name = rs.getString("name"),
        autoNamed = rs.getInt("auto_named") != 0,
        sortOrder = rs.getInt("sort_order"),
        createdAt = rs.getLong("created_at"),
        lastActiveConsoleId = rs.getString("last_active_console_id"),
    )
```

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
gradle test --tests 'db.AppDatabaseMigrationTest' --tests 'db.ConnectionsRepositoryTest'
```
Expected: BUILD SUCCESSFUL（既有 `fresh migrate applies all versions` 仍断言 `[1..9]`，本任务不动它——v10 断言在 Task 4 一并更新）。

- [ ] **Step 5: 编译全量 + 提交**

```bash
gradle compileKotlin
git add src/main/kotlin/db/AppDatabase.kt src/main/kotlin/db/ConsoleModels.kt \
  src/main/kotlin/db/ConnectionsRepository.kt \
  src/test/kotlin/db/AppDatabaseMigrationTest.kt src/test/kotlin/db/ConnectionsRepositoryTest.kt
git commit -m "feat(db): 工作区持久化（workspaces + workspace_consoles，v10 迁移回填）"
```

---

## Task 2: WorkspaceState + WorkspacePrefs

**Files:**
- Create: `src/main/kotlin/app/settings/WorkspacePrefs.kt`
- Create: `src/main/kotlin/app/state/WorkspaceState.kt`
- Test: `src/test/kotlin/app/state/WorkspaceStateTest.kt`

**Interfaces:**
- Consumes: Task 1 的仓库方法 + `db.WorkspaceRecord`。
- Produces（后续任务依赖的确切签名，`ConsoleState` 与 `Main` 直接用）：
  - `WorkspaceState(repository: ConnectionsRepository, loadActive: () -> String? = { WorkspacePrefs.load() }, saveActive: (String?) -> Unit = { WorkspacePrefs.save(it) })`（测试注入内存实现，避免污染真实 `<dataDir>/app.properties`）
  - `val workspaces: List<WorkspaceRecord>`、`val activeWorkspaceId: String?`
  - `fun ensureLoaded()`
  - `fun activeWorkspace(): WorkspaceRecord?`
  - `fun ensureActive(): WorkspaceRecord`
  - `fun createWorkspace(name: String?): WorkspaceRecord`
  - `fun renameWorkspace(id: String, newName: String)`
  - `fun deleteWorkspace(id: String): Boolean`
  - `fun setActive(id: String)`
  - `fun memberIds(wsId: String): List<String>`
  - `fun contains(wsId: String, consoleId: String): Boolean`
  - `fun addMember(wsId: String, consoleId: String): Boolean`
  - `fun removeMember(wsId: String, consoleId: String)`
  - `fun removeConsoleEverywhere(consoleId: String)`
  - `fun orderedMembers(wsId: String): List<String>`
  - `fun touch(wsId: String, consoleId: String)`
  - `fun lastActiveConsoleOf(wsId: String): String?`
  - `fun setLastActive(wsId: String, consoleId: String?)`

- [ ] **Step 1: 写失败测试**

创建 `src/test/kotlin/app/state/WorkspaceStateTest.kt`：

```kotlin
package app.state

import db.ConnectionProfile
import db.ConnectionsRepository
import db.DbType
import i18n.I18n
import i18n.Str
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** WorkspaceState 单元测试：真实 SQLite（临时目录），不碰网络/Compose。 */
class WorkspaceStateTest {

    @TempDir
    lateinit var dir: Path

    private fun open() = ConnectionsRepository(dir.resolve("app.db"), dir.resolve("consoles"))

    private fun profile() = ConnectionProfile(id = "p1", name = "conn", dbType = DbType.SQLITE, database = "x.db")

    /** 会话内保存的「激活工作区」；注入内存实现，避免污染真实 <dataDir>/app.properties。 */
    private var savedActive: String? = null

    private fun newWs(repo: ConnectionsRepository) =
        WorkspaceState(repo, loadActive = { savedActive }, saveActive = { savedActive = it })

    @Test
    fun `ensureActive creates Default auto named workspace when none`() {
        open().use { repo ->
            val wsState = newWs(repo)
            assertNull(wsState.activeWorkspace())
            val ws = wsState.ensureActive()
            assertTrue(ws.autoNamed)
            assertEquals(I18n.t(Str.WorkspaceDefaultName), ws.name)
            assertEquals(ws.id, wsState.activeWorkspaceId)
            assertEquals(1, wsState.workspaces.size)
        }
    }

    @Test
    fun `create rename delete and active fallback`() {
        open().use { repo ->
            val wsState = newWs(repo)
            val w1 = wsState.createWorkspace("W1")
            val w2 = wsState.createWorkspace(null)
            assertFalse(w1.autoNamed)
            assertTrue(w2.autoNamed)
            wsState.setActive(w1.id)
            assertEquals(w1.id, wsState.activeWorkspaceId)

            wsState.renameWorkspace(w2.id, "Renamed")
            assertFalse(wsState.workspaces.first { it.id == w2.id }.autoNamed)
            assertEquals("Renamed", wsState.workspaces.first { it.id == w2.id }.name)

            assertTrue(wsState.deleteWorkspace(w1.id))
            assertNull(wsState.activeWorkspaceId)
            assertEquals(listOf(w2.id), wsState.workspaces.map { it.id })
        }
    }

    @Test
    fun `membership is idempotent and removable everywhere`() {
        open().use { repo ->
            val pid = repo.createConnection(profile())
            val c1 = repo.createConsole(pid, "c1")
            val c2 = repo.createConsole(pid, "c2")
            val wsState = newWs(repo)
            val w1 = wsState.createWorkspace("W1")
            val w2 = wsState.createWorkspace("W2")

            assertTrue(wsState.addMember(w1.id, c1.id))
            assertFalse(wsState.addMember(w1.id, c1.id))
            wsState.addMember(w2.id, c1.id)
            wsState.addMember(w1.id, c2.id)
            assertEquals(listOf(c1.id, c2.id), wsState.memberIds(w1.id))
            assertTrue(wsState.contains(w2.id, c1.id))

            wsState.removeConsoleEverywhere(c1.id)
            assertFalse(wsState.contains(w1.id, c1.id))
            assertFalse(wsState.contains(w2.id, c1.id))
            assertEquals(listOf(c2.id), wsState.memberIds(w1.id))
        }
    }

    @Test
    fun `mru order is isolated per workspace`() {
        open().use { repo ->
            val pid = repo.createConnection(profile())
            val c1 = repo.createConsole(pid, "c1")
            val c2 = repo.createConsole(pid, "c2")
            val c3 = repo.createConsole(pid, "c3")
            val wsState = newWs(repo)
            val w1 = wsState.createWorkspace("W1")
            val w2 = wsState.createWorkspace("W2")
            wsState.addMember(w1.id, c1.id)
            wsState.addMember(w1.id, c2.id)
            wsState.addMember(w2.id, c3.id)

            // w1 里 c1 最新，然后 c2；w2 的 MRU 不影响 w1
            wsState.touch(w1.id, c1.id)
            assertEquals(listOf(c1.id, c2.id), wsState.orderedMembers(w1.id))
            wsState.touch(w2.id, c3.id)
            assertEquals(listOf(c1.id, c2.id), wsState.orderedMembers(w1.id))
            assertEquals(listOf(c3.id), wsState.orderedMembers(w2.id))
        }
    }

    @Test
    fun `last active console persists and stale active workspace falls back to first`() {
        open().use { repo ->
            val pid = repo.createConnection(profile())
            val c = repo.createConsole(pid, "c")
            val wsState = newWs(repo)
            val w1 = wsState.createWorkspace("W1")
            val w2 = wsState.createWorkspace("W2")
            wsState.addMember(w1.id, c.id)
            wsState.setActive(w1.id)
            wsState.setLastActive(w1.id, c.id)

            // 新实例（模拟重启）：activeWorkspace 从注入的存档恢复
            val store = savedActive
            val reloaded = WorkspaceState(repo, loadActive = { store }, saveActive = { savedActive = it })
            assertEquals(w1.id, reloaded.activeWorkspaceId)
            assertEquals(c.id, reloaded.lastActiveConsoleOf(w1.id))

            // 存档指向已删除的工作区 → 回落第一个
            val afterGhost = WorkspaceState(repo, loadActive = { "ghost" }, saveActive = {})
            assertEquals(w1.id, afterGhost.activeWorkspaceId)
        }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `gradle test --tests 'app.state.WorkspaceStateTest'`
Expected: 编译失败（`WorkspaceState` / `WorkspacePrefs` 未定义）。

- [ ] **Step 3: 实现 WorkspacePrefs 与 WorkspaceState**

创建 `src/main/kotlin/app/settings/WorkspacePrefs.kt`：

```kotlin
package app.settings

import db.AppPaths
import java.io.File
import java.util.Properties

/**
 * 应用级偏好：当前激活的工作区。
 * 与 [LanguagePrefs] 共用 `<dataDir>/app.properties`（不同 key），符合「同类项并入已有文件」。
 */
object WorkspacePrefs {

    private const val KEY_ACTIVE = "activeWorkspace"

    private fun prefsFile(): File = AppPaths.dataDirectory().resolve("app.properties").toFile()

    fun load(): String? {
        val f = prefsFile()
        if (!f.exists()) return null
        return runCatching {
            val props = Properties()
            f.inputStream().use { props.load(it) }
            props.getProperty(KEY_ACTIVE)?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** [id] = null 移除 key（零工作区态）。 */
    fun save(id: String?) {
        runCatching {
            val f = prefsFile()
            val props = Properties()
            if (f.exists()) f.inputStream().use { props.load(it) }
            if (id == null) props.remove(KEY_ACTIVE) else props.setProperty(KEY_ACTIVE, id)
            f.parentFile?.mkdirs()
            f.outputStream().use { props.store(it, "db-k app preferences") }
        }
    }
}
```

创建 `src/main/kotlin/app/state/WorkspaceState.kt`：

```kotlin
package app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.settings.WorkspacePrefs
import db.ConnectionsRepository
import db.WorkspaceRecord
import i18n.I18n
import i18n.Str

/**
 * 工作区状态：控制台的虚拟分组。
 *
 * - 成员关系（`workspaceId → [consoleId]`）= 该控制台是否显示在该工作区标签条上；
 *   正文/结果/光标只有一份，跨工作区共享（见 spec）。
 * - MRU 为**会话内**内存（重启后按标签顺序重建）；「上次激活的工作区」与
 *   「每个工作区上次激活的控制台」跨重启持久化。
 * - `ensureActive()` 在零工作区时建一个 auto_named 的「默认」工作区。
 */
class WorkspaceState(
    private val repository: ConnectionsRepository,
    /** 读「当前激活工作区」存档；默认走 `<dataDir>/app.properties`，测试注入内存实现。 */
    private val loadActive: () -> String? = { WorkspacePrefs.load() },
    /** 写「当前激活工作区」存档；默认走 `<dataDir>/app.properties`。 */
    private val saveActive: (String?) -> Unit = { WorkspacePrefs.save(it) },
) {

    var workspaces by mutableStateOf<List<WorkspaceRecord>>(emptyList())
        private set

    var activeWorkspaceId by mutableStateOf<String?>(null)
        private set

    /** workspaceId → 有序列 consoleId（标签顺序）。 */
    private val members = mutableStateMapOf<String, List<String>>()

    /** workspaceId → 会话内 MRU（最近在前）。 */
    private val mruByWorkspace = mutableMapOf<String, MutableList<String>>()

    private var loaded = false

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        workspaces = repository.listWorkspaces()
        members.putAll(repository.listWorkspaceConsoleIds())
        val saved = loadActive()
        activeWorkspaceId = saved?.takeIf { id -> workspaces.any { it.id == id } }
            ?: workspaces.firstOrNull()?.id
    }

    fun activeWorkspace(): WorkspaceRecord? {
        ensureLoaded()
        val id = activeWorkspaceId ?: return null
        return workspaces.firstOrNull { it.id == id }
    }

    /** 确保存在一个激活工作区：优先当前/第一个；零工作区才新建「默认」。 */
    fun ensureActive(): WorkspaceRecord {
        activeWorkspace()?.let { return it }
        workspaces.firstOrNull()?.let { rec ->
            setActive(rec.id)
            return rec
        }
        val created = createWorkspace(null)
        setActive(created.id)
        return created
    }

    /** [name] = null → auto_named（渲染时取 `Str.WorkspaceDefaultName`）。 */
    fun createWorkspace(name: String?): WorkspaceRecord {
        ensureLoaded()
        val rec = repository.createWorkspace(
            name = name ?: I18n.t(Str.WorkspaceDefaultName),
            autoNamed = name == null,
        )
        workspaces = workspaces + rec
        members[rec.id] = emptyList()
        return rec
    }

    fun renameWorkspace(id: String, newName: String) {
        ensureLoaded()
        repository.renameWorkspace(id, newName)
        workspaces = workspaces.map {
            if (it.id == id) it.copy(name = newName, autoNamed = false) else it
        }
    }

    /** 删除工作区；返回它是否是删除前的激活工作区。控制台实体不受影响。 */
    fun deleteWorkspace(id: String): Boolean {
        ensureLoaded()
        val wasActive = activeWorkspaceId == id
        repository.deleteWorkspace(id)
        workspaces = workspaces.filterNot { it.id == id }
        members.remove(id)
        mruByWorkspace.remove(id)
        if (wasActive) {
            activeWorkspaceId = null
            saveActive(null)
        }
        return wasActive
    }

    fun setActive(id: String) {
        ensureLoaded()
        activeWorkspaceId = id
        saveActive(id)
    }

    fun memberIds(wsId: String): List<String> {
        ensureLoaded()
        return members[wsId].orEmpty()
    }

    fun contains(wsId: String, consoleId: String): Boolean = memberIds(wsId).contains(consoleId)

    /** 加入成员（幂等）；返回 true = 本次真正新增。 */
    fun addMember(wsId: String, consoleId: String): Boolean {
        ensureLoaded()
        val added = repository.addConsoleToWorkspace(wsId, consoleId)
        if (added) members[wsId] = members[wsId].orEmpty() + consoleId
        return added
    }

    fun removeMember(wsId: String, consoleId: String) {
        ensureLoaded()
        repository.removeConsoleFromWorkspace(wsId, consoleId)
        members[wsId] = members[wsId].orEmpty().filterNot { it == consoleId }
        mruByWorkspace[wsId]?.remove(consoleId)
    }

    /** 控制台被删除 / 连接被删除时：从所有工作区移除其成员关系。 */
    fun removeConsoleEverywhere(consoleId: String) {
        ensureLoaded()
        members.filterValues { it.contains(consoleId) }.keys.toList().forEach { wsId ->
            repository.removeConsoleFromWorkspace(wsId, consoleId)
            members[wsId] = members[wsId].orEmpty().filterNot { it == consoleId }
            mruByWorkspace[wsId]?.remove(consoleId)
        }
    }

    /** 该工作区成员按 MRU 排序（最近在前）；不在 MRU 里的按标签顺序补到末尾。 */
    fun orderedMembers(wsId: String): List<String> {
        val mem = memberIds(wsId)
        val mru = mruByWorkspace[wsId].orEmpty()
        return mru.filter { it in mem } + mem.filter { it !in mru }
    }

    fun touch(wsId: String, consoleId: String) {
        val list = mruByWorkspace.getOrPut(wsId) { mutableListOf() }
        list.remove(consoleId)
        list.add(0, consoleId)
    }

    fun lastActiveConsoleOf(wsId: String): String? {
        ensureLoaded()
        return workspaces.firstOrNull { it.id == wsId }?.lastActiveConsoleId
    }

    fun setLastActive(wsId: String, consoleId: String?) {
        ensureLoaded()
        repository.setWorkspaceLastActiveConsole(wsId, consoleId)
        workspaces = workspaces.map {
            if (it.id == wsId) it.copy(lastActiveConsoleId = consoleId) else it
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `gradle test --tests 'app.state.WorkspaceStateTest'`
Expected: PASS（5 个用例）。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/app/settings/WorkspacePrefs.kt src/main/kotlin/app/state/WorkspaceState.kt \
  src/test/kotlin/app/state/WorkspaceStateTest.kt
git commit -m "feat(state): WorkspaceState（工作区 CRUD/成员/MRU/激活记忆）+ WorkspacePrefs"
```

---

## Task 3: ConsoleState 接入工作区

**Files:**
- Modify: `src/main/kotlin/app/state/ConsoleState.kt`
- Modify: `src/main/kotlin/app/core/Main.kt`（仅构造 ConsoleState 一处）
- Test: `src/test/kotlin/app/state/ConsoleStateTest.kt`、`src/test/kotlin/app/state/ConsoleStateRunTest.kt`

**Interfaces:**
- Consumes: `WorkspaceState`（Task 2）。
- Produces（保持方法名不变，语义工作区化，避免连锁改名）：
  - `ConsoleState(repository, connectionsState, workspaces, scope, ioDispatcher, confirmDangerous)`（`workspaces` 为新增第 3 个参数）
  - `fun openConsoles(profileId: String): List<ConsoleRecord>`（= 当前工作区中该数据源的控制台）
  - `fun allOpenConsoles(profileIds: List<String>): List<ConsoleRecord>`（= 当前工作区成员，标签顺序）
  - `fun activateForProfile(profileId: String): ConsoleRecord?`（复用规则：ws 内 MRU → 该源 updated_at 最大者入区 → 新建）
  - `fun reopenConsole(consoleId: String): ConsoleRecord?`（= 加入当前工作区并激活）
  - `fun onWorkspaceSwitched()`、`fun onAllWorkspacesGone()`

- [ ] **Step 1: 改写受影响测试**

编辑 `src/test/kotlin/app/state/ConsoleStateTest.kt`：

1. `newState` 加 `workspaces` 参数：
```kotlin
    private fun TestScope.newState(repo: ConnectionsRepository) = ConsoleState(
        repository = repo,
        connectionsState = ConnectionsState(MetaCache(dbPath), ColumnCache(dbPath)),
        workspaces = WorkspaceState(repo, loadActive = { null }, saveActive = {}),
        scope = this,
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
    )
```
2. 用下面两个用例**替换**原 `closeConsole hides from open list and reopens from data source` 与 `switchConsoleByMru skips closed consoles`：
```kotlin
    @Test
    fun `close removes console from current workspace only`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val c1 = state.createConsole(pid, "控制台 1")
            val c2 = state.createConsole(pid, "控制台 2")
            assertEquals(listOf(c1.id, c2.id), state.openConsoles(pid).map { it.id })

            // 在另一个工作区也放入 c2
            val w2 = state.workspaces.let { ws -> ws.createWorkspace("W2") }
            state.workspaces.setActive(w2.id)
            state.reopenConsole(c2.id)
            assertTrue(state.workspaces.contains(w2.id, c2.id))

            // 回到默认工作区，关闭 c2：只影响当前工作区
            val w1 = state.workspaces.workspaces.first { it.id != w2.id }.id
            state.workspaces.setActive(w1)
            state.closeConsole(c2.id)
            assertFalse(state.workspaces.contains(w1, c2.id))
            assertTrue(state.workspaces.contains(w2.id, c2.id))
            assertEquals(listOf(c1.id), state.openConsoles(pid).map { it.id })
            // .sql 仍在（成员移除不删正文）
            assertTrue(Files.isRegularFile(Path.of(c2.filePath)))

            // 关闭最后一个 → 引导态
            state.closeConsole(c1.id)
            assertTrue(state.openConsoles(pid).isEmpty())
            assertNull(state.activeConsoleId)
        }
    }

    @Test
    fun `mru switches only within the active workspace`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val c1 = state.createConsole(pid, "1")
            val c2 = state.createConsole(pid, "2")
            val c3 = state.createConsole(pid, "3")
            // 新建的工作区 W2 只含 c3
            val w2 = state.workspaces.createWorkspace("W2")
            state.workspaces.setActive(w2.id)
            state.reopenConsole(c3.id)
            // W2 只有 c3 → 无可切换
            assertNull(state.switchConsoleByMru(1))
            // 回默认工作区（含 c1,c2,c3）→ 正常循环
            val w1 = state.workspaces.workspaces.first { it.id != w2.id }.id
            state.workspaces.setActive(w1)
            state.onWorkspaceSwitched()
            assertNotNull(state.switchConsoleByMru(1))
        }
    }
```
3. 用下面用例**替换** `activateMostRecent picks max updatedAt across profiles`：
```kotlin
    @Test
    fun `activateMostRecent restores last active of the active workspace`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val c1 = state.createConsole(pid, "c1")
            val c2 = state.createConsole(pid, "c2")
            state.activate(c1)
            // 新实例模拟重启（同一 repo）
            val restarted = newState(repo)
            val rec = restarted.activateMostRecent(listOf(pid))
            assertNotNull(rec)
            assertEquals(c1.id, rec.id)
            assertEquals(c1.id, restarted.activeConsoleId)
        }
    }
```
4. 用下面用例**替换** `activateForProfile prefers last active console`（语义：ws 内复用）：
```kotlin
    @Test
    fun `activateForProfile reuses mru console of the workspace`() = runTest {
        repo().use { repo ->
            val pid = repo.createConnection(profile())
            val state = newState(repo)
            val c1 = state.createConsole(pid, "c1")
            val c2 = state.createConsole(pid, "c2")
            // MRU 最近的是 c2（createConsole 都 activate）
            assertEquals(c2.id, state.activateForProfile(pid)!!.id)
            state.activate(c1)
            assertEquals(c1.id, state.activateForProfile(pid)!!.id)

            // 新工作区里没有任何该源控制台 → 取 updated_at 最大者加入
            val w2 = state.workspaces.createWorkspace("W2")
            state.workspaces.setActive(w2.id)
            repo.renameConsole(c2.id, "c2-new") // c2 成为 updated_at 最大
            val picked = state.activateForProfile(pid)!!
            assertEquals(c2.id, picked.id)
            assertTrue(state.workspaces.contains(w2.id, c2.id))
        }
    }
```

编辑 `src/test/kotlin/app/state/ConsoleStateRunTest.kt`：`newState` 里的 `ConsoleState(...)` 加一行 `workspaces = WorkspaceState(repo, loadActive = { null }, saveActive = {}),`（注入内存存档，不碰真实数据目录）。

- [ ] **Step 2: 跑测试确认失败**

Run: `gradle test --tests 'app.state.ConsoleStateTest' --tests 'app.state.ConsoleStateRunTest'`
Expected: 编译失败（构造参数不匹配）。

- [ ] **Step 3: 实现 ConsoleState 改动**

`src/main/kotlin/app/state/ConsoleState.kt`：

3a. 构造签名加参数（放在 `connectionsState` 之后）：
```kotlin
class ConsoleState(
    private val repository: ConnectionsRepository,
    private val connectionsState: ConnectionsState,
    val workspaces: WorkspaceState,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val confirmDangerous: (suspend (String) -> Boolean)? = null,
) {
```
3b. 删除字段 `lastActivePerProfile`（`private val lastActivePerProfile = mutableMapOf<String, String>()`）与 `mruOrder`（`private val mruOrder = mutableListOf<String>()`）。
3c. `openConsoles` 与 `allOpenConsoles` 替换为：
```kotlin
    /** 当前工作区中该数据源的控制台（标签顺序）。 */
    fun openConsoles(profileId: String): List<ConsoleRecord> =
        allOpenConsoles(listOf(profileId))

    /** 当前工作区成员（按成员顺序，跨数据源）——标签条用。 */
    fun allOpenConsoles(profileIds: List<String>): List<ConsoleRecord> {
        val ws = workspaces.activeWorkspace() ?: return emptyList()
        val allowed = profileIds.toHashSet()
        return workspaces.memberIds(ws.id).asSequence()
            .mapNotNull { findConsole(it) }
            .filter { it.connectionId in allowed }
            .toList()
    }
```
3d. `activate` 里的两行 `lastActivePerProfile[...]` / `mruOrder` 替换为：
```kotlin
        workspaces.activeWorkspace()?.let { ws ->
            workspaces.touch(ws.id, console.id)
            workspaces.setLastActive(ws.id, console.id)
        }
```
3e. `activateForProfile` 整个函数替换为：
```kotlin
    /**
     * 打开某数据源的控制台（双击数据源 / 标题栏切换 / 右键「打开控制台」共用）：
     * 1. ws 内已有该源控制台 → 激活 MRU 最前的；
     * 2. 否则取该源 `updated_at` 最大的控制台加入当前工作区并激活；
     * 3. 该源一个都没有 → 新建（新建即入区）。
     */
    fun activateForProfile(profileId: String): ConsoleRecord? {
        val ws = workspaces.ensureActive()
        val orderedInWs = workspaces.orderedMembers(ws.id).mapNotNull { findConsole(it) }
            .filter { it.connectionId == profileId }
        if (orderedInWs.isNotEmpty()) {
            val chosen = orderedInWs.firstOrNull()
                ?: profileConsoles(profileId).maxByOrNull { it.updatedAt }!!
            activate(chosen)
            return chosen
        }
        val any = profileConsoles(profileId).maxByOrNull { it.updatedAt }
        if (any != null) {
            workspaces.addMember(ws.id, any.id)
            activate(any)
            return any
        }
        return createConsole(profileId, I18n.t(Str.ConsoleDefaultName, 1))
    }
```
3f. `activateMostRecent` 替换为：
```kotlin
    /** 启动回位：恢复当前工作区上次激活的控制台（否则该区 updated_at 最大者）。 */
    fun activateMostRecent(profileIds: List<String>): ConsoleRecord? {
        if (activeConsoleId != null) return activeConsole()
        val ws = workspaces.activeWorkspace() ?: return null
        val allowed = profileIds.toHashSet()
        val members = workspaces.orderedMembers(ws.id).mapNotNull { findConsole(it) }
            .filter { it.connectionId in allowed }
        val last = workspaces.lastActiveConsoleOf(ws.id)?.let { id -> members.firstOrNull { it.id == id } }
        val chosen = last ?: members.maxByOrNull { it.updatedAt } ?: return null
        activate(chosen)
        return chosen
    }
```
3g. `openConsolesByMru` 替换为：
```kotlin
    private fun openConsolesByMru(): List<ConsoleRecord> {
        val ws = workspaces.activeWorkspace() ?: return emptyList()
        return workspaces.orderedMembers(ws.id).mapNotNull { findConsole(it) }
    }
```
3h. 新增工作区切换/清空方法（放在 `endMruCycle` 之后）：
```kotlin
    /** 切换工作区后：flush 旧控制台，激活新工作区的上次激活控制台（否则 updated_at 最大者）。 */
    fun onWorkspaceSwitched() {
        val prev = activeConsole()
        if (prev != null) {
            flushNow(prev.id)
            flushCaretNow(prev.id)
        }
        activeConsoleId = null
        mruCycleIds = null
        val ws = workspaces.activeWorkspace() ?: return
        val members = workspaces.orderedMembers(ws.id).mapNotNull { findConsole(it) }
        val last = workspaces.lastActiveConsoleOf(ws.id)?.let { id -> members.firstOrNull { it.id == id } }
        (last ?: members.maxByOrNull { it.updatedAt })?.let { activate(it) }
    }

    /** 最后一个工作区被删除：进入零工作区引导态。 */
    fun onAllWorkspacesGone() {
        val prev = activeConsole()
        if (prev != null) {
            flushNow(prev.id)
            flushCaretNow(prev.id)
        }
        activeConsoleId = null
        mruCycleIds = null
    }

    /** 当前工作区里激活下一个控制台（优先同数据源）；没有则保持 null。 */
    private fun activateNextInWorkspace(preferProfileId: String? = null): ConsoleRecord? {
        val ws = workspaces.activeWorkspace() ?: return null
        val ordered = workspaces.orderedMembers(ws.id).mapNotNull { findConsole(it) }
        val next = preferProfileId?.let { pid -> ordered.firstOrNull { it.connectionId == pid } }
            ?: ordered.firstOrNull()
        if (next != null) activate(next)
        return next
    }
```
3i. `createConsole` 里在 `buffers[rec.id] = ""` 之后、`activate(rec)` 之前插入：
```kotlin
        workspaces.ensureActive().let { ws -> workspaces.addMember(ws.id, rec.id) }
```
3j. `closeConsole` 替换为：
```kotlin
    /**
     * 关闭控制台 = 从当前工作区移出成员（正文/.sql 保留，其它工作区不受影响）。
     * 若关的是当前激活 → 切到本工作区下一个；没有则引导态。
     */
    fun closeConsole(consoleId: String) {
        val ws = workspaces.activeWorkspace() ?: return
        if (!workspaces.contains(ws.id, consoleId)) return
        workspaces.removeMember(ws.id, consoleId)
        if (activeConsoleId == consoleId) {
            flushNow(consoleId)
            flushCaretNow(consoleId)
            activeConsoleId = null
            activateNextInWorkspace()
        }
    }
```
3k. `reopenConsole` 替换为（去掉 `setClosedFlag`）：
```kotlin
    /** 把已存在的控制台加入当前工作区并激活（已在其中则直接激活）。 */
    fun reopenConsole(consoleId: String): ConsoleRecord? {
        val rec = findConsole(consoleId) ?: return null
        val ws = workspaces.ensureActive()
        if (!workspaces.contains(ws.id, consoleId)) workspaces.addMember(ws.id, consoleId)
        activate(rec)
        return rec
    }
```
3l. 删除 `setClosedFlag` 私有方法。
3m. `deleteConsole` 里 `mruOrder.remove(consoleId)` 换成 `workspaces.removeConsoleEverywhere(consoleId)`；末尾 `if (activeConsoleId == consoleId) { activeConsoleId = null; activateForProfile(rec.connectionId) }` 换成：
```kotlin
        if (activeConsoleId == consoleId) {
            activeConsoleId = null
            activateNextInWorkspace(preferProfileId = rec.connectionId)
        }
```
3n. `onConnectionDeleted` 里 `mruOrder.remove(rec.id)` 换成 `workspaces.removeConsoleEverywhere(rec.id)`。

`src/main/kotlin/app/core/Main.kt`：把 ConsoleState 构造改为（新增 `workspaceState` 变量）：
```kotlin
    val workspaceState = remember { WorkspaceState(repository) }
    val consoleState = remember {
        ConsoleState(repository, connectionsState, workspaceState, scope) { command ->
            // ……（危险命令确认回调保持原样）
        }
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
gradle test --tests 'app.state.ConsoleStateTest' --tests 'app.state.ConsoleStateRunTest' --tests 'app.state.WorkspaceStateTest'
gradle compileKotlin
```
Expected: PASS + 编译通过。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/app/state/ConsoleState.kt src/main/kotlin/app/core/Main.kt \
  src/test/kotlin/app/state/ConsoleStateTest.kt src/test/kotlin/app/state/ConsoleStateRunTest.kt
git commit -m "refactor(state): ConsoleState 控制台生命周期接入工作区（关闭=移出当前工作区）"
```

---

## Task 4: 退役 `consoles.closed`（v11 丢弃列 + 模型/仓库清理）

**Files:**
- Modify: `src/main/kotlin/db/AppDatabase.kt`（`CURRENT_VERSION` 10→11；`migrateToV11`）
- Modify: `src/main/kotlin/db/ConsoleModels.kt`（删 `closed` 字段）
- Modify: `src/main/kotlin/db/ConnectionsRepository.kt`（删 `closed` 读写 + `setConsoleClosed`）
- Test: `src/test/kotlin/db/AppDatabaseMigrationTest.kt`

**Interfaces:**
- Produces: `ConsoleRecord` 不再有 `closed`；`ConnectionsRepository.setConsoleClosed` 删除。

- [ ] **Step 1: 更新测试**

`src/test/kotlin/db/AppDatabaseMigrationTest.kt`：
1. `fresh migrate applies all versions` 的断言改为 `listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)`。
2. 删除 `legacy v2 migrates preserving data and defaults` 里的 v8 断言块：
```kotlin
                // v8 旧控制台默认 closed=0（未关闭）
                st.executeQuery("SELECT closed FROM consoles WHERE id='cc1'").use { rs ->
                    rs.next()
                    assertEquals(0, rs.getInt("closed"))
                }
```
换成 v11 断言：
```kotlin
                // v11 已丢弃 consoles.closed 列
                st.executeQuery("PRAGMA table_info(consoles)").use { rs ->
                    val cols = buildList { while (rs.next()) add(rs.getString("name")) }
                    assertFalse("closed" in cols, "closed 列应已丢弃: $cols")
                }
                // v10 回填：legacy 的 cc1（closed 默认 0）进入一个 auto_named 工作区
                st.executeQuery("SELECT COUNT(*) FROM workspace_consoles WHERE console_id='cc1'").use { rs ->
                    rs.next()
                    assertEquals(1, rs.getInt(1))
                }
```
（顶部 import 增补 `import kotlin.test.assertFalse`。）
3. `v10 backfills ...` 用例的 SQL 仍可用（它手工建了 `closed` 列，迁移到 v10 后 v11 丢弃）。

- [ ] **Step 2: 跑测试确认失败**

Run: `gradle test --tests 'db.AppDatabaseMigrationTest'`
Expected: FAIL（`closed` 列仍存在 / 版本列表为 `[1..10]`）。

- [ ] **Step 3: 实现 v11 + 清理**

`src/main/kotlin/db/AppDatabase.kt`：
1. `CURRENT_VERSION` = `11`。
2. `migrate` 里 v10 块后追加：
```kotlin
        if (!applied.contains(11)) {
            conn.createStatement().use { st -> migrateToV11(st) }
            conn.prepareStatement("INSERT INTO schema_migrations(version) VALUES (11)").use { it.executeUpdate() }
        }
```
3. 新增：
```kotlin
    /** v11：工作区接管「关闭」语义后，consoles.closed 退役。 */
    private fun migrateToV11(st: Statement) {
        st.executeUpdate("ALTER TABLE consoles DROP COLUMN closed")
    }
```

`src/main/kotlin/db/ConsoleModels.kt`：删除 `ConsoleRecord` 的 `val closed: Boolean = false,` 与 KDoc 中的 `[closed]` 段。

`src/main/kotlin/db/ConnectionsRepository.kt`：
1. `listConsoles` / `getConsole` 的 SELECT 去掉 `, closed FROM consoles` → `... caret_start, caret_end FROM consoles`。
2. 删除 `setConsoleClosed` 整个函数。
3. `mapConsole` 删除 `closed = rs.getInt("closed") != 0,`。

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
gradle test --tests 'db.AppDatabaseMigrationTest' --tests 'db.ConnectionsRepositoryTest' --tests 'app.state.ConsoleStateTest'
gradle compileKotlin
```
Expected: PASS + 编译通过。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/db/AppDatabase.kt src/main/kotlin/db/ConsoleModels.kt \
  src/main/kotlin/db/ConnectionsRepository.kt src/test/kotlin/db/AppDatabaseMigrationTest.kt
git commit -m "refactor(db): 退役 consoles.closed（v11 丢弃列，工作区接管关闭语义）"
```

---

## Task 5: i18n 新增 key

**Files:**
- Modify: `src/main/kotlin/i18n/Str.kt`、`src/main/kotlin/i18n/CatalogZh.kt`、`src/main/kotlin/i18n/CatalogEn.kt`
- Test: `src/test/kotlin/i18n/I18nCatalogTest.kt`（既有，自动覆盖）

**Interfaces:**
- Produces: `Str.WorkspaceDefaultName`、`Str.WorkspaceNoWorkspace`、`Str.WorkspaceMenuTooltip`、`Str.WorkspaceNew`、`Str.WorkspaceNewTitle`、`Str.WorkspaceRenameTitle`、`Str.WorkspaceDeleteTitle`、`Str.WorkspaceDeleteConfirm`、`Str.WorkspaceWithCount`、`Str.StarterNoWorkspaceHint`、`Str.MainWorkspaceCreated`。

- [ ] **Step 1: 写测试（先确认现状通过）**

Run: `gradle test --tests 'i18n.I18nCatalogTest'`
Expected: PASS（此时还没加 key；此步只是基线）。

- [ ] **Step 2: 加 key（三处同步）**

`src/main/kotlin/i18n/Str.kt`：在 `// ── 危险操作确认弹窗 ──` 区块之后新增：
```kotlin
    // ── 工作区（控制台虚拟分组） ──
    WorkspaceDefaultName,
    WorkspaceNoWorkspace,
    WorkspaceMenuTooltip,
    WorkspaceNew,
    WorkspaceNewTitle,
    WorkspaceRenameTitle,
    WorkspaceDeleteTitle,
    WorkspaceDeleteConfirm,
    WorkspaceWithCount,
    StarterNoWorkspaceHint,
    MainWorkspaceCreated,
```

`src/main/kotlin/i18n/CatalogZh.kt`：在对应位置新增：
```kotlin
    // ── 工作区 ──
    Str.WorkspaceDefaultName -> "默认"
    Str.WorkspaceNoWorkspace -> "无工作区"
    Str.WorkspaceMenuTooltip -> "工作区"
    Str.WorkspaceNew -> "新建工作区…"
    Str.WorkspaceNewTitle -> "新建工作区"
    Str.WorkspaceRenameTitle -> "重命名工作区"
    Str.WorkspaceDeleteTitle -> "删除工作区"
    Str.WorkspaceDeleteConfirm -> "删除工作区「{0}」？其中的控制台会保留，可从数据源右键重新打开。"
    Str.WorkspaceWithCount -> "{0} · {1}"
    Str.StarterNoWorkspaceHint -> "打开或新建控制台会自动创建一个「默认」工作区。"
    Str.MainWorkspaceCreated -> "已创建工作区「{0}」"
```

`src/main/kotlin/i18n/CatalogEn.kt`：对应英文：
```kotlin
    // ── Workspaces ──
    Str.WorkspaceDefaultName -> "Default"
    Str.WorkspaceNoWorkspace -> "No workspace"
    Str.WorkspaceMenuTooltip -> "Workspaces"
    Str.WorkspaceNew -> "New workspace…"
    Str.WorkspaceNewTitle -> "New workspace"
    Str.WorkspaceRenameTitle -> "Rename workspace"
    Str.WorkspaceDeleteTitle -> "Delete workspace"
    Str.WorkspaceDeleteConfirm -> "Delete workspace \"{0}\"? Its consoles are kept and can be reopened from the data source context menu."
    Str.WorkspaceWithCount -> "{0} · {1}"
    Str.StarterNoWorkspaceHint -> "Opening or creating a console will automatically create a \"Default\" workspace."
    Str.MainWorkspaceCreated -> "Workspace \"{0}\" created"
```

- [ ] **Step 3: 跑测试确认通过**

Run: `gradle test --tests 'i18n.I18nCatalogTest'`
Expected: PASS（含 `allKeysNonBlankInBothLanguages`、`placeholdersMatchAcrossLanguages`；`WorkspaceWithCount`/`WorkspaceDeleteConfirm` 两语言占位符必须一致）。

- [ ] **Step 4: 提交**

```bash
git add src/main/kotlin/i18n/Str.kt src/main/kotlin/i18n/CatalogZh.kt src/main/kotlin/i18n/CatalogEn.kt
git commit -m "feat(i18n): 工作区相关文案（zh + en）"
```

---

## Task 6: 弹窗请求与组件（DialogState + TextInputDialogs）

**Files:**
- Modify: `src/main/kotlin/app/state/DialogState.kt`
- Modify: `src/main/kotlin/app/dialog/TextInputDialogs.kt`

**Interfaces:**
- Produces:
  - `sealed interface app.state.WorkspaceDialogRequest { data object Create; data class Rename(val workspace: db.WorkspaceRecord) }`
  - `DialogState.workspaceDialog: WorkspaceDialogRequest?`
  - `ConfirmRequest.DeleteWorkspace(id: String, name: String)`
  - `@Composable fun WorkspaceNameDialog(request: WorkspaceDialogRequest, onDismiss: () -> Unit, onConfirm: (String) -> Unit)`

- [ ] **Step 1: 加请求类型**

`src/main/kotlin/app/state/DialogState.kt`：
1. 顶部 import 增加 `import db.WorkspaceRecord`。
2. 在 `ConsoleRenameRequest` 之后新增：
```kotlin
/** 工作区新建/重命名弹窗请求。 */
sealed interface WorkspaceDialogRequest {
    data object Create : WorkspaceDialogRequest
    data class Rename(val workspace: WorkspaceRecord) : WorkspaceDialogRequest
}
```
3. `ConfirmRequest` 里新增（放在 `DeleteConsole` 之后）：
```kotlin
    data class DeleteWorkspace(val id: String, val name: String) : ConfirmRequest
```
4. `DialogState` 类里新增：
```kotlin
    var workspaceDialog by mutableStateOf<WorkspaceDialogRequest?>(null)
```

- [ ] **Step 2: 加弹窗 composable**

`src/main/kotlin/app/dialog/TextInputDialogs.kt`：
1. import 增加 `import app.state.WorkspaceDialogRequest`。
2. 在 `FolderNameDialog` 之后新增：
```kotlin
/** 工作区新建/重命名弹窗（与其它单字段弹窗一致：Enter 提交、空输入忽略）。 */
@Composable
fun WorkspaceNameDialog(
    request: WorkspaceDialogRequest,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val isCreate = request is WorkspaceDialogRequest.Create
    val initial = (request as? WorkspaceDialogRequest.Rename)?.workspace?.name ?: ""
    var name by remember(request) { mutableStateOf(initial) }
    val confirm = { onConfirm(name.trim()) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(request) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(if (isCreate) Str.WorkspaceNewTitle else Str.WorkspaceRenameTitle)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(t(Str.CommonName)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent(submitOnEnter(name.isNotBlank(), confirm)),
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = confirm) { Text(t(Str.CommonOk)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(Str.CommonCancel)) } },
    )
}
```
3. `ConfirmDialog` 的 `when (request)` 增加分支（放在 `DeleteConsole` 分支之后）：
```kotlin
        is ConfirmRequest.DeleteWorkspace -> {
            t(Str.WorkspaceDeleteTitle) to t(Str.WorkspaceDeleteConfirm, request.name)
        }
```
（`confirmLabel` 的 `when` 已有 `else -> t(Str.CommonDelete)`，无需改。）

- [ ] **Step 3: 编译确认**

Run: `gradle compileKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 提交**

```bash
git add src/main/kotlin/app/state/DialogState.kt src/main/kotlin/app/dialog/TextInputDialogs.kt
git commit -m "feat(dialog): 工作区新建/重命名弹窗与删除确认请求"
```

---

## Task 7: WorkspaceSwitcher + ConsoleTabBar 集成

**Files:**
- Create: `src/main/kotlin/app/ui/WorkspaceSwitcher.kt`
- Modify: `src/main/kotlin/app/ui/ConsoleTabBar.kt`

**Interfaces:**
- Consumes: `db.WorkspaceRecord`、`Str.Workspace*`、`app.i18n.t`。
- Produces:
  - `@Composable internal fun WorkspaceSwitcher(workspaces: List<WorkspaceRecord>, active: WorkspaceRecord?, countOf: (String) -> Int, hasDirty: (String) -> Boolean, onSelect: (String) -> Unit, onCreate: () -> Unit, onRename: (WorkspaceRecord) -> Unit, onDelete: (WorkspaceRecord) -> Unit)`
  - `ConsoleTabBar` 新增同名透传参数（默认值保证旧调用点仍编译）。

- [ ] **Step 1: 实现 WorkspaceSwitcher**

创建 `src/main/kotlin/app/ui/WorkspaceSwitcher.kt`：

```kotlin
package app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.i18n.t
import db.WorkspaceRecord
import i18n.Str

/**
 * 标签条右侧的工作区切换器：当前工作区名 + 下拉。
 * 下拉项 = 工作区列表（`名字 · N`，有未保存带 ●，当前项 ✓）；悬停行尾浮现「重命名 / 删除」；
 * 底部固定「新建工作区…」。零工作区时按钮显示「无工作区」，下拉只剩新建项。
 */
@Composable
internal fun WorkspaceSwitcher(
    workspaces: List<WorkspaceRecord>,
    active: WorkspaceRecord?,
    countOf: (String) -> Int,
    hasDirty: (String) -> Boolean,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onRename: (WorkspaceRecord) -> Unit,
    onDelete: (WorkspaceRecord) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colors.onSurface.copy(alpha = 0.045f))
                .clickable { open = true }
                .padding(start = 8.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        ) {
            Text(
                active?.let { displayName(it) } ?: t(Str.WorkspaceNoWorkspace),
                fontSize = 12.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = if (active != null) 0.85f else 0.4f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(120.dp),
            )
            Icon(
                Icons.Filled.ArrowDropDown,
                t(Str.WorkspaceMenuTooltip),
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            workspaces.forEach { ws ->
                WorkspaceRow(
                    ws = ws,
                    current = ws.id == active?.id,
                    count = countOf(ws.id),
                    dirty = hasDirty(ws.id),
                    onSelect = { open = false; onSelect(ws.id) },
                    onRename = { open = false; onRename(ws) },
                    onDelete = { open = false; onDelete(ws) },
                )
            }
            if (workspaces.isNotEmpty()) Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.08f))
            DropdownMenuItem(onClick = { open = false; onCreate() }) {
                Text(t(Str.WorkspaceNew), fontSize = 13.sp, color = MaterialTheme.colors.onSurface.copy(alpha = 0.85f))
            }
        }
    }
}

/** 工作区名渲染：auto_named 跟随语言，用户改名后用存储值。 */
@Composable
internal fun displayName(ws: WorkspaceRecord): String =
    if (ws.autoNamed) t(Str.WorkspaceDefaultName) else ws.name

@Composable
private fun WorkspaceRow(
    ws: WorkspaceRecord,
    current: Boolean,
    count: Int,
    dirty: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var hovering by remember { mutableStateOf(false) }
    DropdownMenuItem(
        onClick = onSelect,
        modifier = Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    when (awaitPointerEvent().type) {
                        PointerEventType.Enter -> hovering = true
                        PointerEventType.Exit -> hovering = false
                        else -> Unit
                    }
                }
            }
        },
    ) {
        Icon(
            Icons.Filled.Check,
            null,
            tint = if (current) MaterialTheme.colors.primary else Color.Transparent,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        if (dirty) {
            Text("●", color = Color(0xFFFFB300), fontSize = 9.sp, modifier = Modifier.padding(end = 3.dp))
        }
        Text(
            t(Str.WorkspaceWithCount, displayName(ws), count),
            fontSize = 13.sp,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            color = if (current) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(alpha = 0.85f),
        )
        if (hovering) {
            Spacer(Modifier.width(10.dp))
            Icon(
                Icons.Filled.Edit,
                t(Str.WorkspaceRenameTitle),
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp).clickable(onClick = onRename),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Filled.Delete,
                t(Str.WorkspaceDeleteTitle),
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp).clickable(onClick = onDelete),
            )
        }
    }
}
```

- [ ] **Step 2: ConsoleTabBar 集成**

`src/main/kotlin/app/ui/ConsoleTabBar.kt`：
1. import 增加 `import db.WorkspaceRecord`。
2. 签名新增参数（放在 `onCloseConsole` 之后，全部带默认值以免旧调用点编译失败）：
```kotlin
    workspaces: List<WorkspaceRecord> = emptyList(),
    activeWorkspace: WorkspaceRecord? = null,
    workspaceCountOf: (String) -> Int = { 0 },
    workspaceHasDirty: (String) -> Boolean = { false },
    onSelectWorkspace: (String) -> Unit = {},
    onCreateWorkspace: () -> Unit = {},
    onRenameWorkspace: (WorkspaceRecord) -> Unit = {},
    onDeleteWorkspace: (WorkspaceRecord) -> Unit = {},
```
3. 在 `+` 的 `Box { ... }` 之后（`Row` 结束前）插入：
```kotlin
        Spacer(Modifier.width(6.dp))
        WorkspaceSwitcher(
            workspaces = workspaces,
            active = activeWorkspace,
            countOf = workspaceCountOf,
            hasDirty = workspaceHasDirty,
            onSelect = onSelectWorkspace,
            onCreate = onCreateWorkspace,
            onRename = onRenameWorkspace,
            onDelete = onDeleteWorkspace,
        )
```

- [ ] **Step 3: 编译确认**

Run: `gradle compileKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 提交**

```bash
git add src/main/kotlin/app/ui/WorkspaceSwitcher.kt src/main/kotlin/app/ui/ConsoleTabBar.kt
git commit -m "feat(ui): 标签条右侧工作区切换器（新建/切换/重命名/删除）"
```

---

## Task 8: SqlWorkspace 透传 + Main 接线

**Files:**
- Modify: `src/main/kotlin/app/ui/SqlWorkspace.kt`
- Modify: `src/main/kotlin/app/core/Main.kt`

**Interfaces:**
- Consumes: Task 2/3/6/7 的 API。
- Produces: `SqlWorkspace` 新增透传参数；Main 提供工作区动作与弹窗。

- [ ] **Step 1: SqlWorkspace 参数与透传**

`src/main/kotlin/app/ui/SqlWorkspace.kt`：
1. import 增加 `import db.WorkspaceRecord`。
2. `WindowScope.SqlWorkspace(...)` 签名在 `onCloseConsole` 之后新增（带默认值）：
```kotlin
    workspaces: List<WorkspaceRecord> = emptyList(),
    activeWorkspace: WorkspaceRecord? = null,
    workspaceCountOf: (String) -> Int = { 0 },
    workspaceHasDirty: (String) -> Boolean = { false },
    onSelectWorkspace: (String) -> Unit = {},
    onCreateWorkspace: () -> Unit = {},
    onRenameWorkspace: (WorkspaceRecord) -> Unit = {},
    onDeleteWorkspace: (WorkspaceRecord) -> Unit = {},
```
3. 找到 `ConsoleTabBar(` 调用点，追加同名实参（`workspaces = workspaces, activeWorkspace = activeWorkspace, ...`）。
4. `StarterPane` 新增参数并改提示：
```kotlin
internal fun StarterPane(
    profiles: List<ConnectionProfile>,
    onCreateConsoleAt: (String) -> Unit,
    hasWorkspace: Boolean = true,
)
```
在 `Text(t(Str.EditorNoOpenConsoleHint), ...)` 之后插入：
```kotlin
        if (!hasWorkspace) {
            Text(
                t(Str.StarterNoWorkspaceHint),
                fontSize = 12.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
            )
        }
```
并把调用点 `StarterPane(profiles = profiles, onCreateConsoleAt = onCreateConsoleAt)` 改为 `StarterPane(profiles = profiles, onCreateConsoleAt = onCreateConsoleAt, hasWorkspace = activeWorkspace != null)`。

- [ ] **Step 2: Main 动作与接线**

`src/main/kotlin/app/core/Main.kt`：
1. import 增加：
```kotlin
import app.state.WorkspaceDialogRequest
import app.dialog.WorkspaceNameDialog
import db.WorkspaceRecord
```
2. 在 `AppBody` 的 `val scope = rememberCoroutineScope()` 之后定义工作区动作（放在 `commitEditsNow` 之前）：
```kotlin
    /** 切换工作区：flush 旧控制台 → 换激活 → 恢复新工作区上次激活的控制台。 */
    fun switchWorkspace(id: String) {
        if (wsState.activeWorkspaceId == id) return
        wsState.setActive(id)
        consoleState.onWorkspaceSwitched()
    }

    /** 删除工作区：返回是否删的是激活工作区；激活的删掉后切到第一个，零工作区进引导态。 */
    fun deleteWorkspace(id: String) {
        val wasActive = wsState.deleteWorkspace(id)
        if (!wasActive) return
        val next = wsState.workspaces.firstOrNull()
        if (next != null) {
            wsState.setActive(next.id)
            consoleState.onWorkspaceSwitched()
        } else {
            consoleState.onAllWorkspacesGone()
        }
    }
```
（本段里的 `wsState` = `consoleState.workspaces`；在动作块开头加 `val wsState = consoleState.workspaces`。）
3. `selectRow` 去掉控制台切换（只保留选中）：
```kotlin
    /** 树里选中某行：只记录选中（单击不再打开/切换控制台；打开走双击或右键）。 */
    fun selectRow(rowKey: String?) {
        treeState.select(rowKey)
    }
```
（删除 `val profileId = ...` / `activateForProfile` 相关三行。）
4. `onOpenConsoleForProfile` 改成「确保连接 + 打开控制台」：
```kotlin
                        onOpenConsoleForProfile = { p ->
                            scope.launch {
                                connectionsState.ensureConnectionReady(p)
                                consoleState.activateForProfile(p.id)
                            }
                        },
```
5. `SqlWorkspace(...)` 调用点追加实参：
```kotlin
                        workspaces = wsState.workspaces,
                        activeWorkspace = wsState.activeWorkspace(),
                        workspaceCountOf = { wsId -> wsState.memberIds(wsId).size },
                        workspaceHasDirty = { wsId ->
                            wsState.memberIds(wsId).any { it in consoleState.dirtyConsoleIds }
                        },
                        onSelectWorkspace = ::switchWorkspace,
                        onCreateWorkspace = { dialogState.workspaceDialog = WorkspaceDialogRequest.Create },
                        onRenameWorkspace = { ws -> dialogState.workspaceDialog = WorkspaceDialogRequest.Rename(ws) },
                        onDeleteWorkspace = { ws ->
                            val name = if (ws.autoNamed) I18n.t(Str.WorkspaceDefaultName) else ws.name
                            dialogState.confirm = ConfirmRequest.DeleteWorkspace(ws.id, name)
                        },
```
6. 在弹窗渲染区（`ConsoleNameDialog(...)` 附近）加：
```kotlin
        dialogState.workspaceDialog?.let { req ->
            WorkspaceNameDialog(
                request = req,
                onDismiss = { dialogState.workspaceDialog = null },
                onConfirm = { name ->
                    dialogState.workspaceDialog = null
                    val created = when (req) {
                        is WorkspaceDialogRequest.Create -> wsState.createWorkspace(name)
                        is WorkspaceDialogRequest.Rename -> {
                            wsState.renameWorkspace(req.workspace.id, name)
                            null
                        }
                    }
                    if (created != null) {
                        switchWorkspace(created.id)
                        val label = if (created.autoNamed) I18n.t(Str.WorkspaceDefaultName) else created.name
                        toastState.show(I18n.t(Str.MainWorkspaceCreated, label))
                    }
                },
            )
        }
```
7. 在 `ConfirmDialog(...)` 的 `onConfirm` 分派处（Main 里那个 `when (request)`，现有 `is ConfirmRequest.DeleteConsole -> ...` 旁）加分支：
```kotlin
                    is ConfirmRequest.DeleteWorkspace -> deleteWorkspace(request.id)
```

- [ ] **Step 3: 编译确认**

Run: `gradle compileKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 提交**

```bash
git add src/main/kotlin/app/ui/SqlWorkspace.kt src/main/kotlin/app/core/Main.kt
git commit -m "feat(app): 接线工作区切换器/弹窗/切换动作，单击树只选中"
```

---

## Task 9: 左树双击语义（连接并打开控制台）

**Files:**
- Modify: `src/main/kotlin/tree/DbTreeSidebar.kt`

**Interfaces:**
- Consumes: `Main.openConsoleForProfile`（Task 8 已改为「确保连接 + 打开」）。
- Produces: `RowActions.onActivateProfile: () -> Unit`。

- [ ] **Step 1: 加动作字段**

`src/main/kotlin/tree/DbTreeSidebar.kt`：
1. `class RowActions(...)` 里在 `onOpenConsole` 之后新增：
```kotlin
    /** CONNECTION 行：双击 = 确保连接并打开控制台（进当前工作区）。 */
    val onActivateProfile: () -> Unit = {},
```
2. `RowActions(...)` 构造处（`onOpenConsole = ...` 之后）新增：
```kotlin
                                onActivateProfile = { row.profile?.let(onOpenConsoleForProfile) },
```

- [ ] **Step 2: 改行手势**

`DbTreeSidebar.kt` 的行 `.clickable { ... }` 里 `when` 块替换为：
```kotlin
            when {
                // 单击「继续扫描」：拉下一页键
                row.kind == TreeRowKind.LOAD_MORE -> actions.onLoadMore()
                // 双击连接行：确保连接 + 在当前工作区打开控制台（断开只走右键菜单）
                double && row.kind == TreeRowKind.CONNECTION -> actions.onActivateProfile()
                // 双击表/视图/物化视图 → 预览；双击可展开行 → 展开/收起；其余对象无预览语义
                double && row.kind == TreeRowKind.DB_OBJECT && row.dbObject?.kind?.isPreviewable() == true ->
                    actions.onPreviewTable()
                double && canExpand -> onToggle()
                else -> onSelect()
            }
```
3. 更新 `DbTreeSidebar` 顶部 KDoc 的交互约定段（第 165 行附近）为：
```
 * 交互约定：单击 = 仅选中（不打开控制台）；双击连接行 = 连接（如需）+ 在当前工作区打开控制台；
 * 双击可展开行 = 展开/收起；双击表/视图 = 预览。断开连接走右键菜单。
```

- [ ] **Step 3: 编译 + 全量测试**

Run:
```bash
gradle compileKotlin
gradle test
```
Expected: BUILD SUCCESSFUL；全部单测通过。

- [ ] **Step 4: 提交**

```bash
git add src/main/kotlin/tree/DbTreeSidebar.kt
git commit -m "feat(tree): 单击只选中，双击数据源连接并打开控制台"
```

---

## Task 10: 端到端与人工验证

**Files:**
- 无代码改动（若发现缺陷则回到对应任务修）。

- [ ] **Step 1: 全量自检**

Run:
```bash
export JAVA_HOME=/home/zk/.sdkman/candidates/java/25.0.3-jbr
export PATH=/home/zk/.sdkman/candidates/gradle/9.4.1/bin:$JAVA_HOME/bin:$PATH
gradle compileKotlin && gradle test && gradle smokeJdbc
```
Expected: 全部通过。

- [ ] **Step 2: 主题自查**

Run:
```bash
grep -rn "Color.Black\|Color.White" src/main/kotlin --include=*.kt | grep -v "AppTheme"
```
Expected: 只剩 AppTheme.kt 的色板定义与图标 fill。

- [ ] **Step 3: 把以下人工验证步骤交给用户（不要自跑 GUI）**

1. **迁移**：升级前有若干未关闭控制台 → 启动后标签条仍在，切换器显示「默认」；曾关闭的控制台不在标签条。
2. **切换**：新建工作区 W2 → 空标签条；在 W2 双击数据源 D → 出现 D 的控制台；切回默认看不到它（除非默认也有）。Ctrl+Tab 只在当前工作区循环。
3. **共享**：同一控制台加进两个工作区（两个工作区各双击同一数据源）→ 任一处编辑，另一处内容一致。
4. **关闭**：在 W2 关闭某控制台 → 默认工作区里它仍在；从数据源右键「打开控制台」可重新加入。
5. **删除到零工作区**：删完所有工作区 → 切换器显示「无工作区」、编辑区引导态；此时双击数据源 → 自动建「默认」并承载。
6. **单击只选中**：单击数据源不再打开/切换控制台；双击才连接并打开；断开仅右键。
7. **i18n / 深色**：中英各切一遍、深浅色各切一遍，检查下拉/弹窗/图标无残留文案、无黑字沉底。

- [ ] **Step 4: 更新设计文档索引（可选）**

若 `doc/DESIGN.md` 的 §7 状态表或 §8 UI 布局需要反映工作区，追加一行说明并提交：
```bash
git add doc/DESIGN.md
git commit -m "docs(design): 补充工作区（控制台虚拟分组）"
```

---

## Self-Review 记录

- **Spec 覆盖**：决策 1-12 分别落到 Task 1（持久化/回填）、Task 2（成员/MRU/激活）、Task 3（关闭=移出/复用规则/启动回位）、Task 4（closed 退役）、Task 5（i18n）、Task 6-8（UI/切换/删除到零工作区）、Task 9（单击只选中）。
- **Review Focus 归属**：①→Task 1 `v10 creates no workspace...`；②→Task 2 `stale active workspace falls back...`；③→Task 1 `deleting a console cascades...` + Task 3 `close removes...only`；④→Task 2 auto_named 断言 + Task 7 `displayName` + Task 5 catalog；⑤→Task 3 `mru switches only within...` + `activateForProfile reuses mru...`。
- **类型一致性**：`WorkspaceRecord` 字段、`WorkspaceState` 方法名、`ConsoleState` 构造参数序在 Task 1-8 间一致；`onActivateProfile` 仅在 Task 9 定义与使用。
