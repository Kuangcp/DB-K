# 外部 SQL 文件控制台（External SQL Console）

日期：2026-09-21
状态：设计已确认，待评审 spec

## 背景与动机

项目迭代常产出若干 `.sql` 文件（一次迭代一个/几个）。用户希望**直接把这个文件当控制台用**：
拖进来或选进来 → 选一个数据源 → 选中执行、切目标库、改内容自动写回原文件。
好处：
- 不用「复制文件内容 → 粘到 DB-K 控制台 → 执行」来回搬；
- 项目里的文件始终是权威副本，DB-K 改动与 IDE 改动落在同一文件上，避免两份内容分叉。

与现有「普通控制台」的差别只在**文件归属**与**监听同步**：普通控制台的 `.sql` 由 DB-K 管理
（`<dataDir>/consoles/<id>.sql`），外部控制台绑定用户指定的任意路径，DB-K 不改名、不删除它。

## 已确认的需求决策（用户逐条确认）

1. **生命周期 = A：持久化**。外部控制台就是一条普通 `consoles` 行（加 `external=1`），
   记工作区成员、数据源关联与 `target`，重启后恢复。它不「消失」，只有用户关闭/删除标签或
   文件丢失触发自动隐藏。
2. **外部修改冲突 = A**：缓冲干净 → 静默重载磁盘内容；缓冲脏 → 保留本地 + 提示横幅，
   用户点「载入磁盘版本 / 保留我的」，不点不丢。
3. **数据源关联 = B**：优先用「该路径上次关联的数据源」（持久化在 `consoles.connection_id`），
   无记录时才弹选择框；之后可随时改绑。
4. **文件被外部删除 = A + A2**：运行期保留标签与内存内容、暂停自动保存、横幅提供
   重建/另存为/关闭；退出时若有「缺失且脏」的控制台，弹选择框处理。
   **重启后**文件仍不存在 → 自动隐藏（等价「关闭控制台」，保留行与关联）。
5. **打开入口 = A + D**：文件选择框选单个 `.sql` + 拖拽（拖多个则批量各开一个）。
6. **路径唯一 = A**：按绝对路径全局唯一，跨工作区共享同一控制台实体；重复打开 = 聚焦已有，
   不新建。

## 范围

**做**：外部文件打开/拖入、数据源关联、复用控制台全生命周期（标签/工作区/自动保存/执行/切库/
光标记忆）、磁盘监听与外部修改同步、冲突与缺文件处理、退出保护、i18n、测试。

**不做（YAGNI）**：
- 目录级批量扫描（只做单文件选择 + 拖拽，多文件按拖入批量）；
- 符号链接指向同一文件的强去重（仅做实时路径消解尽力而为）；
- 冲突 diff 视图（只给「载入磁盘版本 / 保留我的」二选一）；
- 文件编码自适应（统一 UTF-8，与现有控制台一致）；
- 不改动普通控制台的行为与存储路径。

## 术语

- **普通控制台**：`external=0`，`file_path = <dataDir>/consoles/<id>.sql`，由 DB-K 管理。
- **外部控制台 / 链接控制台**：`external=1`，`file_path` 为用户指定路径，DB-K 不改名、不删除。

## 终态数据模型

### SQLite（`AppDatabase` CURRENT_VERSION 11 → 12）

```sql
ALTER TABLE consoles ADD COLUMN external INTEGER NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX IF NOT EXISTS idx_consoles_file_path ON consoles(file_path);
```

- `external=0` 是既有全部行与新建普通控制台的默认值，迁移无回填。
- `file_path` 唯一索引落实「按路径全局唯一」。普通控制台路径含随机 id，天然唯一，不会冲突。
- **路径规范化**：存 `Path.toAbsolutePath().normalize().toString()`。打开时先按规范化路径查
  `getConsoleByPath`；文件存在时优先用 `toRealPath()` 消解符号链接再查一次，消解失败回落
  规范化路径。不保证符号链接跨路径的强去重。

### 领域模型（`db/ConsoleModels.kt`）

