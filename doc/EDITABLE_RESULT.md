# 可编辑查询结果（Cell Editing & Commit）设计方案

> 状态：**Phase 2 已实现**（编辑/提交/刷新 + 撤销全部/退出守卫 + 唯一索引回落）。目标读者：后续实现者。
> 与 `AGENTS.md` 冲突时以 `AGENTS.md` 为准。实施进度见文末 §13。

## 0. 背景与目标

当前结果区是**只读**的：`ResultTable` 只能选中/复制/转置/导出。本方案给结果表格加"改一格"的能力，
并把它做成一个**安全的、有事务语义的**小事务工具，而不是简单的字符串拼 SQL。

目标：

1. 结果表格里能改某个单元格 → 本地暂存（不立刻打库）→ 一键**提交**。
2. 提交走**参数化 UPDATE + 单事务 + 影响行数校验**，绝不字符串拼值。
3. 结果区工具条加**提交**与**刷新查询结果**两个图标；刷新会**丢弃未提交修改**，必须先提示确认。
4. 只对"能安全定位到唯一一行"的结果开放编辑；定位不了就明确只读，不做危险猜测。

非目标（本期不做）：

- 新增行 / 删除行。
- 单元格富类型编辑（BLOB/图片/JSON 结构化编辑仍走现有查看器）。
- 结果的本地持久化（未提交修改是**纯瞬态**，见 `AGENTS.md` 持久化分层）。

---

## 1. 总体交互

```
结果表格
 ├─ 选中单元格
 ├─ 普通双击            → 查看完整内容（现有 JSON/图片/MD5 查看器，保持零改动）
 ├─ Ctrl + 双击         → 进入编辑（单行内联；长值/多行 → EditCellDialog）
 │    ├─ Enter            → 暂存并移到下一行
 │    ├─ Tab / Shift+Tab  → 暂存并移到右/左列
 │    └─ Esc              → 放弃本次编辑
 ├─ 改动的格子          → 琥珀色底纹 + 左侧色条（未提交）
 ├─ 右键                  → 编辑单元格 / 设为 NULL / 撤销此格修改 / 查看完整内容（保留）
 └─ 工具条（结果区顶部 28dp）
      [提交 ●N] [刷新]   [撤销全部] | [转置] [导出 CSV] [导出全量]
```

- 「提交」图标在有未提交修改时高亮 + 徽标数字；无修改时置灰。
- 「刷新查询结果」= **只重新执行当前结果 Tab 对应的那条语句**，不动其余 Tab、不动编辑器草稿。
- 有未提交修改时点刷新 → `ConfirmDialog`：
  「有 N 处修改尚未提交，刷新将丢弃这些修改。」`[刷新并丢弃]` / `[取消]`。
- **查看与编辑入口分离**：普通双击仍是现有查看器；**Ctrl + 双击**才进入编辑。
  实现要点：`detectTapGestures` 的回调拿不到修饰键，需在 `SqlWorkspace` 根层用
  `onPreviewKeyEvent` 维护 `ctrlDown` 状态（或自定义 `awaitPointerEventScope` 读
  `PointerEvent.keyboardModifiers.isCtrlPressed`），在 `onDoubleClick` 里据此分流。

> **关于"中间的分隔"图标位置**：实际落在**结果区顶部 28dp 工具条**（`ResultToolbar`，即编辑器与结果之间那条）。
> 不建议把按钮放进 5dp 的 `ResultSplitter`——那是拖拽热区，放图标既看不清也点不准。

---

## 2. 可编辑性判定（最关键的前置）

### 2.1 用驱动元数据，而不是解析 SQL

`parseTableRefs` 只够做补全，判定"能不能改"太脆弱。改为从 **`ResultSetMetaData`** 取每列的来源：

扩展 `jdbc/QueryExecutor.kt` 的 `QueryColumn`（`readResultSet` 里填充）：

```kotlin
data class QueryColumn(
    val name: String,                 // 展示列名（原 getColumnLabel）
    val catalog: String? = null,      // meta.getCatalogName(i)
    val schema: String? = null,       // meta.getSchemaName(i)
    val table: String? = null,        // meta.getTableName(i)
    val baseColumn: String? = null,   // meta.getColumnName(i)（表达式/别名常为空）
    val sqlType: Int = Types.OTHER,   // meta.getColumnType(i)
    val nullable: Boolean = true,     // meta.isNullable(i)
    val readOnly: Boolean = false,    // meta.isReadOnly(i) || isAutoIncrement(i)
)
```

