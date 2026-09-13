# 对象树双击预览：追加到该数据源最后控制台光标处 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 双击对象树中的表/视图/Redis 键时，不再新建控制台或覆盖草稿，而是把预览 SQL/命令追加到该数据源「最后打开的控制台」的光标/选区处，且不自动执行。

**Architecture:** 复用 `ConsoleState.activateForProfile` 选取/重开/新建目标控制台；复用 `SqlWorkspace` 已有的「在编辑器真实 `tfv` 光标处插入」机制（`insertSnippetAtCaret`），把原来局部的 `historyInsert` 请求提升到 `Main` 持有的 `pendingInsert` 状态，由 `SqlWorkspace` 消费。预览路径只写 `pendingInsert`，删掉 `setText` 与自动 `run`。

**Tech Stack:** Kotlin 2.4.0 / Compose Multiplatform 1.12.0 / Gradle 9.4.1 / JDK 25（工具链见 AGENTS.md）。

## Global Constraints

- JDK：`JAVA_HOME=/home/zk/.sdkman/candidates/java/25.0.3-jbr`；Gradle：`/home/zk/.sdkman/candidates/gradle/9.4.1/bin`（不引入 wrapper）。构建前先 `source env-init`（或等价地 export 上述路径到 PATH）。
- 分层铁律：`jdbc/`、`redis/`、`engine/` 禁止 import compose/coroutines。本次改动只在 `app/ui`、`app/core`、`app/state`，符合。
- **所有 Kotlin warning 视为 error**（`allWarningsAsErrors = true`）：改动后不得留下未使用的 import / 变量。
- 主题：新增 UI 无；不改颜色。
- 不改控制台光标落库语义（`setCaret` 1.5s 防抖；切控制台/退出强制落库）。
- 验证命令：`gradle compileKotlin`、`gradle test`、`gradle smokeJdbc`。
- UI 合成点击在本机无效；最终交互为人工验收。

---

### Task 1: 把「待插入请求」提升到 Main（预览/历史共用同一状态）

**Files:**
- Modify: `src/main/kotlin/app/ui/SqlWorkspace.kt`（签名 + `historyInsert` 处理 + `HistoryPanel` 回调）
- Modify: `src/main/kotlin/app/core/Main.kt`（仅 `SqlWorkspace(...)` 调用点新增三个实参）

**Interfaces:**
- Consumes: 现有 `insertSnippetAtCaret(cur: String, selStart: Int, selEnd: Int, snippet: String): Pair<String, Int>`（`SqlWorkspace.kt:514`，不改）。
- Produces（`SqlWorkspace` 新参数，后续 Task 2 依赖）：
  - `insertRequest: String?` — 待插入文本；null = 无请求。
  - `onInsertRequestConsumed: () -> Unit` — 消费后回调（Main 置回 null）。
  - `onRequestInsert: (String) -> Unit` — 请求插入（历史面板用）。
- Produces（`Main` 状态，后续 Task 2 依赖）：`var pendingInsert: String?`。

- [ ] **Step 1: 修改 SqlWorkspace 签名，新增三个参数**

在 `src/main/kotlin/app/ui/SqlWorkspace.kt` 的 `onTextChange: (String) -> Unit,` 之后、`caretOf` 之前插入：

```kotlin
    onTextChange: (String) -> Unit,
    /** 待插入编辑器的文本（预览 / 历史 SQL）：在激活控制台光标/选区处插入，不覆盖草稿。 */
    insertRequest: String?,
    /** 编辑器消费 insertRequest 后回调清空，保证同一文本可再次触发。 */
    onInsertRequestConsumed: () -> Unit,
    /** 请求向编辑器插入文本（历史面板「插入到当前控制台」）。 */
    onRequestInsert: (String) -> Unit,
    /** 读某控制台上次的光标/选区（重启后恢复焦点所在行）。 */
    caretOf: (String) -> Pair<Int, Int>,
```

- [ ] **Step 2: 删除 SqlWorkspace 局部 historyInsert 状态**

删除（原 `SqlWorkspace.kt:243-244` 两行）：

```kotlin
    // 待插入的历史 SQL：在光标/选区处插入（不覆盖整段草稿），由激活控制台的编辑区消费
    var historyInsert by remember { mutableStateOf<String?>(null) }
```

- [ ] **Step 3: 把插入 effect 改为消费 insertRequest**

将 `LaunchedEffect(historyInsert) { ... }`（原 `SqlWorkspace.kt:385-397`）整体替换为：

