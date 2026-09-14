# db-k Roadmap（对标 Navicat / DataGrip 的发展计划）

> 本文回答三个问题：**已定方向（§2）→ 现在有什么 / 差在哪（§3–§5）→ 接下来按什么顺序补（§6）**。
> 多协议后端（Redis / Elasticsearch）的详细设计单独成章（§7）。
> 每阶段验收仍须过 `AGENTS.md` 验证习惯（compileKotlin / test / smokeJdbc / 人工 UI 验收）。

---

## 1. 定位

**全能 IDE 方向**：在「连接 → 写 SQL/命令 → 看结果 → 改数据」日常闭环之上，向成熟产品（Navicat / DataGrip）看齐，
但**有意识砍掉重投入项**（不做 GUI 表设计器、暂不做手动事务、SSH 隧道延后），把资源压在
**查询/结果交互、行级写操作、多协议数据源（NoSQL）**上。

---

## 2. 已定方向（本轮决策记录）

| # | 决策点 | 结论 |
|---|---|---|
| 1 | 产品定位 | **全能 IDE**（但见下） |
| 2 | 结果排序 / 筛选 / 取更多 | **客户端优先**；仅「取更多」才谨慎注入分页并提示不确定性 |
| 3 | 手动事务（BEGIN/COMMIT） | **暂不做**（无实际操作场景）；行写入沿用「提交时内部单事务」 |
| 4 | 行级写操作 | **做增行 / 删行**，与改格一致按**主键定位**，无主键表只读 |
| 5 | 对象管理 | 只做 **DDL 编辑 + 执行**，**不做 GUI 表设计器** |
| 6 | 导入导出 | 导出新增 **JSON / SQL INSERT / Excel**（CSV 已有）；导入后置 |
| 7 | SSH 隧道 | **延后**，以后再说 |
| 8 | 执行计划 | **原始结果表**即可，不做可视化 |
| 9 | NoSQL | **纳入范围**，优先 **Redis → Elasticsearch**（设计见 §7） |
| 10 | 发布验证（原 P8） | **最低优先**，按需触发 |

---

## 3. 里程碑状态

| 里程碑 | 状态 | 说明 |
|---|---|---|
| M0–M4 | ✅ | 脚手架 → 元数据树 → JDBC 运行时 → 编辑执行 → 体验打磨 |
| M5 打包发布 | ⏳ 配置就绪 | AppImage 已产出；Deb / 干净环境未验证（最低优先，见 N12） |

---

## 4. 已完成能力速览（压缩）

| 能力域 | 已完成 |
|---|---|
| 数据源 | PG / MySQL / MariaDB / SQLite / H2 / ClickHouse（HTTP）+ **Redis（N5）** + **Elasticsearch（N6）**；SQL Server / Oracle 走 `<dataDir>/drivers` 外部驱动 |
| 对象树 | 文件夹 → 连接（状态点/懒加载）→ schema → 对象按类型分组计数（**组类型由会话提供，JDBC 11 类 / Redis 仅「键」**）；组折叠 + **按组懒加载**；展开持久化 |
| 编辑器 | 高亮、行号、当前行高亮；关键字 / 表视图 / 列名补全（非 SQL 后端关闭）；选中执行、多语句多 Tab；`Ctrl+Q` DDL、`Ctrl+S` |
| 结果区 | 网格滚动 / 列宽拖动 / 单元格选中复制 / 转置 / CSV（含全量流式）；**客户端排序 + 每列筛选 + 快速过滤（不重跑 SQL）+ 取更多（方言分页追加）**；**单元格编辑 + 提交（含 UPDATE 预览）+ 刷新 + 撤销**；**增行 / 删行（N3，主键定位，与改格同单事务提交）** |
| 查看器 | 长文本弹窗、JSON 树 + 高亮、Base64 图片预览、MD5 |
| 控制台 | 跨源多标签、独立执行目标（库/schema）、光标记忆、关闭可重开 |
| 连接与存储 | app.db SQLite v8（迁移 + FK 级联）、列缓存、密码 AES-256-GCM、连接档案 JSON 导入导出、正文独立 `.sql` |
| 设置与诊断 | 深浅主题持久化、编辑器字体字号、日志/数据目录入口 |
| 工程 | 单测（jdbc/tree/db/app.state/app.ui）+ `smokeJdbc`；Deb / AppImage / MSI 打包配置 |