判定规则：

| 情况 | 结果 |
|---|---|
| 列有非空 `baseColumn` + `table`，且同属一张基表 | 该列**可编辑** |
| 表达式/聚合/常量（`baseColumn` 或 `table` 为空） | 该列**只读**（其余列仍可编辑） |
| 来源表是 VIEW / 物化视图 | 整个结果**只读**（不可靠的 updatable view 不碰） |
| BLOB / 二进制列 | 只读（当前 `cellToString` 是有损的 `[N bytes]`） |
| auto-increment / generated 列 | 只读（见 §3.3；主键列本身可改，WHERE 用旧值） |

**逐列判定**，不是整表判定：`SELECT id, name, upper(name) AS up FROM users` 里 `id`/`name` 可编辑、`up` 只读。

### 2.2 行定位键（必须有）

能改的前提是能唯一定位一行。优先级：

1. **主键**：列出的全部 PK 列都出现在结果中 → 可编辑。
2. **唯一索引**：无 PK 时退而求其次，选一组"列最少的唯一索引"，其列全部在结果中。
3. 都没有 → **只读**（不提供任何"全列匹配"回退，避免重复行误改多行）。

判定在结果落地后、真正编辑前完成（键查询异步 + 缓存），拿不到键时单元格不可进入编辑。

### 2.3 键元数据的来源与缓存

复用现有 `ColumnCatalog`（会话内存 → 磁盘 → 目标库，带 TTL）：

- `jdbc/model/MetadataModels.kt` 的 `ColumnMeta` 增加 `primaryKey: Boolean = false`（带默认值，磁盘缓存向后兼容）。
- 各方言 `loadColumns`（默认实现 `queryTableColumns`）里顺带查 `DatabaseMetaData.getPrimaryKeys(...)` 打标。
- 唯一索引（无 PK 时的回落）：`getIndexInfo(unique=true)` 选**列数最少**的唯一索引作为行定位键（已实现，见 §13 Phase 2）。

这样 §2.2 的判定在 `ColumnCatalog.peek()` 命中时**同步**可得，未命中时异步拉取并回填。

### 2.4 计算产物

新增纯逻辑 `app/ui/ResultEditPlan.kt`（无 compose 依赖，便于单测）：

```kotlin
data class EditableColumn(val index: Int, val baseColumn: String, val sqlType: Int)
data class EditPlan(
    val catalog: String?, val schema: String?, val table: String,
    val columns: List<EditableColumn>,        // 结果列下标 → 真实列
    val keyColumns: List<Int>,                // 定位用的结果列下标（缺失即整结果只读）
)
fun buildEditPlan(result: QueryResult, keyNames: Set<String>): EditPlan?
```

`buildEditPlan` 在 `ConsoleState.run` 成功后调用（有 `QueryColumn` 元数据 + `ColumnCatalog`），
结果随 `ConsoleRunUi` 一起放内存；UI 同步读它决定单元格是否可编辑。

---

## 3. 行定位与 UPDATE 生成

### 3.1 生成器（`jdbc/RowUpdater.kt`，纯 JDBC、阻塞、无 compose）

```kotlin
data class CellValue(val raw: String?)               // null = SQL NULL

data class UpdatePlan(
    val schema: SchemaMeta?, val table: String,
    val sets: List<Pair<String, CellValue>>,         // 目标列 → 新值
    val keys: List<Pair<String, CellValue>>,         // 键列 → 原始值（来自改动前的行）
)

fun renderUpdateSql(plan: UpdatePlan, dialect: DbDialect): String   // 预览/历史用（值转义成字面量）
fun executeBatch(conn: Connection, plans: List<UpdatePlan>, dialect: DbDialect,
                 registerStatement: ((Statement?) -> Unit)?): Int
```

- 标识符一律 `dialect.quoteIdent(...)`；表/列名**只能来自元数据**，不来自用户输入。
- WHERE 用**原始值**（`sets` 用新值）——否则改了主键就找不到行。
- `raw == null` 时：SET 用 `col = ?` + `setNull`；WHERE 用 `col IS NULL`（不能 `= NULL`）。

### 3.2 值类型绑定（关键的正确性点）

当前单元格值都是字符串，直接 `setString` 在 PG 等库上会因类型不匹配失败。按列的 `sqlType` 显式转换：

