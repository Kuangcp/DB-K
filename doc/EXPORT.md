# N8 结果导出设计：JSON / SQL INSERT / Excel（含大数据量流式 + 游标）

> 目标：结果区在既有 CSV 之外新增 **JSON / SQL INSERT / Excel(.xlsx)**；大结果导出**不 OOM**；
> 全量导出重跑 SQL 时优先走**服务端游标 / 流式结果集**，而非把整表读进内存。
> 本文件与 `AGENTS.md` 的分层纪律一致：游标与 JDBC 细节留在 `jdbc/`，格式写出在 `app/core/export/`（纯逻辑、可单测）。

---

## 1. 两条导出路径

| 路径 | 触发 | 数据来源 | 内存 |
|---|---|---|---|
| **A. 当前结果（内存）** | 结果区已有 `QueryResult`（≤ `QueryExecutor.MAX_ROWS`=1000 行） | `result.rows` 直接遍历 | 已在内存，无额外开销 |
| **B. 全量流式（重跑）** | 结果被截断（`truncated`），用户选「全量导出」 | 重跑 `result.sql`，用**游标流式**逐行读 | 常量级（一行 + SXSSF 滑动窗口） |

路径 A 不需要游标；路径 B 是本次的重点。两者共用同一套 **RowSink** 写出器，格式逻辑只实现一遍。

```
                   ┌──────────────┐
 QueryResult.rows →│              │
                   │   RowSink    │→ CSV / JSON / SQL INSERT / XLSX
 JDBC ResultSet   →│ (begin/row/  │
 (游标流式)         │  finish)     │
                   └──────────────┘
```

- `RowSink`：`begin(columns)` → 每行 `row(values)` → `finish(): Long`（返回行数）。
- 文本格式（CSV/JSON/SQL）写 `BufferedWriter`；XLSX 用 POI `SXSSFWorkbook`（滑动窗口 + 临时文件，`dispose()` 清理）。

---

## 2. 游标支持矩阵（关键设计输入）

JDBC 没有统一的「服务端游标」开关，各驱动机制不同。结论：**按方言选择 `CursorStrategy`，把差异封在 `jdbc/StreamingQuery.kt`**。

| 数据库 | 驱动 | 机制 | `CursorStrategy` | fetchSize |
|---|---|---|---|---|
| **PostgreSQL** | pgjdbc | `autoCommit=false` + 正 `fetchSize` → 驱动用**命名 portal（服务端游标）**，逐批 `FETCH`；必须在事务内 | `TRANSACTION_PORTAL` | 1000 |
| **MySQL** | Connector/J | `fetchSize=Integer.MIN_VALUE` → **逐行流式结果集**（连接被独占直到读完）；或用 `useCursorFetch=true`+正 fetchSize 走服务端游标 | `MYSQL_STREAM` | MIN_VALUE |
| **MariaDB** | mariadb-java-client | 同 MySQL（`Integer.MIN_VALUE` 流式） | `MYSQL_STREAM` | MIN_VALUE |
| **SQL Server** | mssql-jdbc | 默认 `responseBuffering=adaptive` **自适应缓冲**，边读边从网络取；`fetchSize` 生效 | `PREFETCH` | 1000 |
| **Oracle** | ojdbc（外部驱动） | `fetchSize` = **行预取**（row prefetch），前向流式；无对外 REF CURSOR | `PREFETCH` | 1000 |
| **ClickHouse** | clickhouse-jdbc | HTTP 响应本身流式；`fetchSize` 控制批 | `PREFETCH` | 1000 |
| **SQLite** | xerial | 无服务端；本地文件顺序扫描，结果集不物化整表 | `NONE` | 1000 |
| **H2** | h2 | 内嵌/本地为主；`fetchSize` 仅提示。| `NONE` | 1000 |