> 外部驱动（SQL Server / Oracle）与大库懒加载**待真实环境人工验收**。

---

## 5. 对标矩阵（Navicat / DataGrip / db-k）

图例：✅ 完整 ｜ ◐ 部分 ｜ ❌ 无 ｜ 🚫 明确不做

| 能力域 | Navicat | DataGrip | db-k | 差距 / 决策 |
|---|---|---|---|---|
| 数据源数量 | ✅（含 NoSQL） | ✅（JDBC 20+） | ◐ 8 JDBC + Redis | **NoSQL 纳入（Redis ✅ / ES 待做）→ §7** |
| SSH 隧道 / SSL | ✅ | ✅ | ❌ | SSH 延后；SSL 待 N10 |
| 对象树导航 | ✅ | ✅ | ◐ | 缺表子节点、搜索过滤（N9） |
| 对象设计器 | ✅ GUI | ◐ 表编辑器 + DDL | ◐ 只读 DDL | **只做 DDL 编辑，不做 GUI（决策 5）** |
| SQL 补全 | ✅ | ✅ | ◐ | 已达日常够用 |
| SQL 格式化 / 折叠 | ✅ | ✅ | ◐ 格式化✅ / 折叠待做 | N2 |
| 查找 / 替换编辑器 | ✅ | ✅ | ✅ 正则/循环/选区 | N2 |
| 保存查询 / 片段 | ✅ | ✅ | ❌ | 可选深化 |
| 执行计划 | ✅ 可视化 | ✅ 计划树 | ◐ 仅执行 | **只做原始结果表（决策 8）→ N11** |
| 结果排序 / 筛选 | ✅ | ✅ | ✅ | N1（客户端优先） |
| 分页 / 取更多 | ✅ | ✅ | ◐ | N1「取更多」已做（方言分页追加）；页码跳转未做 |
| 增 / 删行 | ✅ | ✅ | ✅ | N3（主键定位，已完成） |
| 手动事务 | ✅ | ✅ | 🚫 暂不做 | 决策 3 |
| 导入 | ✅ 多格式 | ✅ | ❌ | 后置 |
| 导出 | ✅ 多格式 | ✅ | ◐ CSV | N8：+JSON / SQL INSERT / Excel |
| 表结构设计 | ✅ GUI | ✅ | 🚫 GUI | 用 DDL 编辑替代（决策 5） |
| Schema / 数据对比 | ✅ | ◐ | ❌ | 可选深化 |
| 用户 / 权限 | ✅ | ✅ | ❌ | 可选深化 |
| 会话 / 锁监控 | ✅ | ✅ | ❌ | N11 顺带 |
| 多窗口 | ✅ | ✅ | ◐ 多控制台 | 可选深化 |
| 深浅主题 | ✅ | ✅ | ✅ | — |

---

## 6. 新一轮路线（按优先级）

优先级原则：**日常查询闭环（N1–N3）→ 多协议数据源（N4–N6）→ 对象与数据流转（N7–N9）→ 连接/运维（N10–N11）→ 发布（N12）**。
每阶段独立可交付、可单独验收。N4（后端抽象）/ N5（Redis）已完成，为 N6 ES 铺好接口。

### 第一优先：日常查询闭环

#### N1 结果网格交互（排序 / 筛选 / 取更多）✅ 已实现
- **对标**：DataGrip 排序 / 按列筛选 / `Fetch more`；Navicat 网格排序筛选。
- **要点**
  1. ✅ 客户端排序（多列稳定、空值恒最后、数值优先比较）+ 每列筛选（`=`/`!=`/`>`/`>=`/`<`/`<=`/`~`/默认包含）+ 顶部快速过滤（纯视图层，不重跑 SQL）；
  2. ✅ 行号列（已有）+ 快速过滤定位命中（筛选条显示「命中 N / 共 M」）；
  3. ✅ 「取更多」：按方言注入分页（默认 `LIMIT/OFFSET`；SQL Server/Oracle `OFFSET … FETCH`，SQL Server 无 ORDER BY 自动补 `ORDER BY (SELECT NULL)`），
     **追加不替换**；无 `ORDER BY` 时 Toast 提示「顺序不保证」；原查询已含分页子句时禁用；仅此操作才重写 SQL（决策 2）；
  4. ✅ **编辑 overlay 与排序/筛选视图的坐标映射**：`ResultView.rowOrder`（视图行→原始行）
     加上 `displayToOriginal` 统一处理转置，编辑/复制/INSERT 全部回到原始坐标。
