# db-k 设计文档：轻量级 JDBC 数据库客户端（Compose Desktop）

> 目标：做一个类似 api-x 架构的轻量数据库客户端。
> 技术底座完全对齐 api-x 已验证过的组合，架构沿用其「分层 + Repository + 状态即 ViewModel」的最佳实践。

---

## 1. 定位与范围

**形态**：单窗口桌面工具，左树 + 右上 SQL 编辑器 + 右下结果区（传统 DB 客户端布局）。

**已支持数据库**：PostgreSQL / MySQL / MariaDB / SQLite / H2（服务端 + 本地文件）/ ClickHouse（内置驱动，全部走 JDBC）。
SQL Server / Oracle 走**外部驱动目录** `<dataDir>/drivers`（Oracle 驱动 license 限制，见 §5）。

**应用自身元数据**（连接、分组文件夹、SQL 历史）持久化在本地 SQLite，与目标库无关。

**明确不做（第一版）**：ER 图、数据编辑表、索引/约束管理、多窗口、插件体系。

---

## 2. 技术选型（复用本机已验证工具链）

| 项 | 选择 | 说明 |
|---|---|---|
| Kotlin / Compose | 2.4.0 / 1.12.0（对齐 api-x） | api-x 在本机构建验证过 |
| Gradle | wrapper 8.3（api-x 同一份，已缓存） | 拷贝 api-x 的 wrapper，避免重新下载 |
| JDK | 25.0.3-jbr（sdkman 已有） | 与 api-x 一致，字体渲染/打包行为统一 |
| UI 组件 | Material2（M2）+ material-icons-core | 跟随 api-x，代码最稳；不用 M3 |
| 日志 | tinylog 2.x | api-x 同款 |
| 元数据存储 | org.xerial:sqlite-jdbc | 应用自己的 SQLite（不是连目标库的） |
| JDBC 驱动 | pgjdbc / mysql-connector-j / mariadb-java-client / sqlite-jdbc / h2 | 全放 classpath（jar 包名不冲突），够轻 |
| 打包 | compose.desktop nativeDistributions | jlink 需补 `java.sql` 等模块（见 §12） |

驱动版本以 mavenCentral 当前稳定为准，构建时锁定具体版本号。

---

## 3. 模块分包（对齐 api-x 四层）

```
src/main/kotlin/
├── app/            # 应用层：Compose UI 组合 + 状态（可 import 一切）
│   ├── core/       #   Main.kt：Window + 全局快捷键 + 副作用动作（CsvExport / DesktopOpen / 日志）
│   ├── state/      #   TreeState / ConnectionsState / ConsoleState / DialogState / ColumnCatalog 等
│   ├── ui/         #   AppTheme、SqlWorkspace（拆分见下）、SqlEditing（补全/高亮）、ResultEditPlan、自绘图标
│   │               #   SqlWorkspace 已拆：ConsoleHeaderBar / ConsoleTabBar / SqlEditorPane /
│   │               #   FindReplaceBar / CompletionPopup / ResultToolbar / ResultPane / ResultTable / ResultCells
│   ├── dialog/     #   连接编辑、设置、单字段输入、查看器（SQL/单元格/DDL/提交预览）
│   └── settings/   #   主题 / 窗口 / 树展开 / 编辑器字体 / Redis 浏览状态 等 properties 持久化
├── i18n/           # 国际化文案纯叶层：Lang / Str key / CatalogZh+En / I18n.t（只依赖 JDK）
├── engine/         # 协议无关契约层（叶层，不 import jdbc/db/app/compose/coroutines）
│   ├── DataSourceSession.kt   # 通用会话接口：元数据 + 执行 + cancel
│   ├── BackendCapabilities.kt # 能力位（可编辑/SQL 补全/DDL/懒加载/编辑器语言）
│   ├── Protocol.kt            # Protocol(JDBC/REDIS/ELASTICSEARCH) + EditorLanguage
│   └── model/                 # SchemaMeta / SchemaObjects / ColumnMeta / QueryResult 等纯模型
├── db/             # 元数据存储层：AppPaths / AppDatabase(migrate) / ConnectionsRepository / 各类 Prefs
├── jdbc/           # JDBC 后端：方言、LiveConnection（实现 DataSourceSession）、QueryExecutor、RowUpdater
│   ├── DbDialect.kt（接口 + GenericDialect）+ 各库方言
│   └── EditableSession.kt     # JDBC 专属写回能力（主键定位 + 参数化 UPDATE）
├── redis/          # Redis 后端（N5）：RedisSession（实现 DataSourceSession）+ RedisProtocol（命令切分/回复渲染）
└── tree/           # 树形模型（UI 树节点）+ DbTreeSidebar 组件
```

