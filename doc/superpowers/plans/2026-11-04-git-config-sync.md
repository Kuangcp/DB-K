# 配置云同步（git 仓库）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 db-k 的连接档案（含密码）、控制台 SQL、工作区与三类设置能通过一个 git 仓库在多设备间加密同步，冲突按实体整份取舍。

**Architecture:** 新叶层 `sync/`（纯 JDK + kotlinx-serialization + tinylog）负责加密、仓库布局、git 命令封装与合并编排；`app/sync/` 的 `DbKSyncAdapter` 把 SQLite 与 `<dataDir>` 文件映射成与 git 无关的快照；`app/state/SyncState` + 两个自绘弹窗提供 UI。传输走系统 `git`（`ProcessBuilder`）。

**Tech Stack:** Kotlin/JVM（JDK 25 JBR）、Gradle 9.4.1、kotlinx-serialization-json 1.8.0、tinylog 2.7.0、kotlin.test + JUnit 5、系统 git ≥ 2.20。

**Spec:** `doc/superpowers/specs/2026-11-04-git-config-sync-design.md`

## Global Constraints

- 构建命令必须在 `JAVA_HOME=/home/zk/.sdkman/candidates/java/25.0.3-jbr`、
  `PATH` 含 `/home/zk/.sdkman/candidates/gradle/9.4.1/bin` 下执行；只用 `gradle`（无 wrapper）。
- `gradle compileKotlin` 必须 0 警告（`allWarningsAsErrors = true`）；`gradle test` 必须全绿。
- `sync/` 是纯叶层：**禁止** import compose / kotlinx-coroutines / `db` / `app` / `jdbc` / `redis` / `es` / `tree`；
  只允许 JDK、`kotlinx.serialization`、`org.tinylog`。
- `sync/` 里不得出现用户可见文案；错误用 `SyncFailure` 枚举表示，UI 再映射到 `Str`。
- 新增用户可见文案必须同时补 `i18n/CatalogZh` 与 `i18n/CatalogEn`（`I18nCatalogTest` 会校验）。
- 任何 `Text`/`Icon` 前景色必须来自主题（`MaterialTheme.colors.onSurface` 等），禁止写死黑/白；
  语义色只做色块。新增界面必须深浅色、中英各检查一遍。
- 所有落盘写操作：临时文件 + `ATOMIC_MOVE`；文本一律显式 UTF-8。
- 测试临时目录用 `@TempDir` 或 `Files.createTempDirectory`，`try/finally` 递归清理。
- 涉及系统 git 的测试必须有 `Assumptions.assumeTrue(GitRepo.isAvailable())` 跳过保护。
- 命令封装一律带 `-c core.quotepath=false`；不执行交互式 git（不弹编辑器、不要求 stdin）。
- 提交信息用 Conventional Commits，中文描述：`feat: …` / `test: …` / `docs: …`。
- **不要**替用户跑 GUI、改 `~/.local/share/db-k` 下的真实数据或环境变量；UI 任务只给人工验收步骤。

## Review Focus

以下五类输入/故障是这份设计最容易出人意料地错的地方，每条都必须在指定任务的测试里钉住：

1. **口令错误或口令丢失**：拉取在解密/校验失败时必须**一个本地字节都不改**（Task 12/16）。
2. **本地状态为空却推送**：新装设备/数据目录被清空后点推送，不能把远端仓库清成空（Task 6/12 守卫）。
3. **同一条连接两边都改**：必须识别为冲突并让用户整份取舍，选择后两台设备收敛；任何情况下不得把
   冲突标记 `<<<<<<<` 或错口令数据写进本地库（Task 7/10）。
4. **无改动重复同步**：GCM 随机 IV 会让相同明文产生不同密文；同一状态连续导出两次不得产生新提交
   （Task 4）。
5. **路径与编码**：中文文件名/中文数据目录、CRLF、`.enc` 被 git 当文本换行归一化（Task 3/17）。

---

## 文件结构

```
src/main/kotlin/sync/                  ← 新叶层（无 UI/无 DB/无协程）
  SecretBox.kt                         口令派生 + AES-GCM + 口令校验串
  SyncPaths.kt                         仓库路径约定与「路径 ↔ 实体」解析
  SyncModels.kt                        DTO / 快照 / 冲突 / 结果类型
  SyncAdapter.kt                       应用适配接口
  GitRepo.kt                           系统 git 封装（无业务语义）
  SyncEngine.kt                        导出 / 提交 / 合并 / 冲突 / 导入编排
src/main/kotlin/app/sync/               ← db-k 适配与编排
  DbKSyncAdapter.kt                    SQLite + <dataDir> 文件 ⇄ SyncSnapshot
  SyncPrefs.kt                         <dataDir>/sync.properties
  SyncService.kt                       单线程串行执行 + 口令/密钥会话缓存
src/main/kotlin/app/state/SyncState.kt 可观察同步状态
src/main/kotlin/app/dialog/SyncSection.kt        设置窗口「云同步」分区
src/main/kotlin/app/dialog/SyncConflictDialog.kt 冲突取舍弹窗
src/test/kotlin/sync/*Test.kt
src/test/kotlin/app/sync/*Test.kt
```

---

### Task 1: SecretBox（口令派生与条目加密）

**Files:**
- Create: `src/main/kotlin/sync/SecretBox.kt`
- Modify: `src/main/kotlin/db/ProfileTransfer.kt`（`deriveKey` 改为转调 `SecretBox.deriveKey`，消除两套 PBKDF2 参数）
- Test: `src/test/kotlin/sync/SecretBoxTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  object SecretBox {
      const val ENC_PREFIX: String            // "dbk-sync:v1:"
      const val CHECK_PLAINTEXT: String       // "db-k-sync-v1"
      const val SALT_BYTES: Int               // 16
      fun newSalt(): ByteArray
      fun deriveKey(passphrase: String, salt: ByteArray): javax.crypto.SecretKey
      fun encrypt(key: javax.crypto.SecretKey, plain: ByteArray): String   // 返回 ENC_PREFIX + base64(iv||ct)
      fun decrypt(key: javax.crypto.SecretKey, stored: String): ByteArray  // 非前缀/口令错 → IllegalArgumentException
      fun isEncrypted(text: String): Boolean
  }
  ```

- [ ] **Step 1: 写失败测试**

```kotlin
package sync

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SecretBoxTest {
    private val salt = ByteArray(16) { it.toByte() }
    private val key = SecretBox.deriveKey("correct horse", salt)
    private val otherKey = SecretBox.deriveKey("wrong horse", salt)

    @Test
    fun `roundtrip preserves bytes and uses prefix`() {
        val plain = """{"id":"c1","password":"p@ss"}""".toByteArray()
        val stored = SecretBox.encrypt(key, plain)
        assertTrue(stored.startsWith(SecretBox.ENC_PREFIX))
        assertContentEquals(plain, SecretBox.decrypt(key, stored))
    }

    @Test
    fun `random iv makes ciphertext differ for same plaintext`() {
        val plain = "same".toByteArray()
        assertTrue(SecretBox.encrypt(key, plain) != SecretBox.encrypt(key, plain))
    }

    @Test
    fun `wrong passphrase fails to decrypt`() {
        val stored = SecretBox.encrypt(key, SecretBox.CHECK_PLAINTEXT.toByteArray())
        assertFailsWith<IllegalArgumentException> { SecretBox.decrypt(otherKey, stored) }
    }

    @Test
    fun `same passphrase different salt derives different key`() {
        val otherSalt = ByteArray(16) { (it + 1).toByte() }
        val k2 = SecretBox.deriveKey("correct horse", otherSalt)
        val stored = SecretBox.encrypt(key, "x".toByteArray())
        assertFailsWith<IllegalArgumentException> { SecretBox.decrypt(k2, stored) }
    }

    @Test
    fun `malformed input rejected`() {
        assertFailsWith<IllegalArgumentException> { SecretBox.decrypt(key, "not-a-blob") }
        assertFailsWith<IllegalArgumentException> { SecretBox.decrypt(key, SecretBox.ENC_PREFIX + "!!!") }
        assertEquals(false, SecretBox.isEncrypted("not-a-blob"))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `JAVA_HOME=… PATH=… gradle test --tests 'sync.SecretBoxTest'`
Expected: 编译失败（`SecretBox` 未定义）。

- [ ] **Step 3: 实现**

参数与 `db/ProfileTransfer.kt` 保持一致：PBKDF2WithHmacSHA256、200_000 轮、256 bit；
AES/GCM/NoPadding、12B 随机 IV、128bit tag；`Base64.getEncoder()` 无换行。
`decrypt` 用 `require(...)` 抛 `IllegalArgumentException`（区分「前缀不对」「base64 坏」「AEADBadTagException」→
统一消息 "bad ciphertext"）。

- [ ] **Step 4: 跑测试确认通过**

Run: `… gradle test --tests 'sync.SecretBoxTest'`
Expected: PASS（5 个用例）。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/sync/SecretBox.kt src/test/kotlin/sync/SecretBoxTest.kt
git commit -m "feat: 同步口令派生与条目加解密"
```

---

### Task 2: SyncPaths + SyncModels