| `java.sql.Types` | 绑定方式 | 失败文案 |
|---|---|---|
| INTEGER/BIGINT/SMALLINT/TINYINT | `setLong`/`setBigDecimal`（先 parse） | 「不是合法的整数」 |
| DECIMAL/NUMERIC | `setBigDecimal` | 「不是合法的小数」 |
| FLOAT/REAL/DOUBLE | `setDouble` | 「不是合法的浮点数」 |
| BOOLEAN/BIT | `setBoolean`（接受 true/false/1/0） | 「不是布尔值」 |
| DATE / TIME / TIMESTAMP | `setDate/Time/Timestamp`（ISO 解析） | 「不是合法的日期/时间」 |
| 其它（CHAR/VARCHAR/TEXT/JSON/ENUM…） | `setString` | —— |

转换失败**在提交前**逐条报出（指出第几行第几列、原值、期望类型），不产生半截事务。

### 3.3 主键 / 生成列的规则

- 键列出现在结果里 → 参与 WHERE；用户改键列本身也允许（WHERE 仍用旧值）。
- 自动列（`isAutoIncrement`、PG identity/generated）标记只读，不允许编辑。

### 3.4 无可用键即只读（明确决策）

无主键且无可用唯一索引时**不允许任何修改**，不提供"全列匹配"的不安全回退（重复行会误改多行）。
单元格保持只读，tooltip 说明「该结果缺少用于定位的主键/唯一键，只读」。

---

## 4. 暂存修改模型（本地 overlay）

### 4.1 数据结构

```kotlin
data class CellKey(val row: Int, val col: Int)        // 原始（非转置）坐标
data class PendingEdits(
    val planId: String,                                // 归属的结果身份
    val edits: Map<CellKey, CellValue>,                // 仅存"改过的格子"
)
```

- 坐标永远是**原始行/列**；转置视图下做 `view → original` 映射（见 §4.3）。
- 未提交修改是瞬态，**不落盘**（`AGENTS.md`：纯瞬态只放内存）。

### 4.2 归属与失效

- 在 `ConsoleState` 里加 `val editBuffers = mutableStateMapOf<String, PendingEdits>()`（key = consoleId）。
- 新一次执行 / 切换控制台 / 结果身份变化 → 丢弃该控制台 buffer（切换控制台的丢弃要提示，见 §6）。
- 归属身份用 `planId`（例如 `sql + result 行数 + 代次`）；不一致即视为过期，丢弃并提示。

### 4.3 转置视图映射

`transposeResult` 后新坐标 `(r, c)`：`c == 0` 是原列名标签（只读）；`c >= 1` 对应
原 `(row = c-1, col = r)`。新增纯函数 `viewToOriginal(transposed, r, c, result): CellKey?` 并单测。

### 4.4 单元格状态渲染

- 未提交：琥珀底（`0xFFFFB300` 约 0.18 透明）+ 左侧 2dp 色条；文字仍走 `onSurface`。
- 提交后：恢复普通底纹。
- 状态文案区显示「· N 处未提交」。

---

## 5. 提交执行管线

### 5.1 `ConsoleState.commitEdits(consoleId): CommitResult`

```
1. 取 buffer；空 → 直接返回
2. 取 plan + 连接状态；ensureConnectionReady（复用 run 的逻辑）
3. 值转换预检（§3.2）——任何一条失败即中止，不做部分提交
4. live.onConnection { conn ->
       查询会话上下文 applyContext(conn, contextSql)   // 目标 schema 一致
       事务：autoCommit=false
         逐条 PreparedStatement UPDATE，校验 affected==1，累计
       commit / 任一失败 rollback
       autoCommit 还原
   }
5. 成功：把 edits 应用到内存结果行（runSlots[consoleId] 的 result 拷贝，作为刷新前的即时反馈），清 buffer
6. 记录 sql_history（生成的可读 UPDATE），Toast「已提交 N 处修改」
7. 失败：保留 buffer，返回 friendly 错误（约束冲突/行不存在/未唯一命中/类型不符）
```

要点：

- **必须复用同一条 `LiveConnection`**，走它的单线程执行器，禁止另开连接（`AGENTS.md` 硬规则）。
- 单事务：一次提交的多格修改要么全成功要么全回滚。
- `affected != 1` 视为失败：`0` = 行已被删/改；`>1` = 定位不唯一（防御无键模式）。
- 事务期间 UI 显示执行中，禁止再次提交/刷新。

### 5.2 内存结果的更新

