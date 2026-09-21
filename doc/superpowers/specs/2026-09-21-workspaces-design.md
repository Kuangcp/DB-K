# 工作区（Workspace）：控制台的虚拟分组

日期：2026-09-21
状态：设计已确认，待评审 spec

## 背景与动机

控制台越攒越多，标签条是「跨数据源的一长条」。用户在多个项目/任务之间切换时，
需要把当前一整套控制台收起来、把另一套拿出来，而不是在几十个标签里找。
左侧目录树只负责组织数据源（文件夹），不承担这个职责。

**工作区 = 控制台的分组上下文**（Chrome Tab Group 类比）：切换工作区 = 换一整条标签条。
工作区与左侧树无关，也不影响数据源归属。

## 已确认的需求决策（用户逐条确认）

1. **关闭的作用域按工作区**（选了 B 的简化实现）：
   - 新增 `workspaces` + `workspace_consoles` 成员表；**成员关系 = 该控制台是否显示在该工作区的标签条上**。
   - **「关闭控制台」= 从当前工作区移出成员**，不删 `consoles` 行、不删 `.sql`。
   - `consoles.closed` 列**退役**（迁移后 DROP）。
2. **控制台是共享实体**：正文、光标、结果、MRU 只有一份；工作区纯粹是虚拟分组。一个控制台可同时属于多个工作区。
3. **单击左树只选中**：不再触发「打开控制台」。双击数据源 = 连接（如需）+ 打开控制台；断开只走右键菜单。
4. **双击数据源 / 右键「打开控制台」= 加入当前激活工作区并聚焦**；若该控制台已在当前工作区，直接跳焦点过去。**不做**「移动到工作区」菜单。
5. **复用规则（双击数据源的快捷入口）**：
   - 当前工作区已有该数据源的控制台 → 激活其中最近使用的（MRU）。
   - 否则 → 取该数据源 `updated_at` 最大的控制台，加入当前工作区并激活。
   - 该数据源一个控制台都没有才新建。
6. **`+` 按钮 = 永远在当前工作区新建一个控制台**。
7. **工作区生命周期**：任意工作区可改名、可删除；删除只解除成员关系，控制台与 `.sql` 保留。
   **允许零工作区**；零工作区时「打开/新建控制台」会自动建一个工作区并承载它。
8. **默认工作区名跟随语言**：中文「默认」、英文「Default」。做法见 §3 的 `auto_named`。
9. **持久化**：`workspaces`/成员关系进 SQLite（要按它查询、要随实体级联）；「当前激活工作区」是应用级偏好，进 `<dataDir>/app.properties`。
10. **记忆范围**：Ctrl+Tab 在当前工作区内**跨数据源**循环；MRU 顺序**会话内**（重启后按标签顺序重建）；跨重启记住「上次激活的工作区」与「每个工作区上次激活的控制台」。
11. **排序**：列出顺序按 `sort_order`（新建排最后），**不做手工拖拽排序**。
12. **不做切工作区快捷键**（低频行为）。

## 终态数据模型

### SQLite（`AppDatabase` CURRENT_VERSION 9 → 10）

```sql
CREATE TABLE workspaces (
    id                     TEXT PRIMARY KEY NOT NULL,
    name                   TEXT NOT NULL,               -- auto_named=1 时占位（仅日志兜底），UI 渲染走 i18n
    auto_named             INTEGER NOT NULL DEFAULT 0,  -- 1 = 名字跟随语言（"默认"/"Default"）；用户改名后置 0
    sort_order             INTEGER NOT NULL DEFAULT 0,
    created_at             INTEGER NOT NULL,
    last_active_console_id TEXT NULL                    -- 该工作区上次激活的控制台
);
CREATE TABLE workspace_consoles (
    workspace_id TEXT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    console_id   TEXT NOT NULL REFERENCES consoles(id)   ON DELETE CASCADE,
    sort_order   INTEGER NOT NULL DEFAULT 0,            -- 加入顺序 = 标签顺序
    added_at     INTEGER NOT NULL,
    PRIMARY KEY (workspace_id, console_id)              -- 去重：同一控制台在一个工作区只出现一次
);
CREATE INDEX idx_ws_consoles_console ON workspace_consoles(console_id);
```

- `PRIMARY KEY (workspace_id, console_id)` 天然保证 add 幂等。
- `ON DELETE CASCADE` 保证：删工作区/删控制台/删连接（→ 删控制台）时成员自动清理。