- **实现**：`app/ui/ResultView.kt`（纯逻辑 + 单测）、`ResultTable` 接入视图层、`ConsoleState.fetchMore`、`DbDialect.paginate`。
- **验收**：排序/筛选不触发重新执行；取更多追加且编辑坐标不错位；`ResultViewTest` / `ConsoleStateRunTest.fetchMore` / `DbDialectTest.paginate` 全绿；深色待人工复查。

#### N2 SQL 编辑器专业度（格式化 / 查找替换 / 折叠）◐ 格式化与查找替换已实现，折叠待做
- **对标**：DataGrip 格式化、查找替换（正则）、代码折叠。
- **要点**
  1. ✅ **SQL 格式化**（自研零重依赖，`app/ui/SqlFormatter.kt`）：缩进 / 关键字大小写（UPPER/LOWER/PRESERVE）/ 子句换行 /
     顶层列表每项一行 / 顶层 AND·OR 换行；**语义不变**由「词元签名双向断言」保证（`tokens(format(x)) == tokens(x)`，大小写归一），
     另测幂等性。动作：顶栏图标 / `Ctrl+Alt+L`；有选区只格式化选区，整段则整段。
  2. ✅ **查找替换**（`app/ui/SqlFindReplace.kt` 纯逻辑）：字面量 / 正则、区分大小写、整词；循环下一个/上一个（Enter / Shift+Enter）、
     计数「n/m」、命中滚动到视口中部；打开时用选区预填（选区查找）；替换 / 全部替换（正则支持 `$1` 分组）。
     入口：顶栏图标 / `Ctrl+F`·`Ctrl+H`；撤销依赖 `BasicTextField` 内建 undo（**待人工确认**）。
  3. ❌ **代码折叠（按语句 / 括号 / CTE）待做**：当前编辑器把语法高亮 span 直接叠加在 `BasicTextField` 的 `TextFieldValue` 上，
     折叠需引入「显示偏移 ↔ 真实偏移」映射层（选区、光标落库、补全、行号、onValueChange 全部要换算），
     侵入性大且易破坏现有编辑器，故单独立项后再做。
- **验收**：格式化语义不变（`SqlFormatterTest` 词元签名 + 幂等）✅；查找替换可撤销（待人工 UI 确认）◐。

#### N3 行级写操作（增行 / 删行）✅ 已实现
- **对标**：Navicat / DataGrip 网格增删行。
- **要点**
  1. ✅ 结果网格「插入行 / 删除行」：`ResultEdits` overlay（单元格改值 + 待插入行 + 待删除行）纯内存暂存，
     工具条 `AddRow`/`DeleteRow` 图标（删除按当前选中行标记，可再点撤销；已有待删行时批量二次确认）；
  2. ✅ 与改格共用提交管线：`RowUpdater.executeWriteBatch` 单事务按 **DELETE → UPDATE → INSERT** 执行，
     每条 `affected==1` 校验、任一失败整体回滚；提交前预览（`renderWriteSql`）扩展覆盖 INSERT/DELETE；
  3. ✅ **主键定位**：复用 `buildEditPlan` 判定——无主键 / 键不在结果列 / 视图 → 整表只读，增删一并禁用；
     待删除行不可再改格（标记删除时丢弃该行改格），未填写任何值的待插入行提交前拦截；
  4. ✅ 不引入手动事务（决策 3）：提交成功仍固定刷新当前 Tab 取服务端权威值。