`StatementOutcome.result` 是 `QueryResult`。提交成功后先在本 outcome 上应用内存拷贝，
随后由 §5.3 的服务端刷新覆盖（权威值）。

### 5.3 提交后自动刷新（固定行为）

服务端可能有默认值/触发器/生成列，本地拷贝未必等于真实值。
因此**提交成功后固定触发一次当前 Tab 的刷新**（§6.1），用服务端权威数据覆盖本地行；
此时 buffer 已清空，不会触发丢弃提示。

---

## 6. 刷新与未提交守卫

### 6.1 刷新语义

「刷新查询结果」= **只重新执行当前激活结果 Tab 对应的那条 SQL**，替换该 outcome，保留其它 Tab 不变，
也**不触碰编辑器草稿**。实现上新增 `ConsoleState.refreshOutcome(consoleId, index)`：

- 取 `run.outcomes[index].sql`，走与 `run` 相同的执行路径（ensureConnectionReady → 同一条 `LiveConnection`）；
- 用新结果替换 `outcomes[index]`（`runSlots` 拷贝），其它 outcome / `activeIndex` 不变；
- 执行期间 UI 显示执行中，禁止再次刷新/提交。

### 6.2 丢弃确认

所有会丢 buffer 的动作统一走一个确认请求（`app/state/DialogState.kt`）：

```kotlin
data class DiscardEdits(
    val consoleId: String,
    val count: Int,
    val actionLabel: String,   // 刷新查询结果 / 重新执行 / 切换控制台 / 关闭控制台 …
) : ConfirmRequest
```

文案：「有 N 处修改尚未提交，<动作>将丢弃这些修改。」`[继续并丢弃]` / `[取消]`。

覆盖的触发点：刷新、手动执行（Ctrl+Enter）、切换结果 Tab、切换控制台、关闭/删除控制台、断开连接、退出应用。

### 6.3 其它守卫

- 有未提交修改时，「提交」按钮可用，其余破坏性动作先确认。
- 退出应用（`onCloseRequest`）发现 buffer 非空 → 先弹确认（与现有 `flushAllSync` 并存，不冲突）。

---

## 7. 工具栏与图标

`app/ui/DbIcons.kt` 新增两个手绘图标（保持 24dp viewport）：

- `Commit`：对勾 / 上箭头入仓样式，语义=提交。
- `Refresh`：环形箭头，语义=刷新。

`ResultToolbar` 右侧动作区顺序（用细分隔线与查看类动作隔开）：

```
[提交 (徽标 N)] [刷新] [撤销全部] | [转置] [导出 CSV] [导出全量]
```

- 提交：`enabled = dirty && plan != null && !executing`；dirty 时 `tint = primary`，否则 `onSurface 0.25`。
- 刷新：`enabled = !executing && result != null`。
- 样式复用 `ResultIconButton`（24dp 热区 / 15dp 图标 / 500ms tooltip），提交徽标用现有计数徽章风格。

快捷键：

- 刷新：`F5`（结果区聚焦时）。
- 提交：**无快捷键**，只点击工具条「提交」图标（避开编辑器 `Ctrl+Enter`=执行 SQL 等冲突）。

---

## 8. 各层改动清单

| 层 | 文件 | 改动 |
|---|---|---|
| jdbc | `jdbc/QueryExecutor.kt` | `QueryColumn` 扩展元数据字段；`readResultSet` 填充 |
| jdbc | `jdbc/RowUpdater.kt`（新） | `UpdatePlan`、`renderUpdateSql`、`executeBatch`、类型绑定 |
| jdbc | `jdbc/DbDialect.kt` / `GenericDialect.kt` | `loadColumns` 顺带 `getPrimaryKeys` 打标；无 PK 时回落唯一索引（已实现） |
| jdbc | `jdbc/model/MetadataModels.kt` | `ColumnMeta.primaryKey`（带默认值，缓存兼容） |
| jdbc | `jdbc/JdbcSmoke.kt` | UPDATE / 回滚 / 影响行数校验冒烟 |
| app | `app/ui/ResultEditPlan.kt`（新，纯逻辑） | 可编辑性判定、`view↔original` 映射、值转换预检 |
| app/state | `app/state/ConsoleState.kt` | `editBuffers`、`commitEdits`、`refreshOutcome`、守卫、结果落地时算 plan |
| app/state | `app/state/DialogState.kt` | `ConfirmRequest.DiscardEdits` |
| app/ui | `app/ui/DbIcons.kt` | `Commit`、`Refresh` |
| app/ui | `app/ui/SqlWorkspace.kt` | `ResultToolbar` 加两图标；`ResultTable` 内联编辑 + 单元格状态 + 右键项 |
| app/dialog | `app/dialog/EditCellDialog.kt`（新） | 长值/多行编辑 + 「设为 NULL」+ 确定/取消（Enter 提交，沿用 `submitOnEnter`） |
| core | `app/core/Main.kt` | 接线 commit/refresh/守卫回调 |