### 领域模型

```kotlin
data class WorkspaceRecord(
    val id: String,
    val name: String,
    val autoNamed: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long = 0,
    val lastActiveConsoleId: String? = null,
)
```

`ConsoleRecord` 删除 `closed` 字段。

### `activeWorkspace`

`<dataDir>/app.properties` 新增 key `activeWorkspace`（新 `app/settings/WorkspacePrefs.kt`，与 `LanguagePrefs` 共用同一文件）。
值 = workspace id；不存在/失效时不写回、回落「第一个工作区」。

## 迁移（v10）

1. 建 `workspaces` + `workspace_consoles`。
2. 若存在 `closed=0` 的 `consoles` 行 → 建一个 `auto_named=1` 的工作区（名字取迁移时 `I18n.lang`，
   迁移发生在 `Main.kt:137` 设置语言之后，能取到正确语言），按 `connection_id, sort_order, created_at`
   顺序把这些控制台写入成员表（保持今天的标签顺序）。
   若没有任何未关闭控制台（全新用户）→ **不建工作区**（零工作区合法）。
3. `ALTER TABLE consoles DROP COLUMN closed`（SQLite 3.35+；该列无索引/约束，可安全 DROP）。

## 状态层设计（方案 1）

依赖方向单向：`Main → ConsoleState → WorkspaceState`。

### 新增 `app/state/WorkspaceState.kt`

- 状态：`workspaces`、`activeWorkspaceId`、`members: Map<workspaceId, List<consoleId>>`（有序）、
  会话内每工作区 MRU 顺序。
- CRUD：`createWorkspace(name: String?)`（`null` = auto_named）、`renameWorkspace(id, name)`（置 `autoNamed=false`）、
  `deleteWorkspace(id): Boolean`（返回是否删的是当前激活的）、`setActive(id)`（同步 `WorkspacePrefs`）、
  `ensureActive(): WorkspaceRecord`（零工作区时建「默认」并激活；否则返回当前/第一个）。
- 成员：`memberIds(wsId)`、`contains(wsId, consoleId)`、`addMember(wsId, consoleId)`（幂等，附到末尾）、
  `removeMember(wsId, consoleId)`、`removeConsoleEverywhere(consoleId)`。
- 记忆：`touch(wsId, consoleId)` + `orderedMembers(wsId)`（MRU，会话内）、
  `lastActiveConsoleOf(wsId)` / `setLastActive(wsId, consoleId?)`（落 `workspaces.last_active_console_id`）。
- 名字渲染辅助：`displayName(ws)`（`autoNamed` → `I18n.t(Str.WorkspaceDefaultName)`，否则 `ws.name`）。

### `ConsoleState` 改动

- 构造注入 `workspaces: WorkspaceState`。
- `openConsoles(profileId)` → `openConsolesInActiveWs(profileId)`；`allOpenConsoles(profileIds)` → `activeWsConsoles(profileIds)`。
- `activate(rec)`：额外 `workspaces.touch(currentWs, rec.id)` + `setLastActive(currentWs, rec.id)`。
- `activateForProfile(profileId)`（双击数据源 / 标题栏切换 / 右键打开 共用核心）：
  1. `ws = workspaces.ensureActive()`；
  2. ws 内该数据源已有成员 → 激活其中 MRU 最前的（无 MRU 则 `updated_at` 最大）；
  3. 否则 → 取该数据源 `updated_at` 最大的控制台（不管属哪个工作区），`addMember(ws, it)` 后激活；
  4. 该数据源无控制台 → `createConsole`（新建即入 ws）。
- `createConsole`：新建后自动 `addMember(ensureActive(), new)`。
- `closeConsole(id)`：`removeMember(currentWs, id)`；若关的是当前激活 → flush 后切到 ws 内 MRU 下一个，没有则 `activeConsoleId=null`（引导态）。
- `deleteConsole(id)`：`removeConsoleEverywhere(id)`；若删的是当前激活 → 在 ws 内切换。
- `onConnectionDeleted(profileId)`：对其下每个控制台 `removeConsoleEverywhere`。
- `switchConsoleByMru(delta)`：快照取 `workspaces.orderedMembers(activeWs)`（会话内 `mruCycleIds` 复用现有机制）。
- `restoreOnStartup(profileIds)`（取代 `activateMostRecent`）：取 `activeWorkspaceId`；有则激活其
  `last_active_console_id`（仍是成员且存在）→ 否则 ws 内 `updated_at` 最大的成员 → 否则 null。