**分层纪律**：
- `engine/` 是**协议无关叶层**：JDBC / Redis / ES 都实现它的 `DataSourceSession`；
  它不依赖 `jdbc`/`db`/`app`，也不依赖 compose/coroutines（阻塞 API，由 app 层包 `Dispatchers.IO`）；
- `jdbc/`、`redis/`、`tree/`、`db/` 不依赖 compose，纯 Kotlin + JDK，逻辑可单测；
- `i18n/` 是文案纯叶层（只依赖 JDK），任何层可取文案（非 UI 用 `I18n.t`，UI 用可组合 `t`），见 `doc/I18N.md`；
- JDBC 专属概念（主键写回、DDL、事务）留在 `jdbc/`（如 `EditableSession`），不进入通用层；新增协议按能力位降级；
- `app/` 允许 import 所有层，负责状态与调用编排；**后端选择集中在 `app/state/SessionFactory`**（按 `dbType.protocol`）；
- 依赖方向单向：`app → (engine, db, jdbc, redis, tree)`，`jdbc/redis → engine`，`db → engine/model`，`engine` 不反向依赖；
  `i18n/` 被所有层单向依赖。

---

## 4. 核心数据模型

### 4.1 存储模型（SQLite 行 → data class）

```kotlin
enum class DbType { POSTGRES, MYSQL, MARIADB, SQLITE, H2, H2LOCAL, CLICKHOUSE, SQLSERVER, ORACLE }

data class ConnectionProfile(
    val id: String,
    val name: String,
    val folderId: String?,
    val color: String? = null,
    val dbType: DbType,
    val host: String, val port: Int, val database: String,  // SQLite: database=文件路径
    val user: String?, val password: String?,
    val extraParams: String = "",   // sslmode=require&connectTimeout=5
    val sortOrder: Int = 0,
)
// URL 由 dbType 模板 + 字段拼装（host:port/db?extra），首版不开放手写 override URL
```

### 4.2 UI 树模型 + 虚拟树渲染

树深度：`文件夹* → 数据源 → 库(catalog/schema) → [表|视图|触发器] 分组 → 对象`。

```kotlin
sealed interface UiTreeNode {
    val id: String; val name: String; val depth: Int; val icon: IconKind
    data class Folder(n, folderId, children...)        : UiTreeNode
    data class DataSource(profileId, connStatus)       : UiTreeNode   // 连接状态点
    data class Schema(catalog, schema, childrenLoaded) : UiTreeNode
    data class ObjectGroup(kind: Table|View|Trigger)   : UiTreeNode
    data class DbObject(catalog, schema, kind, name)   : UiTreeNode   // 表/视图/触发器
}
```

**渲染策略**：不用递归嵌套 lazy（深度大、滚动抖动），改为**扁平展开**——
给定 `expandedIds + 元数据缓存`，把可见节点展开成 `List<UiTreeNode>`，单条 `LazyColumn` 每行一个节点（缩进按 depth）。这与 api-x 树（嵌套数据类递归）不同，是 DB 工具更优解。

**懒加载契约**：`DataSource` 展开 → 拉 catalog/schema 列表；`Schema` 展开 → 拉该库的表/视图/触发器名（只读名字，快）；双击/右键表 → 才拉 columns/主键/行预览。避免大库启动即卡。P6 起 `Schema` 展开进一步只取「组计数 + 核心类型」（表/视图/物化视图/序列），重类型组（触发器/例程/…）**组展开时才拉正文**（PG 开启 `DbDialect.lazyObjectGroups`；其余库默认回落全量 `loadObjects`，行为不变）。

**命名空间即过滤器（Redis）**：`BackendCapabilities.namespaceAsFilter=true` 时，树里不出现「库」一级，
连接节点下直接是 `TreeRowKind.FILTER`（DB 下拉 + 类型下拉 + key 模式输入）与单一「键」组。
`ConnectionsState.activeNamespaceOf` 是当前 DB 的权威值（树过滤条与工作台 `TargetSwitcher` 双向同步，
控制台执行目标用连接级 `activeDb`、与 `console.target` 解耦）；`searchObjects` 以 `SCAN MATCH` 游标分页，
树底「继续扫描（已显示 N）」续拉。浏览状态（DB / pattern / 类型）按连接持久化到 `<dataDir>/redis.properties`
（`app/settings/RedisPrefs`，属应用级浏览偏好，不入 app.db）。

