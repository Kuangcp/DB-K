# 连接保活与自动重连设计（Connection Health）

> 关联：`TODO.md`「连接的可用性检测，现在执行会报错」、`doc/DESIGN.md` §6。
> 状态：**已实现**（用前校验；心跳不做）。实现见 `jdbc/LiveConnection.kt` + `jdbc/ConnectionHealth.kt`。

---

## 1. 问题与复现

现象：连接放置一段时间后再执行 SQL，报

```
com.mysql.cj.exceptions.ConnectionIsClosedException: No operations allowed after connection closed.
Caused by: com.mysql.cj.exceptions.CJCommunicationsException:
  The client was disconnected by the server because of inactivity.
  See wait_timeout and interactive_timeout for configuring this behavior.
    at jdbc.QueryExecutor.execute(QueryExecutor.kt:80)   // conn.createStatement()
```

根因不是 bug，而是长连接模型的必然：

- `LiveConnection` 每条连接持有一个**从建连一直用到底**的 `java.sql.Connection`，中途从不校验；
- 服务端/中间件会在空闲后单方面关闭 TCP 会话（MySQL `wait_timeout`/`interactive_timeout`；云厂商代理常 60~300s；PG `idle_session_timeout`；NAT/防火墙会丢 NAT 表项）；
- 客户端对此毫不知情：`Connection.isClosed` 仍是 `false`，直到下一次真正发 SQL 才由驱动发现对端已断。

因此**任何"只靠连接对象状态判断"的做法都不成立**，必须在**使用前探测**或**后台保活**。

---

## 2. 结论：校验优先，心跳为辅

| 方案 | 作用 | 问题 |
|---|---|---|
| A. 后台心跳（定时 `SELECT 1`） | 让服务端不回收连接 | 无法应对网络中断/故障转移；持续消耗服务端资源（Serverless 库无法缩容）；每连接一个定时器 |
| B. 使用前校验（lazy validate + reconnect） | 死连接在**执行前**被发现并重建，用户无感 | 空闲后首条语句多一次探测往返 |
| C. A+B | 低延迟 + 强健壮 | 复杂度最高 |

**决策：以 B 为主（保证正确性），心跳作为可选的每连接开关（后续增强，默认关）。**

理由：本项目无手动事务（`Roadmap` 决策 3），连接上**没有需要保留的未提交状态**；所有会话上下文
（`USE db` / `SET search_path`）都由 `applyContext` 在**每条语句执行前**重新下发。因此「执行前重建连接」
在语义上是安全的——这正是可以「使用前校验」而无副作用的前提。

---

## 3. 设计

### 3.1 分层与边界

- 全部改动落在 `jdbc/`（`LiveConnection` + `DbDialect` + `GenericDialect`/各方言）与少量 `app/state` 状态联动；
- `engine.DataSourceSession` 契约**不变**（健康检测是 JDBC 实现细节，不污染通用层）；
- `jdbc/` 不得引入协程（`AGENTS.md`），故若要做心跳，用 JDK `ScheduledExecutorService` 投递到现有单线程执行器，或放到 app 层协程；本期不做；
- `RedisSession`、`ElasticsearchSession` 走各自机制（见 §3.8），本期不改。

### 3.2 每库健康策略（`DbDialect` 新增能力位）

```kotlin
/** 连接健康策略；null = 该库无网络空闲问题（嵌入式/本地文件）。 */
data class ConnectionHealth(
    /** 校验语句；null = 优先用 JDBC4 Connection.isValid(timeout)。 */
    val validationQuery: String? = null,
    /** 空闲超过该毫秒数才值得校验（避免每条语句都多一次往返）。 */
    val idleBeforeCheckMs: Long = 60_000,
    /** 探测超时（秒），同时约束 isValid 与半开 TCP 的最坏等待。 */
    val validationTimeoutSeconds: Int = 3,
)

interface DbDialect {
    // 现有成员省略…
    /** 健康检测策略；需要连接档案做判断（如 H2 嵌入式 vs tcp）时用 profile。 */
    fun healthFor(profile: ConnectionProfile): ConnectionHealth? = null
}
```

| 库 | 空闲会被踢？ | 校验方式 | `isValid` | 说明 |
|---|---|---|---|---|
| MySQL / MariaDB | 是（`wait_timeout`；代理常更短） | `SELECT 1`（等价 COM_PING） | ✅ | **禁用** `autoReconnect`（不恢复会话态、官方不推荐） |
| PostgreSQL | 是（`idle_session_timeout`/NAT） | `SELECT 1` | ✅ | 连接参数可加 `tcpKeepAlive=true`（防 NAT 老化，不防服务端踢） |
| Oracle | 是（防火墙/IDLE_TIME profile） | `SELECT 1 FROM DUAL` | ojdbc8+ ✅ | 老驱动回落校验语句 |
| SQL Server | 少见（Azure 有 idle resiliency） | `SELECT 1` | ✅ | 驱动 `connectRetryCount/Interval` 为补充 |
| ClickHouse | HTTP 短连接，基本无 | `SELECT 1` | 视驱动 | 可不做 |
| H2 服务端 | 仅 `jdbc:h2:tcp://` | `SELECT 1` | ✅ | 嵌入式（`host` 空）返回 `null` |
| H2 本地文件 | 否（本地文件） | — | — | `H2LocalDialect.healthFor` 返回 `null` |
| SQLite | 否（本地文件） | — | — | `healthFor` 返回 `null` |