```kotlin
data class ConsoleRecord(
    val id: String,
    val connectionId: String,
    val name: String,
    val filePath: String,
    val external: Boolean = false,   // 新增
    val sortOrder: Int = 0,
    val updatedAt: Long = 0,
    val target: String = "",
    val caretStart: Int = 0,
    val caretEnd: Int = 0,
)
```

### 仓库（`db/ConnectionsRepository.kt`）

- `listConsoles` / `getConsole` / `mapConsole`：SELECT 列表加入 `external`，映射为布尔。
- `getConsoleByPath(path: String): ConsoleRecord?`：按 `file_path` 精确查（唯一索引支撑）。
- `createExternalConsole(connectionId, name, filePath): ConsoleRecord`：插行 `external=1`、
  **不建文件、不写空内容**（与 `createConsole` 的差异就在这两点）。
- `rebindConsoleFile(consoleId, newPath)`：「另存为…」时把控制台重绑到新路径（更新 `file_path`），
  `external` 保持 1；调用方需先保证新路径未被占用（否则报冲突）。
- `deleteConsole` / `deleteConnection`：删除文件时按 `external` 分支——`external=1` **绝不删除**文件。

## 设计

### 1. 打开与关联

1. 入口（见 §UI）解析出若干绝对路径。
2. 每个路径先规范化 + 尝试实时消解，然后 `getConsoleByPath`：
   - **命中**：复用该控制台（若已在当前工作区 → 直接激活；否则加入当前工作区 + 激活）。
     命中时其 `connectionId` 即数据源关联，不再询问。
   - **未命中**：数据源 = 记忆（不可能，无行）→ 故进入「待选数据源」集合。
3. 批量打开时，把所有未命中的文件合并到**一次数据源选择框**（默认高亮当前激活数据源）。
4. 对每个未命中项调用 `createExternalConsole(connectionId, name = 文件名, filePath)`，
   加入当前工作区、激活、开始监听。
5. `target`（执行目标库/schema）沿用现有控制台机制：默认空 = 连接默认，可在头部切换并持久化。

### 2. 生命周期与自动保存

- 复用 `activate()`：首次加载从 `filePath` 读盘进缓冲。文件读不到 → 缓冲置空 + 标记 `missing`。
- `setText` → 脏标记 + 3s 防抖 `flushNow`（现有逻辑不变）。
- `flushNow` 对外部控制台的处理：
  - 文件存在 → 写盘（见 §3 原子写）+ 更新 `lastDiskContent`，清脏。
  - `missing` → **跳过写盘、保持脏、不报错刷屏**（绝不擅自重建文件）。
- `activate` / `close` / `delete` / 连接删除：普通控制台逻辑不变，仅在删除分支跳过外部文件。
- 光标记忆（`caret_start/caret_end`）、执行、结果、切库（`sessionContextSqlFor`）全部复用。

### 3. 文件监听与外部修改同步

新组件 `app/core/ExternalFileWatcher.kt`（纯 JDK，`WatchService` + 一个守护线程）：

- 接口抽取：`interface FileWatcher { fun watch(Path); fun unwatch(Path); fun close() }`，`ConsoleState` 依赖接口，
  测试注入 fake。
- 监听**父目录**（覆盖 IDE「临时文件 + rename 覆盖」的原子保存），事件按文件名过滤到已注册路径。
- 目录注册**引用计数**，归零才 cancel；目录被删/重建时由安全轮询重新注册。
- **防抖** 300ms（某路径稳定 ≥300ms 才回调）；`OVERFLOW` → 该目录全部路径标记；
- **安全兜底**：每 2s 比对 mtime+size 快照，发现变化即入待处理（兜 kqueue/网络盘漏事件）。
- 回调 `onFileChanged(path)` 由 `ConsoleState` 经 `scope.launch { ... }`（UI 线程）处理。

`ConsoleState.handleExternalChange(path)`：