```kotlin
        // 预览 / 历史 SQL 插入：在光标/选区处插入（有选区则替换之），不覆盖整段草稿；
        // 光标落到插入内容之后。在此处做是因为 tfv 是编辑器的权威状态。
        LaunchedEffect(insertRequest) {
            val snippet = insertRequest ?: return@LaunchedEffect
            onInsertRequestConsumed()
            val (newText, caret) = insertSnippetAtCaret(
                cur = tfv.text,
                selStart = tfv.selection.start,
                selEnd = tfv.selection.end,
                snippet = snippet,
            )
            tfv = TextFieldValue(newText, TextRange(caret))
            onCaretChange(consoleId, caret, caret)
            onTextChange(newText)
        }
```

- [ ] **Step 4: 历史面板改为回调请求插入**

将 `SqlWorkspace.kt` 中 `extraAction = if (activeConsole != null) { "插入到当前控制台" to { historyInsert = row.sqlText; historyView = null } } else null,`（原 `:488-493`）替换为：

```kotlin
            extraAction = if (activeConsole != null) {
                "插入到当前控制台" to {
                    onRequestInsert(row.sqlText)
                    historyView = null
                }
            } else null,
```

- [ ] **Step 5: Main 新增 pendingInsert 状态**

在 `src/main/kotlin/app/core/Main.kt` 的 `var resultsVisible by remember { mutableStateOf(true) }` 之后插入：

```kotlin

    // 待插入编辑器的文本（预览 / 历史 SQL）：在激活控制台光标/选区处追加，由 SqlWorkspace 消费后清空
    var pendingInsert by remember { mutableStateOf<String?>(null) }
```

- [ ] **Step 6: Main 的 SqlWorkspace 调用点传入新参数**

在 `SqlWorkspace(` 调用实参里、`onTextChange = { ... },` 之后（`Main.kt:651-654` 附近）插入：

```kotlin
                        insertRequest = pendingInsert,
                        onInsertRequestConsumed = { pendingInsert = null },
                        onRequestInsert = { pendingInsert = it },
```

- [ ] **Step 7: 编译**

Run: `source env-init && gradle compileKotlin`
Expected: `BUILD SUCCESSFUL`，无 warning-as-error。若报「未使用变量」检查是否漏删 `historyInsert` 残留。

- [ ] **Step 8: 跑单测（历史插入纯函数仍应在）**

Run: `source env-init && gradle test`
Expected: 全部 PASS（含 `InsertSnippetTest`）。

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/app/ui/SqlWorkspace.kt src/main/kotlin/app/core/Main.kt
git commit -m "refactor(ui): 编辑器插入请求提升到 Main，预览/历史共用"
```

---

### Task 2: 双击预览改为「目标控制台光标处追加 + 不自动执行」

**Files:**
- Modify: `src/main/kotlin/app/core/Main.kt`（`previewObject` 重写，`:357-394`）
- Modify: `TODO.md`（删除树区域第 35 行对应条目）

**Interfaces:**
- Consumes（Task 1 产出）：`Main.pendingInsert: String?`。
- Consumes（既有）：`ConsoleState.activateForProfile(profileId: String): ConsoleRecord?`（`ConsoleState.kt:380`）、`ConsoleState.setTarget(consoleId, target)`、`ConnectionSession.previewQuery(schema, obj)`、`capabilities.sessionContext` / `objectPreview`。
- Produces: 无新公开 API。

- [ ] **Step 1: 重写 previewObject**

将 `src/main/kotlin/app/core/Main.kt` 的 `previewObject`（从 `/** 双击对象 → 预览 ... */` 到该函数结束的 `}`）整体替换为：

```kotlin
    /**
     * 双击对象 → 预览（SQL 后端 = SELECT 前 100 行；Redis = 按 key 类型的查看命令）：
     * 追加到该数据源最后打开控制台的光标/选区处，不新建、不覆盖草稿、不自动执行。
     */
    fun previewObject(row: TreeRowInfo) {
        val p = row.profile ?: return
        val obj = row.dbObject ?: return
        val session = connectionsState.sessionOf(p.id)
        if (session == null || !session.capabilities.objectPreview) {
            toastState.show("当前连接不支持对象预览")
            return
        }
        if (!obj.kind.isPreviewable()) {
            toastState.show("该对象类型不支持预览")
            return
        }
        val sql = session.previewQuery(row.schema, obj)
        // 复用该数据源已有控制台（优先最近激活；全关闭则重开最近改动；都没有则新建 控制台 1）——
        // 不因双击而重复新建控制台。
        val target = consoleState.activateForProfile(p.id) ?: return
        // 会话型目标（多 schema）：预览对象的命名空间随之切换；
        // Redis DB 是连接级过滤器（flatNamespaceOf），执行目标与 console.target 解耦，不在此写
        if (!connectionsState.flatNamespaceOf(p.id) &&
            session.capabilities.sessionContext && row.schema != null
        ) {
            consoleState.setTarget(target.id, row.schema.displayName)
        }
        // 交给编辑器在目标控制台光标/选区处插入，不自动执行
        pendingInsert = sql
    }
