# 对象树双击预览：改为「追加到该数据源最后控制台的光标处」

日期：2026-09-13
状态：已评审，待实现

## 背景与动机

当前左侧对象树双击表/视图（以及 Redis 键）时，`Main.previewObject` 的行为是：

- 若当前激活控制台与目标数据源相同**且正文为空** → 复用该控制台，`setText` 为预览 SQL；
- 否则**新建一个「控制台 N」**并 `setText`；
- 最后**自动执行**该预览语句。

问题：每次双击都在新建控制台，标签条很快被大量一次性控制台塞满；且预览 SQL 会覆盖当前控制台的整段草稿（空控制台复用语义其实也是覆盖）。

期望：双击时优先复用已有控制台，把预览 SQL **插入到该数据源最后打开控制台的光标/选区处**，不新建、不覆盖已有内容、不自动执行。

## 已确认的需求决策（用户确认）

1. **范围按数据源（connection profile）**：只在该源下找「已打开且最近激活」的控制台；没有则在该源下新建（沿用 `activateForProfile` 语义）。
2. **只追加不执行**：不自动运行；由用户自行 `Ctrl+Enter` 执行（与历史 SQL「插入到当前控制台」一致）。
3. **统一应用**：JDBC 表/视图/物化视图与 Redis 键预览走同一路径，行为一致。
4. 关掉的控制台不算「已打开」：若该源控制台全被关闭，则重开最近改动的那个（`activateForProfile` 已有逻辑），不新建。

## 现状关键代码

- `app/core/Main.kt:357` `previewObject(row)`：现行为（复用/新建 + setText + 自动 run）。
- `app/core/Main.kt:380` `ConsoleState.activateForProfile(profileId)`（`app/state/ConsoleState.kt:380`）：
  已实现「优先已打开的最近激活 → 全关闭则重开最近改动 → 都没有则新建 控制台 1」并激活，正好复用。
- `app/ui/SqlWorkspace.kt`：
  - `historyInsert`（`:244`）局部状态 + `LaunchedEffect(historyInsert)`（`:385`）已经在编辑器真实 `tfv` 的光标/选区处插入，不覆盖草稿，并把光标落到插入内容之后。
  - `insertSnippetAtCaret`（`:514`）为纯函数，已有单测 `src/test/kotlin/app/ui/InsertSnippetTest.kt`。
  - `HistoryPanel` 的「插入到当前控制台」在 `:489` 设置 `historyInsert`。
- 光标来源：`ConsoleState.caretOf(consoleId)`（内存草稿优先，重启后读 `consoles.caret_start/caret_end`）；切换控制台时 `SqlWorkspace` 用 `remember(consoleId)` 以该值初始化 `tfv` 选区（`:364`）。

## 设计

### 1. Main：改为选取目标控制台 + 请求插入

`previewObject` 改为：

```
校验 session / capabilities / obj.kind.isPreviewable()   // 不变
sql = session.previewQuery(row.schema, obj)
target = consoleState.activateForProfile(p.id)           // 复用「判断是否已有控制台」
if (session.capabilities.sessionContext && row.schema != null)
    consoleState.setTarget(target.id, row.schema.displayName)   // Redis DB 命名空间，不变
pendingInsert = sql                                      // 请求编辑器在光标处插入
```

删除：`reuse` 判断、`createConsole(p.id, name)` 分支、`setText`、新建提示 `toast`、末尾 `scope.launch { run(...) }`。
`sessionContext` 后 `run(fresh, p)` 一并删除；不再需要为刷新字段而重取 `fresh` 记录。

`suggestConsoleName` **保留**（`createConsoleFor` 仍在用，`Main.kt:452`）。

### 2. SqlWorkspace：把「待插入请求」提升为参数

签名新增三个参数：

- `insertRequest: String?` —— Main 持有的待插入文本（源：预览、历史 SQL）。
- `onInsertRequestConsumed: () -> Unit` —— 编辑器消费后清空，保证同一文本可再次触发。
- `onRequestInsert: (String) -> Unit` —— 供 `HistoryPanel`「插入到当前控制台」回填。

内部改动：

- 删除局部 `historyInsert`，改用 `insertRequest`。
- `LaunchedEffect(insertRequest)`：主体沿用现有逻辑（`insertSnippetAtCaret` → 更新 `tfv` → `onCaretChange` → `onTextChange`），结束时调 `onInsertRequestConsumed()`。
- `HistoryPanel` 的 `extraAction` 改为 `onRequestInsert(row.sqlText)`。

### 3. 插入落点语义

- 目标控制台发生切换时，`remember(consoleId)` 在组合期用 `caretOf(consoleId)` 初始化 `tfv` 选区，因此插入点就是「最后打开控制台记忆的光标/选区」。
- 目标控制台本就是当前控制台时，插入点是当前实时光标/选区。
- 有选区则替换选区，无则插入；仅当在行尾且紧邻非空白字符时补换行（`insertSnippetAtCaret` 既有行为）。
- 插入后光标落到插入内容之后，并触发脏标记/防抖自动保存。

### 4. 数据流

```
双击树行 (onPreviewTable)
  → Main.previewObject
      → consoleState.activateForProfile(p.id)   // 选/建/重开 + 激活
      → (sessionContext) setTarget
      → pendingInsert = sql
  → recomposition：activeConsole / insertRequest 更新
  → SqlWorkspace.LaunchedEffect(insertRequest)  // tfv 已是目标控制台光标
      → insertSnippetAtCaret
      → tfv / onCaretChange / onTextChange
      → onInsertRequestConsumed()（pendingInsert = null）
```

## 边界与不改动项

- **不自动执行**：无 run 调用；用户手动执行。
- **关闭的控制台**：由 `activateForProfile` 重开最近改动者，不新建。
- **无任何控制台**：`activateForProfile` 新建「控制台 1」，随后在其光标（空文本 = 0）处插入。
- **Redis**：同样追加预览命令；`sessionContext` 的 `setTarget` 保留。
- **连接不可用/不支持预览/对象类型不可预览**：校验与提示不变。
- 不改 `DdlTarget`、右键菜单、「打开控制台」等其它入口。

## 测试

- `insertSnippetAtCaret`：已有 `InsertSnippetTest` 覆盖（空文本、行尾、行中、选区替换、越界等），无需新增。
- `activateForProfile` 的选取/重开/新建语义：已有 `ConsoleStateTest` 覆盖，保持复用。
- 需人工验收（Compose 交互，本机合成点击不可用）：
  1. 空源双击表 → 新建控制台 1，光标处出现 `SELECT ... LIMIT 100`，不自动执行；
  2. 已有控制台且光标在文本中间双击表 → 在光标处插入，前后文本保留；
  3. 切到别的数据源后再回来双击该源对象 → 回到该源最后激活控制台；
  4. Redis 键双击 → 追加对应查看命令；
  5. 深色模式无文字/背景回归。

## 影响文件

- `app/core/Main.kt`：`previewObject` 重写；新增 `pendingInsert` 状态并传入 `SqlWorkspace`。
- `app/ui/SqlWorkspace.kt`：参数提升、`historyInsert` → `insertRequest`、`HistoryPanel` 回调。
- `TODO.md`：完成后删除对应条目。

## 明确不做（YAGNI）

- 不做「预览 SQL 自动执行」开关。
- 不做「插入后自动滚动到插入位置」（现有行为即可；如需另议）。
- 不改历史 SQL 的插入语义（保持只插入不执行）。