### 4.3 JDBC 运行时模型

```kotlin
// 元数据（探测结果，只取名字层）
data class DbSchema(catalog: String?, schema: String?, tables: List<TableMeta>)
data class TableMeta(name: String, kind: ObjKind /*TABLE|VIEW|TRIGGER|SYSTEM*/, system: Boolean)
data class ColumnMeta(name: String, jdbcType: String, size: Int, nullable: Boolean, pk: Boolean)

// 一次执行的结果
sealed interface RunOutcome {
    data class Query(cols: List<String>, rows: List<List<Cell?>>, pageSize, total, truncated, elapsedMs)
    data class Update(count: Long, elapsedMs)
    data class Failure(message: String, sqlState: String?)
}
```

---

## 5. 方言抽象（jdbc/dialect）

```kotlin
interface DbDialect {
    val dbType: DbType
    fun openConnection(profile: ConnectionProfile): Connection   // Class.forName + DriverManager
    fun loadSchemas(conn: Connection): List<SchemaMeta>          // PG 返回 schema 列表
    fun loadObjects(conn: Connection, s: SchemaMeta): ObjectsMeta// 该库的表/视图/触发器
    fun quoteIdent(name: String): String                          // PG/H2 用 "x"，MySQL 用 `x`
    fun loadColumns(conn: Connection, s: SchemaMeta?, table: String): List<ColumnMeta>  // 列补全用（精确表名）
    fun tableDdl(conn: Connection, s: SchemaMeta?, name: String): String?                // 对象定义 DDL（Ctrl+Q）
    fun limitSql(sql: String, n: Int): String                     // 预览 LIMIT；SQL Server 用 TOP
    fun listCatalogs(conn: Connection): List<String>              // MySQL: catalog==database
}

object DialectRegistry { fun forType(t: DbType): DbDialect }
```

- `GenericDialect` 用 `java.sql.DatabaseMetaData` 兜底（getTables/getColumns/getTriggers），新库可零代码接入；
- PG 实现：过滤 `pg_catalog`/`information_schema`；SQLite：无 catalog 概念，只有 `main`，走 `sqlite_master`；MySQL/MariaDB：catalog 即库，schema 层合一；
- 双击表「预览 100 行」= `limitSql("SELECT * FROM schema.table", 100)`，标识符统一 `quoteIdent`。
- 对象定义（Ctrl+Q）：能精确给出的方言覆写 `tableDdl`——SQLite（`sqlite_master.sql`）、
  MySQL/MariaDB/ClickHouse（`SHOW CREATE TABLE`）；PostgreSQL 无此语句，用 `pg_catalog` 重建
  （列名/类型/默认值/NOT NULL + 主键，不含索引/外键/注释）；其余库回落「由列元数据重建」。
  读取走独立元数据连接（不占执行连接），浮窗内定宽滚动展示 + 「复制全部」，见 `app/dialog/ViewerDialogs.kt`。
  ⚠ 查看器一律用**显式尺寸的 `DialogWindow`**（`rememberDialogState(size = …)`），不可用 `AlertDialog`：
  AlertDialog 的窗口会 pack-to-content，`verticalScroll`/`LazyColumn` 在 pack 测量时把窗口撑成内容全高，
  滚动后内容整体移出窗口（实测：大片留白/内容消失）。

---

## 6. 运行时连接与线程模型（核心纪律）

- 每个已保存连接 = 一个 `LiveConnection`：
  - 持有懒创建的 `java.sql.Connection` + **单线程 Executor**（该连接所有 JDBC 调用串行投递）；
  - 状态机：`IDLE → CONNECTING → CONNECTED / ERROR`，状态以 snapshot state 暴露给 Compose；
  - 生命周期由 `ConnectionsState` 管理：连接/断开/重连/刷新，以及应用退出时全部 close。
