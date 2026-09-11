# 列补全设计（COLUMN_COMPLETION）

目标：在编辑器里写 `SELECT ▮ FROM table_x2` 或 `SELECT t.▮ FROM table_x2 t` 时，
按当前语句的 FROM/JOIN 上下文补全**列名**，而不是只补表名与关键字。

本文只描述方案与取舍；分阶段落地见文末。

## 1. 现状与约束

- 现有补全（`app/ui/SqlEditing.kt` + `app/ui/SqlWorkspace.kt`）只按前缀匹配
  `completionIdentifiers`（全量表/视图名，来自 `db/MetaCache.kt`）+ SQL 关键字；
- 元数据层（`jdbc/model/MetadataModels.kt`、`MetaCache`）**不存列**；
- 两条硬约束：
  1. **FROM 常在 caret 之后**：用户先写 `SELECT ` 再回头补列，解析必须针对 caret
     所在的**整条语句**，不能只看 caret 之前；
  2. **执行连接会被改写**：`ConsoleState.sessionContextSqlFor` 每次执行前切换 schema，
     因此元数据查询不能依赖执行连接当前的 schema，必须用独立、显式传 schema 的连接。

## 2. 性能 / 便捷性取舍

| 决策点 | 全量预取 | 按需拉取（采用） |
| --- | --- | --- |
| 元数据量 | 表数×列数，`getColumns("%")` 在全库上是扫描，缓存 JSON 膨胀 | 只取语句里出现的 1~3 张表，`getColumns(精确表名)` 走索引 |
| 新鲜度 | 24h TTL | 连接/刷新/删除档案时失效 |
| 首次延迟 | 连接时一次付清 | 命中内存缓存 0 延迟；未命中异步拉取后弹层出现 |
| 离线 | 可磁盘缓存 | 首期仅会话内存（P2 再落盘） |

结论：**列按需拉取**；表/视图名继续用已缓存的对象清单做兜底与 schema 解析。

## 3. 分层设计

### 3.1 元数据层（jdbc）

```kotlin
// jdbc/model/MetadataModels.kt
@Serializable
data class ColumnMeta(
    val name: String,
    val typeName: String? = null,
    val nullable: Boolean = true,
    val ordinal: Int = 0,
)

// jdbc/DbDialect.kt —— 默认走 DatabaseMetaData.getColumns(精确表名)
fun loadColumns(conn: Connection, schema: SchemaMeta?, table: String): List<ColumnMeta>
```

关键点：

- **必须传精确表名**，绝不传 `%`（性能分水岭）；
- schema 语义对齐 `loadSchemas`：`SchemaMeta.catalog`（MySQL 形态）与
  `SchemaMeta.schema`（PG/H2 形态）；SQLite 伪 schema `main` 需转 `null`；
- 大小写兜底：精确名取不到时再试大写/小写（H2 折大写、PG 折小写）；
- P2 可对 PG/MySQL/ClickHouse 用系统表覆写（更快更稳）。

### 3.2 语句解析（app/ui，纯函数、可单测）

```kotlin
data class CompletionTable(val name: String, val schema: SchemaMeta)
data class TableRef(val qualifier: String?, val schema: String?, val table: String, val alias: String?)
data class SqlScope(val tables: List<TableRef>, val complex: Boolean)
data class SqlQualifier(val qualifier: String, val wordStart: Int, val wordEnd: Int, val wordText: String)

fun statementRangeAt(sql: String, caret: Int): IntRange?   // caret 所在语句（复用分隔符词法）
fun parseTableRefs(statement: String): SqlScope            // FROM / JOIN / 逗号列表 + 别名
fun sqlQualifiedPrefix(text: String, caret: Int): SqlQualifier?  // t. / schema.t. 后的前缀
fun resolveTableRef(ref, knownTables, schemas, defaultSchema): SchemaMeta?
```

规则：

- `FROM`/`JOIN` 后读限定名 `[schema.]table`，再吃 `AS x` / 裸别名；
- 逗号列表 `FROM a, b` 都收；别名在 `ON/USING`/子句关键字前截止；
- `FROM (`（子查询/CTE）→ `complex = true`，本期放弃列补全，退回「表名 + 关键字」；
- **只把终止完整的表名当引用**（表名 token 后是空白/`,`/`)`/`;`/语句尾），避免边敲表名
  边触发查询。

schema 归属：限定名按 schema 名匹配；未限定先查已缓存对象清单（同名跨 schema 时优先
控制台 `target`），查不到回落 `target`/连接默认。

### 3.3 缓存与竞态（app/state）