**Files:**
- Create: `src/main/kotlin/sync/SyncPaths.kt`, `src/main/kotlin/sync/SyncModels.kt`
- Test: `src/test/kotlin/sync/SyncPathsTest.kt`, `src/test/kotlin/sync/SyncModelsTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  object SyncPaths {
      const val SALT_FILE = "salt.bin"
      const val CHECK_FILE = "secret.check"
      const val META_FILE = "meta.json"
      const val README_FILE = "README.md"
      fun appRoot(workTree: Path, app: String): Path
      fun saltFile(root: Path): Path
      fun checkFile(root: Path): Path
      fun metaFile(root: Path): Path
      fun folderFile(root: Path, id: String): Path
      fun connectionFile(root: Path, id: String): Path
      fun workspaceFile(root: Path, id: String): Path
      fun consoleMetaFile(root: Path, id: String): Path
      fun consoleBodyFile(root: Path, id: String): Path
      fun settingFile(root: Path, name: String): Path       // "live-templates.json" | "keymap.json" | "themes.json"
      /** 把绝对路径解析成实体引用；salt/check/meta/README/未知文件 → null。 */
      fun parse(root: Path, file: Path): SyncRef?
  }

  sealed interface SyncRef {
      val id: String
      data class Folder(override val id: String) : SyncRef
      data class Connection(override val id: String) : SyncRef
      data class Workspace(override val id: String) : SyncRef
      data class ConsoleMeta(override val id: String) : SyncRef
      data class ConsoleBody(override val id: String) : SyncRef
      data class Setting(val name: String) : SyncRef { override val id: String get() = name }
  }
  ```
  `SyncModels.kt`（全部 `@Serializable`，除注明）：
  ```kotlin
  @Serializable data class FolderSync(val id: String, val name: String, val parentId: String? = null, val sortOrder: Int = 0)
  @Serializable data class ConnectionSync(
      val formatVersion: Int = 1, val id: String, val name: String, val dbType: String,
      val folderId: String? = null, val host: String = "", val port: Int = 0,
      val database: String = "", val user: String? = null, val password: String? = null,
      val extraParams: String = "", val color: String? = null, val sortOrder: Int = 0,
      val keySeparator: String = ":", val displayTz: String = "",
      val queryTimeoutSeconds: Int = 20, val updatedAt: Long = 0,
  )
  @Serializable data class WorkspaceSync(
      val id: String, val name: String, val autoNamed: Boolean = false, val sortOrder: Int = 0,
      val createdAt: Long = 0, val lastActiveConsoleId: String? = null,
      val consoleIds: List<String> = emptyList(),
  )
  @Serializable data class ConsoleMetaSync(
      val id: String, val connectionId: String, val name: String,
      val target: String = "", val sortOrder: Int = 0, val updatedAt: Long = 0,
  )
  @Serializable data class SyncMeta(val formatVersion: Int = 1, val app: String)
  @Serializable data class KeymapSync(val overrides: Map<String, String> = emptyMap())
  /** 控制台正文不序列化进 JSON，直接写 .sql 文本。 */
  data class ConsoleSync(val meta: ConsoleMetaSync, val body: String)
  data class SyncSnapshot(
      val folders: List<FolderSync> = emptyList(),
      val connections: List<ConnectionSync> = emptyList(),
      val workspaces: List<WorkspaceSync> = emptyList(),
      val consoles: List<ConsoleSync> = emptyList(),
      /** 仓库 settings/*.json 的原始文本；null = 本地没有该文件（不写）。 */
      val liveTemplatesJson: String? = null,
      val keymap: KeymapSync? = null,
      val themesJson: String? = null,
  ) { fun isEmpty(): Boolean }   // 定义：connections.isEmpty() && consoles.isEmpty()（空库守卫只看这两类，Review Focus 2）
  data class RemovedIds(
      val folders: Set<String> = emptySet(), val connections: Set<String> = emptySet(),
      val workspaces: Set<String> = emptySet(), val consoles: Set<String> = emptySet(),
  )
  data class SyncStats(val written: Int = 0, val deleted: Int = 0, val unchanged: Int = 0)
  sealed interface ConflictPreview {
      data class Connection(val name: String, val dbType: String, val host: String, val port: Int,
                            val database: String, val user: String?) : ConflictPreview
      data class Sql(val lineCount: Int, val head: List<String>) : ConflictPreview
      data class Named(val label: String) : ConflictPreview
      data class Unknown(val sizeBytes: Int) : ConflictPreview
  }
  data class ConflictItem(
      val path: String, val kind: SyncEntityKind, val id: String?,
      val local: ConflictPreview, val remote: ConflictPreview,
      val localBytes: ByteArray, val remoteBytes: ByteArray,
  )
  enum class SyncEntityKind { FOLDER, CONNECTION, WORKSPACE, CONSOLE_META, CONSOLE_BODY, SETTINGS, UNKNOWN }
  enum class ConflictChoice { LOCAL, REMOTE }
  enum class SyncEntityAction { UPSERT, DELETE }   // 供 Task 5 的变更计划用
  enum class SyncFailure { NOT_CONFIGURED, GIT_MISSING, WRONG_PASSPHRASE, PUSH_REJECTED, GIT_FAILED, IO_FAILED }
  data class SyncOutcome(
      val ok: Boolean, val failure: SyncFailure? = null, val detail: String? = null,
      val stats: SyncStats = SyncStats(), val conflicts: List<ConflictItem> = emptyList(),
      val skippedConsoles: Int = 0,
  )
  ```

- [ ] **Step 1: 写失败测试（路径往返 + 无关文件）**

```kotlin
package sync

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncPathsTest {
    private val tree: Path = Path.of("/tmp/repo")
    private val root: Path = SyncPaths.appRoot(tree, "db-k")

    @Test
    fun `entity paths roundtrip through parse`() {
        val cases = listOf(
            SyncPaths.folderFile(root, "f1") to SyncRef.Folder("f1"),
            SyncPaths.connectionFile(root, "c1") to SyncRef.Connection("c1"),
            SyncPaths.workspaceFile(root, "w1") to SyncRef.Workspace("w1"),
            SyncPaths.consoleMetaFile(root, "s1") to SyncRef.ConsoleMeta("s1"),
            SyncPaths.consoleBodyFile(root, "s1") to SyncRef.ConsoleBody("s1"),
            SyncPaths.settingFile(root, "themes.json") to SyncRef.Setting("themes.json"),
        )
        for ((file, expected) in cases) assertEquals(expected, SyncPaths.parse(root, file), file.toString())
    }

    @Test
    fun `infra files are not entities`() {
        listOf(SyncPaths.saltFile(root), SyncPaths.checkFile(root), SyncPaths.metaFile(root))
            .forEach { assertNull(SyncPaths.parse(root, it)) }
        assertNull(SyncPaths.parse(root, root.resolve("README.md")))
        assertNull(SyncPaths.parse(root, root.resolve("unknown.json")))
        assertNull(SyncPaths.parse(root, tree.resolve("api-x/collections/x.json")))
    }

    @Test
    fun `app roots are isolated`() {
        assertTrue(SyncPaths.appRoot(tree, "db-k") != SyncPaths.appRoot(tree, "api-x"))
    }
}
```

第二个测试文件 `SyncModelsTest`：断言 `SyncSnapshot(connections = emptyList()).isEmpty()` 为 true、
含任一连接/folder/console 为 false；断言 `ConnectionSync` 序列化时 `formatVersion` 与 `id` 必出现、
`Json { prettyPrint = true; encodeDefaults = false; ignoreUnknownKeys = true }` 下往返相等。

- [ ] **Step 2: 跑测试确认失败**

Run: `… gradle test --tests 'sync.SyncPathsTest' --tests 'sync.SyncModelsTest'`
Expected: 编译失败。

- [ ] **Step 3: 实现**

`parse` 用 `root.relativize(file)` 的安全写法：文件不在 `root` 下直接 null；按父目录名 +
文件名后缀判定（`connections/<id>.enc`、`consoles/<id>.sql` / `consoles/<id>.meta.json`、
`folders/<id>.json`、`workspaces/<id>.json`、`settings/<name>`）。`syncJson` 集中定义：
`Json { prettyPrint = true; encodeDefaults = false; ignoreUnknownKeys = true }`（`internal val`，供后续任务复用）。

- [ ] **Step 4: 跑测试确认通过**

Run: `… gradle test --tests 'sync.SyncPathsTest' --tests 'sync.SyncModelsTest'`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/sync/SyncPaths.kt src/main/kotlin/sync/SyncModels.kt \
        src/test/kotlin/sync/SyncPathsTest.kt src/test/kotlin/sync/SyncModelsTest.kt