- **UI 线程永不碰 JDBC**。所有同步 JDBC 调用包成 `suspend fun`（内部 `withContext(ioDispatcher) { executor.submit {...} }`），Compose 主线程只改 `mutableStateOf`。
- **取消执行**：记录当前 `Statement` 引用，取消时投递 `statement.cancel()`（JDBC 原生线程安全）；真卡死可降级为整连接 close + 重建。
- 查询防呆：默认 `maxRows=1000`、超时 30s；**大字段内存治理**（`QueryExecutor`）：单格超 `MAX_CELL_CHARS`(1M) 截断并加标记（`isTruncatedCell`，截断单元格禁止就地编辑）；单次结果总字符预算 `MAX_RESULT_CHARS`(16M) 超出即停止读取并置 `truncated`；BLOB 走 `getBinaryStream` 只统计字节数。网格渲染只取前缀预览（512 字符），避免 Compose 按巨大段落排版。
- **原生内存治理**（`app/core/NativeMemory`）：Skia/Compose 资源靠 Cleaner 在 GC 时才回收，JVM 堆小时 GC 不触发 → RSS 只涨不降。两条措施：① 图片查看器显式 close 底层 Skia 对象，并把底图缩到预览预算（`MAX_PREVIEW_PIXELS` 2MP）；② glibc 多线程 arena 会把峰值锁成常驻（每线程 64MB arena，free 后不跨线程复用）——`gradle run` / AppImage 启动前置 `MALLOC_ARENA_MAX=1`（必须在 JVM 启动前设，`main()` 里 mallopt 已太晚），重活后 `malloc_trim(0)` 归还空闲页。
- **连接健康（用前校验）**：长连接会被服务端/中间件空闲踢掉（MySQL `wait_timeout`、PG `idle_session_timeout`、NAT 等），
  而客户端 `Connection.isClosed` 仍为 false，直到下次发 SQL 才暴露。`LiveConnection` 把所有 JDBC 调用收敛到 `onConnection` 单点入口：
  空闲超阈值（默认 60s，`DbDialect.healthFor` 给策略）先用 `isValid` / 方言校验语句探活，探不通即重建；
  读操作若途中断连自动重试一次，写操作不重试（服务端可能已执行）但强制重建连接。嵌入式/本地库跳过校验。详见 `doc/CONNECTION_HEALTH.md`。

---

## 7. 状态管理（api-x ViewModel 模式）

App 顶层 `remember { XxxState(...) }` 拆成独立状态类，组件纯参数 + 回调，副作用集中到 `app/core/AppActions.kt`：

| 状态类 | 职责 |
|---|---|
| TreeState | folders、expandedIds、selection、滚动定位、schema 元数据缓存 `profileId→DbSchema`、各节点 loading/error 标记 |
| ConnectionsState | `profileId → LiveConnection`、连接状态、正在执行集合 |
| EditorState | 目标连接、sqlText（450ms 防抖自动保存）、选中片段、快捷键状态 |
| ResultState | 本次执行 outcome 列表、激活 tab、编辑/结果分割比例 |
| HistoryState | SQL 历史（执行即入库，面板可选） |
| DialogState / ThemeState / ToastState | 弹窗开关、深浅色、轻提示 |

关键动作（放 AppActions）：`connectDataSource / disconnect / refreshSchema / executeSql / cancelExecution / previewTable`。

数据流：点击运行 → `executeSql(EditorState.sqlText, 目标连接)` → QueryExecutor（IO）→ 结果写回 ResultState → ResultPanel 重组 → 历史入 SQLite（IO）。

---

## 8. UI 布局（传统 DB 客户端）

```
┌────────────────────────────────────────────────────────────────┐
│ 顶部工具栏：数据源状态点 | 当前连接/库下拉 | [▶ 运行] | 主题 | 设置 │
├──────────────┬─────────────────────────────────────────────────┤
│ 左侧树        │  SQL 编辑器（monospace，软换行，Ctrl+Enter 运行）   │
│  工具栏:       ├── 5dp 细线（整条可拖，悬停高亮，比例持久化）───── │
│  ＋连接 ＋文件夹 │ [结果1][结果2]  N 列 × M 行 · T ms   ⇄ ⭳ ✕    │
│  搜索框(后置)   │   数据网格：表头固定 + LazyColumn 行虚拟化          │
│  树(扁平展开)   │   列头: 可拖列宽；NULL 灰显；大字段截断            │
│  ＋右键菜单     │                                                 │
└──────────────┴─────────────────────────────────────────────────┘
```

