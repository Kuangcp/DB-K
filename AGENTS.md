# db-k 开发约定（AGENTS.md）

本文件对后续所有 agent/开发者生效。与 `doc/DESIGN.md` 冲突时以本文件为准。

## 工具链（不要改动）

- JDK：`JAVA_HOME=/home/zk/.sdkman/candidates/java/25.0.3-jbr`
- Gradle：`/home/zk/.sdkman/candidates/gradle/9.4.1/bin`（不引入 wrapper）
- 构建/运行前先 `export` 上面两个路径到 PATH；常用命令：
  - 编译检查：`gradle compileKotlin`
  - JDBC 层自检：`gradle smokeJdbc`
  - 运行：`gradle run`（X11 下需 `export DISPLAY=:0.0`）

## 分层

- `app/` → 依赖 `db/`、`tree/`、`jdbc/`（UI 编排、snapshot 状态）
- `tree/` → 依赖 `jdbc/model`、`db` 领域类型（只做行派生与渲染，不碰 compose 状态之外的东西）
- `db/` → 自有 SQLite 存储（connections/folders/sql_history）
- `jdbc/` → JDBC 运行时；**禁止 import compose/coroutines**（阻塞 API，由 app 层以 `Dispatchers.IO` 包裹）
- 状态即 snapshot：UI 可观察状态一律 `mutableStateOf`，读写经 `app/state` 的类方法

## 主题适配（主题切换 / 深色可读性）★ 必读

色板唯一来源：`app/ui/AppTheme.kt` 的 `appMaterialColors(isDark)`。
浅色背景 `#EBECF0`、深色 `background #292B2E / surface #32353B`（与 api-x 同源）。

### 硬性规则

1. **任何 `Text` / `Icon` 的前景色必须来自主题**：
   - 主文字：`MaterialTheme.colors.onSurface`（或 `onBackground`）
   - 次要文字：`MaterialTheme.colors.onSurface.copy(alpha = 0.4f~0.7f)`
   - **禁止**依赖 `LocalContentColor` 默认值、禁止写死 `Color.Black` / 纯白当文字色。
2. `Main.kt` 已在 `MaterialTheme` 内兜底 `LocalContentColor = onSurface`（M2 MaterialTheme
   不会设置该值、默认黑字在深色下不可见——历史 bug 根因）。但新组件仍**必须显式给色**，
   兜底只是保险，不是偷懒借口。
3. 允许写死的只有**语义色**：状态点绿 `0xFF43A047` / 红 `0xFFE53935` / 黄 `0xFFFFB300` / 灰
   `0xFF9E9E9E`、对象徽章色（表蓝 `0xFF5586E4`、视图青 `0xFF26A69A` 等）、错误红。
   语义色只做**色块/点/徽章背景**，其上文字若有仍走主题色。
4. 背景一律 `MaterialTheme.colors.background / surface`，**禁止硬编码背景深浅色**。
5. 新增任何界面后，必须人工切深色检查：无黑字沉底、无过曝白块，再交付。

### 主题切换按钮

- 位置：`SqlWorkspace` 右上角，**icon-only**（不要文字按钮）：
  - 当前浅色 → 显示 `DbIcons.Moon`（点击进深色）
  - 当前深色 → 显示 `DbIcons.Sun`（点击回浅色）
- 样式参照 api-x：`IconButton` + `Icon(tint = onSurface alpha 0.7)`，24dp 热区、18dp 图标。
- 状态：`app/core/Main.kt` 的 `var isDark`（session 级，不持久化；如需持久化参照 api-x ThemeState）。

### 自查

```bash
grep -rn "Color.Black\|Color.White" src/main/kotlin --include=*.kt | grep -v "AppTheme"
```
输出应只剩 AppTheme.kt 的色板定义与图标 fill（图标 fill 黑属正常，经 Icon tint 覆盖）。

## UI 交互基线

- 编辑器执行快捷键：`Ctrl+Enter`（`SqlWorkspace` 内 `onPreviewKeyEvent` 拦截）。
- SQL 执行走 `app/state/ConsoleState.run` → 单线程 `LiveConnection` 执行器，
  禁止另起线程直连同一 `java.sql.Connection`（会并发冲突）。
- 树操作回调由 `Main` 层 `rememberCoroutineScope` 调度，`app/state` 的 suspend 动作内部
  已切 `Dispatchers.IO`。

## M4 控制台 / 持久化约定

- **控制台结构**：一个数据源可多个控制台，每个控制台 = app.db `consoles` 行（元数据）
  + `<dataDir>/consoles/<id>.sql` 单文件（正文）。**rename 只改行不改文件名**（id 稳定）。
  删除连接时由 `ConnectionsRepository.deleteConnection` 级联删行+文件（smoke 已验证）。
- 编辑器文本：内存 buffer 为唯一权威；防抖 700ms 自动写回 .sql 文件
  （`ConsoleState.setText` 内 scope.launch { delay(AUTOSAVE_MS); withContext(IO){ flushNow } }）。
  **必须落盘时机**：切控制台 / 执行前 / 退出（`onCloseRequest` 调 `flushAllSync`）。
- 执行目标 = 控制台绑定的数据源（`activeConsole.connectionId`），与树选中解耦；
  树选中只是导航（选中即 `activateForProfile`）。
- 双击表/视图 → 预览 SELECT（`DialectRegistry.previewSelect`）：当前控制台空则复用，否则新建
  「控制台 N」（自动命名可右键改名）；TRIGGER 不预览。
- 窗口几何：`WindowPrefs` 存 `<dataDir>/window.properties`（px 值）；退出 onCloseRequest 保存
  `windowState.position/size`（position 单位 Dp，需 *density 转 px；isSpecified=false 时不存 x/y，下次居中）。
- CSV 导出：`CsvExport` 写 UTF-8、双引号转义、NULL→空；AWT FileDialog 以 null owner 在 Linux 可用。
- UI 自动化（xdotool 合成点击）在本机对 Compose 窗口无效：布局/像素用截图+python3 分析，
  交互最终一律人工验收；不要为合成点击耗费时间。

## 验证习惯（每阶段必做）

1. `gradle compileKotlin` 无错
2. `gradle smokeJdbc` 通过（驱动层 + QueryExecutor 冒烟）
3. 程序化可验的部分用 sqlite3/python3 直查 `~/.local/share/db-k/app.db`（库表、迁移、演示连接）
4. UI 交互（点击/主题/弹窗）以本机 X11 实跑 + 截图留档 `/tmp/dbk-*.png`，最终人工确认