- `onWorkspaceSwitched()`：先 flush 旧控制台正文/光标，再按上面规则激活新工作区的控制台；空工作区 → 引导态。
- `reopenConsole(id)` → `openConsoleInWorkspace(id)`：`ensureActive` + `addMember`（已在则跳过）+ `activate`。
- 删除已无用的 `lastActivePerProfile`（「每工作区上次激活」由 `WorkspaceState` 承担）。
- 结果/编辑 overlay（`runSlots`、`editBuffers`、`redisKeyMetas` 等）仍按 `consoleId` 存放，天然跨工作区共享，无需改动。

### 树右键「打开控制台 ▸」

- 候选仍为 `allConsoles(profileIds)`（该数据源**全部**控制台，含不属于任何工作区的）。
- 标 ✓ 的语义从「当前激活」改为「**已在当前工作区中**」。
- 点击任一项 = `openConsoleInWorkspace(id)`（加入当前工作区并激活）；尾随「新建控制台…」= `createConsoleFor`。

### `ConnectionsRepository` 改动

- 删除：`setConsoleClosed`；`listConsoles`/`getConsole`/`mapConsole` 去掉 `closed` 列。
- 新增：`listWorkspaces()`、`createWorkspace(name, autoNamed)`、`renameWorkspace(id, name)`、
  `deleteWorkspace(id)`、`setWorkspaceLastActiveConsole(wsId, consoleId?)`、
  `listWorkspaceConsoleIds()`（全量：`workspaceId → [consoleId]`，按 `sort_order, added_at`）、
  `addConsoleToWorkspace(wsId, consoleId)`（INSERT OR IGNORE + `sort_order = max+1`）、
  `removeConsoleFromWorkspace(wsId, consoleId)`。

### `Main` 编排

- 创建 `WorkspaceState`（`remember`），启动时 `load()` + `consoleState.restoreOnStartup(...)`。
- `selectWorkspace(id)`：`consoleState.beforeWorkspaceSwitch()` → `workspaces.setActive(id)` → `consoleState.onWorkspaceSwitched()`。
- `deleteWorkspace(id)`：`returned wasActive`；若激活 → 选剩余第一个 `setActive` + `onWorkspaceSwitched`，否则 `activeConsoleId=null`。
- `openConsoleForProfile(p) = scope.launch { connectionsState.ensureConnectionReady(p); consoleState.activateForProfile(p.id) }`。

## UI 设计

### 标签条（`ConsoleTabBar`）

- 右侧布局：`[…tabs…]  [ + ]  [ 工作区名 ▾ ]`。
- 下拉项：`名字 · N`（N = 该工作区控制台数；有未保存则名字前加语义色 ●），当前项高亮 + ✓；
  行悬停右侧浮出「重命名」「删除」两个 14dp 图标；底部固定「新建工作区…」。
- 零工作区：按钮显示灰色 `无工作区`，下拉只剩「新建工作区…」。
- 新建/重命名复用 `app/dialog/TextInputDialogs.kt` 单字段弹窗（自带 `submitOnEnter`）；
  删除复用 `ConfirmDialog`（`ConfirmDeleteConsoleTitle/Message` 同款写法，新增工作区专用文案）。
- 控制台 chip 右键菜单不变（重命名 / **关闭控制台** / 删除）；「关闭控制台」语义改为「从当前工作区移除」，标签文字不改。
- 主题：下拉/弹层/图标全部走 `MaterialTheme.colors.onSurface` 系；语义色仅用于 ●/✓ 背景。

### 引导态（`StarterPane`）

- 零工作区时提示语换成「打开或新建控制台会自动创建一个『默认』工作区」，并保留按数据源新建按钮。
- 有工作区但该工作区无控制台时，沿用现有「打开/新建控制台」引导。

### 参数传递（`SqlWorkspace`）

新增参数：`workspaces`、`activeWorkspace`、`onSelectWorkspace`、`onCreateWorkspace`、`onRenameWorkspace`、`onDeleteWorkspace`；
`consoles` 改为「当前工作区成员按标签顺序解析出的 `ConsoleRecord`」。

## 树点击改动（`DbTreeSidebar` + `Main`）