1. 反查 consoleId（监听时维护 `path → consoleId` 反向表，兜底 `getConsoleByPath`）。
2. 磁盘文件不存在 → `externalMissing += id`（并暂停该控制台自动保存），后续不再比对内容。
3. 存在则读盘内容，与 `lastDiskContent[consoleId]` 比对；相等 → 自身写入，忽略。
4. 不同：
   - 缓冲**干净** → 静默重载：`buffers[id] = disk`、更新 `lastDiskContent`、`bumpTextRevision(id)`。
   - 缓冲**脏** → `externalConflict += id`（保留本地，等用户选择）。

`lastDiskContent` 在「初次加载」「静默重载」「载入磁盘版本」「我们成功写盘」四处更新，
用于忽略自身写入。

**原子写**：`ConsoleFiles.write` 改为同目录临时文件 + `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`，
失败回落普通写。避免外部工具/监听读到半截内容。

**编辑器同步（关键改动点）**：`EditorArea` 的每控制台 `TextFieldState` 只在首次 `getOrPut` 时吃
`editorText`，外部重载不会自动刷新。方案：`ConsoleState` 维护 `textRevision: Map<consoleId, Int>`；
`EditorArea` 增加 `LaunchedEffect(consoleId, revision)`：当 `state.text != 权威缓冲` 时
`state.edit { replace(0, length, buffer); selection = TextRange(夹取) }`。这样同时解决
「外部重载」与「隐藏后重开时缓存过期」；正常输入时二者相等，不打架；历史「回弹串台」是旧
`BasicTextField(value=)` 的问题，这里走 `TextFieldState.edit`，安全。

**监听生命周期**：外部控制台**首次被加载时 watch**，直到**删除控制台 / 删除其连接**才 unwatch。
关闭标签**不 unwatch**（避免「关闭 → 文件被改 → 重开读到旧缓存」）。隐藏控制台缓冲很小，多监听
路径无成本。

### 4. 冲突与缺文件处理

- 冲突横幅：「磁盘文件已被外部修改」+ `[载入磁盘版本] [保留我的]`。
  - 载入磁盘版本：缓冲 = 磁盘内容、取消该控制台未决防抖任务、清脏、清冲突、bump revision。
  - 保留我的：清冲突标记；下次自动保存/落盘覆盖磁盘。
- 缺文件横幅：「文件已丢失：<path>」+ `[重建文件] [另存为…] [关闭控制台] [复制路径]`。
  - 重建文件：按原路径写回缓冲（父目录不存在则禁用并提示）。
  - 另存为…：写缓冲到新路径并 `rebindConsoleFile` 重绑；新路径已被占用则提示冲突。
- 运行期仍可编辑、可执行（执行只读缓冲，不写文件；执行前 flush 对 missing 跳过，不弹窗）。

### 5. 退出保护

复用 `DialogState` 的 `ConfirmRequest` 机制新增一类请求，在现有退出确认链**先于**结果修改处理：

- 触发条件：存在「`external=1` 且 `missing` 且脏」的控制台。
- 对话框列出这些控制台，按钮：`[重建全部]`（父目录不存在时禁用）/ `[逐个另存为…]` /
  `[放弃这些内容并退出]` / `[取消退出]`。
- 处理完成后继续原有退出流程（`flushAllSync` + 结果修改确认 + 窗口几何落盘）。

### 6. 启动自动隐藏

启动加载工作区与控制台后调用 `ConsoleState.pruneMissingExternal()`：

- 对每个 `external=1` 且文件不存在的控制台，`workspaces.removeConsoleEverywhere(id)`
  （等价「关闭控制台」，文件消失则各处都用不了），**保留行与数据源关联**。
- 文件回来后可从数据源右键「打开控制台」重新打开（`reopenConsole`）。

## UI

### 入口

- 标签条「+」下拉新增 **「打开 SQL 文件…」**（置于数据源列表之上，分隔线隔开），
  用 AWT `FileDialog`（与 CSV 导出同源）。
- **拖拽**：`Modifier.dragAndDropTarget` 挂编辑区根，接受 `DragData.FilesList`；
  只接受扩展名 `.sql`（大小写不敏感），其余一次性 toast 提示忽略；多文件逐个打开。
