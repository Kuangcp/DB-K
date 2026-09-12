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
- 状态：`Main.kt` 的 `var isDark`，**持久化**：启动读 `ThemePrefs.load()`（`<dataDir>/theme.properties`，key `dark`），
  切换时同步 `ThemePrefs.save(isDark)`。新增主题相关配置不得只存内存。

### 设置窗口（顶栏齿轮）

- 位置：`SqlWorkspace` 右上角主题切换**右侧**，`Icons.Filled.Settings` icon-only。
- 窗口：`app/dialog/SettingsDialog.kt` 的独立 `DialogWindow`（自带 `MaterialTheme` + `LocalContentColor` 兜底，
  不要依赖主窗口主题），左侧分区导航（当前仅「通用设置」）/ 右侧内容 / **左下角版本号** `v<NAME>-<COMMIT>`。
- 配置项：编辑器字体族（系统字体名，空 = 默认等宽）+ 字号（8~24sp，带实时预览）。
- 状态：`Main.kt` 的 `var editorSettings`，**持久化** `<dataDir>/editor.properties`（`EditorPrefs`/`EditorSettings`）；
  保存后即时重排编辑器（行高按 `EditorSettings.lineHeightSp` 随字号等比）。版本号来自构建产生的 `app/build/Version.kt`。

## 会话日志（每次启动一个文件，按月分目录）★ 必读

- 约定：`<dataDir>/logs/<yyyy-MM>/<yyyy-MM-dd>_<N>.log`（N=当天会话序号，同一天每启动一次 +1），
  目录默认在应用数据目录下（Linux `~/.local/share/db-k/logs/`，跟随 AppPaths debugHome 重定向），
  不再落在项目/工作目录；可用 `-Ddbk.logDir=<dir>` 覆盖。
- 实现：自定义 tinylog writer `app/core/SessionLogWriter.kt`，注册于 `src/main/resources/tinylog.properties`
  （`writer2 = app.core.SessionLogWriter`；`writer = console` 保留开发输出）。
- 依赖：`tinylog-impl` 是 **implementation**（writer 类编译需要 writers 抽象类）。
- 坑：tinylog 懒初始化——只有首次 `Logger.*` 调用才建 writer。所以 `main()` 第一行必须有确定的首条日志
  （现为 `Logger.info("db-k session start; …")`）；新增启动流程不要删它。
- writer 构造：必须同时提供无参与 `(Map<String,String>)` 两个公开构造（tinylog 反射实例化用）。
- 新增「运行日志写哪」自查：`find ~/.local/share/db-k/logs -name '*.log' | tail` 应与今天日期/启动次数对应。
- 入口：设置窗口「通用设置 → 诊断」可显示并一键打开日志 / 数据目录（`AppPaths.logsDirectory()` /
  `dataDirectory()`，`app/core/DesktopOpen.kt`；无桌面环境回落复制路径 + 行内提示）。

### 自查

```bash
grep -rn "Color.Black\|Color.White" src/main/kotlin --include=*.kt | grep -v "AppTheme"
```
输出应只剩 AppTheme.kt 的色板定义与图标 fill（图标 fill 黑属正常，经 Icon tint 覆盖）。

## UI 交互基线

- 编辑器执行快捷键：`Ctrl+Enter`（`SqlWorkspace` 内 `onPreviewKeyEvent` 拦截）。
- **单字段输入弹窗必须支持 Enter 提交**：新建/重命名文件夹、重命名/新建控制台等
  「只有一个输入框 + 确定/取消」的弹窗，回车（含数字键盘回车）等同于点「确定」，
  空输入时忽略。实现：输入框 Modifier 挂 `onPreviewKeyEvent(submitOnEnter(...))`
  （见 `app/dialog/TextInputDialogs.kt`）——单行 `TextField` 会吞回车，必须用 preview 拦截。
  新增同类弹窗照此办理，不要让用户只能用鼠标点。多字段表单（如连接编辑）不适用。