补充：
- **PostgreSQL 是唯一必须显式开事务**的（`autoCommit=true` 时 pgjdbc 会把整个结果集拉进内存）。`StreamingQuery` 在 `finally` 里 `rollback` 并恢复 `autoCommit`，只读导出无副作用。
- **MySQL 流式**期间该连接不能执行别的语句——导出在线程内独占，读完即关结果集，符合现有「单线程执行器串行」模型。
- 真正的跨库通用做法只有「`fetchSize` + 前向只读结果集」；上表是把每个驱动的**最优路径**显式化。
- 本应用支持的数据源中，仅 JDBC 有游标概念；Redis（`SCAN` 游标）/ ES（`search_after`/`from+size`）各有自己的分页方式，本方案不涉及（导出走各自后端的分页 API，后续如需再扩展 `engine` 契约）。

---

## 3. 代码结构

```
jdbc/
  CursorStrategy.kt      # 枚举：NONE / TRANSACTION_PORTAL / MYSQL_STREAM / PREFETCH
  StreamingQuery.kt      # 按策略配置 Statement，逐行读 ResultSet → onMeta/onRow
  DbDialect.kt           # + cursorStrategy / streamFetchSize（各方言覆写）
  QueryExecutor.kt       # + columnMetas(meta) 暴露列元数据（供流式与写回共用）
  LiveConnection.kt      # + streamQuery(sql, ctx, onMeta, onRow)（JDBC 专属，同 EditableSession 思路）
app/core/export/
  ExportFormat.kt        # CSV / JSON / SQL_INSERT / EXCEL（标签 + 扩展名）
  ExportOptions.kt       # 表名 / 批量大小 / JSON 缩进
  ExportText.kt          # csvField / jsonString / sqlLiteral / 类型判定（纯函数）
  RowSink.kt             # begin/row/finish 抽象
  CsvSink.kt JsonSink.kt SqlInsertSink.kt XlsxSink.kt
  ResultExport.kt        # 编排：writeCached（内存）/ writeStreamed（游标）
```

分层：
- `jdbc/*` 只依赖 `java.sql` / `engine.model`，不碰 compose；
- `app/core/export/*` 只有 `java.io` + POI + `engine.model`（`ResultExport` 额外用 `jdbc.LiveConnection`，属 app→jdbc 合法方向）；
- UI 只在「导出」弹窗里选格式与参数，实际落盘在 `Dispatchers.IO`。

---

## 4. 各格式规则

| 格式 | NULL | 数字 | 布尔 | 备注 |
|---|---|---|---|---|
| CSV | 空串 | 原样文本 | 原样文本 | 与现有规则一致（逗号/引号/换行转义） |
| JSON | `null` | 数值类型输出**裸数字**（`BigDecimal` 保精度） | `true`/`false` | UTF-8；键重名自动加 `#2` |
| SQL INSERT | `NULL` | 裸数字 | 裸 `TRUE`/`FALSE` | 值用单引号包裹并**双写单引号**；表名由用户输入（可 `schema.table`），列名加引号；按 batchSize 分组多值 INSERT |
| Excel | 空单元格 | 数值单元格 | 文本 | 表头加粗 + 冻结首行；SXSSF 滑动窗口 100 行落临时文件 |

**SQL INSERT 转义局限**：采用标准 SQL（单引号双写），对 PG / SQLite / H2 / Oracle / SQL Server 安全；
MySQL/MariaDB 默认把 `\` 当转义字符，含反斜杠的数据可能被解释。首版不做「方言感知转义」，在弹窗内提示。

**XLSX 大数据量**：`SXSSFWorkbook(100)` 只保留 100 行在内存，其余溢写到临时文件；`dispose()` 删除临时文件。
即使导出百万行，堆占用也是常量级。

---

## 5. 验收

- `gradle compileKotlin` / `gradle test` / `gradle smokeJdbc` 全绿；
- 新增单测：`ExportText/Sink` 纯逻辑、`StreamingQuery`（SQLite + H2 内嵌，含 autocommit 恢复断言）、XLSX 回读断言；
- 手工：导出 JSON/SQL/Excel 可被第三方工具（jq / sqlite3 / LibreOffice）读回；被截断结果选「全量流式」不 OOM。
