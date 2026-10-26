# 每个数据源可配「查询超时」- 设计

日期：2026-10-26
状态：待批准

## 意图与原因

现在超时是写死的常量：JDBC `QueryExecutor.QUERY_TIMEOUT_SECONDS = 30`（语句执行 + 会话上下文切换）、
`RowUpdater.UPDATE_TIMEOUT_SECONDS = 30`；Elasticsearch `send()` 的 HTTP 请求超时 60s；Redis 压根没设，
吃 Jedis 默认 socket timeout（毫秒级偏短）。结果是一跑十几分钟的分析查询，就被 30s / 2s 提前掐掉。

需求：**每个数据源可选配置查询超时**，重点是放宽或关掉上限，让长查询跑完。跑飞的查询不靠超时兜，
靠已有的「取消」（`DataSourceSession.cancel()`）。

## 目标

- 连接档案上多一个「查询超时」配置，随连接持久化、可导出导入。
- 语义统一可预期：`0` = 不限时；`N > 0` = 单条语句最长执行 N 秒。**默认 20 秒**（新建连接预填、存量连接迁移后也是 20）。
- 三后端都生效：JDBC（语句 + 写回）、Elasticsearch（HTTP 请求）、Redis（socket 读等待）。
- 超时错误对用户可读（一句话说明是超时，而非一串驱动原文）。

## 非目标

- 不做到「单条语句临时覆盖超时」（不加工具栏开关、不解析 SQL hint）。
- 不做全局默认超时设置项（设置窗口不新增配置）。
- 不改元数据探测 / DDL 读取 / 补全 / 探活校验（`ConnectionHealth` 的 3s、`DbDialect` 内部 5s 保持不变）。
- 不改变全量流式导出（`StreamingQuery` 保持 0 无限，导出不该被超时打断）。
- 不做「超时后自动重试」。

## 反目标（即便跑通也算失败）

- 把「0 = 不限时」在某个后端偷偷映射成别的语义（例如 Redis 仍走 Jedis 默认 2s）。
- 超时后连接状态不明、下一次执行莫名报断连而没有提示。
- 为了这个配置让连接弹窗变成多页表单或引入条件显隐规则。

## 约束

- 持久化分层（AGENTS.md）：连接是业务实体 → 落 SQLite `connections` 表，进 `app.db` 版本 +1 的迁移，
  不落 properties。
- 所有用户可见文案走 i18n key，zh + en 各一份。
- `jdbc` / `redis` / `es` 三层禁止 import compose/coroutines；三者都只读 `ConnectionProfile`，天然满足。

## 方案（已选定：A）

**A. 原生超时机制**（选定）：一个字段，三后端各自映射到自家原生能力——JDBC `Statement.setQueryTimeout`、
ES `HttpRequest.timeout`、Redis Jedis socket timeout。改动面最小，全部落在已有分层里。

**B. 统一看门狗**（放弃）：session 层自己计时，到点调现有 cancel。三后端语义完全一致、超时与手动取消
走同一条代码；但要额外维护定时器、要把「取消」和「超时」在错误上区分开，而且 JDBC 的 `Statement.cancel`
对「还在排队没轮到」的语句无效，到点也可能停不下来。在「0 = 不限 + 手动取消兜底」的前提下收益不明显。

**C. 塞进已有「附加参数」**（放弃）：如 `queryTimeout=600`。零迁移零 UI，但只在 JDBC 有意义、
Redis/ES 没地方放、不可发现、还得解析 URL 参数。

## 具体设计

### 1. 数据模型与持久化

- `db.ConnectionProfile` 新增 `val queryTimeoutSeconds: Int = 20`（0 = 不限时），放在 `displayZone` 之后。
  默认值取 20 让「没显式配过的档案」（含迁移上来的存量行、旧导出文件）与新建连接一致。
- `AppDatabase` 升到 v14：`ALTER TABLE connections ADD COLUMN query_timeout_seconds INTEGER NOT NULL DEFAULT 20`，
  迁移写法照 v13（先 `PRAGMA table_info` 查列，已有则跳过）。存量连接迁移后即 20 秒。
- `ConnectionsRepository`：`listConnections` / `getConnection` 的 SELECT 列表、`createConnection` 的 INSERT
  （占位符 +1）、`updateConnection` 的 SET、`mapRow` 全部带上该列；读出来 `coerceAtLeast(0)`。
- `ProfileTransfer`：`ConnectionDto` 加 `val queryTimeoutSeconds: Int = 20`（旧文件无该字段 → 20，
  与新建默认一致；想表达「不限时」的档案会显式写 0），encode/decode 各一处。**`FORMAT_VERSION` 保持 1**：
  字段可缺省，`encodeDefaults = false` 让等于默认值 20 的档案不写该字段，新旧文件互相兼容。

### 2. 连接编辑弹窗（`app/dialog/ConnectionEditorDialog.kt`）

- 在 `if (!isEmbedded) { … }` 整块（主机/账号/时区/附加参数）**之后**加一行 `FormRow(t(Str.FormQueryTimeout))`：
  数字输入框（只收数字、上限 5 位，与端口框同写法，宽 120dp）+ 同行浅色提示文案，`remember(request)`
  初值取 `initial?.queryTimeoutSeconds`（新建 = 20）。放在整块之后而不是「时区与附加参数之间」，
  是为了让本地库（SQLite / H2 Local）也能看到这一项。