`ColumnCatalog`：`mutableStateMapOf` 快照缓存 + `Mutex` 串行拉取（重复请求去重）+
每 profile LRU 上限（默认 200 表）。`peek` 同步出候选，`ensure` 异步回填，回填后快照
变化自动重组合。数据源断开/刷新/删除时 `invalidate(profileId)`。

### 3.4 连接选择：独立元数据连接

每 profile 用一个懒建的**独立 `LiveConnection`**（第二个 `java.sql.Connection`）：

- 不排队在执行线程后（长查询时补全不卡）；
- 不受 `sessionContextSql` 切换影响，schema 全部显式传入；
- 符合 AGENTS「禁止并发直连同一 `java.sql.Connection`」——从根上就是两条连接。

懒建：只有首次需要列补全时才打开，随 `disconnect`/`forget`/`disposeAll` 关闭。

### 3.5 UI 接线（SqlWorkspace）

- `Main` 由已缓存对象清单构造 `completionTables`（名 + schema），并复用其派生
  `completionIdentifiers`；
- `EditorPane` 每帧派生：语法词 / 限定符前缀 → caret 所在语句 → `parseTableRefs`
  → `resolveTableRef` → `catalog.peek`；
- 候选顺序：限定符模式只出该表列；非限定模式 = **FROM 表列 → 表名 → 关键字**；
- `LaunchedEffect(consoleId, profileId, refsKey)` 触发 `catalog.ensure`，仅对已解析出的表。

## 4. 交互结果

| 输入（▮ = caret） | 候选 |
| --- | --- |
| `SELECT na▮ FROM users` | `name`（列）+ 前缀匹配的表名/关键字 |
| `SELECT u.▮ FROM users u` | `users` 的列（别名限定） |
| `SELECT users.▮ FROM users` | `users` 的列（表名限定） |
| `SELECT public.users.▮ FROM public.users` | 同上（schema 限定） |
| `SELECT ▮ FROM (SELECT 1 AS a) s` | 无列（`complex`），退回表名/关键字 |
| 未连接 / 未知表 | 退回表名/关键字 |

## 5. 分阶段

- **P0（已实现）**：`ColumnMeta` + `loadColumns` 默认实现；`statementRangeAt` /
  `parseTableRefs` / `sqlQualifiedPrefix` / `resolveTableRef`；`ColumnCatalog` 内存缓存 +
  独立元数据连接；SqlWorkspace 接线；`t.` 支持。不落盘、不做子查询。
- **P1（已实现）**：弹层左侧类别色点 + 右侧详情（列类型 / 别名指向表 / 表所属 schema）；
  别名进候选；FROM 表名写完即预取（`TableRef.isComplete`，边敲不查）；Ctrl+Space 显式唤起
  空前缀，列出上下文列/表。
- **P2（已实现）**：列磁盘缓存（app.db 新表 `column_cache`，一行 = 一表；指纹失配按未命中；
  过期先用旧值再后台刷新；断开只清内存 `evict`，档案编辑/元数据刷新/删除才清磁盘 `invalidate`）；
  PG/MySQL/ClickHouse 列探测分别走 `information_schema.columns` / `information_schema.COLUMNS` /
  `system.columns`（失败或为空回落通用 JDBC 实现）。
- **P3（已实现）**：语句解析只看括号深度 0（内层子查询 FROM 不污染）；`FROM (子查询) alias`
  记为派生表（无列）、无别名时置 `complex`；`WITH c(a,b) AS (…)` 的显式列名可补全，
  CTE 名不再被当真实表查 schema；函数/过程/聚合名进候选（PG 等探测到的）；
  `SELECT *` 可 Ctrl+Space 展开为 FROM 表列（`insertText` 上屏，不自动弹层以免劫持 Enter）。
- **P4（已实现）弹层触发时机**：只有**文本真正变化**（敲字/删除/粘贴）才自动弹层；
  鼠标点击/移动光标等纯选区变化不弹（`commitEdit` 里 `editing = v.text != value.text`）；
  Ctrl+Space 可随时主动召唤（基于光标处的前缀/限定符给候选，空前缀列上下文列/表）；
  接受候选项后不自动重开，等下次敲键。

## 6. 性能预算与风险

- 首次 `ensure` 1~3 表典型 < 50ms（冷连接可能 ~100ms+），异步展示可接受；命中缓存 0 查询；
- 风险：跨 schema 同名表歧义（target 优先）、元数据权限不足（吞异常回落）、
  子查询误补（`complex` 放弃）、`schema.` 与 `alias.` 歧义（先别名后 schema）；
- 明确不做：没有表就猜列名。