- `Main.selectRow`：去掉 `activateForProfile`，只 `treeState.select`。
- `DbTreeSidebar` 行手势：
  - `LOAD_MORE` 不变；
  - **双击 `CONNECTION` → `onActivateProfile()`**（无论已连/未连，都「确保连接 + 打开控制台」；不再触发断开）；
  - 双击可预览对象 → `onPreviewTable()` 不变；
  - 双击可展开行 → 展开/收起；其余单击 → `onSelect()`。
- 右键「打开控制台」与双击连接行共用 `Main.openConsoleForProfile`（都先 `ensureConnectionReady`）。
- 断开只保留右键菜单项。

## i18n（zh + en，全部新增 key）

| key | zh | en |
|---|---|---|
| `WorkspaceDefaultName` | 默认 | Default |
| `WorkspaceNoWorkspace` | 无工作区 | No workspace |
| `WorkspaceMenuTooltip` | 工作区 | Workspaces |
| `WorkspaceNew` | 新建工作区… | New workspace… |
| `WorkspaceNewTitle` | 新建工作区 | New workspace |
| `WorkspaceRenameTitle` | 重命名工作区 | Rename workspace |
| `WorkspaceDeleteTitle` | 删除工作区 | Delete workspace |
| `WorkspaceDeleteConfirm` | 删除工作区「{0}」？其中的控制台会保留，可从数据源右键重新打开。 | Delete workspace “{0}”? Its consoles are kept and can be reopened from the data source context menu. |
| `WorkspaceWithCount` | {0} · {1} | {0} · {1} |
| `StarterNoWorkspaceHint` | 打开或新建控制台会自动创建一个「默认」工作区。 | Opening or creating a console will automatically create a “Default” workspace. |
| `MainWorkspaceCreated` | 已创建工作区「{0}」 | Workspace “{0}” created |

`auto_named` 工作区的名字渲染统一走 `Str.WorkspaceDefaultName`（不在 UI 里用存储的 `name`）。

## 测试

- `AppDatabaseMigrationTest`：新增 v9→v10 用例（2 个未关闭 + 1 个已关闭控制台 → 建 1 工作区、2 条成员、
  `closed` 列已不存在、已关闭的不入区、标签顺序保持）；更新旧 v8 读 `closed` 的断言。
- 新增 `WorkspaceStateTest`：CRUD、`ensureActive` 零工作区建「默认」、成员 add 幂等/去重、
  `removeConsoleEverywhere`、MRU 顺序按工作区隔离、`setActive` 落 `app.properties`、`deleteWorkspace` 返回值。
- `ConsoleStateTest`：改掉引用 `closed` 的用例；新增「ws 内已有成员 → 取 MRU」「ws 无成员 → 取该源 `updated_at`
  最大并加入当前区」「该源无控制台 → 新建入区」「关闭只影响当前工作区」「删连接清空各工作区成员」
  「切工作区恢复 `last_active_console_id`」。
- 纯逻辑/DB 用 `gradle test` 自跑；UI 行为按 AGENTS 只给**人工验证步骤**，不跑 GUI 自动化。

## 人工验证步骤（交付时提供，由用户执行）

1. **迁移**：升级前有若干未关闭控制台 → 启动后标签条仍在，且切换器显示「默认」；已关闭的控制台不出现在标签条。
2. **切换**：新建工作区 W2 → W2 为空标签条；在 W2 双击数据源 D → 出现 D 的控制台；切回默认 → 看不到它（除非默认也有）。Ctrl+Tab 只在当前工作区循环。
3. **共享**：把同一控制台加进两个工作区（在两个工作区各双击同一数据源）→ 在任一处编辑，另一处内容一致。
4. **关闭**：在 W2 关闭某控制台 → 默认工作区里它仍在；从数据源右键「打开控制台」可重新加入。
5. **删除工作区**：删到零工作区 → 切换器显示「无工作区」、编辑区引导态；此时双击数据源 → 自动建「默认」并承载。
6. **单击只选中**：单击数据源不再打开/切换控制台；双击才连接并打开；断开仅右键。
7. **i18n / 深色**：中英各切一遍、深浅色各切一遍，检查下拉/弹窗/图标无残留文案、无黑字沉底。

## 明确不做

- 手工拖拽工作区排序、工作区固定/置顶。
- 「移动到工作区」菜单、工作区配色、工作区折叠。
- 切工作区快捷键。
- 工作区与左侧树文件夹的任何关联。