git commit -m "feat: 同步仓库路径约定与快照模型"
```

---

### Task 3: GitRepo（系统 git 封装）

**Files:**
- Create: `src/main/kotlin/sync/GitRepo.kt`
- Test: `src/test/kotlin/sync/GitRepoTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class GitRepo(val workTree: java.nio.file.Path) {
      data class GitResult(val exit: Int, val out: String, val err: String) { val ok: Boolean get() = exit == 0 }
      companion object { fun isAvailable(): Boolean; fun version(): String? }
      fun run(vararg args: String): GitResult
      fun init(): GitResult
      fun setLocalIdentity(name: String = "db-k", email: String = "db-k@localhost"): GitResult
      fun clone(remote: String, branch: String): GitResult
      fun hasCommits(): Boolean
      fun add(paths: List<String>): GitResult
      fun commit(message: String): Boolean            // false = 无可提交内容
      fun fetch(remote: String, branch: String): GitResult
      fun merge(ref: String, allowUnrelated: Boolean = false): GitResult
      fun mergeInProgress(): Boolean
      fun mergeAbort(): GitResult
      fun conflictedPaths(): List<String>             // 仅暂存区有 1/2/3 阶段的路径
      fun stageBlob(stage: Int, path: String): ByteArray   // git show :<stage>:<path>
      fun writeFile(relativePath: String, bytes: ByteArray)
      fun deleteFile(relativePath: String)
      fun push(remote: String, branch: String): GitResult
      fun setRemote(remote: String, url: String): GitResult
      fun currentBranch(): String
      fun resetHard(ref: String): GitResult
      fun logOneLine(): List<String>
  }
  ```

- [ ] **Step 1: 写失败测试（本地裸仓做远端，不联网）**

```kotlin
package sync

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitRepoTest {
    @TempDir lateinit var dir: Path

    private fun git(vararg args: String): GitRepo.GitResult =
        GitRepo(dir).run(*args)   // 注意：以下直接用 ProcessBuilder 建裸仓更简单

    private fun bare(name: String): Path {
        val p = dir.resolve(name)
        val r = ProcessBuilder("git", "init", "--bare", p.toString())
            .redirectErrorStream(true).start()
        r.inputStream.readBytes()
        assertEquals(0, r.waitFor())
        return p
    }

    @Test
    fun `init commit push clone and merge conflict detection`() {
        assumeTrue(GitRepo.isAvailable())
        val remote = bare("remote.git")
        val a = GitRepo(dir.resolve("a")).apply {
            init(); setLocalIdentity(); setRemote("origin", remote.toString())
        }
        a.writeFile("db-k/a.txt", "one".toByteArray())
        assertTrue(a.add(listOf("db-k")).ok)
        assertTrue(a.commit("first"))
        assertTrue(a.push("origin", "main").ok)

        val b = GitRepo(dir.resolve("b"))
        assertTrue(b.clone(remote.toString(), "main").ok)
        assertEquals(listOf("one"), b.logOneLine().map { it.substringAfter(' ') }.take(1))

        b.writeFile("db-k/a.txt", "theirs".toByteArray())
        b.add(listOf("db-k")); b.commit("b edit")
        a.writeFile("db-k/a.txt", "ours".toByteArray())
        a.add(listOf("db-k")); a.commit("a edit")

        assertTrue(a.fetch("origin", "main").ok)
        assertFalse(a.merge("origin/main").ok)       // 冲突 → 非 0
        assertTrue(a.mergeInProgress())
        assertEquals(listOf("db-k/a.txt"), a.conflictedPaths())
        assertEquals("ours", String(a.stageBlob(2, "db-k/a.txt")))
        assertEquals("theirs", String(a.stageBlob(3, "db-k/a.txt")))

        a.writeFile("db-k/a.txt", String(a.stageBlob(3, "db-k/a.txt")).toByteArray())
        assertTrue(a.add(listOf("db-k/a.txt")).ok)
        assertTrue(a.commit("resolve with remote"))
        assertFalse(a.mergeInProgress())
    }

    @Test
    fun `commit returns false when nothing changed`() {
        assumeTrue(GitRepo.isAvailable())
        val g = GitRepo(dir.resolve("empty")).apply { init(); setLocalIdentity() }
        g.writeFile("db-k/x.txt", "x".toByteArray()); g.add(listOf("db-k")); g.commit("first")
        assertFalse(g.commit("second"))
    }

    @Test
    fun `chinese paths and files are handled`() {
        assumeTrue(GitRepo.isAvailable())
        val g = GitRepo(dir.resolve("cn")).apply { init(); setLocalIdentity() }
        g.writeFile("db-k/控制台/一.sql", "select 1;".toByteArray())
        assertTrue(g.add(listOf("db-k")).ok)
        assertTrue(g.commit("中文路径"))
        assertTrue(g.logOneLine().any { it.contains("中文路径") })
    }
}
```

第 1 步里断言一律用 `GitResult.ok`；`GitRepoTest` 需要 `ProcessBuilder` 直接建裸仓（见 `bare()` 辅助）。

- [ ] **Step 2: 跑测试确认失败**

Run: `… gradle test --tests 'sync.GitRepoTest'`
Expected: 编译失败。

- [ ] **Step 3: 实现**

要点：
- 全部命令 `ProcessBuilder("git", "-c", "core.quotepath=false", *args).directory(workTree.toFile())`，
  stdin 关掉（`redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))` 不可跨平台 → 用
  `process.outputStream.close()`），stdout/stderr 用 UTF-8 读，合并到两个 stream 字段。
- `commit(message)`：先 `git diff --cached --quiet`，退出码 1 才 `git -c user.name=… -c user.email=… commit -m`；
  返回是否真的产生提交。
- `init()`：`git init -b main`（git ≥2.28）；失败回落 `git init` + `symbolic-ref HEAD refs/heads/main`。
- `hasCommits()`：`git rev-parse --verify HEAD` 退出码。
- `clone(remote, branch)`：在 `workTree.parent` 下执行 `git clone --branch <branch> <remote> <workTree>`；
  若 `workTree` 已存在且非空 → 返回 exit=1。
- `conflictedPaths()`：`git diff --name-only --diff-filter=U`。
- `mergeInProgress()`：`workTree.resolve(".git/MERGE_HEAD")` 存在。
- `stageBlob(stage, path)`：`git show :<stage>:<path>` 拿字节（二进制安全：`out` 字段改造成
  `runBytes` 私有方法，`GitResult.out` 仍是 UTF-8 文本，blob 走 `runRaw`）。
- `writeFile`：`Files.createDirectories(parent)` + `ATOMIC_MOVE` 写。
- 任何方法都不抛异常（除 `writeFile` IO），失败信息放 `GitResult.err`。

- [ ] **Step 4: 跑测试确认通过**

Run: `… gradle test --tests 'sync.GitRepoTest'`
Expected: PASS（git 缺失时 SKIP）。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/sync/GitRepo.kt src/test/kotlin/sync/GitRepoTest.kt
git commit -m "feat: 系统 git 命令封装"
```

---

### Task 4: SyncEngine 导出工作树（含 `.enc` 去抖与删除）

**Files:**
- Create: `src/main/kotlin/sync/SyncEngine.kt`
- Test: `src/test/kotlin/sync/SyncEngineExportTest.kt`

**Interfaces:**
- Consumes: `SecretBox`、`SyncPaths`、`SyncModels`、`GitRepo`
- Produces:
  ```kotlin
  interface SyncAdapter {
      fun exportSnapshot(): SyncSnapshot
      fun applySnapshot(snapshot: SyncSnapshot, removed: RemovedIds): Int   // 返回跳过（孤儿）控制台数
  }

  class SyncEngine(
      private val app: String,
      private val adapter: SyncAdapter,
      private val git: GitRepo,
      private val workTree: java.nio.file.Path,
  ) {
      fun root(): java.nio.file.Path
      fun loadOrCreateSalt(): ByteArray
      fun loadOrCreateCheck(key: javax.crypto.SecretKey): Boolean   // false = 已有 check 且口令不符
      fun writeTree(key: javax.crypto.SecretKey): SyncStats          // 导出快照到工作树
  }
  ```

- [ ] **Step 1: 写失败测试**