- 同一行右侧给一句浅色提示（`onSurface` alpha 0.55）：`t(Str.QueryTimeoutHint)` =
  「秒；0 = 不限时（留空用默认 20）」。
- 所有数据源类型都显示（本地 SQLite/H2 也支持语句超时；Redis/ES 的语义差异写在这行的提示里太长，
  放进 `doc/DESIGN.md`，不在弹窗里展开）。
- `build()` 里 `queryTimeoutSeconds = timeoutText.toIntOrNull()?.coerceIn(0, 86_400) ?: 20`
  （输入框被清空时回落默认 20，而不是意外变成「不限时」）。

### 3. 后端映射

| 后端 | 落点 | `0` 的行为 |
|---|---|---|
| JDBC 执行 | `QueryExecutor.execute(..., timeoutSeconds)` → `stmt.queryTimeout = s`；`applyContext(conn, ctx, timeoutSeconds)` 同理 | 调用 `setQueryTimeout(0)`（JDBC 规定 = 无限制） |
| JDBC 写回 | `RowUpdater.executeWriteBatch(..., timeoutSeconds)` → `ps.queryTimeout = s` | 同上 |
| Elasticsearch | `ElasticsearchSession.send()` 建 `HttpRequest` 时 `s > 0` 才 `.timeout(Duration.ofSeconds(s))` | 不设请求超时（HttpClient 默认无限制）；建连 10s 不动 |
| Redis | `buildConnection()`：`Jedis(HostAndPort(host, port), redisClientConfig(profile))`，其中 `connectionTimeoutMillis` 固定 10s、`socketTimeoutMillis = s * 1000` | socket 无限等待（见风险）；建连仍限 10s，不让 0 把 TCP 建连也变成无限等 |

JDBC 侧超时值经 `LiveConnection.runStatement` / `streamQuery`(不变) / `applyWriteOps` 从 `profile` 取，
不再有 `QUERY_TIMEOUT_SECONDS` / `UPDATE_TIMEOUT_SECONDS` 常量（`applyContext` 也改成显式传参，
不再自己取常量）。游标导出、`paginate` 走的还是 `runStatement` → 自动继承。

### 4. 超时可读化

- `app/state/ConnectionsState.kt` 的 `friendlySqlError(t, timeoutSeconds: Int? = null)`：先在 cause 链里
  认 `SQLTimeoutException` / `SocketTimeoutException` / `HttpTimeoutException` / `TimeoutException`，
  命中且 `timeoutSeconds != null` 时返回 `I18n.t(Str.QueryTimeoutExceeded, timeoutSeconds)`
  （「查询超过 {0} 秒未完成，已取消」/ "Query cancelled after {0} seconds"）。参数有默认值 → 其余调用点不动。
- `ConsoleState.run` / `refreshOutcome` / `commitEdits` 三处把 `profile.queryTimeoutSeconds` 传进去
  （`run` 是长查询主战场，另外两处顺手一致）。
- 超时后不特殊处理连接：JDBC 驱动多数只是取消语句（PG/MySQL 走 cancel/KILL QUERY，autocommit 下连接仍可用）；
  真被驱动打死的，下一次调用会走 `LiveConnection` 已有的探活/重连。Redis 侧 `cancel`/重连路径已存在。

## 我的判断（会照此实现，有异议请指出）

1. **写回操作（`RowUpdater`）也受这个配置约束**：安全方向一致——用户配 0 是「我信任长查询」，
   写回本身就是短事务。若只给查询、不给写回，反而多一个没人懂的例外。
2. **默认值 20 秒**（按你的要求，原方案是 0 = 不限时）：比现在的 30s 常量更紧一点，长查询需要用户
   自己把该值调大或设 0。`0` 仍然是「不限时」的显式表达，跑飞的靠取消。
3. **Redis 的 0 = socket 无限等待**：照实映射，不偷偷回落默认 2s。风险是半死连接会一直挂着，
   只能靠取消（断 socket → 下次惰性重连）解开。写进 `doc/DESIGN.md`。
4. **`paginate`（取更多）、刷新结果、`applyContext` 目标库切换都继承该超时**；元数据/DDL/补全/探活不受影响。
5. 弹窗对三种后端都显示同一行，不做按类型分支（Redis 的 socket 超时语义写在文档里而不是 UI 里）。

## 遗留 / 交给实现者

- 纯映射逻辑（秒 → JDBC 秒 / Jedis 毫秒 / ES Duration 或 null）抽成可单测的小函数，
  单测覆盖 `0` 与正数两种取值；不强求「真的等 N 秒再超时」的集成测试（慢且易 flaky）。
- `doc/DESIGN.md` 的 `ConnectionProfile` 样例与「查询防呆：超时 30s」那句要同步改成
  「默认不限，按连接配置」。
- 手动验收：新建连接默认框里是 20；配 1 秒 + 一条慢查询 → 看到超时提示；配 0 + 慢查询 → 能跑完，
  中途「取消」仍然有效；存量连接升级后弹窗里显示 20。