- **实现**：`jdbc/RowUpdater.kt`（`InsertPlan`/`DeletePlan`/`WriteOp`/`executeWriteBatch`）、
  `app/ui/ResultWritePlan.kt`（`ResultEdits`/`PendingInsert`/`buildWriteOps`）、
  `ConsoleState`（overlay 读写 + `commitEdits`/`previewCommit`）、`SqlWorkspace`（待插入行渲染 + 行级操作）。
- **验收**：`compileKotlin / test / smokeJdbc` 全绿；新增 `ResultWritePlanTest`（9）、
  `RowUpdaterTest` 增补 INSERT/DELETE/混合回滚（4）、`ConsoleStateTest` 增补 overlay（3）、
  `smokeJdbc` row-updater 段增补增删改单事务。⚠️ Compose UI 交互待人工验收（含深色）。

### 第二优先：多协议后端（NoSQL，设计见 §7）

#### N4 后端抽象重构（行为不变）✅ 已实现
- 新增 `engine/` 协议无关契约层：`DataSourceSession`（元数据 + `runStatement` + `cancel`）、
  `BackendCapabilities` 能力位、`Protocol` / `EditorLanguage`；中立模型移到 `engine/model`
  （`SchemaMeta` / `SchemaObjects` / `ColumnMeta` / `QueryResult` 等，`engine` 不反向依赖 `jdbc`/`db`）。
- `LiveConnection` 实现 `DataSourceSession`；JDBC 专属写回另立 `jdbc.EditableSession`
  （`applyUpdatePlans`），app 层按能力位判断，不再直接碰 `Connection`。
- `ConnectionsState` 改持 `DataSourceSession`（新增 `sessionOf`，JDBC 专属路径用 `jdbcConnection`）；
  `ConsoleState` 的执行/刷新/提交经会话接口，`runStatement` 内部封装 context 切换与 cancel 登记。
- **行为零变化**：`compileKotlin / test / smokeJdbc` 全绿；新增 `JdbcSessionContractTest` 锁定契约与能力位。
- **验收**：✅ 无功能差异（待后续 Redis/ES 实现同一接口时验证扩展点）。

#### N5 Redis 后端（命令台 + key 浏览）✅ 已实现
- 新包 `redis/`：`RedisSession`（Jedis 5.2，阻塞式，实现 `engine.DataSourceSession`，所有调用串行到
  单线程执行器，`cancel()` 跨线程断连后重连）+ `RedisProtocol`（纯逻辑：按行切命令 / 引号分词 /
  回复→二维结果渲染，可单测）。
- **对象组数据驱动**（N5 顺带重构）：`ObjectKind` 新增 `KEY` + `SQL_OBJECT_KINDS`；
  `DataSourceSession.objectGroups()` 由会话提供，树不再硬编码组枚举（`ObjectGroupKind` 删除，
  `TreeRowInfo.groupKind: ObjectKind?`），JDBC 维持原 11 组，Redis 仅「键」组。
- **命名空间即过滤器（Redis 浏览重构）**：`BackendCapabilities.namespaceAsFilter=true` 时，DB 不再是树的一级，
  连接节点下直接是「过滤条（DB 下拉 + 类型下拉 + key 模式搜索）+ 键组」；同时只有**一个当前 DB**
  （`ConnectionsState.activeNamespaceOf` 为权威值，树过滤条与工作台 TargetSwitcher 双向同步）。
  切 DB 只保留该 DB 的键缓存并重扫；pattern 输入 300ms 防抖后重扫（`SCAN MATCH`）。
- **键搜索 / 分页 / 标注**：`DataSourceSession.searchObjects(ns, KEY, ObjectSearch)` 返回
  `ObjectSearchResult(objects, nextCursor, finished)`；`RedisSession` 用 `SCAN MATCH ... COUNT 200`
  分批扫描（去重），凑够 500 条或扫完为止，超过则树底「继续扫描（已显示 N）」按游标续拉；
  每页 pipeline `TYPE`+`TTL` 标注行尾「类型 · TTL」。类型过滤在客户端完成（Jedis 5.2 `ScanParams` 无 `TYPE`）。
  过滤条的 DB/pattern/类型按连接持久化到 `<dataDir>/redis.properties`（`app/settings/RedisPrefs`）。
