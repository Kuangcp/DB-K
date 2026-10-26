# 每数据源可配「查询超时」实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让每个数据源档案带一个「查询超时」配置（默认 20 秒，`0` = 不限时），JDBC / Redis / Elasticsearch 三个后端都按它生效，超时错误给一句人话。

**Architecture:** 一个字段走完整条链路：`connections` 表新列 → `ConnectionProfile.queryTimeoutSeconds` → 三个会话实现各自映射到原生超时机制（`Statement.setQueryTimeout` / Jedis socket timeout / `HttpRequest.timeout`）。语义与取值映射集中在一个纯逻辑对象 `engine.QueryTimeout` 里，供三后端与弹窗共用、可单测。

**Tech Stack:** Kotlin 2.4 / JDK 25 / Compose Desktop / SQLite (xerial) / Jedis 5.2 / `java.net.http.HttpClient` / JUnit 5 + kotlin.test。

**Spec:** `docs/superpowers/specs/2026-10-26-per-connection-query-timeout-design.md`

## Global Constraints

- 构建环境（先 export 到 PATH）：`JAVA_HOME=/home/zk/.sdkman/candidates/java/25.0.3-jbr`、`/home/zk/.sdkman/candidates/gradle/9.4.1/bin`。无 gradle wrapper，命令一律用 `gradle`。
- 超时语义：`0` = 不限时；正数 = 单条语句最长执行秒数；**默认 20**；对话框输入上限 86400。
- 分层：`db` 只可依赖 `engine`/`engine.model`（不可 import `jdbc`）；`jdbc`/`redis`/`es` 禁止 import compose/coroutines；`i18n` 是纯叶层。
- 所有用户可见文案走 `i18n/Str.kt` 的 key，`CatalogZh` + `CatalogEn` 必须同时补（`I18nCatalogTest` 会校验）。
- 不做 UI 自动化验证：碰界面的任务只写人工验证步骤，由用户执行。
- 每个任务结束都要 `gradle test` 通过再提交。

## Review Focus

1. `0`（不限时）在三个后端都必须真的是「不设超时」，尤其 Redis 的 Jedis socket timeout `0` = 无限等待，不得悄悄回落默认值 —— 由 Task 1 的映射单测钉住。
2. 存量连接升级（v13 → v14）后超时是 20 秒而不是 0 —— Task 2 用裸 SQL 插一行再读列值钉住。
3. 旧导出文件（无 `queryTimeoutSeconds` 字段）导入后是 20，不是 0 —— Task 2 的 `ProfileTransferTest` 钉住。
4. 输入框被清空或填了非数字时回落 20，绝不变成「不限时」 —— Task 1 的 `normalize` 单测 + Task 7 的调用方式钉住。
5. 超时异常必须被报成「查询超过 N 秒未完成」，不能落到「连接已断开」或驱动原文 —— Task 6 的 `FriendlySqlErrorTest` 钉住。

---

### Task 1: `engine.QueryTimeout` —— 语义常量与三后端映射

**Files:**
- Create: `src/main/kotlin/engine/QueryTimeout.kt`
- Test: `src/test/kotlin/engine/QueryTimeoutTest.kt`

**Interfaces:**
- Consumes: 无（JDK 的 `java.time.Duration`）。
- Produces:
  - `object engine.QueryTimeout`
  - `const val DEFAULT_SECONDS = 20`、`const val MAX_SECONDS = 86_400`、`const val CONNECT_TIMEOUT_MS = 10_000`
  - `fun normalize(seconds: Int?): Int`
  - `fun socketTimeoutMillis(seconds: Int): Int`
  - `fun httpTimeout(seconds: Int): java.time.Duration?`

- [ ] **Step 1: 写失败测试**

```kotlin
package engine

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueryTimeoutTest {

    @Test
    fun `normalize falls back to default and clamps`() {
        assertEquals(20, QueryTimeout.normalize(null))
        assertEquals(20, QueryTimeout.normalize(20))
        assertEquals(0, QueryTimeout.normalize(0))
        assertEquals(0, QueryTimeout.normalize(-5))
        assertEquals(300, QueryTimeout.normalize(300))
        assertEquals(86_400, QueryTimeout.normalize(999_999))
    }

    @Test
    fun `redis socket timeout is millis and zero stays unlimited`() {
        assertEquals(0, QueryTimeout.socketTimeoutMillis(0))
        assertEquals(30_000, QueryTimeout.socketTimeoutMillis(30))
    }

    @Test
    fun `es request timeout is null when disabled`() {
        assertNull(QueryTimeout.httpTimeout(0))
        assertEquals(Duration.ofSeconds(45), QueryTimeout.httpTimeout(45))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `gradle test --tests 'engine.QueryTimeoutTest'`
Expected: 编译失败（`Unresolved reference: QueryTimeout`）。

- [ ] **Step 3: 实现 `engine/QueryTimeout.kt`**

```kotlin
package engine