```kotlin
package sync

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncEngineExportTest {
    @TempDir lateinit var dir: Path

    private val salt = ByteArray(16) { 7 }
    private val key = SecretBox.deriveKey("pw", salt)

    private class FakeAdapter(var snapshot: SyncSnapshot) : SyncAdapter {
        override fun exportSnapshot() = snapshot
        override fun applySnapshot(snapshot: SyncSnapshot, removed: RemovedIds) = 0
    }

    private fun engine(adapter: SyncAdapter, tree: Path = dir.resolve("repo")) =
        SyncEngine("db-k", adapter, GitRepo(tree), tree)

    private fun snapshot(vararg ids: String) = SyncSnapshot(
        connections = ids.map {
            ConnectionSync(id = it, name = "c-$it", dbType = "POSTGRES", host = "10.0.0.1",
                port = 5432, database = "db", user = "u", password = "secret-$it")
        },
        consoles = listOf(ConsoleSync(
            ConsoleMetaSync(id = "s1", connectionId = ids.firstOrNull() ?: "c1", name = "n"),
            "select 1;",
        )),
    )

    @Test
    fun `writes entities as separate files and encrypts connections`() {
        val e = engine(FakeAdapter(snapshot("c1", "c2")))
        val salt = e.loadOrCreateSalt()
        assertEquals(16, salt.size)
        assertContentEquals(salt, Files.readAllBytes(SyncPaths.saltFile(e.root())))
        val stats = e.writeTree(key)
        assertEquals(4, stats.written)   // 2 enc + 1 sql + 1 meta
        val root = e.root()
        assertTrue(Files.exists(SyncPaths.connectionFile(root, "c1")))
        assertFalse(Files.readString(SyncPaths.connectionFile(root, "c1")).contains("secret-c1"))
        assertEquals("select 1;", Files.readString(SyncPaths.consoleBodyFile(root, "s1")))
        // 明文可由引擎解密还原
        val plain = SecretBox.decrypt(key, Files.readString(SyncPaths.connectionFile(root, "c1")))
        assertTrue(String(plain).contains("\"host\": \"10.0.0.1\""))
    }

    @Test
    fun `unchanged connection is not rewritten (IV debounce)`() {
        val adapter = FakeAdapter(snapshot("c1"))
        val e = engine(adapter)
        e.writeTree(key)
        val first = Files.readString(SyncPaths.connectionFile(e.root(), "c1"))
        val stats = e.writeTree(key)
        assertEquals(0, stats.written)
        assertEquals(1, stats.unchanged)
        assertEquals(first, Files.readString(SyncPaths.connectionFile(e.root(), "c1")))
    }

    @Test
    fun `entities removed locally are deleted from tree`() {
        val adapter = FakeAdapter(snapshot("c1", "c2"))
        val e = engine(adapter)
        e.writeTree(key)
        adapter.snapshot = snapshot("c1")
        val stats = e.writeTree(key)
        assertEquals(1, stats.deleted)
        assertFalse(Files.exists(SyncPaths.connectionFile(e.root(), "c2")))
        assertTrue(Files.exists(SyncPaths.connectionFile(e.root(), "c1")))
    }

    @Test
    fun `salt and check survive tree rewrites`() {
        val e = engine(FakeAdapter(snapshot("c1")))
        val salt = e.loadOrCreateSalt()
        assertTrue(e.loadOrCreateCheck(key))
        e.writeTree(key)
        val stats = e.writeTree(key)
        assertEquals(0, stats.deleted)
        assertContentEquals(salt, e.loadOrCreateSalt())
        assertTrue(Files.exists(SyncPaths.checkFile(e.root())))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `… gradle test --tests 'sync.SyncEngineExportTest'`
Expected: 编译失败。

- [ ] **Step 3: 实现**

- `writeTree`：先 `loadOrCreateSalt` 元数据不动；按 id 排序遍历写文件。
  连接：`ConnectionSync` → JSON（`syncJson.encodeToString`）→ `SecretBox.encrypt` → 文本写；
  **先去抖**：目标文件存在且 `SecretBox.decrypt(key, 现有文本)` 的字节与新明文完全一致 → 计 `unchanged`、不写。
  解密失败（旧口令/损坏）→ 直接覆写（计 `written`）。
- 删除：枚举 `root/{folders,connections,workspaces,consoles,settings}` 下文件，凡是 `SyncPaths.parse`
  结果不在本次集合内 → 删除（`consoles/<id>.sql` 与 `<id>.meta.json` 各自判定）。绝不删
  `salt.bin` / `secret.check` / `meta.json` / `README.md`。
- `settings/*`：`liveTemplatesJson` → `settings/live-templates.json`；`keymap` → `settings/keymap.json`；
  `themesJson` → `settings/themes.json`。文本相同不写。
- 首次写树时写 `meta.json`（`SyncMeta(app = app)`）与 `README.md`（内容见 Task 17；此处可先写最小占位，
  但**不再覆写已存在文件**）。
- 统计：`written` / `deleted` / `unchanged`。

- [ ] **Step 4: 跑测试确认通过**

Run: `… gradle test --tests 'sync.SyncEngineExportTest'`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/sync/SyncEngine.kt src/main/kotlin/sync/SyncAdapter.kt \
        src/test/kotlin/sync/SyncEngineExportTest.kt
git commit -m "feat: 同步快照导出工作树与密文去抖"
```

---

### Task 5: 读取合并结果并生成导入计划

**Files:**
- Modify: `src/main/kotlin/sync/SyncEngine.kt`
- Modify: `src/main/kotlin/sync/SyncModels.kt`（如需 `SyncPlan`）
- Test: `src/test/kotlin/sync/SyncEngineReadTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class SyncPlan(
      val snapshot: SyncSnapshot, val removed: RemovedIds,
      val skippedConsoles: Int, val corruptFiles: List<String> = emptyList(),
  )
  // SyncEngine 新增
  fun readTree(key: javax.crypto.SecretKey): Result<SyncPlan>   // 失败 = 有 .enc 解不开/JSON 损坏
  fun apply(plan: SyncPlan): Int                                // 转调 adapter.applySnapshot，返回跳过数
  ```

- [ ] **Step 1: 写失败测试**

```kotlin
@Test
fun `read tree restores what was written`() {
    val e = engine(FakeAdapter(snapshot("c1", "c2")))
    e.writeTree(key)
    val plan = e.readTree(key).getOrThrow()
    assertEquals(setOf("c1", "c2"), plan.snapshot.connections.map { it.id }.toSet())
    assertEquals("secret-c1", plan.snapshot.connections.single { it.id == "c1" }.password)
    assertEquals("select 1;", plan.snapshot.consoles.single().body)
}

@Test
fun `removed ids computed from local snapshot`() {
    val adapter = FakeAdapter(snapshot("c1", "c2"))
    val e = engine(adapter)
    e.writeTree(key)
    // 模拟远端删掉 c2
    Files.delete(SyncPaths.connectionFile(e.root(), "c2"))
    val plan = e.readTree(key).getOrThrow()
    assertEquals(setOf("c2"), plan.removed.connections)
    assertEquals(setOf("c1"), plan.snapshot.connections.map { it.id }.toSet())
}

@Test
fun `orphan console is skipped and counted`() {
    val adapter = FakeAdapter(snapshot("c1").copy(
        consoles = listOf(
            ConsoleSync(ConsoleMetaSync(id = "s1", connectionId = "c1", name = "ok"), "a"),
            ConsoleSync(ConsoleMetaSync(id = "s2", connectionId = "gone", name = "orphan"), "b"),
        ),
    ))
    val e = engine(adapter)
    e.writeTree(key)
    val plan = e.readTree(key).getOrThrow()
    assertEquals(1, plan.skippedConsoles)
    assertEquals(listOf("s1"), plan.snapshot.consoles.map { it.meta.id })
}

@Test
fun `wrong key makes read fail instead of returning partial data`() {
    val e = engine(FakeAdapter(snapshot("c1")))
    e.writeTree(key)
    val other = SecretBox.deriveKey("other", ByteArray(16) { 7 })
    assertTrue(e.readTree(other).isFailure)
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `… gradle test --tests 'sync.SyncEngineReadTest'`
Expected: FAIL（`readTree` 未定义）。

- [ ] **Step 3: 实现**

- 先完整解析/解密（任一 `.enc` 失败 → `Result.failure(IllegalArgumentException)`，不返回半成品）。
- 采用「仓库为准 + 本地快照求差」：`removed` = `adapter.exportSnapshot()` 里存在、读出的快照里不存在的 id
  （四类各自求差；控制台同时出现在 `removed.consoles` 时其 `.sql` 一并算删除）。
- 孤儿过滤：控制台 `connectionId` 不在 `snapshot.connections` 中 → 移出快照并 `skippedConsoles++`。
- 设置：`settings/*.json` 存在才读；`keymap.json` 解析失败 → 视为空覆盖（并记入 `corruptFiles`）。
- `apply(plan)` = `adapter.applySnapshot(plan.snapshot, plan.removed)`。

- [ ] **Step 4: 跑测试确认通过**

Run: `… gradle test --tests 'sync.SyncEngineReadTest'`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/sync/SyncEngine.kt src/main/kotlin/sync/SyncModels.kt \
        src/test/kotlin/sync/SyncEngineReadTest.kt
git commit -m "feat: 读取合并结果并生成导入计划"
```

---

### Task 6: 推送流程（提交 + 推送 + 空库守卫）

**Files:**
- Modify: `src/main/kotlin/sync/SyncEngine.kt`
- Test: `src/test/kotlin/sync/SyncEnginePushTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  // SyncEngine 新增
  fun ensureRepo(remoteUrl: String, branch: String): SyncFailure?   // null = ok；负责 init + setRemote + 身份
  fun commitLocal(hostLabel: String): Boolean                     // add + commit；false = 无变更
  fun push(remote: String, branch: String): SyncOutcome
  fun isRemoteEmptyGuardTripped(): Boolean                        // 本地快照为空 && 树内已有连接文件
  /** 仅用于「克隆并导入」与测试准备；已有 .git 时返回失败。 */
  internal fun gitClone(remote: String, branch: String): SyncFailure?
  ```

- [ ] **Step 1: 写失败测试**

```kotlin
@Test
fun `push commits and updates remote`() {
    assumeTrue(GitRepo.isAvailable())
    val remote = bare("remote.git")
    val e = engine(FakeAdapter(snapshot("c1")), dir.resolve("work"))
    assertNull(e.ensureRepo(remote.toString(), "main"))
    e.writeTree(key)
    assertTrue(e.commitLocal("dev-a"))
    assertTrue(e.push("origin", "main").ok)
    val other = GitRepo(dir.resolve("clone")).apply { assertTrue(clone(remote.toString(), "main").ok) }
    assertTrue(java.nio.file.Files.exists(other.workTree.resolve("db-k/connections/c1.enc")))
}

@Test
fun `no changes produce no commit`() {
    val e = engine(FakeAdapter(snapshot("c1")), dir.resolve("work"))
    e.ensureRepo(bare("r2.git").toString(), "main")
    e.writeTree(key)
    assertTrue(e.commitLocal("dev-a"))
    assertFalse(e.commitLocal("dev-a"))
}

@Test
fun `push rejected when remote advanced`() {
    val remote = bare("r3.git")
    val a = engine(FakeAdapter(snapshot("c1")), dir.resolve("a"))
    a.ensureRepo(remote.toString(), "main"); a.writeTree(key); a.commitLocal("a"); assertTrue(a.push("origin", "main").ok)

    val b = engine(FakeAdapter(snapshot("c1")), dir.resolve("b"))
    assertNull(b.ensureRepo(remote.toString(), "main"))     // 空目录 + 已有远端
    // 直接 clone 场景由 Task 12 的 clone 流程覆盖；这里模拟落后一方的推送
    assertNull(b.gitClone(remote.toString(), "main"))
    b.writeTree(key)
    b.commitLocal("b")
    assertEquals(SyncFailure.PUSH_REJECTED, b.push("origin", "main").failure)
}