- 命名空间列表 = DB（`CONFIG GET databases` 探测，失败回落 db0–db15）；连库只取 `DBSIZE` 计数，
  键组完全懒加载（仅在选中 DB 时拉首页），且**键易变、绕过 `MetaCache`**。
- 双击 key → 按类型生成查看命令（`GET`/`HGETALL`/`LRANGE`/`SMEMBERS`/`ZRANGE`/`XRANGE`），
  控制台目标解析见 `ConsoleState.sessionContextSqlFor`：flat 协议用连接级 `activeDb`，与 `console.target` 解耦
  （Redis 工作台隐藏 TargetSwitcher 的「默认」项，标签显示「DB」）；命令台可跑任意原生命令（含写命令），
  回复统一转二维网格（`HGETALL`/`CONFIG` 双列，嵌套数组扁平化，nil/空集合占位）。
- **危险命令二次确认**：`RedisProtocol.dangerousCommand`（`FLUSHALL`/`FLUSHDB`/`SHUTDOWN`/
  `SWAPDB`/`DEBUG`/`SCRIPT`/`REPLICAOF`）；`ConsoleState` 注入 suspend 钩子 → 复用 `ConfirmDialog`。
- 接线：`app/state/SessionFactory` 按 `dbType.protocol` 创建会话（唯一知道所有实现的地方）；
  `ConsoleState` 按协议切分语句（SQL `;` / Redis 按行）；`sqlCompletion` 能力位关闭 Redis 的 SQL 关键字补全；
  连接编辑弹窗「测试连接」改走 `SessionFactory`，Redis 显示「连接串」而非 JDBC URL。
- **验收**：`compileKotlin / test / smokeJdbc` 全绿；新增 `RedisProtocolTest`（9）、`RedisSessionTest`（3）、
  `SessionFactoryTest`（2）；新增 `gradle smokeRedis` 真服务端自检（建连 / 五类键 / SCAN+TYPE / 命令渲染 /
  搜索+类型过滤+TTL / 600 键分页去重 / 取消重连），已对无密码、`requirepass`、ACL user 三种服务端实测通过。
  ⚠️ Compose UI 交互待人工验收。

#### N6 Elasticsearch 后端（索引浏览 + DSL 查询）✅ 已实现
- 新包 `es/`：`ElasticsearchProtocol`（纯逻辑：DSL 解析 / `from·size` 改写 / `_search` 响应→二维结果 /
  `_cat` 与 `_mapping` 解析 / 错误提取，可单测）+ `ElasticsearchSession`（`java.net.http.HttpClient`，
  实现 `engine.DataSourceSession`，在途请求登记 `CompletableFuture`，`cancel()` 取消请求）。
- **连接**：复用现有 host/port/database/user/password/extraParams（**无表结构迁移**）。
  `extraParams` 支持 `scheme=https`、`path=/es`（反向代理前缀）；`user`+`password` = Basic 认证，
  **用户名留空、密码非空 = API Key**（`Authorization: ApiKey`，密码仍走 vault 加密存储）；
  `database` = 默认索引（可空）。编辑弹窗类型切换 / 测试连接 / 连接地址预览均按协议适配。
- **浏览**：命名空间 = 集群（`GET /` 的 `cluster_name`）；对象组 = 索引 / 别名（数据驱动 `objectGroups()`，
  `lazyObjectGroups=true` 连库只取计数，展开组再拉 `_cat/indices` / `_cat/aliases`）；
  `objectDdl` = 格式化的 `_mapping`（树右键「查看映射」+ `Ctrl+Q`），`loadColumns` 扁平展开嵌套与 multi-field。
- **查询**：控制台输入 JSON 对象 DSL，顶层可选 `index`/`_index` 指定目标索引（缺省回落连接默认索引，
  再缺省则 `/_search` 全集群）；未被识别的键原样作 `_search` 请求体，`from`/`size` 缺省注入（默认 100）。
  双击索引/别名生成 `match_all` DSL 骨架（插入控制台不自动执行），右键「复制查询（DSL）」。