> 默认实现：`GenericDialect.healthFor` 返回 `ConnectionHealth()`（网络库通用）；
> `SQLiteDialect` 返回 `null`；`H2Dialect` 按 `profile.host.isBlank()` 决定；`H2LocalDialect`（本地文件）恒 `null`。

### 3.3 `LiveConnection` 生命周期

新增/改造（伪码）：

```kotlin
@Volatile private var lastActivityNanos = 0L

private fun ensureAlive(conn: Connection?): Connection {
    if (conn == null) return reopen()
    val h = dialect.healthFor(profile) ?: return conn
    if (System.nanoTime() - lastActivityNanos < h.idleBeforeCheckMs * 1_000_000) return conn // 快路径
    if (isAlive(conn, h)) return conn
    Logger.info("connection lost (idle); reconnecting {}", profile.name)
    return reopen()
}

private fun isAlive(conn: Connection, h: ConnectionHealth): Boolean = runCatching {
    if (h.validationQuery == null) conn.isValid(h.validationTimeoutSeconds)
    else conn.createStatement().use { st ->
        st.queryTimeout = h.validationTimeoutSeconds
        st.execute(h.validationQuery); true
    }
}.getOrDefault(false)

private fun reopen(): Connection {
    runCatching { conn?.close() }
    val fresh = dialect.openConnection(profile)   // 失败则抛，交给上层
    conn = fresh
    lastActivityNanos = System.nanoTime()
    return fresh
}

fun <T> onConnection(block: (Connection) -> T): T {
    val future = executor.submit(Callable {
        val c = ensureAlive(conn)
        try {
            block(c)
        } finally {
            lastActivityNanos = System.nanoTime()
        }
    })
    return try { future.get() } catch (e: ExecutionException) { throw e.cause ?: e }
}
```

要点：
- **所有 JDBC 调用都经 `onConnection`**，所以校验是**单点**的，`loadNamespaces`/`loadColumns`/`runStatement`/`streamQuery`/`applyWriteOps` 全部受益，无需逐方法改；
- 校验/重连在**单线程执行器内**完成，天然无并发竞态；
- 重连后 `applyContext` 会在同一 block 内对新连接重新下发目标库/schema，会话上下文不丢；
- `onConnection` 成为**自愈入口**：`conn == null`（曾从未建连或已 `close()`）也会尝试建连，避免上层漏调 `open()` 时直接失败；
- `lastActivityNanos` 在 block 结束时（`finally`）更新，保证「空闲时长」按最后一次真实使用计。

### 3.4 断连识别（方言无关）

校验在「用前」已覆盖绝大多数场景；但**执行途中**断连（长查询期间网络抖动/服务端重启）仍可能发生。
统一用 JDBC 标准判据识别，避免逐驱动嗅探私有异常名：

```kotlin
/** 是否为「连接已断」类异常（SQLState 08xx / 标准三种 Connection 异常）。 */
fun isConnectionLost(t: Throwable): Boolean =
    generateSequence(t) { it.cause }.any {
        it is SQLNonTransientConnectionException ||
        it is SQLRecoverableException ||
        it is SQLTransientConnectionException ||
        (it is SQLException && it.sqlState?.startsWith("08") == true)
    }
```

覆盖：MySQL `CJCommunicationsException`(08S01)、`ConnectionIsClosedException`、
PG `PSQLException`(08006/08001)、Oracle `SQLRecoverableException`(ORA-03113/03114/17008)、SQL Server 等。

### 3.5 中途断连的语义（不盲目重试）

> 核心原则：**校验用前 → 不需要重试**；只有「跑到一半才断」才涉及重试，而这种情况**写操作不能自动重试**
> （可能服务端已执行、只是回包丢失，重试会重复写入）。

| 操作 | 用前校验 | 中途断连时 |
|---|---|---|
| 元数据（`loadSchemas`/`loadObjects`/`loadColumns`/`tableDdl`） | ✅ | 重连后**自动重试一次**（幂等读） |
| `runStatement` 且 `isQueryLike(sql)`（SELECT/EXPLAIN…） | ✅ | 重连后**自动重试一次**（只读） |
| `runStatement` 非查询（DML/DDL） | ✅ | 重连，**不重试**；抛 `连接已中断，已自动重连，请重试执行` |
| `applyWriteOps`（行写回） | ✅ | 重连，**不重试**（同上） |
| `streamQuery`（全量导出） | ✅ | 已产出过行 → 不重试（否则重复行）；0 行 → 可重试一次 |