交互细节（按里程碑逐条落地）：
- 树右键：数据源 → 连接/断开/刷新/编辑/删除 + **「打开控制台 ▸」向右级联**（列出该数据源全部控制台，标 ✓ 的为当前，尾随「新建控制台…」）；表/视图 → 预览 100 行 / 复制 `SELECT *` / 复制表名 / 查看定义 DDL；
  一个数据源可有多个控制台（每个 = `consoles` 一行 + 独立 `<dataDir>/consoles/<id>.sql`），脚本条「+」也能新建；树右键菜单为自绘单弹层（`Popup` + 根/子两列），不用嵌套 `DropdownMenu`（避免子弹层夺焦时父层 dismiss）；
- 控制台标签条右键：重命名 / **关闭控制台** / 删除控制台。关闭 = 打 `consoles.closed` 标记（仅隐藏，保留行与 .sql），标签条只显示未关闭的；可从数据源右键级联重新打开；删除才真正删行+删文件；
- 查看定义：`Ctrl+Q` 看**编辑器光标所在的表/视图**（裸表名/`schema.表名`/别名均可，最近动过编辑器时优先），
  否则看左侧树选中项（或右键「查看定义」）→ 浮窗取 DDL（见 §5），正文可滚动 + 「复制全部」；
- 双击表：`SELECT * FROM t LIMIT 100` 注入编辑器并直接运行；
- 结果支持多语句一次执行（`;` 切分或整段提交），每个结果集一个 Tab，消息 Tab 收错误/update 行数，错误不弹窗刷屏；
- 编辑器与结果区之间只隔一根 **5dp 可拖细线**（整条热区、悬停变主题色并加粗、N/S 光标），外侧仅 8dp padding，
  不再有独立的执行条/空白带；执行动作 + 状态 + 多语句 Tab 合并进**结果区顶部一条 28dp 工具条**
  （左：多语句 Tab；右：`N 列 × M 行 · T ms` / `执行中…` + 纯图标动作），无结果时不渲染该条（中间只剩细线）。
  动作为纯图标 + 悬停 tooltip：转置、导出 CSV、导出全量（截断时）、取消；
  「清空」按钮已移除（会连带清空结果，副作用大于收益）；
- 单元格大段文本（如 `SHOW CREATE TABLE`）：**双击单元格**或右键「查看完整内容」→ 浮窗等宽显示全文（字符/行数、定高滚动 + 右侧滚动条、可整体复制）；
  内容若被识别为 JSON（顶层对象/数组且语法合法，后台线程解析），浮窗默认切到 **JSON 树视图**：
  语法着色 + 容器行可点击展开/收起（收起时右侧给单行摘要）、工具栏「展开全部/收起全部」、
  可一键切回「原文」（原文同样按 JSON 高亮）；
- 列宽：默认按内容采样估算（首 300 行；64~320dp）；**拖表头右缘分隔线**可改为任意宽度
  （40~1600dp，拖拽中亮显，hidpi 下按 dp 累积避免小拖失效）；结果/转置变化时重建。
  单列结果、或转置后只剩一个值列（列名 + 行 1）时，该值列自适应吃掉右侧空白（上限 600dp，避免超长字段把列撑成巨宽）；
  手拖过该列后不再自动加宽；
- 单元格选中：单击（按下即选中，不等双击判定）高亮**单元格 + 整行**（单元格 primary 底 + 1dp 描边，
  整行 primary 淡底，行号/列头同步高亮）；方向键在单元格间移动并把目标行/列滚入视口；`Ctrl+C` 复制选中单元格（NULL → 空串）；
  双击仍开大字段查看器；换结果/转置即清空选中；
- 编辑器鼠标拖拽选区：指针停在上/下边缘（30dp 区）时持续自动滚动（越靠边越快，8~48px/帧）
  并同步把选区焦点延伸到边缘所在文本行；固定端锁在拖拽起点那一侧。原因是编辑器为
  「BasicTextField + 外层 verticalScroll」结构，文本域不知道外层滚动、不会自己滚；
  在父 Box 上以 Final pass 旁路观察指针（不干涉文本域自身的选区手势）。
- 深/浅主题 + 窗口几何/树展开/分割比持久化（api-x 同款 prefs）。

---

## 9. 本地元数据存储（db 层，独立于目标库）

数据目录沿用 api-x 方案（XDG/APPDATA + `debugHome` 沙箱重定向便于开发调试）。