- **结果与分页**：命中→行，列 = `_index` / `_id` / `_score` + 各 `_source` 顶层字段并集（对象/数组落紧凑 JSON 文本）；
  复用 N1「取更多」：`BackendCapabilities.fetchMore` + `DataSourceSession.paginate()`（ES 改写 `from/size`）——
  分页从 JDBC 专有 `DialectRegistry` 提到能力位 + 会话方法，JDBC/ES 共用同一 UI 路径。
- **编辑器**：`editorLanguage=JSON` 能力位驱动 JSON 语法高亮（`JsonSupport` 复用），并关闭 SQL 补全。
- **验收**：`compileKotlin / test / smokeJdbc` 全绿；新增 `ElasticsearchProtocolTest`（13）、
  `ElasticsearchSessionIntegrationTest`（9，用本地 `HttpServer` 假装 ES 跑端到端）、`SessionFactoryTest` 增补（2）；
  新增 `gradle smokeEs` 真服务端自检（建连 / 集群名 / 建索引+写文档 / 计数与清单 / `_mapping` / DSL 搜索 /
  `from·size` 分页 / 预览，最后删临时索引；连不上打印 SKIP）。
  ⚠️ Compose UI 交互（含深色）待人工验收。

### 第三优先：对象与数据流转

#### N7 对象管理：DDL 编辑与执行
- 现有 DDL 查看升级为「编辑并执行」（`Ctrl+Q` 查看 → 可切换到可编辑 DDL 窗口，执行走当前控制台）。
- **不做** GUI 表设计器（决策 5）。DDL 由方言 `tableDdl` 生成初稿，用户自行修改。
- **验收**：编辑后的 DDL 可在演示库执行；失败有可读错误。

#### N8 导出扩展：JSON / SQL INSERT / Excel
- 结果区导出格式新增 JSON、SQL INSERT（可指定表名/批量大小）、Excel（Apache POI）。
- CSV 已支持（含全量流式）；导入（CSV 向导）后置。
- **待定**：POI 体积/许可确认（§8 Q5）。
- **验收**：导出文件可被第三方工具读回；大结果导出不 OOM（流式写入）。

#### N9 树导航增强
- 表子节点（列 / 索引 / 外键 / 触发器，按需懒加载，复用列缓存）；树内搜索 / 过滤；
  集群级目录（DB Objects / Server Objects）。
- **验收**：大库展开子节点不卡；搜索命中可键盘跳转。

### 第四优先：连接与运维

#### N10 连接增强（SSL / 驱动管理 / 测试连接）
- SSL/TLS 与高级 JDBC 参数面板；外部驱动管理 UI（列出已加载驱动、打开驱动目录）；「测试连接」按钮。
- SSH 隧道**延后**（决策 7）。
- **验收**：新建连接可测连通性；驱动缺失有明确指引。

#### N11 执行计划与运维面板
- `EXPLAIN` 走现有执行路径，结果**原始表格**呈现（决策 8）；
- 会话列表 + 锁等待（按方言能力，支持取消会话）。
- **验收**：演示库能出计划结果；会话面板可刷新并取消指定会话。

### 最低优先

#### N12 发布验证与分发（原 P8）
- Deb / AppImage 干净环境安装自检；MSI 需 Windows；一条命令 `compileKotlin + test + smokeJdbc`。
- 触发条件：对外分发或版本发布时再做。

### 可选深化（不单独排期）
保存的查询 / 片段、`FOR UPDATE` 行锁、schema diff / 数据对比、用户/权限管理、ER 图、多窗口、SSH 隧道、CSV 导入向导。

---

## 7. 多协议后端设计（Redis / Elasticsearch）

### 7.1 现状：一切都绑在 JDBC 上
- `LiveConnection` 内含 `java.sql.Connection` + `DbDialect`；`QueryExecutor` 只吃 SQL 文本；
  `ConnectionsState` / `ConsoleState` 直接依赖 `LiveConnection`。
- `QueryResult(columns, rows, affectedRows)` 是**二维表**，天生适合 ES，不太适合 Redis（值是结构化的）。

### 7.2 目标抽象
新增 `engine/` 包（不依赖 compose/coroutines），把「连接 + 元数据 + 执行」提到接口；
`jdbc/` 与未来 `redis/`、`es/` 都是它的实现：