实现上把「可重试」作为 `runOnConnection(retryOnLoss: () -> Boolean, block)` 的判据，默认 `true`；
写路径传 `false`（断连后只重建连接、再次抛出），`streamQuery` 以「是否已回调过 `onRow`」动态决定。
另外：**中途断连即使不重试也会先强制重建连接**（写操作可能已在服务端执行，不能重跑，但连接对后续操作恢复可用）。

### 3.6 心跳（可选，默认关）

本期**不做**。若后续需要（例如某用户库 timeout 极短、重连开销大），设计为：

- 每连接一个 `health.keepAliveSeconds`；0 = 关；
- 用 `ScheduledExecutorService` 或 app 层协程定时 `executor.submit { if (userIdle) validate() }`；
- 仅当连接处于 CONNECTED 且用户空闲时才探活，避免与正在执行的语句排队冲突（同单线程执行器，天然串行，只会稍延后）。

### 3.7 UI 状态与提示

- 用前校验成功重连：**对用户完全透明**，仅写会话日志（`Logger.info("connection lost while idle …")`）；
- 中途断连且不可重试（DML/DDL/行写回）：连接已重建，错误文案统一为
  「连接已中断，已尝试自动重连，请重试执行」（`app/state/ConnectionsState.friendlySqlError`）；
- **不自动标记连接状态为 ERROR**：因为写路径已重建连接（实际可用），误标反而更差；
  「重连失败 → 状态点变红」作为后续增强（需 `LiveConnection` 区分「已重建」与「重建失败」再透出）。

### 3.8 其它后端

- **Redis**：Jedis 自带 `ping`，但长连接同样会被服务端 `timeout`/NAT 踢。后续可用 `JedisPool` 的
  `testOnBorrow` 或 `RedisSession` 内 `PING` 校验，复用同一套「用前校验 + 重连」思路；
- **Elasticsearch**：HTTP 无状态，每个请求独立建连，无空闲问题，**不做**。

---

## 4. 影响文件

| 文件 | 改动 |
|---|---|
| `jdbc/DbDialect.kt` | 新增 `ConnectionHealth` + `healthFor(profile)` 默认实现 |
| `jdbc/GenericDialect.kt` | 默认返回 `ConnectionHealth()`；`openConnection` 可选注入 `connectTimeout` 等 |
| `jdbc/SQLiteDialect.kt` / `H2Dialect.kt` | 覆写 `healthFor`（本地/嵌入式返回 `null`；H2 tcp 启用） |
| `jdbc/LiveConnection.kt` | `ensureAlive`/`isAlive`/`reopen`/`lastActivityNanos`；`onConnection` 加 `retryOnLoss` |
| `jdbc/DbDialect.kt` 或新 `jdbc/ConnectionLoss.kt` | `isConnectionLost` 判据 |
| `jdbc/QueryExecutor.kt` | 无需改（仍由 `LiveConnection` 包一层） |
| `app/state/ConsoleState.kt` | failure 分支识别断连 → 提示 + 标记状态（可选） |
| `app/state/ConnectionsState.kt` | `metaLive` 自动复用同一 `LiveConnection` 能力（无需改） |
| `doc/DESIGN.md` §6 | 补一段「连接健康」小节 |
| 测试 | 单测：`ConnectionHealth` 策略、`isConnectionLost` 判据；smoke：H2 建连后 `close` 底层连接再执行，断言自动重连成功 |

**不需要改**：`engine/`、`tree/`、`db/`、UI 组件（透明恢复）。

---

## 5. 验收

1. `gradle compileKotlin` / `gradle test` / `gradle smokeJdbc` 全绿；
2. 新增 smoke 用例：建连 → 手工 `conn.close()` 模拟服务端踢连接 → 再执行查询/元数据 → 断言自动重连并返回结果；
3. 人工：MySQL 连接空闲超过 `wait_timeout`（或用 `KILL` 从另一会话杀掉）后再执行，应无报错直接出结果；
   会话日志出现 `connection lost (idle); reconnecting`；
4. 反向：拔网线/停库后再执行，应给出「连接不可用」可读错误，且连接状态点变红、可重连。

---

## 6. 明确不做（本期）

- 手动事务（`Roadmap` 决策 3）——没有它，重连才是安全的；
- 连接池（HikariCP 等）——单连接桌面场景收益低、引入生命周期复杂度；
- 心跳/定时探活——留作后续可选开关（§3.6）；
- MySQL `autoReconnect`——官方不推荐且不恢复会话态，明确不启用。