`app.db`（SQLite）表设计，`schema_migrations` 版本迁移机制照抄 api-x：

```sql
folders       (id TEXT PK, name, parent_id NULL REFERENCES folders, sort_order)
connections   (id TEXT PK, folder_id NULL REFERENCES folders ON DELETE SET NULL,
               name, db_type, host, port, database_name, user_name, password,
               extra_params, color, sort_order, created_at, updated_at)
sql_history   (id TEXT PK, profile_id NULL, sql_text, executed_at_ms, duration_ms)
consoles      (id TEXT PK, connection_id REFERENCES connections ON DELETE CASCADE, name, file_path,
               sort_order, created_at, updated_at, target, caret_start, caret_end, closed)
meta_cache    (profile_id TEXT PK, fingerprint, saved_at_ms, payload)          -- 目录元数据缓存
column_cache  (profile_id, object_key, fingerprint, saved_at_ms, payload)      -- 列清单缓存
saved_queries (id TEXT PK, folder_id NULL, name, sql_text)   -- 后置里程碑
```

**密码策略**：第一版本地明文（面向本地开发工具，README 明示风险）；后续可加简单 AES + 本地密钥文件或 master password（DBeaver 模式）。不在第一版引入 OS keychain 依赖。

访问全部收敛到 `ConnectionsRepository`（prepareStatement + try-with-resources，同 api-x Repository 写法）。

---

## 10. 快捷键

快捷键统一登记在 `app/settings/ShortcutModels.kt` 的 `ShortcutCommand`（命令 id / 标签 / 作用域 / 默认键）；
各拦截点用 `keymap.matches(...)` / `keymap.matchAnyAwt(...)` 匹配，不再散落字面量。完整设计见 `doc/KEYBINDINGS.md`。

**扩展业务功能（设置窗口「快捷键」分区可改，覆盖写 `<dataDir>/keymap.properties`）**：

| 键 | 动作 |
|---|---|
| Ctrl+Enter | 执行选中 SQL（编辑器内；无选中不执行） |
| Ctrl+Alt+L | 格式化 SQL（选区或整段） |
| Ctrl+Q | 查看表/视图定义 DDL（浮窗）：优取编辑器光标下的表名（含 `schema.表`/别名），其次左侧树选中项 |
| Alt+D | 显示/隐藏结果区 |
| Ctrl+T | 结果行列转置（只剩一个值列时该列自适应加宽） |
| F5 | 刷新当前结果 Tab |

**基础编辑键（固定，不进设置，登记于同一注册表）**：

| 键 | 动作 |
|---|---|
| Ctrl+S | 保存控制台 |
| Ctrl+F / Ctrl+H | 查找替换 |
| Ctrl+Space | 编辑器主动召唤补全（按光标处前缀/限定符给候选；空前缀列上下文列/表；`SELECT *` 展开为列） |
| Ctrl+C | 结果区聚焦时复制选中单元格值（NULL → 空串） |
| ↑↓←→ | 结果区聚焦时移动选中单元格 |
| Esc | 取消当前执行 |

后置：`Ctrl+B` 收起/展开树、`Ctrl+1..9` 切结果 Tab、`Ctrl+↑/↓` 翻 SQL 历史。

全局键（Alt+D / Ctrl+Q）用 AWT `KeyEventDispatcher` 拦截（api-x 同款；X11 下修饰键+字母会额外派发字符事件，
必须在事件进入 Compose 前整颗吃掉，见 `Main.kt` 注释），编辑器/结果区内快捷键在各自 composable 层拦截。

设置窗口（非快捷键）：顶栏右上角 **设置** icon（齿轮，主题切换右侧）→ 独立 `DialogWindow`（与主窗口同款主题），
左侧分区导航「通用设置 / 快捷键」，左侧左下角显示版本号 `v<NAME>-<COMMIT>`。
「通用设置」右侧配置**编辑器字体族 / 字号**（带实时预览）；保存写 `<dataDir>/editor.properties` 并即时重排编辑器
（字号变化时行高按 20/13 等比缩放，行号槽同步），取消不改。
「快捷键」右侧按作用域分组列出可配置的业务功能，点组合键胶囊录制新键（AWT 级捕获，需含 Ctrl/Alt 或 F1–F12），
每行「重置」、底部「全部恢复默认」，同作用域冲突红字提示但不阻断；保存写 `<dataDir>/keymap.properties` 并即时生效。
版本号由构建期 `generateVersion` 任务生成 `app/build/Version.kt`（含 git short hash）。
「通用设置」底部还有**诊断**分区（P9）：展示 `AppPaths.logsDirectory()`（`<dataDir>/logs`，`-Ddbk.logDir` 可覆盖）
与数据目录，按钮经 `Desktop.open` 用文件管理器打开；无桌面环境则复制路径 + 行内提示（设置窗口是独立窗口，主窗口 Toast 会被遮住）。