分层遵守：`jdbc/` 只做阻塞 JDBC（禁止 compose/coroutines）；`app/state` 内部切 `Dispatchers.IO` 并复用 `LiveConnection`。

---

## 9. 边界与错误处理

| 情况 | 处理 |
|---|---|
| 结果被截断（>1000 行） | 仍可编辑已加载行；提示"仅前 1000 行可改" |
| NULL 与空串 | 两态分离：空串合法；NULL 用右键「设为 NULL」或编辑框的空态标记，绝不把空串当 NULL |
| 无可用键 | 只读，tooltip 说明原因（不做任何回退） |
| 视图 / 聚合 / 无基表来源 | 只读，tooltip 说明原因 |
| 约束冲突（唯一/外键/NOT NULL） | 捕获 SQLException → `friendlySqlError`，保留 buffer，指出冲突列 |
| 行已被并发删除/修改 | `affected==0` → 报「目标行已不存在或被修改」，建议刷新 |
| WHERE 命中多行 | `affected>1` → 回滚 + 报错 |
| 值类型不合法 | 提交前预检拦截，逐格报错 |
| 连接断开 | 复用 `ensureConnectionReady`；失败则保留 buffer |

---

## 10. 分阶段实施

- **Phase 0（地基，无 UI 变化）**：`QueryColumn` 元数据扩展；`ColumnMeta.primaryKey`；`ResultEditPlan` 纯逻辑 + 单测；`RowUpdater` 生成器/绑定/事务 + 单测 + 冒烟。
- **Phase 1（单格改 + 提交）**：内联编辑、buffer、提交图标、`commitEdits`、成功后 `refreshOutcome` + 丢弃确认。
- **Phase 2（守卫 + 唯一索引回落）**：撤销全部、退出守卫、`getIndexInfo` 唯一索引回落（已实现）。
- **Phase 3（打磨）**：`EditCellDialog`（长值/多行）、UPDATE 预览、`SELECT ... FOR UPDATE`。

---

## 11. 测试计划

- 纯逻辑（`src/test/kotlin/app`）：
  - `ResultEditPlan`：可编辑列判定、表达式列只读、键缺失→只读、转置坐标映射。
  - 值转换：各 `Types` 正常/异常输入；NULL 与空串区分。
  - `renderUpdateSql`：引号/复合主键/`IS NULL`/NULL。
- 集成（嵌入式 SQLite/H2）：建表带主键 → SELECT → plan → `executeBatch` → 查库校验；
  约束冲突 → 断言整体回滚、buffer 保留；
  防御性：`affected != 1` → 断言回滚。
- `gradle smokeJdbc` 增补 updater 段。
- 人工：深浅色下检查未提交底纹/图标/徽标可读性；确认弹窗 Enter/Esc。

---

## 12. 决策记录（已定稿）

1. **编辑入口**：普通双击 = 查看器（保持现状）；**Ctrl + 双击 = 进入编辑**。
2. **刷新范围**：只刷新当前激活结果 Tab。
3. **无主键**：只读，不允许修改；不做不安全回退。
4. **提交后**：固定触发一次当前 Tab 刷新。
5. **快捷键**：`F5` 刷新；提交无快捷键，仅点击工具条「提交」图标。

---

## 13. 实施进度

### Phase 0（地基，已完成）