- SQL 执行走 `app/state/ConsoleState.run` → 单线程 `LiveConnection` 执行器，
  禁止另起线程直连同一 `java.sql.Connection`（会并发冲突）。
- 树操作回调由 `Main` 层 `rememberCoroutineScope` 调度，`app/state` 的 suspend 动作内部
  已切 `Dispatchers.IO`。

## 持久化分层（新增需要落盘的状态时先看这里）★ 必读

判断口诀：**删掉实体时该不该跟着消失 / 要不要按它查询？** 是 → SQLite；否则 → properties。

1. **业务实体状态 → `app.db`（SQLite，单连接 + FK 级联）**：connections / folders /
   consoles（元数据 + 光标）/ sql_history / meta_cache。加字段 = `AppDatabase` 版本 +1 的迁移
   + repository 读写 + 模型字段（如 `ConsoleRecord`），删实体靠 FK 级联清理，别手写清理。
2. **应用/窗口级全局偏好 → `<dataDir>/*.properties`（`app/settings/*Prefs`）**：theme、window、
   tree-expand、editor（编辑器字体/字号）。都是标量、无查询/级联需求；新增同类项合并进已有文件，别每项开一个文件。
3. **大块正文 → 独立文件**，DB 只存路径（`consoles/<id>.sql`）。
4. **纯瞬态 → 只放内存**：结果集（`runSlots`）、补全弹层、错误文案等，可随时重建，不落盘。
5. **外部 JDBC 驱动 jar → `<dataDir>/drivers/`**（`-Ddbk.driversDir` 可覆盖），独立 classloader 加载（`jdbc/ExternalDrivers.kt`），启动时 `ensureLoaded()`，新增 jar 需重启。

### 控制台光标记忆（`consoles.caret_start/caret_end`）

- 语义：每个控制台记住「最后焦点所在的行/选区」，切控制台与**重启**后都恢复到该行
  （视口居中；首/尾行由滚动夹取自然贴顶/贴底）。
- 写入：内存草稿为权威（`ConsoleState.caretDrafts`），每次 `setCaret` 重置 1.5s 防抖落库
  （`CARET_SAVE_MS`）；**强制落库点** = 切控制台（`activate`）、退出（`flushAllSync`）。
- **写光标绝不更新 `updated_at`**：它表达内容/元数据变更，被光标移动刷新会让
  `activateForProfile` / `activateMostRecent` 的「最近改动」启发式失真（smoke 有断言守着）。
- UI 侧（`SqlWorkspace`）：`remember(consoleId)` 在**组合期同步**恢复选区，再由 `EditorPane`
  的 `LaunchedEffect(consoleId)` 按该行居中滚动。**不要改回在 `LaunchedEffect` 里恢复选区**：
  子层 effect 先于父层执行，会读到切换前的旧值。

## M4 控制台 / 持久化约定

- **控制台结构**：一个数据源可多个控制台，每个控制台 = app.db `consoles` 行（元数据）
  + `<dataDir>/consoles/<id>.sql` 单文件（正文）。**rename 只改行不改文件名**（id 稳定）。
  删除连接时由 `ConnectionsRepository.deleteConnection` 级联删行+文件（smoke 已验证）。
- **关闭 vs 删除**：`consoles.closed`（v8）= 从标签条隐藏但**保留行与 .sql**；标签条只显示
  `openConsoles`，关闭当前激活则切到同源另一已打开（无则引导态），可从数据源右键「打开控制台」
  级联 `reopenConsole` 重新打开。`rename`/`setConsoleClosed` 都**不动 `updated_at`**。
- 编辑器文本：内存 buffer 为唯一权威；防抖 3s 自动写回 .sql 文件
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
2. `gradle test` 通过（单元测试：jdbc/tree/db 纯逻辑 + 嵌入式库集成，`src/test/kotlin`）
3. `gradle smokeJdbc` 通过（驱动层 + QueryExecutor 冒烟）
4. 程序化可验的部分用 sqlite3/python3 直查 `~/.local/share/db-k/app.db`（库表、迁移、演示连接）