- 批量打开时，未解析数据源的文件合并成一次选择框（列出全部连接 + `TypeBadge`，
  默认高亮当前激活数据源）。

### 标签与标识

- 外部控制台标签名默认文件名（含扩展名），前加「链接文件」小图标（`DbIcons` 新增）。
- `missing`：文件名旁警示（语义黄 0xFFFFB300），tooltip「文件已丢失」。
- `conflict`：标签加提示点。
- 标签右键新增：**复制文件路径** / **在文件管理器中显示**（`DesktopOpen`） /
  **重新载入磁盘版本**；external 的「删除」文案追加「仅移除控制台记录，不删除文件」。

### 对话框与横幅

- 编辑器上方细横幅（非阻塞，仿查找栏）承载冲突/缺文件提示与动作，仅当激活控制台为 external
  且处于冲突/缺失时显示。
- 数据源选择框、退出对话框、另存为 `FileDialog`。

### 主题与 i18n

- 横幅/图标用主题色 + 既有语义色（黄警示），按 AGENTS 检查深色可读性。
- 所有用户可见文案走 `Str` key，zh + en 同时补：入口、选择框标题/说明、链接图标 tooltip、
  冲突横幅、缺文件横幅、退出对话框、右键菜单、各类 toast。

## 持久化小结

- 进 SQLite：`consoles.external`、`consoles.file_path` 唯一索引；`path → 数据源` 即
  `consoles.connection_id`；`target` 已持久化。
- 不新增 `<dataDir>/*.properties`。
- 瞬态（内存）：`lastDiskContent`、`externalConflict`、`externalMissing`、`textRevision`、
  `path → consoleId` 反向表。

## 涉及文件（预期）

- `db/AppDatabase.kt`（v12 迁移）
- `db/ConsoleModels.kt`（`external` 字段）
- `db/ConnectionsRepository.kt`（读写 `external`、`getConsoleByPath`、`createExternalConsole`、
  `rebindConsoleFile`、删除分支）
- `db/ConsoleFiles.kt`（原子写）
- `app/core/ExternalFileWatcher.kt`（新增）
- `app/state/ConsoleState.kt`（监听接线、冲突/缺失/退出/prune、textRevision）
- `app/core/Main.kt`（入口、拖拽、对话框接线、启动 prune、退出链）
- `app/ui/EditorArea.kt`（revision 同步、横幅、拖拽目标）
- `app/ui/ConsoleTabBar.kt` / `ConsoleHeaderBar.kt`（标识、右键、打开入口）
- `app/ui/DbIcons.kt`（链接/警示图标）
- `app/state/DialogState.kt`（退出请求类型）
- `i18n/Str.kt` + `CatalogZh.kt` + `CatalogEn.kt`

## 测试计划

- `ExternalFileWatcherTest`：watch → 写文件 → 超时内收到回调；unwatch 不再回调；父目录被删不崩溃；
  `close()` 后线程退出。
- `ConsoleState` 外部同步（注入 fake watcher，`runTest` 虚拟时间）：干净自动重载、脏则置冲突、
  「载入磁盘版本」清脏、「保留我的」忽略后续一次性事件、自身写入被忽略、missing 暂停自动保存、
  prune 移除成员但保留行、rebind 更新路径。
- 仓库/迁移：`external` 列读写默认值、`file_path` 唯一索引、`getConsoleByPath`、
  `createExternalConsole` 不建文件、`deleteConsole` 不删外部文件。
- `ConsoleFiles` 原子写往返。

## 风险与开放点

- **WatchService 语义差异**：Linux inotify 与 macOS kqueue 事件类型不同，靠父目录监听 + mtime
  安全轮询兜底；实现时需在真实 IDE 保存路径下人工验证一次。
- **Compose 外部拖拽 API**：`DragData.FilesList` 已确认存在于 1.12，实现初期做一次小验证。
- **符号链接去重**：仅尽力而为，不做硬保证。