---

## 11. 里程碑（从空目录到可分发）

| 阶段 | 内容 | 验收 |
|---|---|---|
| **M0 脚手架** | 拷贝 api-x 的 gradle wrapper/settings；build.gradle.kts 最小化；AppTheme + 空窗口 | `gradle run` 出窗口 |
| **M1 元数据+树** | AppPaths + app.db 迁移 + ConnectionsRepository；文件夹/连接 CRUD 弹窗；左树 + 拖拽分割 + 展开持久化 | 能建文件夹和连接档案（未真正连库） |
| **M2 JDBC 运行时** | DbDialect 四方言 + LiveConnection + 懒加载 schema 树 + 连接状态点 + 断线重连；先拿 SQLite 冒烟（无需服务端） | 树能展开到表/视图/触发器 |
| **M3 编辑执行** | SqlEditor(自动保存) + QueryExecutor + ResultPanel 网格/消息 + 历史入库 + Esc 取消 | 连上 PG/MySQL 跑查询出网格 |
| **M4 体验打磨** | 双击表预览、CSV 导出、错误展示、Toast、几何持久化、右键菜单 | 日常可用 |
| **M5 打包** | nativeDistributions + jlink modules（java.sql/java.sql.rowset/java.naming/java.management）+ MSI/Deb | 安装包可跑 |

**建议实施顺序理由**：M1 不碰 JDBC 就能把「树 + 弹窗 + 本地存储」这层最繁琐的 UI 先立起来（api-x 同路径：集合树先于 HTTP 引擎）；M2 引入真正的元数据驱动树；M3 才做执行闭环。

---

## 12. 打包与风险

- jlink 模块：JDBC/驱动需要 `java.sql`（api-x 注释里已踩过）、`java.sql.rowset`、`java.naming`（部分驱动）、`java.management`；
- 驱动体积：PG+MySQL+MariaDB+SQLite+H2 约 40MB 内，可接受；想更轻可后续做「驱动目录按需加载」；
- Oracle 驱动 license 不可中央仓库直接引入 → 放 `<dataDir>/drivers/` 用独立 classloader 加载（见 §5、`jdbc/ExternalDrivers.kt`）；
- 编辑器高亮：后置接 highlight-compose 或自写轻量 SQL tokenizer；
- 大库风险：schema 探测必须懒加载 + IO 线程，绝不启动时全量探测；
- 工具链风险：如果 Kotlin 2.4.0 + Gradle 8.3 wrapper 组合出现意外问题，退回方案是复制 api-x 完整 gradle 配置逐项对齐（同机器同缓存，概率极低）。

---

## 13. 从 api-x 提炼、本项目坚持的最佳实践清单

1. 工具链复用本机已验证组合（wrapper/Kotlin/Compose/JBR），不重新发明版本矩阵。
2. 四层分包 + 单向依赖；`jdbc/` `db/` `tree/` 不 import compose。
3. Repository 模式收敛 SQLite 访问；schema_migrations 版本化迁移。
4. 状态即 ViewModel：细分状态类 + `remember` 创建 + 回调下发，副作用收拢到 AppActions。
5. 树扁平展开渲染 + 元数据懒加载，DB 客户端大库不卡的关键。
6. JDBC 全走单线程 executor + suspend 包装，UI 线程只改 snapshot。
7. 大结果集治理：maxRows 截断、分页取数、单元格截断、CSV 独立导出通道。
8. 防抖自动保存（450ms）；窗口/分割/展开持久化。
9. 模型分层：UI 树节点 / 存储行 / JDBC 探测模型三者分离，边界转换。
10. 错误人性化：SQLState + 方言友好文案，展示在消息区而非弹窗刷屏。
11. debugHome 沙箱重定向，开发期不污染真实数据。
12. 所有长耗时动作带 loading/取消，符合桌面工具心智。