```

- [ ] **Step 2: 确认无遗留未使用引用**

Run: `source env-init && grep -n "suggestConsoleName" src/main/kotlin/app/core/Main.kt`
Expected: 仅剩 `createConsoleFor`（`Main.kt:452` 附近）的定义与使用；`previewObject` 内不再引用。`suggestConsoleName` 保留，不删除。

- [ ] **Step 3: 编译**

Run: `source env-init && gradle compileKotlin`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 4: 单测 + JDBC 冒烟**

Run: `source env-init && gradle test && gradle smokeJdbc`
Expected: 均通过（`smokeJdbc` 走嵌入式 H2/SQLite，不受本 UI 改动影响，作为回归）。

- [ ] **Step 5: 删除 TODO 条目**

删除 `TODO.md` 树区域中的这一行：

```
- 双击表名，不要直接新建控制台，而是判断 是否已经有控制台了，然后selectlimit 100的SQL追加的逻辑是 选择 最后打开的控制台 的 光标位置  进行追加这个SQL。
```

- [ ] **Step 6: 人工验收（Compose 交互，必须真人操作）**

启动：`source env-init && export DISPLAY=:0.0 && gradle run`，逐条确认：

1. 某数据源无控制台时双击表 → 新建「控制台 1」，光标处出现 `SELECT ... LIMIT 100`，**不自动执行**；
2. 已有控制台、光标停在文本中间时双击表 → 在光标处插入，前后文本原样保留；
3. 切到另一个数据源后，回到原数据源双击对象 → 使用原数据源最后激活的控制台（不新建、不串源）；
4. 关闭该源全部控制台后双击对象 → 重开最近改动的控制台（`profileConsoles` 数量不增加）；
5. 历史面板「插入到当前控制台」仍正常（回归 Task 1 的提升）；
6. Redis 连接双击键 → 追加对应查看命令，不自动执行；
7. 深色模式无黑字沉底 / 过曝白块。

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/app/core/Main.kt TODO.md
git commit -m "feat(tree): 双击预览改为在该数据源最后控制台光标处追加，不自动执行"
```

---

## 说明：为什么没有新增自动化测试

本次改动的两个纯逻辑单元已被现有测试覆盖，且新行为本身是 Compose 组合期的状态编排（`activateForProfile` 选/建/重开 + `insertSnippetAtCaret` 光标插入），没有新的可独立单测的纯函数：

- `insertSnippetAtCaret`：`src/test/kotlin/app/ui/InsertSnippetTest.kt`（空文本/行尾/行中/选区替换/反向选区/越界）。
- `activateForProfile` 的选取/重开/新建语义：`src/test/kotlin/app/state/ConsoleStateTest.kt`（“双击数据源 → 重新打开最近改动的那个，不新建”）。

因此以既有单测 + `smokeJdbc` 回归 + 上表人工验收为准，不为 UI 编排强行造测试。

## Self-Review

- **Spec coverage**：决策 1（按数据源）→ Task 2 Step 1 `activateForProfile`；决策 2（只追加不执行）→ Task 2 Step 1 删除 `run`；决策 3（统一应用，含 Redis）→ 同一 `previewObject`，`sessionContext` 分支保留；决策 4（全关闭重开）→ 复用 `activateForProfile` + 验收第 4 条；插入落点/光标来源 → Task 1 Step 3 复用 `insertSnippetAtCaret` 与 `remember(consoleId)` 初始化选区。无遗漏。
- **Placeholder scan**：无 TBD/TODO/“自行处理”。
- **Type consistency**：`insertRequest: String?` / `onInsertRequestConsumed: () -> Unit` / `onRequestInsert: (String) -> Unit` / `pendingInsert: String?` 在 Task 1、Task 2 使用一致；`insertSnippetAtCaret` 参数与返回类型与现有实现一致。