```kotlin
// engine/model
enum class Protocol { JDBC, REDIS, ELASTICSEARCH }
data class BackendCapabilities(
    val editableResult: Boolean,   // 是否支持改格/增删
    val sqlCompletion: Boolean,    // 关键字/表/列补全
    val ddl: Boolean,              // 是否支持对象定义
    val serverContext: Boolean,    // 会话级 schema 切换（PG search_path 等）
    val lazyGroups: Boolean,       // 复用 P6 能力位
    val editorLanguage: EditorLanguage,  // SQL | REDIS_COMMAND | JSON
)
data class ExecutionRequest(val text: String, val namespace: SchemaMeta?, val target: String?)

// engine/runtime
interface DataSourceSession : AutoCloseable {
    val profileId: String
    val protocol: Protocol
    val capabilities: BackendCapabilities
    fun connect()
    fun loadNamespaces(): List<SchemaMeta>              // schema / Redis DB / ES 集群
    fun loadObjects(ns: SchemaMeta): SchemaObjects      // 复用现有模型
    fun loadColumns(ns: SchemaMeta?, name: String): List<ColumnMeta>
    fun preview(ns: SchemaMeta?, name: String): String  // 生成预览语句/命令
    fun execute(req: ExecutionRequest, limit: Int, onStatement: (Any?) -> Unit): List<StatementOutcome>
    fun cancel()
}
```

- **JDBC 实现**：现有 `LiveConnection` + `DbDialect` 包一层，`capabilities` 全 true（按方言细调）；
  JDBC 专属概念（主键定位、`RowUpdater`、DDL）留在 `jdbc` 内，不污染通用层。
- **执行入口不变**：`Ctrl+Enter` → `session.execute(...)`，只是「语句切分」不同（SQL 分号 / Redis 单行 / ES 单个 JSON body）。
- **结果模型**：二维 `QueryResult` 通用；对 Redis 的非表格值，允许后端产出「结构化文本视图」
  （走现有查看器 / JSON 树），作为 `StatementOutcome` 的一种形态。

### 7.3 树与结果如何复用
- 树：命名空间沿用 `SchemaMeta`（Redis = `db0..db15` 但以过滤条呈现，ES = 集群名）；对象沿用 `SchemaObjects` + `ObjectKind`
  （Redis key 类型 / ES index），懒加载沿用 P6 的 `lazyGroups`。
- 结果：ES 文档 → 行 = 文档、列 = `_id/_score/_source`（`_source` 可双击进 JSON 树）；
  Redis → key/value/ttl 网格 + value viewer。
- 编辑器：按 `editorLanguage` 切换高亮/补全（Redis 命令补全；ES 用现成 JSON 高亮）。

### 7.4 Redis 设计要点（已实现，见 N5）
- **连接**：host / port / password（支持 ACL user）/ db；无 JDBC URL，编辑弹窗显示 `redis://host:port/db`。
- **客户端选型**：**Jedis**（阻塞式，契合「阻塞 API + app 层 IO 包裹」）；Lettuce 需 Netty，暂不引。
- **浏览（命名空间即过滤器）**：DB 不进树层级，而是连接下的过滤条（DB 下拉 + 类型下拉 + key 模式搜索）；
  `SCAN MATCH` 游标分页，「继续扫描」续拉；每页 pipeline `TYPE`+`TTL` 作文本后缀；
  同时只查看一个 DB（`activeDb`），树与工作台同步。
- **查看**：双击 key 按类型生成查看命令，在控制台出二维网格（非表格值走占位/扁平化文本）。
- **命令台**：任意原生命令（含写命令），回复统一转二维结果；危险命令二次确认。
- **未做**：专用 value viewer（JSON 树 / 图片 / TTL 编辑）、命令补全、Redis 命令语法高亮
  （`editorLanguage` 能力位已预留）、工作台内表格式 key 浏览器（方案 B，暂不做）。

### 7.5 Elasticsearch 设计要点（已实现，见 N6）
- **连接**：host/port + 账号密码（Basic）/ API Key（用户名留空、密码填 key）；HTTPS 与反向代理前缀走 `extraParams`
  （`scheme=https`、`path=/es`）；`database` 作默认索引复用现有连接表（无迁移）。