@Test
fun `empty local snapshot against non-empty tree trips guard`() {
    val e = engine(FakeAdapter(snapshot("c1")))
    e.writeTree(key)
    val empty = engine(FakeAdapter(SyncSnapshot()), e.root())
    assertTrue(empty.isRemoteEmptyGuardTripped())
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `… gradle test --tests 'sync.SyncEnginePushTest'`
Expected: FAIL。

- [ ] **Step 3: 实现**

- `ensureRepo`：`workTree/.git` 不存在 → `git init` + `setLocalIdentity`；`git remote get-url origin` 失败或与
  `remoteUrl` 不同 → `setRemote("origin", remoteUrl)`；`remoteUrl` 空 → `NOT_CONFIGURED`。
- `commitLocal(hostLabel)`：`git.add(listOf(app))`（只加本应用命名空间，避免误提交别的应用）+ `commit`
  消息 `"db-k sync: $hostLabel <ISO_INSTANT>"`。
- `push`：`git.push(remote, branch)`；stderr/out 含 `non-fast-forward` / `[rejected]` → `PUSH_REJECTED`；
  其它非 0 → `GIT_FAILED`。
- `isRemoteEmptyGuardTripped`：`adapter.exportSnapshot().isEmpty()` 且 `root()/connections` 下已有 `*.enc`。
- 测试辅助 `gitClone(remote, branch)`：`GitRepo(workTree).clone(...)`（把它做成 `SyncEngine` 的 internal 方法，
  供测试与 Task 12 的「克隆并导入」共用）。

- [ ] **Step 4: 跑测试确认通过**

Run: `… gradle test --tests 'sync.SyncEnginePushTest'`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/sync/SyncEngine.kt src/test/kotlin/sync/SyncEnginePushTest.kt
git commit -m "feat: 同步推送流程与空库守卫"
```

---

### Task 7: 拉取与冲突取舍

**Files:**
- Modify: `src/main/kotlin/sync/SyncEngine.kt`
- Test: `src/test/kotlin/sync/SyncEnginePullTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  sealed interface PullOutcome {
      data class Synced(val stats: SyncStats, val skippedConsoles: Int) : PullOutcome
      data class NeedResolution(val conflicts: List<ConflictItem>) : PullOutcome
      data class Failed(val failure: SyncFailure, val detail: String? = null) : PullOutcome
  }
  // SyncEngine 新增
  fun pull(remote: String, branch: String, key: SecretKey, passphraseOk: Boolean, hostLabel: String): PullOutcome
  fun resolveConflicts(choices: Map<String, ConflictChoice>, key: SecretKey, autoPush: Boolean): SyncOutcome
  fun abortMerge(): Unit
  ```
  冲突摘要必须脱敏：连接 `password` 永不进 `ConflictPreview`；SQL 预览只取前 5 行、单行截断到 120 字符。

- [ ] **Step 1: 写失败测试（真实 git + 临时裸仓）**

```kotlin
@Test fun `pull merges non-overlapping console edits without conflict`() {
    // A/B 各改不同控制台正文 → PullOutcome.Synced(stats.written > 0, skipped == 0)
    // 且 a.adapter.lastApplied!!.consoles 同时含 A 与 B 的正文
}

@Test fun `pull reports conflict for same connection changed on both sides`() {
    // PullOutcome.NeedResolution；conflicts.single().kind == CONNECTION
    // localBytes/remoteBytes 均可用 key 解密；local.host == "10.0.0.1"，remote.host == "10.0.0.2"
}

@Test fun `resolving with REMOTE writes remote version and converges`() {
    // resolveConflicts(mapOf(path to REMOTE), key, autoPush = true).ok
    // a.adapter.lastApplied 中该连接 == B 版本；B 再 pull → 无冲突、快照相同
}

@Test fun `delete on one side propagates when other side untouched`() {
    // B 删 c2 并推送；A 未改 → PullOutcome.Synced；a.adapter.lastRemoved.connections == setOf("c2")
}

@Test fun `conflict preview never contains password`() {
    // ConflictPreview 转字符串后不含 "password"、不含 "secret-c1"、不含 "secret-c2"
}

@Test fun `wrong passphrase stops before touching local state`() {
    // pull(key, passphraseOk = false, …) == PullOutcome.Failed(WRONG_PASSPHRASE)
    // 且 a.adapter.applyCalls == 0、工作树无新提交（git rev-parse HEAD 不变）
}
```

测试夹具：两个 `SyncEngine`（工作树 `a`/`b`）+ 一个裸仓；各自 FakeAdapter 持有可变快照；
`pull` 前先 `writeTree` + `commitLocal` + `push`。断言：
- 非重叠：`PullOutcome.Synced`，且 `a` 的 adapter 收到合并后快照（FakeAdapter 记录最后一次 `applySnapshot`）。
- 同连接冲突：`NeedResolution`，`conflicts.single().kind == CONNECTION`，`localBytes`/`remoteBytes` 都能解密；
  `preview` 文本不含 `"password"` 也不含实际密码值。
- 选 `REMOTE` 后：`resolveConflicts` 返回 ok；`a` 的 adapter 快照里该连接等于 `b` 的版本；`b` 拉取后双方一致。
- 删除传播：`b` 删掉连接并推送，`a` 未改动 → 拉取后 `a` 的 `removed.connections == {"c2"}`。
- 口令错：`Failed(WRONG_PASSPHRASE)` 且 `FakeAdapter.applyCalls == 0`（Review Focus 1）。

- [ ] **Step 2: 跑测试确认失败**

Run: `… gradle test --tests 'sync.SyncEnginePullTest'`
Expected: FAIL。

- [ ] **Step 3: 实现**

顺序（与 spec §9.3 一致）：
1. `passphraseOk == false` → `Failed(WRONG_PASSPHRASE)`，立即返回（不 export、不 merge）。
2. `mergeInProgress()` → `mergeAbort()`（上次遗留）。
3. `hasCommits()` 为 false → `fetch` + `resetHard("origin/$branch")`（全新空仓）。
4. 否则：`exportToTree` + `commitLocal`；**首次接入守卫**（`isRemoteEmptyGuardTripped()` 且默认分支无本地提交）时跳过该步。
5. `fetch`；`merge("origin/$branch", allowUnrelated = 无共同祖先)`：共同祖先检测用
   `git merge-base HEAD origin/<branch>` 退出码非 0 时才允许 unrelated。
6. 冲突：`conflictedPaths()` 过滤到 `root()` 前缀 → 构建 `ConflictItem`（`stageBlob(2)/(3)`，摘要按
   `SyncPaths.parse` 分支；控制台正文冲突时 `local/remote` 预览用 `ConflictPreview.Sql`；
   `.enc` 用解密后字段生成 `Connection` 预览；解密失败 → `Unknown(size)`）。
7. `resolveConflicts`：按 `choices[path]` 用对应 stage 内容写回（`writeFile`）→ `add` → `commit("db-k sync: resolve conflicts")`
   → `readTree` + `apply` → 可选 `push`。
8. 无冲突：`readTree` + `apply`（`apply` 内部单事务）→ 可选 `push` → `Synced(stats, skipped)`。

- [ ] **Step 4: 跑测试确认通过**

Run: `… gradle test --tests 'sync.SyncEnginePullTest'`
Expected: PASS（git 缺失时 SKIP）。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/sync/SyncEngine.kt src/test/kotlin/sync/SyncEnginePullTest.kt
git commit -m "feat: 拉取合并与冲突取舍流程"
```

---

### Task 8: 仓库 upsert / 批量导入 API

**Files:**
- Modify: `src/main/kotlin/db/ConnectionsRepository.kt`
- Create: `src/main/kotlin/db/SyncImportModels.kt`
- Test: `src/test/kotlin/db/SyncImportTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class SyncImportRequest(
      val folders: List<FolderRow> = emptyList(),
      val connections: List<ConnectionProfile> = emptyList(),
      val connectionUpdatedAt: Map<String, Long> = emptyMap(),
      val workspaces: List<WorkspaceRecord> = emptyList(),
      val workspaceConsoles: Map<String, List<String>> = emptyMap(),
      val consoles: List<ConsoleRecord> = emptyList(),
      val removedFolderIds: Set<String> = emptySet(),
      val removedConnectionIds: Set<String> = emptySet(),
      val removedWorkspaceIds: Set<String> = emptySet(),
      val removedConsoleIds: Set<String> = emptySet(),
  )
  // ConnectionsRepository 新增
  fun listAllConsoles(): List<ConsoleRecord>
  fun connectionUpdatedAt(): Map<String, Long>
  fun importSyncedState(req: SyncImportRequest)   // 单事务；控制台 .sql 文件删除也在此完成
  ```

- [ ] **Step 1: 写失败测试**

```kotlin
@Test
fun `import upserts folders connections workspaces consoles with fixed ids`() {
    open().use { repo ->
        repo.importSyncedState(SyncImportRequest(
            folders = listOf(FolderRow("f1", "prod", null, 3)),
            connections = listOf(newProfile(id = "c1").copy(password = "pw")),
            connectionUpdatedAt = mapOf("c1" to 1234L),
            workspaces = listOf(WorkspaceRecord("w1", "默认", true, 0, 99, "s1")),
            workspaceConsoles = mapOf("w1" to listOf("s1")),
            consoles = listOf(ConsoleRecord("s1", "c1", "脚本", "/ignored/ignored.sql")),
        ))
        assertEquals(listOf("f1"), repo.listFolders().map { it.id })
        assertEquals("pw", repo.getConnection("c1")!!.password)
        assertEquals(1234L, repo.connectionUpdatedAt()["c1"])
        assertEquals(listOf("s1"), repo.listAllConsoles().map { it.id })
        assertEquals(listOf("s1"), repo.listWorkspaceConsoleIds()["w1"])
    }
}

@Test
fun `import preserves caret closed and external flags`() {
    open().use { repo ->
        val id = repo.createConnection(newProfile())
        val console = repo.createConsole(id, "n")
        repo.setConsoleCaret(console.id, 5, 7)
        repo.importSyncedState(SyncImportRequest(
            connections = listOf(newProfile(id = id)),
            consoles = listOf(console.copy(name = "renamed", filePath = "/ignored.sql")),
        ))
        val after = repo.getConsole(console.id)!!
        assertEquals("renamed", after.name)
        assertEquals(5, after.caretStart); assertEquals(7, after.caretEnd)
    }
}

@Test
fun `removed ids delete rows and console files`() {
    open().use { repo ->
        val id = repo.createConnection(newProfile())
        val console = repo.createConsole(id, "n")
        val file = dir.resolve("consoles/${console.id}.sql")
        repo.writeConsoleContent(console.id, "x")
        assertTrue(java.nio.file.Files.exists(file))
        repo.importSyncedState(SyncImportRequest(
            removedConsoleIds = setOf(console.id), removedConnectionIds = setOf(id),
        ))
        assertNull(repo.getConsole(console.id))
        assertNull(repo.getConnection(id))
        assertFalse(java.nio.file.Files.exists(file))
    }
}

@Test
fun `import is idempotent`() {
    open().use { repo ->
        val req = SyncImportRequest(folders = listOf(FolderRow("f1", "prod")),
            connections = listOf(newProfile(id = "c1")))
        repo.importSyncedState(req); repo.importSyncedState(req)
        assertEquals(1, repo.listConnections().size); assertEquals(1, repo.listFolders().size)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `… gradle test --tests 'db.SyncImportTest'`
Expected: 编译失败。

- [ ] **Step 3: 实现**

- `importSyncedState` 事务顺序：`autoCommit=false` → 删除 `workspace_consoles`（按被删控制台/工作区）→
  删控制台行（非 external 顺带删文件）→ 删连接 → 删文件夹 → 删工作区 → upsert 文件夹 →
  upsert 连接（写入 `PasswordVault.encrypt`、`updated_at` 用 `connectionUpdatedAt[id] ?: now`）→
  upsert 工作区 + 成员 → upsert 控制台 → `commit`；异常 `rollback` + 原样抛出。
- upsert 统一用 `INSERT OR IGNORE` + `UPDATE`，避免改动未同步列（`caret_*`、`closed`、`external`、`created_at`）。
- 控制台 `filePath` 一律重算为 `consolesDir.resolve("<id>.sql")`；`external` 保持本机现值（新增行默认 0）。
- 工作区 `lastActiveConsoleId` 指向不存在的控制台 → 置 NULL（避免悬空引用）。
- 文件夹 `parentId` 指向被删/不存在的文件夹 → 置 NULL。

- [ ] **Step 4: 跑测试确认通过**

Run: `… gradle test --tests 'db.SyncImportTest'`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/db/ConnectionsRepository.kt src/main/kotlin/db/SyncImportModels.kt \
        src/test/kotlin/db/SyncImportTest.kt
git commit -m "feat: 同步导入的幂等 upsert 与批量删除"
```

---

### Task 9: 设置项（快捷键）纯编解码

**Files:**
- Modify: `src/main/kotlin/app/settings/KeymapPrefs.kt`
- Test: `src/test/kotlin/app/settings/KeymapSyncCodecTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  // KeymapPrefs 新增（纯函数，不碰文件）
  fun encodeOverrides(keymap: Keymap): Map<String, String>                       // 只写与默认不同的；解绑写 ""
  fun decodeOverrides(raw: Map<String, String>): Keymap                          // 无法解析的值忽略并保留默认
  ```

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun `encode only writes non-default commands`() {
    // keymap = Keymap.Default.rebind(ShortcutCommand.EXECUTE, 空列表) → encodeOverrides 只含 "execute"
    // keymap = Keymap.Default → encodeOverrides 为空
}
@Test fun `unbound command encodes as empty string and decodes back`() {
    // encodeOverrides(…)["execute"] == "" ; decodeOverrides(mapOf("execute" to "")).chordsOf(EXECUTE) == emptyList()
}
@Test fun `unknown command id and malformed chord are ignored`() {
    // decodeOverrides(mapOf("nope" to "CTRL+X", "execute" to "NOT+A+CHORD")) == Keymap.Default
}
@Test fun `roundtrip preserves overrides`() {
    // 任意非默认绑定 → encode → decode → chordsOf 相等
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `… gradle test --tests 'app.settings.KeymapSyncCodecTest'`
Expected: 编译失败。

- [ ] **Step 3: 实现**

把现有 `load()` 里的解析逻辑与 `save()` 里的编码逻辑抽成上面两个纯函数，`load/save` 改为调用它们
（行为不变，已有 `KeymapTest` 必须继续通过）。

- [ ] **Step 4: 跑测试确认通过**

Run: `… gradle test --tests 'app.settings.KeymapSyncCodecTest' --tests 'app.settings.KeymapTest'`
Expected: PASS（含原有用例）。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/app/settings/KeymapPrefs.kt \
        src/test/kotlin/app/settings/KeymapSyncCodecTest.kt
git commit -m "refactor: 快捷键覆盖项纯编解码，供同步复用"
```

---

### Task 10: DbKSyncAdapter —— 导出本地快照

**Files:**
- Create: `src/main/kotlin/app/sync/DbKSyncAdapter.kt`
- Test: `src/test/kotlin/app/sync/DbKSyncAdapterExportTest.kt`

**Interfaces:**
- Consumes: `ConnectionsRepository`（Task 8 的新增 API + 既有 `listFolders/listConnections/listWorkspaces/listWorkspaceConsoleIds/readConsoleContent`）、
  `sync.SyncAdapter/SyncSnapshot/*`。
- Produces:
  ```kotlin
  class DbKSyncAdapter(
      private val repo: ConnectionsRepository,
      private val dataDir: java.nio.file.Path,
  ) : SyncAdapter {
      override fun exportSnapshot(): SyncSnapshot
      override fun applySnapshot(snapshot: SyncSnapshot, removed: RemovedIds): Int   // Task 11 实现
  }
  ```
  映射规则：
  - `FolderRow` → `FolderSync`（原样）
  - `ConnectionProfile` → `ConnectionSync`（`dbType.name`、`DisplayZone.toDb`、`connectionUpdatedAt()[id] ?: 0`；
    **不含** `secret.key`，`password` 为明文，由引擎加密）
  - `WorkspaceRecord` + `listWorkspaceConsoleIds()[id]` → `WorkspaceSync`
  - `ConsoleRecord`（`external == false`）→ `ConsoleSync(meta, repo.readConsoleContent(id))`；
    `external == true` 一律不导出
  - 设置：`<dataDir>/live-templates.json`、`<dataDir>/themes.json` 原文读取（不存在 → null）；
    `<dataDir>/keymap.properties` → `KeymapSync`（Task 9 的纯函数）

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun `exports folders connections workspaces and console bodies`() {
    // 建 folder f1 / connection c1(folderId=f1) / console s1(正文 "select 1;") + workspace w1(含 s1)
    // snapshot.folders.single().parentId == null; snapshot.connections.single().dbType == "SQLITE"
    // snapshot.consoles.single().body == "select 1;"; snapshot.workspaces.single().consoleIds == listOf("s1")
}

@Test fun `external consoles are excluded`() {
    // createExternalConsole(...) 后 exportSnapshot().consoles 不含该 id
}

@Test fun `connection carries plaintext password and local updatedAt`() {
    // 建连接 password="pw" → snapshot.connections.single().password == "pw"
    // 且 updatedAt == repo.connectionUpdatedAt()[id]（> 0）
}

@Test fun `missing settings files export as null and keymap as empty`() {
    // 空 dataDir → liveTemplatesJson == null && themesJson == null
    // keymap!!.overrides.isEmpty()
}
```

夹具：`@TempDir` + `ConnectionsRepository(dir/app.db, dir/consoles)` 建数据；写
`dir/live-templates.json` / `dir/themes.json` / `dir/keymap.properties` 断言原文被带出。

- [ ] **Step 2: 跑测试确认失败**

Run: `… gradle test --tests 'app.sync.DbKSyncAdapterExportTest'`
Expected: 编译失败。

- [ ] **Step 3: 实现**

只做映射，不写任何文件；`applySnapshot` 先 `throw NotImplementedError()`（Task 11 替换）。

- [ ] **Step 4: 跑测试确认通过**

Run: `… gradle test --tests 'app.sync.DbKSyncAdapterExportTest'`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/app/sync/DbKSyncAdapter.kt src/test/kotlin/app/sync/DbKSyncAdapterExportTest.kt
git commit -m "feat: db-k 同步快照导出适配器"
```

---

### Task 11: DbKSyncAdapter —— 应用合并结果

**Files:**
- Modify: `src/main/kotlin/app/sync/DbKSyncAdapter.kt`
- Test: `src/test/kotlin/app/sync/DbKSyncAdapterApplyTest.kt`

**Interfaces:**
- Produces: `override fun applySnapshot(snapshot: SyncSnapshot, removed: RemovedIds): Int`
  （返回 `skippedConsoles`；文件写入失败只记 `Logger.error`，不抛）

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun `apply roundtrips export`() {
    // src repo 造数据 → snapshot → adapter2.applySnapshot(snapshot, RemovedIds())
    // repo2.listConnections() 的 id/name/host/port/database/user/password 与源一致
}

@Test fun `apply writes console bodies`() {
    // Files.readString(dataDir.resolve("consoles/s1.sql")) == "select 1;"
}

@Test fun `apply removes entities not in snapshot`() {
    // 本地有 c1/s1；applySnapshot(空快照, RemovedIds(connections=setOf("c1"), consoles=setOf("s1")))
    // repo.getConnection("c1") == null && !Files.exists(consoles/s1.sql)
}

@Test fun `apply keeps external consoles and caret`() {
    // 本地 external 控制台与 caret=5/7 → apply 后 external 仍 true、caret 仍 5/7
}

@Test fun `apply writes settings files`() {
    // snapshot.liveTemplatesJson="{\"fileVersion\":1,\"templates\":[]}" 等 → 三个文件内容/文件存在性符合预期
}

@Test fun `apply is idempotent`() {
    // 连续两次 apply → listConnections().size 不变、无异常
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `… gradle test --tests 'app.sync.DbKSyncAdapterApplyTest'`
Expected: FAIL。

- [ ] **Step 3: 实现**

- 构建 `SyncImportRequest`：`ConnectionProfile` 由 `ConnectionSync` 反向映射（`DbType.valueOf` 失败 → 跳过该连接并
  `Logger.warn`；`DisplayZone.fromDb`）；`ConsoleRecord.filePath = AppPaths.consoleFile(id)`（用注入的 `dataDir`
  拼 `consoles/<id>.sql`，**不使用** `AppPaths` 静态方法，以便测试隔离）。
- 调 `repo.importSyncedState(req)`。
- 事务提交后写控制台正文（内容与磁盘不同才写，`ConsoleFiles.write`）。
- 写设置文件：`live-templates.json` / `themes.json` 原文非 null 才写；`keymap` 非 null 时用 Task 9 的
  纯函数编码成 `Properties` 写 `keymap.properties`（空覆盖 → 删文件，与 `KeymapPrefs.save` 语义一致）。
- 返回 `skipped`（由引擎在 Task 5 已过滤，这里恒 0；保留返回值以便将来扩展）。

- [ ] **Step 4: 跑测试确认通过**

Run: `… gradle test --tests 'app.sync.DbKSyncAdapterApplyTest'`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/app/sync/DbKSyncAdapter.kt src/test/kotlin/app/sync/DbKSyncAdapterApplyTest.kt
git commit -m "feat: db-k 同步导入应用到本地库与文件"
```

---

### Task 12: SyncPrefs + SyncService + SyncState

**Files:**
- Create: `src/main/kotlin/app/sync/SyncPrefs.kt`, `src/main/kotlin/app/sync/SyncService.kt`,
  `src/main/kotlin/app/state/SyncState.kt`
- Test: `src/test/kotlin/app/sync/SyncPrefsTest.kt`, `src/test/kotlin/app/sync/SyncServiceTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class SyncSettings(
      val remoteUrl: String = "",
      val localDirName: String = "sync",          // 相对 dataDir，避免把绝对路径写进测试/配置
      val branch: String = "main",
      val passphrase: String? = null,
      val autoPushAfterPull: Boolean = true,
      val autoPushOnExit: Boolean = false,
      val lastSyncAtMs: Long = 0,
  )
  class SyncPrefs(private val file: java.nio.file.Path) {
      fun load(): SyncSettings
      fun save(settings: SyncSettings)            // 原子写 + POSIX 600
  }
  class SyncService(
      private val repo: ConnectionsRepository,
      private val dataDir: java.nio.file.Path,
      private val hostLabel: String = java.net.InetAddress.getLocalHost().hostName,
  ) {
      val state: SyncState
      suspend fun configure(settings: SyncSettings)         // 保存 + 刷新 configured
      suspend fun clearPassphrase(): Unit
      suspend fun cloneAndImport(passphrase: String, onPassphraseStored: (String) -> Unit = {}): SyncOutcome
      suspend fun pull(): PullOutcome
      suspend fun push(): SyncOutcome
      suspend fun resolveConflicts(choices: Map<String, ConflictChoice>): SyncOutcome
      suspend fun changePassphrase(old: String, new: String): SyncOutcome
  }
  class SyncState {
      var running by mutableStateOf(false)
      var configured by mutableStateOf(false)
      var passphraseSet by mutableStateOf(false)
      var lastResult by mutableStateOf<SyncOutcome?>(null)
      var conflicts by mutableStateOf<List<ConflictItem>>(emptyList())
  }
  ```

- [ ] **Step 1: 写失败测试**

```kotlin
// SyncPrefsTest
@Test fun `settings roundtrip and defaults`()          // save → load 相等；missing file → 全默认
@Test fun `malformed file falls back to defaults without throwing`()   // 写垃圾内容 → load() 不抛
@Test fun `saved file is not world readable on posix`() // assume posix；权限 == rw-------

// SyncServiceTest（注入 FakeAdapter 的 engine 工厂；用临时裸仓，git 缺失则 SKIP）
@Test fun `pull with wrong passphrase reports failure and leaves local untouched`() {
    // prefs.passphrase 已存但 secret.check 由另一口令生成
    // pull() == Failed(WRONG_PASSPHRASE)；repo.listConnections() 不变；state.running == false
}
@Test fun `pull stores passphrase only after success`() {
    // 首次 pull 传 passphrase=正确 → prefs.load().passphrase == 该值；
    // 传错口令 → prefs.load().passphrase 仍为 null
}
@Test fun `clearPassphrase removes it from prefs`()      // clearPassphrase() 后 prefs.load().passphrase == null
@Test fun `running flag resets even when action fails`()  // 网络/路径错误 → finally 复位
```

`SyncService` 构造需要一个可替换的引擎工厂，签名固定为：
`internal fun engineFactory: (SyncSettings) -> SyncEngine`（默认实现读 `SyncPrefs` + `DbKSyncAdapter`）；
测试注入 FakeAdapter 版。

- [ ] **Step 2: 跑测试确认失败**

Run: `… gradle test --tests 'app.sync.SyncPrefsTest' --tests 'app.sync.SyncServiceTest'`
Expected: 编译失败。

- [ ] **Step 3: 实现**

- `SyncPrefs`：`Properties` 键名 `remoteUrl/localDir/branch/passphrase/autoPushAfterPull/autoPushOnExit/lastSyncAtMs`；
  `save` 用 tmp + `ATOMIC_MOVE`，随后 `Files.setPosixFilePermissions` 600（非 POSIX 忽略）。
- `SyncService`：`Mutex` 串行；每个动作 `withContext(Dispatchers.IO)`；`state.running` 在
  `try/finally` 中复位；口令校验失败**不**保存口令；成功 `pull` 后写 `lastSyncAtMs`。
  每个动作开始前调 `ConsoleState.flushAllSync()`（编辑器 buffer 落盘，spec §9.2）。
  `pull()` 成功后若 `settings.autoPushAfterPull` → 再调 `engine.push(...)`（失败只记结果，不回滚导入）。
- `pull()` 内部：读 prefs → 需要口令时用已记住的；无口令 → `Failed(WRONG_PASSPHRASE)` 并让 UI 提示输入；
  `NeedResolution` 时把 `conflicts` 写入 `state` 并保留引擎实例（`pendingPull`）供 `resolveConflicts` 继续。
- `changePassphrase(old, new)`：先用旧口令校验 `secret.check` → 失败 `WRONG_PASSPHRASE`；成功后
  `loadOrCreateCheck(newKey)` 重写 + `writeTree(newKey)` + `commitLocal` + `push`。

- [ ] **Step 4: 跑测试确认通过**

Run: `… gradle test --tests 'app.sync.SyncPrefsTest' --tests 'app.sync.SyncServiceTest'`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/app/sync/SyncPrefs.kt src/main/kotlin/app/sync/SyncService.kt \
        src/main/kotlin/app/state/SyncState.kt src/test/kotlin/app/sync/SyncPrefsTest.kt \
        src/test/kotlin/app/sync/SyncServiceTest.kt
git commit -m "feat: 同步配置、串行服务与状态模型"
```

---

### Task 13: i18n 文案（zh + en）

**Files:**
- Modify: `src/main/kotlin/i18n/Str.kt`
- Modify: `src/main/kotlin/i18n/CatalogZh.kt`
- Modify: `src/main/kotlin/i18n/CatalogEn.kt`
- Test: 现有 `src/test/kotlin/i18n/I18nCatalogTest.kt`（无需新增断言，跑通即可）

**Interfaces:**
- Produces: spec §11.3 列出的全部 key（一个不漏），以及 `SyncFailure` 对应的错误文案：
  `SyncErrorNotConfigured`、`SyncErrorGitMissing`、`SyncErrorWrongPassphrase`、`SyncErrorPushRejected`、
  `SyncErrorGitFailed`、`SyncErrorIoFailed`；冲突实体类型徽章：`SyncKindConnection`、`SyncKindFolder`、
  `SyncKindWorkspace`、`SyncKindConsoleMeta`、`SyncKindConsoleBody`、`SyncKindSettings`。

- [ ] **Step 1: 先跑现有 i18n 测试确认基线**

Run: `… gradle test --tests 'i18n.*'`
Expected: PASS。

- [ ] **Step 2: 加 key 与双语原文**

`Str.kt` 枚举按现有分组风格追加；中文与英文都写（英文避免直译腔，保持设置窗口味）。

- [ ] **Step 3: 跑测试确认通过**

Run: `… gradle test --tests 'i18n.*'`
Expected: PASS（占位符一致、非空、无遗漏）。

- [ ] **Step 4: 提交**

```bash
git add src/main/kotlin/i18n/Str.kt src/main/kotlin/i18n/CatalogZh.kt src/main/kotlin/i18n/CatalogEn.kt
git commit -m "feat: 云同步中英文案"
```

---

### Task 14: 设置窗口「云同步」分区

**Files:**
- Create: `src/main/kotlin/app/dialog/SyncSection.kt`
- Modify: `src/main/kotlin/app/dialog/SettingsDialog.kt`（新增第 4 个导航项 + 注入 `syncPanel` 参数）
- Modify: `src/main/kotlin/app/core/Main.kt`（构造 `SyncState`/`SyncService`，把 `syncPanel` 传进设置窗）
- Test: `src/test/kotlin/app/dialog/SyncSectionLogicTest.kt`（纯函数）

**Interfaces:**
- Consumes: `SyncState`、`SyncService`、Task 13 的 key。
- Produces:
  ```kotlin
  // SyncSection.kt 中的纯逻辑（可单测）
  data class SyncStatusText(val key: Str, val args: List<Any> = emptyList())
  fun relativeLocalDirLabel(dir: java.nio.file.Path, dataDir: java.nio.file.Path): String   // 相对 dataDir 时返回 "sync"，否则绝对路径
  fun syncStatusText(state: SyncState): SyncStatusText?   // null = 还没同步过；失败 → 对应错误 key；成功 → SyncResultSummary
  @Composable fun SyncPanel(state: SyncState, actions: SyncActions, theme: ThemeSpec, language: Lang)
  data class SyncActions(
      val onSaveSettings: (SyncSettings) -> Unit, val onClone: () -> Unit,
      val onPull: () -> Unit, val onPush: () -> Unit, val onClearPassphrase: () -> Unit,
  )
  // SettingsDialog 新增参数
  syncPanel: @Composable () -> Unit = {}
  ```

- [ ] **Step 1: 写失败测试（纯函数）**

```kotlin
@Test fun `local dir inside data dir is shown as relative name`()     // == "sync"
@Test fun `local dir outside data dir is shown absolute`()            // == "/data/other"
@Test fun `status key maps failures to error keys and success to summary`() {
    // lastResult = SyncOutcome(ok = false, failure = GIT_MISSING) → SyncStatusText(SyncErrorGitMissing, [])
    // lastResult = SyncOutcome(ok = true, stats = SyncStats(written = 2, deleted = 1)) → 按 SyncResultSummary 的占位符顺序给 args
}
@Test fun `no result yet yields null key`()                            // syncStatusText(...) == null
```

- [ ] **Step 2: 跑测试确认失败**

Run: `… gradle test --tests 'app.dialog.SyncSectionLogicTest'`
Expected: 编译失败。

- [ ] **Step 3: 实现**

布局按 spec §11.1：URL / 本地目录（TextField，失焦或回车保存）/ 分支 / 口令状态行 + 三个按钮
（克隆并导入、拉取、推送）/ 两个开关 / 一行密文提示。所有 `Text` 显式给 `MaterialTheme.colors.onSurface`
或 alpha 变体；按钮禁用条件：`running || !configured`。口令输入弹窗用 `FramelessDialog` + 单 `TextField`，
**Enter 提交**（`onPreviewKeyEvent`，参考 `app/dialog/TextInputDialogs.kt` 的 `submitOnEnter`）。

`Main.kt` 接线：创建 `syncState = remember { SyncState() }`、`syncService`；`rememberCoroutineScope()` 里调
`suspend` 动作；`SettingsDialog(..., syncPanel = { SyncPanel(...) })`。

- [ ] **Step 4: 跑测试 + 编译确认通过**

Run: `… gradle test --tests 'app.dialog.SyncSectionLogicTest' && … gradle compileKotlin`
Expected: PASS + 0 警告。

- [ ] **Step 5: 人工验收（不代跑 GUI）**

```
1) 打开 设置 → 云同步：字段/开关齐全；深浅色切换后无黑字沉底、无过曝白块。
2) 未填 URL 时「拉取/推送」禁用，有一行说明。
3) 中英各切一遍：无残留中文/英文，占位符无错位。
4) 单行口令输入框按 Enter 等同「确定」，Esc 关闭。
```

- [ ] **Step 6: 提交**

```bash
git add src/main/kotlin/app/dialog/SyncSection.kt src/main/kotlin/app/dialog/SettingsDialog.kt \
        src/main/kotlin/app/core/Main.kt src/test/kotlin/app/dialog/SyncSectionLogicTest.kt
git commit -m "feat: 设置窗口云同步分区"
```

---

### Task 15: 冲突取舍弹窗

**Files:**
- Create: `src/main/kotlin/app/dialog/SyncConflictDialog.kt`
- Modify: `src/main/kotlin/app/core/Main.kt`（`syncState.conflicts` 非空时显示弹窗，回调解 `resolveConflicts`）
- Test: `src/test/kotlin/app/dialog/SyncConflictLogicTest.kt`

**Interfaces:**
- Consumes: `ConflictItem`、`ConflictPreview`、`ConflictChoice`、`SyncService.resolveConflicts`。
- Produces:
  ```kotlin
  fun conflictLabel(item: ConflictItem, language: Lang): String        // 实体类型 + 人类可读名
  fun conflictPreviewLines(p: ConflictPreview, language: Lang): List<String>   // 摘要行（密码永不出现）
  fun allChosen(items: List<ConflictItem>, choices: Map<String, ConflictChoice>): Boolean
  @Composable fun SyncConflictDialog(
      items: List<ConflictItem>, theme: ThemeSpec, language: Lang,
      onConfirm: (Map<String, ConflictChoice>) -> Unit, onCancel: () -> Unit,
  )
  ```

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun `label uses entity kind and preview name`()
@Test fun `preview lines never contain password text`()          // Review Focus 3
@Test fun `all chosen only when every path has a choice`()
@Test fun `sql preview shows line count and head lines`()
```

- [ ] **Step 2: 跑测试确认失败**

Run: `… gradle test --tests 'app.dialog.SyncConflictLogicTest'`
Expected: 编译失败。

- [ ] **Step 3: 实现**

`FramelessDialog(title = t(Str.SyncConflictTitle), width = 860.dp, height = 560.dp, resizable = true)`；
左侧 `LazyColumn` 冲突项（kind 徽章 + 名称），右侧上下「本地 / 远端」摘要 + 两个 radio；
底部「取消」（= 放弃整次拉取，回滚到拉取前）/「确定」（`allChosen` 才可用）。
Esc 走 `onCloseRequest` = 取消语义。所有文字用主题色。

- [ ] **Step 4: 跑测试确认通过**

Run: `… gradle test --tests 'app.dialog.SyncConflictLogicTest' && … gradle compileKotlin`
Expected: PASS + 0 警告。

- [ ] **Step 5: 人工验收**

```
前置：两台设备各配一条同名同 id 的连接（或同机两个数据目录），都改同一个连接的不同字段。
1) A 推送；B 拉取 → 期望：弹冲突窗，列出该连接，两侧摘要显示 host/port/db/user，密码为 ****。
2) 不选任何一项时「确定」不可用。
3) 选「远端」并确定 → B 本地连接变为远端版本；再点推送/拉取，双方一致，无新冲突。
4) 重来一次，弹窗点「取消」→ 期望：本地数据未变，提示已取消。
5) 深浅色 + 中英各切一遍检查。
```

- [ ] **Step 6: 提交**

```bash
git add src/main/kotlin/app/dialog/SyncConflictDialog.kt src/main/kotlin/app/core/Main.kt \
        src/test/kotlin/app/dialog/SyncConflictLogicTest.kt
git commit -m "feat: 同步冲突取舍弹窗"
```

---

### Task 16: 克隆并导入 / 换口令 / 退出自动推送 / git 缺失

**Files:**
- Modify: `src/main/kotlin/app/sync/SyncService.kt`
- Modify: `src/main/kotlin/app/sync/SyncPrefs.kt`（如需要新字段）
- Modify: `src/main/kotlin/app/core/Main.kt`（`onCloseRequest` 里按 `autoPushOnExit` 触发；启动时探测 git）
- Test: `src/test/kotlin/app/sync/SyncServiceFlowTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  // SyncService 新增/完善
  suspend fun probeGit(): Boolean                     // false → state.gitAvailable = false
  suspend fun exitAutoPush(): Unit                    // 仅当 autoPushOnExit && configured && passphraseSet
  // SyncState 新增
  var gitAvailable: Boolean
  ```

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun `clone and import pulls remote tree into empty local db`() {
    // 远端已有 c1/s1；本地空 → cloneAndImport(正确口令).ok；
    // repo.listConnections().single().id == "c1"；prefs.passphrase == 口令
}
@Test fun `clone into directory that already has a git repo is refused`() {
    // 目标目录已有 .git → Failed(GIT_FAILED)；仓库内容未被改写
}
@Test fun `change passphrase with wrong old passphrase changes nothing`() {
    // changePassphrase("bad", "new") == Failed(WRONG_PASSPHRASE)；每个 .enc 文件字节不变
}
@Test fun `change passphrase rewrites every enc file and keeps data readable`() {
    // 成功后：所有 .enc 内容与之前不同；用新口令 readTree 能还原原连接；旧口令 readTree 失败
}
@Test fun `exit auto push does nothing when disabled or unconfigured`() {
    // autoPushOnExit=false → 远端 ref 不变；configured=false → 不抛异常
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `… gradle test --tests 'app.sync.SyncServiceFlowTest'`
Expected: FAIL。

- [ ] **Step 3: 实现**

- `cloneAndImport`：目录已有 `.git` → `Failed(GIT_FAILED, detail="already-initialized")`；否则 `git clone` →
  校验口令（`secret.check`）→ `readTree` → `apply` → 保存口令 + `lastSyncAtMs`。
- `changePassphrase`：见 Task 12 实现；额外断言 `.enc` 全部重写（比较前后文件内容不同）。
- `exitAutoPush`：`try { push() } catch` 只记日志（退出路径不阻塞、不弹窗）。
- `probeGit`：`GitRepo.isAvailable()`；`false` 时 UI 显示 `SyncErrorGitMissing` 并禁用动作。

- [ ] **Step 4: 跑测试确认通过**

Run: `… gradle test --tests 'app.sync.SyncServiceFlowTest'`
Expected: PASS（git 缺失时 SKIP）。

- [ ] **Step 5: 人工验收**

```
1) 新设备：设置里填远端 URL（空目录）+ 输入口令 → 点「克隆并导入」→ 期望：树/控制台/设置全部出现，
   连接密码可直接连接；重启后不再要求口令。
2) 改一次口令：旧口令输错 → 明确报错且仓库文件不变；输对 → 提交一次，另一台设备拉取时提示输入新口令。
3) 「退出时自动推送」打开后改一个控制台并退出应用 → 检查远端多一次提交；关闭该开关再退出 → 无新提交。
4) 在 PATH 找不到 git 的环境（可用 `PATH=/usr/bin` 之外的临时 shell 验证，由你决定是否做）→
   设置分区显示「未检测到 git」、按钮禁用、其它功能正常。
```

- [ ] **Step 6: 提交**

```bash
git add src/main/kotlin/app/sync/SyncService.kt src/main/kotlin/app/state/SyncState.kt \
        src/main/kotlin/app/core/Main.kt src/test/kotlin/app/sync/SyncServiceFlowTest.kt
git commit -m "feat: 克隆导入、换口令与退出自动推送"
```

---

### Task 17: README / .gitattributes / 文档与最终验证

**Files:**
- Modify: `src/main/kotlin/sync/SyncEngine.kt`（写 `README.md` 与根 `.gitattributes`，已存在不覆盖）
- Modify: `doc/DESIGN.md`（在持久化/设置相关段落加一段「云同步」指向 spec）
- Test: `src/test/kotlin/sync/SyncEngineInitTest.kt`

**Interfaces:**
- Produces: `ensureRepo` 首次初始化时写：
  - `<root>/README.md`：说明仓库由 db-k 生成、`*.enc` 是密文、控制台 SQL 是明文、冲突请回应用内处理。
  - `<workTree>/.gitattributes`：`*.enc -text`、`*.sql text eol=lf`（已存在则追加缺失行）。

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun `init writes readme and gitattributes once`()
@Test fun `existing gitattributes is extended not overwritten`()
```

- [ ] **Step 2: 跑测试确认失败**

Run: `… gradle test --tests 'sync.SyncEngineInitTest'`
Expected: FAIL。

- [ ] **Step 3: 实现**

- [ ] **Step 4: 跑测试确认通过**

Run: `… gradle test --tests 'sync.SyncEngineInitTest'`
Expected: PASS。

- [ ] **Step 5: 全量验证**

```bash
export JAVA_HOME=/home/zk/.sdkman/candidates/java/25.0.3-jbr
export PATH="$JAVA_HOME/bin:/home/zk/.sdkman/candidates/gradle/9.4.1/bin:$PATH"
gradle compileKotlin     # 期望：BUILD SUCCESSFUL，0 警告
gradle test              # 期望：全部通过（git 缺失时相关用例 SKIP）
gradle smokeJdbc         # 期望：通过（回归）
```

- [ ] **Step 6: 提交**

```bash
git add src/main/kotlin/sync/SyncEngine.kt src/test/kotlin/sync/SyncEngineInitTest.kt doc/DESIGN.md
git commit -m "feat: 同步仓库初始化说明文件与文档补充"
```

---

## 范围说明

本计划只覆盖 db-k 的 M1–M4。spec §16 的 M5（api-x 接入、抽共享模块）是另一份计划：
届时以本计划产出的 `sync/` 代码为蓝本，在新项目里实现 `ApiXSyncAdapter` 并跑同样的集成测试。

## 实施顺序说明

- Task 1–7 只碰 `sync/`，可以独立完成并全部单测（git 相关用例在无 git 环境 SKIP）。
- Task 8 是 db 层改动，必须早于 10/11。
- Task 9 必须先于 Task 10（adapter 导出/写回 keymap.properties 依赖纯编解码）。
- Task 12 必须早于 14/15/16（UI 与服务都依赖它）。
- Task 13 必须早于 14/15（UI 引用 `Str` key 才能编译）。
- Task 17 最后执行，含全量回归。

## 完成定义

- `gradle compileKotlin` 0 警告；`gradle test` 全绿；`gradle smokeJdbc` 通过。
- 设计文档 §11.3 的每个 key 都在 zh/en catalog 里存在且被 UI 使用（无死 key：`grep -c` 可核对）。
- 两台设备（或两个数据目录）走完 Task 14/15/16 的人工验收清单，用户确认通过。
- 未在日志/提交信息/冲突摘要中出现口令或明文密码。