import java.time.Duration

/**
 * 连接级「查询超时」语义（`0` = 不限时）与其到各后端取值形式的唯一映射。
 * 三后端与连接编辑弹窗都从这里取常量/换算，避免各写一份魔数。
 */
object QueryTimeout {
    const val DEFAULT_SECONDS = 20
    const val MAX_SECONDS = 86_400
    /** 建连超时与查询超时解耦：Jedis / ES 都用它，避免 `0` 让 TCP 建连也无限等。 */
    const val CONNECT_TIMEOUT_MS = 10_000

    fun normalize(seconds: Int?): Int = (seconds ?: DEFAULT_SECONDS).coerceIn(0, MAX_SECONDS)
    /** JDBC 规定 `setQueryTimeout(0)` = 无限制，所以 0 原样传递。 */
    fun socketTimeoutMillis(seconds: Int): Int = normalize(seconds) * 1000
    fun httpTimeout(seconds: Int): Duration? =
        normalize(seconds).takeIf { it > 0 }?.let { Duration.ofSeconds(it.toLong()) }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `gradle test --tests 'engine.QueryTimeoutTest'`
Expected: PASS（3 个用例全绿）。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/engine/QueryTimeout.kt src/test/kotlin/engine/QueryTimeoutTest.kt
git commit -m "feat: 新增 engine.QueryTimeout（查询超时语义与三后端映射）"
```

---

### Task 2: 持久化 —— 字段、v14 迁移、仓库、导出导入

**Files:**
- Modify: `src/main/kotlin/db/ConnectionsModels.kt`（`ConnectionProfile` 末尾加字段）
- Modify: `src/main/kotlin/db/AppDatabase.kt`（`CURRENT_VERSION` 13 → 14；新增 `migrateToV14`；`migrate()` 里加 14 的 block）
- Modify: `src/main/kotlin/db/ConnectionsRepository.kt`（2 处 SELECT、2 处 INSERT、1 处 UPDATE、`mapRow`）
- Modify: `src/main/kotlin/db/ProfileTransfer.kt`（`ConnectionDto`、encode、decode）
- Test: `src/test/kotlin/db/AppDatabaseMigrationTest.kt`、`src/test/kotlin/db/ConnectionsRepositoryTest.kt`、`src/test/kotlin/db/ProfileTransferTest.kt`

**Interfaces:**
- Consumes: `engine.QueryTimeout.DEFAULT_SECONDS`、`engine.QueryTimeout.MAX_SECONDS`（Task 1）。
- Produces: `ConnectionProfile.queryTimeoutSeconds: Int`（默认 `DEFAULT_SECONDS`）；数据库列 `connections.query_timeout_seconds INTEGER NOT NULL DEFAULT 20`；`ConnectionDto.queryTimeoutSeconds: Int`。

- [ ] **Step 1: 写失败测试**

`AppDatabaseMigrationTest` 里把版本断言改成含 14：

```kotlin
assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14), versions)
```

同文件新增（钉住「存量行迁移后 = 20」）：

```kotlin
@Test
fun `v14 gives existing connections the default query timeout`() {
    val db = dir.resolve("v14.db")
    raw(db).use { AppDatabase.migrate(it) }
    raw(db).use { c ->
        c.createStatement().use { st ->
            // 不显式写该列 → 取列默认值
            st.executeUpdate(
                "INSERT INTO connections(id, name, db_type, created_at, updated_at) " +
                    "VALUES ('c1','n','SQLITE',1,1)",
            )
            st.executeQuery("SELECT query_timeout_seconds FROM connections WHERE id = 'c1'").use { rs ->
                assertTrue(rs.next())
                assertEquals(20, rs.getInt(1))
            }
        }
    }
}
```

`ConnectionsRepositoryTest` 新增（钉住往返读写 + 默认为 20）：

```kotlin
@Test
fun `query timeout round trips and defaults to 20`() {
    open().use { repo ->
        val defaultId = repo.createConnection(newProfile(id = "d1"))
        assertEquals(20, repo.getConnection(defaultId)!!.queryTimeoutSeconds)

        val customId = repo.createConnection(newProfile(id = "d2").copy(queryTimeoutSeconds = 300))
        assertEquals(300, repo.getConnection(customId)!!.queryTimeoutSeconds)

        repo.updateConnection(repo.getConnection(customId)!!.copy(queryTimeoutSeconds = 0))
        assertEquals(0, repo.getConnection(customId)!!.queryTimeoutSeconds)
        assertEquals(0, repo.listConnections().first { it.id == customId }.queryTimeoutSeconds)
    }
}
```

`ProfileTransferTest` 新增（钉住旧文件默认 20、新文件往返）：

```kotlin
@Test
fun `query timeout survives round trip and defaults to 20 for old bundles`() {
    val profile = ConnectionProfile(id = "c1", name = "n", dbType = DbType.POSTGRES, queryTimeoutSeconds = 300)
    val decoded = ProfileTransfer.decode(ProfileTransfer.encode(emptyList(), listOf(profile), includePasswords = false))
    assertEquals(300, decoded.connections.single().queryTimeoutSeconds)

    // 旧版导出文件没有该字段
    val legacy = """{"formatVersion":1,"app":"db-k","exportedAt":1,"folders":[],"connections":[
        {"id":"c2","name":"old","dbType":"POSTGRES"}]}"""
    assertEquals(20, ProfileTransfer.decode(legacy).connections.single().queryTimeoutSeconds)
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `gradle test --tests 'db.*'`
Expected: 编译失败（`queryTimeoutSeconds` 不存在 / `v14.db` 上缺失列）。

- [ ] **Step 3: 实现**

`ConnectionsModels.kt` —— `ConnectionProfile` 在 `displayZone` 之后加：

```kotlin
    /** 单条语句的最长执行秒数；0 = 不限时。默认 [engine.QueryTimeout.DEFAULT_SECONDS]。 */
    val queryTimeoutSeconds: Int = engine.QueryTimeout.DEFAULT_SECONDS,
```

`AppDatabase.kt` —— `CURRENT_VERSION = 14`；`migrate()` 里 13 的 block 之后照抄一条 14 的 block；新增（与 v13 同款幂等写法，只是列名与默认值不同）：

```kotlin
    /** v14：每连接查询超时（秒），0 = 不限时；存量行按列默认值取 20 秒。 */
    private fun migrateToV14(st: Statement) {
        val has = runCatching {
            st.executeQuery("PRAGMA table_info(connections)").use { rs ->
                while (rs.next()) if (rs.getString("name") == "query_timeout_seconds") return@use true
                false
            }
        }.getOrElse { true }
        if (!has) {
            runCatching {
                st.executeUpdate(
                    "ALTER TABLE connections ADD COLUMN query_timeout_seconds INTEGER NOT NULL DEFAULT 20",
                )
            }
        }
    }
```

`ConnectionsRepository.kt` —— 六处改动：
1. `listConnections` 的 `SELECT ... key_separator, display_tz` → 末尾加 `, query_timeout_seconds`。
2. `getConnection` 的单行 SELECT 同样加该列。
3. `createConnection` 的 INSERT：列清单加 `query_timeout_seconds`（放在 `display_tz` 之后）、`VALUES` 占位符 16 → 17；绑定顺序变成 `14=DisplayZone.toDb`、`ps.setInt(15, p.queryTimeoutSeconds)`、`setLong(16, now)`、`setLong(17, now)`。
4. `updateConnection`：SET 加 `query_timeout_seconds = ?`（放在 `display_tz = ?` 之后，`updated_at = ?` 之前），后续索引顺移 → `ps.setInt(13, p.queryTimeoutSeconds)`、`setLong(14, now)`、`setString(15, p.id)`。
5. `importProfiles` 的 `INSERT INTO connections(...)` 同样加列与占位符（顺序与 `createConnection` 一致：`13=key_separator`、`14=query_timeout_seconds`、`15/16=now`）。
6. `mapRow` 末尾加：`queryTimeoutSeconds = rs.getInt("query_timeout_seconds").coerceAtLeast(0),`

`ProfileTransfer.kt` —— `ConnectionDto` 末尾加；encode 里 `ConnectionDto(...)` 加一行；decode 的 `ConnectionProfile(...)` 加一行：

```kotlin
        /** 旧版导出文件无此字段 → 默认 20 秒（与新建连接一致）；想表达「不限时」会显式写 0。 */
        val queryTimeoutSeconds: Int = QueryTimeout.DEFAULT_SECONDS,
```

```kotlin
                    queryTimeoutSeconds = p.queryTimeoutSeconds,   // encode
                    queryTimeoutSeconds = c.queryTimeoutSeconds,   // decode
```

`ProfileTransfer` 需要 `import engine.QueryTimeout`（`db` → `engine` 已允许）。

- [ ] **Step 4: 跑测试确认通过**

Run: `gradle test --tests 'db.*'`
Expected: PASS（含新加 3 个用例）。

- [ ] **Step 5: 顺手核对导入路径未漏列**

Run: `grep -n "query_timeout_seconds" src/main/kotlin/db/ConnectionsRepository.kt`
Expected: 6 处（2 SELECT + 2 INSERT + 1 UPDATE + mapRow）。少于 6 说明漏了一处。

- [ ] **Step 6: 提交**

```bash
git add src/main/kotlin/db src/test/kotlin/db
git commit -m "feat: 连接档案新增查询超时字段（app.db v14）"
```

---

### Task 3: JDBC —— 语句执行、上下文切换、写回都按档案超时

**Files:**
- Modify: `src/main/kotlin/jdbc/QueryExecutor.kt`（删 `QUERY_TIMEOUT_SECONDS`；`execute` / `applyContext` 加参数）
- Modify: `src/main/kotlin/jdbc/RowUpdater.kt`（删 `UPDATE_TIMEOUT_SECONDS`；`executeBatch` / `executeWriteBatch` / `executeOne` 加参数）
- Modify: `src/main/kotlin/jdbc/LiveConnection.kt:232-286`（三个调用点传档案值；导出路径传 0）
- Test: `src/test/kotlin/jdbc/StatementTimeoutRecorder.kt`（新建测试辅助）、`src/test/kotlin/jdbc/QueryExecutorTest.kt`

**Interfaces:**
- Consumes: `engine.QueryTimeout.DEFAULT_SECONDS` / `jdbcSeconds`；`ConnectionProfile.queryTimeoutSeconds`（Task 2）。
- Produces:
  - `QueryExecutor.execute(conn, sql, ctx: TemporalContext? = null, rowLimit: Int? = null, wireSql: String? = null, timeoutSeconds: Int = QueryTimeout.DEFAULT_SECONDS, registerStatement: ((Statement?) -> Unit)? = null): QueryResult`
  - `QueryExecutor.applyContext(conn, contextSql: String?, timeoutSeconds: Int = QueryTimeout.DEFAULT_SECONDS)`
  - `RowUpdater.executeWriteBatch(conn, ops, dialect, ctx: TemporalContext? = null, timeoutSeconds: Int = QueryTimeout.DEFAULT_SECONDS, registerStatement: ((Statement?) -> Unit)? = null): Int`
  - `RowUpdater.executeBatch(conn, plans, dialect, timeoutSeconds: Int = QueryTimeout.DEFAULT_SECONDS, registerStatement: ((Statement?) -> Unit)? = null): Int`（透传给 `executeWriteBatch`）

- [ ] **Step 1: 写失败测试**

新建 `src/test/kotlin/jdbc/StatementTimeoutRecorder.kt`（用 JDK 动态代理记录 `Statement.setQueryTimeout` 收到的值，不需要真数据库）：

```kotlin
package jdbc

import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement

/** 记录 `setQueryTimeout` 实际下发的秒数（-1 = 从未调用），用于验证超时传递链路。 */
class TimeoutRecorder {
    var statementTimeout: Int = -1
    var preparedTimeout: Int = -1

    fun connection(): Connection {
        val stmt = proxyStatement(Statement::class.java) { statementTimeout = it }
        val ps = proxyStatement(PreparedStatement::class.java) { preparedTimeout = it }
        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "createStatement" -> stmt
                "prepareStatement" -> ps
                "getAutoCommit" -> true
                "toString" -> "TimeoutRecorderConnection"
                else -> defaultValue(method.returnType)
            }
        } as Connection
    }

    private fun <T : Statement> proxyStatement(type: Class<T>, record: (Int) -> Unit): T =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(type)) { _, method, args ->
            when (method.name) {
                "setQueryTimeout" -> { record(args!![0] as Int); null }
                "executeUpdate" -> 1
                "close" -> null
                "toString" -> "TimeoutRecorderStatement"
                else -> defaultValue(method.returnType)
            }
        } as T

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        else -> null
    }
}
```

`QueryExecutorTest` 新增：

```kotlin
@Test
fun `execute passes configured timeout to statement`() {
    val rec = TimeoutRecorder()

    QueryExecutor.execute(rec.connection(), "UPDATE t SET x = 1", timeoutSeconds = 45)
    assertEquals(45, rec.statementTimeout)

    QueryExecutor.execute(rec.connection(), "UPDATE t SET x = 1", timeoutSeconds = 0)
    assertEquals(0, rec.statementTimeout)

    QueryExecutor.applyContext(rec.connection(), "USE db1", timeoutSeconds = 7)
    assertEquals(7, rec.statementTimeout)
}

@Test
fun `write batch passes configured timeout to prepared statement`() {
    val rec = TimeoutRecorder()
    // 空 ops 直接返回 0，不建语句（SQLiteDialect 是 object，无需真实连接）
    assertEquals(0, RowUpdater.executeWriteBatch(rec.connection(), emptyList(), SQLiteDialect, timeoutSeconds = 45))
    assertEquals(-1, rec.preparedTimeout)
}
```

> 说明：`RowUpdater` 的非空路径需要真实连接（SQLite），已有 `RowUpdaterTest` 覆盖功能；此处只钉「参数可传、空批次不建语句」，真实超时下发在 Step 5 的人工/冒烟环节确认。

- [ ] **Step 2: 跑测试确认失败**

Run: `gradle test --tests 'jdbc.QueryExecutorTest'`
Expected: 编译失败（`timeoutSeconds` 命名参数不存在）。

- [ ] **Step 3: 实现**

`QueryExecutor.kt`：
- 删 `private const val QUERY_TIMEOUT_SECONDS = 30`。
- `execute(...)` 参数列表在 `wireSql` 之后、`registerStatement` 之前插入 `timeoutSeconds: Int = QueryTimeout.DEFAULT_SECONDS`，`stmt.queryTimeout = timeoutSeconds` 替换原常量赋值。
- `applyContext(conn, contextSql, timeoutSeconds: Int = QueryTimeout.DEFAULT_SECONDS)`，内部 `st.queryTimeout = timeoutSeconds`。
- 文件顶部 `import engine.QueryTimeout`，删掉原来的字符串/常量注释里提到 30s 的措辞（`LiveConnection.cancel()` 的 KDoc「仍会按其自身超时（30s）结束」改成「仍会按其自身超时结束」）。

`RowUpdater.kt`：
- 删 `UPDATE_TIMEOUT_SECONDS`。
- `executeWriteBatch(..., ctx, timeoutSeconds: Int = QueryTimeout.DEFAULT_SECONDS, registerStatement)`，透传给 `executeOne`。
- `executeOne(conn, op, dialect, ctx, timeoutSeconds: Int, registerStatement)`，`ps.queryTimeout = timeoutSeconds`。
- `executeBatch(conn, plans, dialect, timeoutSeconds: Int = QueryTimeout.DEFAULT_SECONDS, registerStatement)` 也补参数并透传（它只是 `executeWriteBatch` 的 UPDATE-only 包装，冒烟与既有单测在用）。
- `import engine.QueryTimeout`。

`LiveConnection.kt`：
- `runStatement`：`QueryExecutor.applyContext(conn, sessionContextSql, profile.queryTimeoutSeconds)`；`QueryExecutor.execute(..., timeoutSeconds = profile.queryTimeoutSeconds, registerStatement = ...)`。
- `streamQuery`（N8 全量导出）：`applyContext(conn, sessionContextSql, timeoutSeconds = 0)`（导出不被打断，与 `StreamingQuery` 里的 `st.queryTimeout = 0` 一致）。
- `applyWriteOps`：`applyContext(conn, sessionContextSql, profile.queryTimeoutSeconds)`；`RowUpdater.executeWriteBatch(conn, ops, dialect, temporalCtx, profile.queryTimeoutSeconds) { ... }`。
- `cancel()` 的 KDoc 同步去掉「（30s）」。

- [ ] **Step 4: 跑测试确认通过**

Run: `gradle test --tests 'jdbc.*'`
Expected: PASS（含新加 2 个用例，且既有 `QueryExecutorIntegrationTest` / `RowUpdaterTest` 全绿）。

- [ ] **Step 5: 冒烟**

Run: `gradle smokeJdbc`
Expected: 输出 `SMOKE OK`（或项目既有的成功收尾行），无异常堆栈。

- [ ] **Step 6: 提交**

```bash
git add src/main/kotlin/jdbc src/test/kotlin/jdbc
git commit -m "feat: JDBC 执行/写回改用档案配置的查询超时"
```

---

### Task 4: Redis —— socket 超时按档案，建连超时保持不变

**Files:**
- Modify: `src/main/kotlin/redis/RedisSession.kt:83-95`（`buildConnection`）
- Test: `src/test/kotlin/redis/RedisClientConfigTest.kt`（新建）

**Interfaces:**
- Consumes: `engine.QueryTimeout.CONNECT_TIMEOUT_MS`、`engine.QueryTimeout.socketTimeoutMillis`（Task 1）；`ConnectionProfile.queryTimeoutSeconds`（Task 2）。
- Produces: `internal fun redisClientConfig(profile: ConnectionProfile): redis.clients.jedis.JedisClientConfig`（顶层函数，供 `buildConnection` 与单测共用）。

- [ ] **Step 1: 写失败测试**

```kotlin
package redis

import db.ConnectionProfile
import db.DbType
import kotlin.test.Test
import kotlin.test.assertEquals

class RedisClientConfigTest {

    private fun profile(seconds: Int) =
        ConnectionProfile(id = "r1", name = "r", dbType = DbType.REDIS, queryTimeoutSeconds = seconds)

    @Test
    fun `configured timeout becomes socket timeout`() {
        val cfg = redisClientConfig(profile(300))
        assertEquals(300_000, cfg.socketTimeoutMillis)
        assertEquals(10_000, cfg.connectionTimeoutMillis)
    }

    @Test
    fun `zero means unlimited socket wait but connect still bounded`() {
        val cfg = redisClientConfig(profile(0))
        assertEquals(0, cfg.socketTimeoutMillis)
        assertEquals(10_000, cfg.connectionTimeoutMillis)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `gradle test --tests 'redis.RedisClientConfigTest'`
Expected: 编译失败（`Unresolved reference: redisClientConfig`）。

- [ ] **Step 3: 实现**

`RedisSession.kt` 文件末尾（类外）加顶层函数，`buildConnection` 改用它：

```kotlin
/**
 * Jedis 客户端配置：**查询超时 → socket 读超时**（0 = 无限等待，靠取消解），
 * 建连超时固定 [QueryTimeout.CONNECT_TIMEOUT_MS]，避免「不限时」让 TCP 建连也无限等。
 */
internal fun redisClientConfig(profile: ConnectionProfile): JedisClientConfig =
    DefaultJedisClientConfig.builder()
        .connectionTimeoutMillis(QueryTimeout.CONNECT_TIMEOUT_MS)
        .socketTimeoutMillis(QueryTimeout.socketTimeoutMillis(profile.queryTimeoutSeconds))
        .build()
```

`buildConnection()` 里 `val j = Jedis(host, port)` 改为：

```kotlin
        val j = Jedis(HostAndPort(host, port), redisClientConfig(profile))
```

新增 import：`redis.clients.jedis.HostAndPort`、`redis.clients.jedis.DefaultJedisClientConfig`、`redis.clients.jedis.JedisClientConfig`、`engine.QueryTimeout`。认证与 `select(db)` / `ping()` 逻辑保持不变（仍在 `auth` 之后执行）。

- [ ] **Step 4: 跑测试确认通过**

Run: `gradle test --tests 'redis.RedisClientConfigTest'`
Expected: PASS（2 个用例）。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/redis/RedisSession.kt src/test/kotlin/redis/RedisClientConfigTest.kt
git commit -m "feat: Redis 后端 socket 超时按档案配置"
```

---

### Task 5: Elasticsearch —— HTTP 请求超时按档案

**Files:**
- Modify: `src/main/kotlin/es/ElasticsearchSession.kt:292-300`（`send`）

**Interfaces:**
- Consumes: `engine.QueryTimeout.httpTimeout`（Task 1）；`ConnectionProfile.queryTimeoutSeconds`（Task 2）。
- Produces: 无新签名（`send` 内部改动）。

- [ ] **Step 1: 写失败测试**

无离线可断言的行为（需要真集群），本任务**不新增测试**；映射的 `null` / 正数两种取值已由 Task 1 的 `es request timeout is null when disabled` 覆盖。实现后靠 Task 7 的人工验收与 `smokeEs`（有集群时）覆盖。

- [ ] **Step 2: 实现**

`send()` 里

```kotlin
        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
```

改成

```kotlin
        val builder = HttpRequest.newBuilder(uri)
        // 查询超时（0 = 不限时）→ 请求超时；建连超时仍在 newClient() 里固定 10s
        QueryTimeout.httpTimeout(profile.queryTimeoutSeconds)?.let { builder.timeout(it) }
```

新增 `import engine.QueryTimeout`；确认 `java.time.Duration` 仍被 `newClient()` 的 `connectTimeout` 使用（否则删掉该 import 会被编译器提醒未使用）。

- [ ] **Step 3: 编译 + 全量测试**

Run: `gradle compileKotlin && gradle test`
Expected: 编译无警告级错误，全部测试 PASS。

- [ ] **Step 4: 提交**

```bash
git add src/main/kotlin/es/ElasticsearchSession.kt
git commit -m "feat: Elasticsearch 请求超时按档案配置"
```

---

### Task 6: 超时错误可读化

**Files:**
- Modify: `src/main/kotlin/i18n/Str.kt`、`src/main/kotlin/i18n/CatalogZh.kt`、`src/main/kotlin/i18n/CatalogEn.kt`
- Modify: `src/main/kotlin/app/state/ConnectionsState.kt:31-46`（`friendlySqlError` + 新增 `isTimeout`）
- Modify: `src/main/kotlin/app/state/ConsoleState.kt`（4 处 `friendlySqlError` 调用传超时秒数）
- Test: `src/test/kotlin/app/state/FriendlySqlErrorTest.kt`

**Interfaces:**
- Consumes: `ConnectionProfile.queryTimeoutSeconds`（Task 2）；`Str.QueryTimeoutExceeded`。
- Produces:
  - `internal fun friendlySqlError(t: Throwable?, timeoutSeconds: Int? = null): String`
  - `internal fun isTimeout(t: Throwable?): Boolean`
  - `Str.QueryTimeoutExceeded`（`{0}` = 秒数）

- [ ] **Step 1: 写失败测试**

`FriendlySqlErrorTest` 新增：

```kotlin
@Test
fun `sql timeout becomes a readable message with the configured seconds`() {
    val err = java.sql.SQLTimeoutException("ERROR: canceling statement due to user request", "57014")
    assertEquals("查询超过 20 秒未完成，已取消", friendlySqlError(err, timeoutSeconds = 20))
    // 未传秒数（例如非执行路径）→ 退回原有 SQLException 文案
    assertTrue(friendlySqlError(err).contains("canceling statement"))
}

@Test
fun `socket and http timeouts are recognized through the cause chain`() {
    val redis = RuntimeException(java.net.SocketTimeoutException("Read timed out"))
    assertEquals("查询超过 5 秒未完成，已取消", friendlySqlError(redis, timeoutSeconds = 5))

    val es = java.util.concurrent.CompletionException(
        java.net.http.HttpTimeoutException("request timed out"),
    )
    assertEquals("查询超过 5 秒未完成，已取消", friendlySqlError(es, timeoutSeconds = 5))
}

@Test
fun `real connection loss keeps its own message`() {
    val lost = java.sql.SQLNonTransientConnectionException("connection reset")
    assertEquals(friendlySqlError(lost), friendlySqlError(lost, timeoutSeconds = 20))
}
```

（该测试文件需 `import kotlin.test.assertTrue`；语言默认 zh，`I18n.lang` 未改时的既有用例已这么断言。）

- [ ] **Step 2: 跑测试确认失败**

Run: `gradle test --tests 'app.state.FriendlySqlErrorTest'`
Expected: 编译失败（`friendlySqlError` 无 `timeoutSeconds` 参数 / `Str.QueryTimeoutExceeded` 不存在）。

- [ ] **Step 3: 实现**

i18n：`Str.kt` 在 `DisplayZoneInvalid,` 之后加 `QueryTimeoutExceeded,`（注释归到连接弹窗一节）；`CatalogZh` 对应位置加 `Str.QueryTimeoutExceeded -> "查询超过 {0} 秒未完成，已取消"`；`CatalogEn` 加 `Str.QueryTimeoutExceeded -> "Query cancelled after {0} seconds"`。

`ConnectionsState.kt`：

```kotlin
internal fun friendlySqlError(t: Throwable?, timeoutSeconds: Int? = null): String {
    val cause = t ?: return I18n.t(Str.ConnectionUnknownError)
    if (isConnectionLost(cause)) return I18n.t(Str.ConnectionLost)
    if (timeoutSeconds != null && isTimeout(cause)) return I18n.t(Str.QueryTimeoutExceeded, timeoutSeconds)
    val root = generateSequence(cause) { it.cause }.last()
    ...
}

/** 超时类异常（驱动取消 / socket 读超时 / HTTP 请求超时）——与「连接已断」区分开。 */
internal fun isTimeout(t: Throwable?): Boolean =
    generateSequence(t) { it.cause }.any { e ->
        e is java.sql.SQLTimeoutException ||
            e is java.net.SocketTimeoutException ||
            e is java.net.http.HttpTimeoutException ||
            e is java.util.concurrent.TimeoutException
    }
```

`ConsoleState.kt` 4 处调用补秒数（都在有 `profile` 的方法里）：
- `commitEdits`（约 453 行）：`friendlySqlError(t, profile.queryTimeoutSeconds)`
- `refreshOutcome`（约 506 行）：同
- `fetchMore`（约 596 行）：同
- `run`（约 1230 行）：同

- [ ] **Step 4: 跑测试确认通过**

Run: `gradle test --tests 'app.state.*' --tests 'i18n.*'`
Expected: PASS（新增 3 个用例 + `I18nCatalogTest` 占位符一致）。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/i18n src/main/kotlin/app/state src/test/kotlin/app/state/FriendlySqlErrorTest.kt
git commit -m "feat: 查询超时给可读提示，不再落到驱动原文"
```

---

### Task 7: 连接编辑弹窗加「查询超时」一行

**Files:**
- Modify: `src/main/kotlin/i18n/Str.kt`、`CatalogZh.kt`、`CatalogEn.kt`（`FormQueryTimeout`、`QueryTimeoutHint`）
- Modify: `src/main/kotlin/app/dialog/ConnectionEditorDialog.kt`（state、`build()`、表单行）

**Interfaces:**
- Consumes: `engine.QueryTimeout.normalize` / `DEFAULT_SECONDS`（Task 1）；`ConnectionProfile.queryTimeoutSeconds`（Task 2）；`Str.FormQueryTimeout` / `Str.QueryTimeoutHint`。
- Produces: 无（UI 行为）。

- [ ] **Step 1: 补 i18n key**

`Str.kt` 在 `FormDisplayZone,` 之后加 `FormQueryTimeout,`、`QueryTimeoutHint,`；
`CatalogZh`：`Str.FormQueryTimeout -> "查询超时"`、`Str.QueryTimeoutHint -> "秒；0 = 不限时（留空用默认 20）"`；
`CatalogEn`：`Str.FormQueryTimeout -> "Query timeout"`、`Str.QueryTimeoutHint -> "seconds; 0 = unlimited (empty = default 20)"`。

- [ ] **Step 2: 实现表单行**

state（放在 `var displayZone` 之后）：

```kotlin
    var timeoutText by remember(request) {
        mutableStateOf((initial?.queryTimeoutSeconds ?: QueryTimeout.DEFAULT_SECONDS).toString())
    }
```

`build()` 里加：

```kotlin
        queryTimeoutSeconds = QueryTimeout.normalize(timeoutText.toIntOrNull()),
```

表单行：插在 `if (!isEmbedded) { ... }` 整块之后、URL 预览的 `if (!isEmbedded || dbType == DbType.H2LOCAL) { ... }` 之前（本地库也要能配）：

```kotlin
            FormRow(t(Str.FormQueryTimeout)) {
                CompactField(
                    value = timeoutText,
                    onValueChange = { timeoutText = it.filter { c -> c.isDigit() }.take(5) },
                    placeholder = QueryTimeout.DEFAULT_SECONDS.toString(),
                    modifier = Modifier.width(120.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    t(Str.QueryTimeoutHint),
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
                )
            }
```

新增 `import engine.QueryTimeout`。

- [ ] **Step 3: 编译**

Run: `gradle compileKotlin`
Expected: 无错误。

- [ ] **Step 4: 人工验证（交给用户执行）**

前置：项目能 `gradle run` 启动。
1. 右键任一文件夹 →「新建连接」：表单里出现「查询超时」一行，输入框预填 `20`，右侧提示为「秒；0 = 不限时（留空用默认 20）」。
2. 输入框清空 → 保存 → 重新打开该连接：显示 `20`（不是 0）。
3. 填 `0` → 保存 → 重新打开：显示 `0`。
4. 切深色主题：该行文字与时区/附加参数行同色，无黑字沉底。
5. 切 English：标签是 `Query timeout`、提示是英文，无残留中文。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/i18n src/main/kotlin/app/dialog/ConnectionEditorDialog.kt
git commit -m "feat: 连接编辑弹窗支持配置查询超时"
```

---

### Task 8: 文档同步与全量验证

**Files:**
- Modify: `doc/DESIGN.md:82-94`（`ConnectionProfile` 样例）与 `doc/DESIGN.md:181`（查询防呆那句）

**Interfaces:**
- Consumes: 前七个任务的成品。
- Produces: 无代码。

- [ ] **Step 1: 更新 `doc/DESIGN.md`**

`ConnectionProfile` 样例里 `extraParams` 之后加一行：

```kotlin
    val queryTimeoutSeconds: Int = 20,   // 0 = 不限时；Exp：JDBC setQueryTimeout / Redis socket / ES HTTP
```

第 181 行「超时 30s」改为：

```markdown
超时**按连接配置**（`connection.queryTimeoutSeconds`，默认 20s，`0` = 不限时；落点：JDBC `Statement.setQueryTimeout`、
Elasticsearch `HttpRequest.timeout`、Redis Jedis socket timeout——注意 Redis 的 `0` 是 socket 无限等待，
半死连接只能靠「取消」解开；全量流式导出恒为不限时）
```

- [ ] **Step 2: 全量验证**

```bash
gradle compileKotlin && gradle test && gradle smokeJdbc
```

Expected: 编译无错；`test` 全绿（含 i18n 一致性、db 迁移、超时映射）；`smokeJdbc` 成功收尾。

- [ ] **Step 3: 人工端到端验收（交给用户执行）**

1. 用一个 PG/MySQL/SQLite 连接，把超时改成 `1`，执行一条慢查询（如 `SELECT pg_sleep(10)` / `SELECT SLEEP(10)`）→ 结果区报「查询超过 1 秒未完成，已取消」，**不是**驱动原文、也不是「连接已断开」。
2. 同连接改成 `0`，再跑同一条慢查询 → 能跑完出结果；执行中点「取消」仍能立刻中断。
3. 结果区「取更多」与「提交编辑」用的仍是该连接的超时（前者看提示、后者可只做功能回归）。
4. （有 Redis 实例时）`gradle smokeRedis` 通过；（有 ES 实例时）`gradle smokeEs` 通过。

- [ ] **Step 4: 提交**

```bash
git add doc/DESIGN.md
git commit -m "docs: 同步查询超时的默认值与三后端落点"
```

---

## 计划外的发现（不在本计划范围）

- `ConnectionsRepository.importProfiles` 的 `INSERT INTO connections(...)` **漏了 `display_tz`**（v13 加列时没同步导入路径），
  因此导入的连接「时间显示时区」永远回落默认。本计划只顺手补 `query_timeout_seconds`，`display_tz` 的缺失修复另开一次改动。