- **客户端选型**：✅ **`java.net.http.HttpClient` + `kotlinx.serialization.json`**（零重依赖，jlink 仅加 `java.net.http` 模块）；
  官方 `elasticsearch-java` 不引入（体积 + 兼容面）。
- **浏览**：`_cat/indices` / `_cat/aliases?format=json` 列索引与别名；`_mapping` 展开字段（嵌套 / multi-field 扁平化）。
- **查询**：控制台输入 JSON DSL（顶层 `index` + `_search` 请求体）→ 网格；分页 `from/size` 走 N1 能力位；
  错误响应提取 `error.reason` 作可读提示。
- **结果**：列 = `_index` / `_id` / `_score` + `_source` 顶层字段并集；行 = 文档；嵌套值落紧凑 JSON 文本。
- **未做**：`search_after` / PIT 深分页（`from+size` 上限 10000）、聚合结果专用视图、写操作
  （PUT/POST/DELETE 文档）、ES 专用补全（`editorLanguage` 已预留）。

### 7.6 分期与风险
- 分期：**N4 抽象重构 → N5 Redis → N6 ES**。
- 风险 1：**抽象过度**——JDBC 路径很深（`RowUpdater` / 补全 / DDL / 事务），
  用 `capabilities` 能力位隔离，避免通用层出现 JDBC 专属概念。
- 风险 2：**二维结果模型对 Redis 不自然**——保留「结构化文本视图」逃生通道。
- 风险 3：**依赖体积**——Jedis / POI / HTTP 客户端都需在打包时验证 jlink 模块与产物大小。

---

## 8. 待定问题（接着定）

| # | 问题 | 影响 |
|---|---|---|
| Q1 | 「全能 IDE」是否要 ER 图 / 权限管理 / schema diff？（当前列在可选深化） | 决定是否从「可选」升为独立阶段 |
| Q2 | Redis 客户端：Jedis（倾向）还是 Lettuce？ | ✅ 已定：Jedis（N5 已实现） |
| Q3 | ES：走 `HttpClient`（倾向）还是官方 `elasticsearch-java`？兼容 ES 7.x / 8.x 哪些？ | ✅ 已定：`java.net.http.HttpClient` + `kotlinx.serialization.json`（N6 已实现）；未特化版本，按 REST 通用处理 |
| Q4 | Redis/ES 控制台是否允许写命令（SET/DEL/PUT/POST）？还是第一版纯只读？ | ✅ 已定：允许写命令 + 危险命令二次确认（N5） |
| Q5 | Excel 导出确认引入 Apache POI？体积 / 许可可接受吗？ | N8 依赖 |
| Q6 | Redis TLS / ES HTTPS 是否 N5/N6 就要求？（SSH 已延后，TLS 场景不同） | 连接层设计 |

---

## 9. 阶段粒度与架构纪律

- 每阶段拆 2~5 次提交；单提交 = 一个可感知的小能力（遵守 `AGENTS.md` 分层与主题规则）。
- 任何 UI 可感知变化须人工切深色复检（无黑字沉底、无过曝白块）。
- **架构稳定项**：JDBC 单线程执行、snapshot 状态、SQLite 持久化分层不因新功能改变；
  跨层扩展优先走**能力位**（如 P6 的 `lazyObjectGroups`、§7 的 `BackendCapabilities`）。
- 持久化新增：连接表已通用（host/port/db/user/password），NoSQL 连接复用；如需 `protocol` 字段则 app.db 版本 +1 迁移。

---

## 10. 关联文档

- `AGENTS.md`：工具链、分层、主题规则、持久化分层、验证习惯。
- `doc/DESIGN.md`：数据模型、方言抽象、线程模型、打包风险。
- `doc/EDITABLE_RESULT.md`、`doc/COLUMN_COMPLETION.md`：已实现子系统的设计与进度。
- `doc/PACKAGING.md`：Deb / AppImage / MSI 构建矩阵。
- `TODO.md`：零散即时事项（随做随删）。