| 文件 | 内容 |
|---|---|
| `jdbc/model/MetadataModels.kt` | `ColumnMeta.primaryKey`（带默认值，磁盘缓存向后兼容） |
| `jdbc/DbDialect.kt` | `markPrimaryKeys`：`getPrimaryKeys` 打标（大小写变体兜底，失败静默）；`queryTableColumns` 返回前打标 |
| `jdbc/{Postgres,MySql,ClickHouse}Dialect.kt` | 各自 fast path 也调 `markPrimaryKeys`，保证 PK 标记一致 |
| `jdbc/QueryExecutor.kt` | `QueryColumn` 扩展 `catalog/schema/table/baseColumn/sqlType/nullable/autoIncrement/readOnly`；`readColumnMeta` 独立容错读取 |
| `jdbc/RowUpdater.kt`（新） | `CellValue/ColumnValue/UpdatePlan`、`renderUpdateSql`（预览/历史）、`executeBatch`（单事务 + 影响行数=1 校验 + 回滚）、按 `sqlType` 参数绑定、`isBinarySqlType` |
| `app/ui/ResultEditPlan.kt`（新） | `CellKey`、`EditPlan`、`buildEditPlan`（元数据+主键判定，含 `knownColumns` 二次校验）、`viewToOriginal`（转置映射）、`applyEdits`、`buildUpdatePlans`（WHERE 用原始值） |
| 测试 | `app/ui/ResultEditPlanTest`（判定/映射/生成计划）、`jdbc/RowUpdaterTest`（SQLite 端到端/事务回滚/类型/NULL/复合主键）；`JdbcSmoke` 新增 `row-updater` 段 |

**实测踩坑（重要，给实现者）**：SQLite(xerial) 的 `ResultSetMetaData.getCatalogName(i)` **返回的是表名**而非 catalog。
若直接把它当 catalog，`RowUpdater` 会拼出 `UPDATE "users"."users"`。已在 `readColumnMeta` 里归一化：
`catalog == table`（忽略大小写）时视为无 catalog。H2/SQLite 主键标记均在集成测试中守住。

### Phase 1（单格改 + 提交 + 刷新，已完成）

| 文件 | 内容 |
|---|---|
| `app/state/ConsoleState.kt` | `editBuffers`（CelliKey→CellValue，纯内存）/`editPlans`/`resultBusy`；`ensureEditPlan`（结果列元数据 → schema 解析 → `ColumnCatalog.ensure` → PK/基表判定 → `buildEditPlan`）；`commitEdits`（`buildUpdatePlans` + `RowUpdater.executeBatch` 单事务 + 历史，成功后清 overlay 并固定 `refreshOutcome`）；`refreshOutcome`（只重跑当前 Tab 语句并替换该 outcome，保留其它 Tab/草稿）；`run`/`selectRunOutcome`/`deleteConsole` 清理编辑态 |
| `app/state/DialogState.kt` | `ConfirmRequest.DiscardResultEdits(count, actionLabel, onDiscard)`（带确认后动作） |
| `app/dialog/TextInputDialogs.kt` | `ConfirmDialog` 支持「继续/取消」（非删除文案） |
| `app/ui/DbIcons.kt` | `Commit` / `Refresh` 图标 |
| `app/ui/SqlWorkspace.kt` | 工具条提交/刷新按钮（提交带未提交数量徽标与高亮）；`Ctrl+双击`内联编辑（Enter 提交 / Esc 取消 / 失焦提交）；`pending` 琥珀底色与「撤销此单元格修改」「置为 NULL」「编辑单元格」右键项；`F5` 刷新 |
| `app/core/Main.kt` | 提交/刷新回调；刷新/Tab 切换/重跑前 `editCount>0` → `DiscardResultEdits` 确认；结果落地/切 Tab 后 `LaunchedEffect` 重算编辑计划 |

### Phase 2（守卫 + 唯一索引回落，已完成）

| 文件 | 内容 |
|---|---|
| `jdbc/DbDialect.kt` | `markPrimaryKeys` 无 PK 时回落 `readUniqueKey`（`getIndexInfo` 唯一索引，选列数最少者）；驱动不支持/无唯一索引返回 null |
| `jdbc/model/MetadataModels.kt` | `primaryKey` 语义扩展为「行定位键（PK 优先，回落唯一索引）」 |
| `app/state/ConsoleState.kt` | `totalEditCount()`（退出守卫）；`editBuffers` 供标签提示 |
| `app/core/Main.kt` | 窗口关闭（`onCloseRequest`）时有未提交修改 → `DiscardResultEdits` 确认后再退出；标签传入各控制台未提交数 |
| `app/ui/DbIcons.kt` | `Undo` 图标 |
| `app/ui/SqlWorkspace.kt` | 工具条「撤销全部」图标（有修改时显示）；控制台标签显示 `✦N` 未提交数 |

### 后续

- **Phase 3**：`EditCellDialog`（长值/多行）、UPDATE 预览、`SELECT ... FOR UPDATE`。
