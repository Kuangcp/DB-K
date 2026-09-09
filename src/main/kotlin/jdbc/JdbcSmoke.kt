package jdbc

import db.ConnectionProfile
import db.ConnectionsRepository
import db.DbType
import org.tinylog.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * JDBC 层自检（无需 UI）：gradle smokeJdbc
 * 用临时 SQLite/H2 建示例库 → 方言探测 → 打印树结构，验证方言与懒加载数据通路。
 */
fun main() {
    val dir = Files.createTempDirectory("dbk-smoke")
    Logger.info("smoke dir: {}", dir)

    smokeSqlite(dir)
    smokeH2(dir)
    smokeCancel(dir)
    smokeDbStore(dir)

    // ClickHouse 无本地服务：仅验证驱动类可加载 + URL 格式（真实连接靠用户环境）
    val chDriver = Class.forName(DbType.CLICKHOUSE.driverClass)
    val chProfile = ConnectionProfile(
        id = "smoke-ch", name = "smoke", dbType = DbType.CLICKHOUSE,
        host = "localhost", port = DbType.CLICKHOUSE.defaultPort, database = "default",
    )
    Logger.info("[ClickHouse] driver={} url={}", chDriver.name, chProfile.urlPreview())
    check(chProfile.urlPreview().startsWith("jdbc:clickhouse://localhost:8123/default"))

    Logger.info("smoke result: {}", "SQLite + H2 + cancel + db-store PASS (ClickHouse driver load OK)")
}

private fun smokeSqlite(dir: Path) {
    val dbFile = dir.resolve("demo.db")
    DriverManager.getConnection("jdbc:sqlite:$dbFile").use { c ->
        c.createStatement().use { st ->
            st.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)")
            st.execute("CREATE TABLE orders (id INTEGER PRIMARY KEY, user_id INTEGER, amount REAL)")
            st.execute("CREATE VIEW rich_orders AS SELECT id, amount FROM orders WHERE amount > 100")
            st.execute("CREATE TRIGGER trg_users_ai AFTER INSERT ON users BEGIN UPDATE orders SET user_id = NEW.id WHERE user_id IS NULL; END")
        }
    }

    val profile = ConnectionProfile(
        id = "smoke-sqlite", name = "smoke", dbType = DbType.SQLITE, database = dbFile.toString(),
    )
    val dialect = SQLiteDialect
    dialect.openConnection(profile).use { conn ->
        val schemas = dialect.loadSchemas(conn)
        Logger.info("[SQLite] schemas={}", schemas.map { it.displayName })
        check(schemas.map { it.displayName } == listOf("main"))
        val objects = dialect.loadObjects(conn, schemas.first())
        Logger.info("[SQLite] tables={}", objects.tables)
        Logger.info("[SQLite] views={}", objects.views)
        Logger.info("[SQLite] triggers={}", objects.triggers.map { "${it.name}@${it.tableName}" })
        check(objects.tables.containsAll(listOf("orders", "users")))
        check(objects.views.contains("rich_orders"))
        check(objects.triggers.single().name == "trg_users_ai")
        Logger.info("[SQLite] previewSql: {}", dialect.previewSelect(schemas.first(), "users"))

        // QueryExecutor：SELECT 读行、非查询 update 影响行数
        val q1 = QueryExecutor.execute(conn, "SELECT id, name FROM users ORDER BY id")
        check(q1.isQuery && q1.columns.size == 2 && q1.rows.size == 0)
        val q2 = QueryExecutor.execute(conn, "INSERT INTO users(name) VALUES ('probe')")
        check(!q2.isQuery && q2.affectedRows == 1)
        val q3 = QueryExecutor.execute(conn, "SELECT count(*) AS n FROM users")
        check(q3.isQuery && q3.rows.single().single() == "1")
        Logger.info("[SQLite] query executor PASS rows={} affected={}", q3.rows.single().single(), q2.affectedRows)
    }
}

private fun smokeH2(dir: Path) {
    // H2 file 模式（host 留空 → urlPreview 拼 jdbc:h2:<path>），连接即建库
    val profile = ConnectionProfile(
        id = "smoke-h2", name = "smoke", dbType = DbType.H2, host = "",
        database = dir.resolve("demo-h2").toString(), user = "sa", password = "",
    )
    H2Dialect.openConnection(profile).use { conn ->
        conn.createStatement().use { st ->
            st.execute("CREATE TABLE account (id INT PRIMARY KEY, balance DECIMAL)")
            st.execute("CREATE TABLE tx_log (id INT PRIMARY KEY, account_id INT, delta DECIMAL)")
            st.execute("CREATE VIEW big_balances AS SELECT id, balance FROM account WHERE balance > 1000")
        }
        val schemas = H2Dialect.loadSchemas(conn)
        Logger.info("[H2] url={} schemas={}", profile.urlPreview(), schemas.map { it.displayName })
        check(schemas.map { it.displayName }.contains("PUBLIC"))
        val objects = H2Dialect.loadObjects(conn, schemas.first { it.displayName == "PUBLIC" })
        Logger.info("[H2] tables={}", objects.tables)
        Logger.info("[H2] views={}", objects.views)
        check(objects.tables.any { it.equals("account", ignoreCase = true) })
        check(objects.views.any { it.equals("big_balances", ignoreCase = true) })
        Logger.info("[H2] previewSql: {}", H2Dialect.previewSelect(schemas.first(), "account"))
    }
}

/**
 * 取消语义自检（H2）：长查询执行中 Statement.cancel → 语句尽快收尾，
 * 且取消不能毁掉连接（同连接后续查询必须可用，回归单线程执行器语义）。
 */
private fun smokeCancel(dir: Path) {
    val profile = ConnectionProfile(
        id = "smoke-h2cancel", name = "smoke", dbType = DbType.H2, host = "",
        database = dir.resolve("demo-h2c").toString(), user = "sa", password = "",
    )
    H2Dialect.openConnection(profile).use { conn ->
        // 造一份需要逐行扫描的负载（SYSTEM_RANGE 的 COUNT 会被优化器直接算出，不够长）
        conn.createStatement().use { st ->
            st.execute("CREATE TABLE big_rows AS SELECT X AS id, REPEAT('xy', 30) AS pad FROM SYSTEM_RANGE(1, 600000)")
        }
        val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "smoke-exec").apply { isDaemon = true } }
        try {
            val stmtRef = AtomicReference<java.sql.Statement?>()
            val future: java.util.concurrent.Future<QueryResult> = exec.submit(
                java.util.concurrent.Callable {
                    QueryExecutor.execute(conn, "SELECT COUNT(*) FROM big_rows WHERE INSTR(pad, 'x') > 0") { stmtRef.set(it) }
                },
            )
            // 轮询等待语句登记（最多 3s）；登记后立刻 cancel
            var registered = false
            repeat(120) {
                if (stmtRef.get() != null) {
                    registered = true
                    return@repeat
                }
                Thread.sleep(25)
            }
            val cancelSent = registered && runCatching { stmtRef.get()!!.cancel(); true }.getOrDefault(false)
            Logger.info("[H2] cancel registered={} sent={}", registered, cancelSent)
            // 语句应尽快收尾（驱动收到 cancel 后抛异常，或极端情况下照常完成）
            val ended = runCatching { future.get(8, TimeUnit.SECONDS) }
            Logger.info("[H2] cancelled query finished within timeout: success={}", ended.isSuccess)
            // 关键断言：取消不伤连接，同连接后续查询必须可用
            val afterFuture: java.util.concurrent.Future<QueryResult> = exec.submit(
                java.util.concurrent.Callable { QueryExecutor.execute(conn, "SELECT 1 AS ok") },
            )
            val after = afterFuture.get(8, TimeUnit.SECONDS)
            check(after.isQuery && after.rows.single().single() == "1")
            Logger.info("[H2] connection usable after cancel PASS", "PASS")
        } finally {
            exec.shutdownNow()
        }
    }
}

/**
 * 应用库自检：v2（旧 sql_history 无 ok/row_count/error_message 列）→ v3 在线迁移，
 * 旧行可读且默认成功；insert/list/clear 全链路。
 */
private fun smokeDbStore(dir: Path) {
    val dbFile = dir.resolve("app.db")
    // 手工搭一个 v2 时代的旧库（版本 1、2 已记录；sql_history 为旧 4 列结构）
    DriverManager.getConnection("jdbc:sqlite:$dbFile").use { raw ->
        raw.createStatement().use { st ->
            st.execute("CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY NOT NULL)")
            st.execute("CREATE TABLE folders (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, parent_id TEXT NULL REFERENCES folders(id) ON DELETE CASCADE, sort_order INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)")
            st.execute("CREATE TABLE connections (id TEXT PRIMARY KEY NOT NULL, folder_id TEXT NULL REFERENCES folders(id) ON DELETE SET NULL, name TEXT NOT NULL, db_type TEXT NOT NULL, host TEXT NOT NULL DEFAULT '', port INTEGER NOT NULL DEFAULT 0, database_name TEXT NOT NULL DEFAULT '', user_name TEXT NULL, password TEXT NULL, extra_params TEXT NOT NULL DEFAULT '', color TEXT NULL, sort_order INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
            st.execute("CREATE TABLE sql_history (id TEXT PRIMARY KEY NOT NULL, profile_id TEXT NULL REFERENCES connections(id) ON DELETE SET NULL, sql_text TEXT NOT NULL, executed_at_ms INTEGER NOT NULL, duration_ms INTEGER NOT NULL)")
            st.execute("CREATE INDEX idx_sql_history_at ON sql_history(executed_at_ms)")
            st.executeUpdate("INSERT INTO schema_migrations(version) VALUES (1),(2)")
            st.execute("INSERT INTO connections(id, name, db_type, sort_order, created_at, updated_at) VALUES ('c-old','旧档案','SQLITE',0,1000,1000)")
            st.execute("INSERT INTO sql_history(id, profile_id, sql_text, executed_at_ms, duration_ms) VALUES ('h-old','c-old','SELECT 1',1000,5)")
        }
    }
    ConnectionsRepository(dbFile).use { repo ->
        // v3 迁移后旧行可读（默认 ok=1 / row_count=0）
        val old = repo.listHistoryByProfile("c-old")
        check(old.size == 1) { "旧行应保留在历史中" }
        check(old.single().ok && old.single().sqlText == "SELECT 1")
        // 新档案 + 新格式历史：成功（带行数）/ 失败（带错误）
        val pid = repo.createConnection(
            ConnectionProfile(id = "smoke-new", name = "新档案", dbType = DbType.SQLITE, database = "x.db"),
        )
        repo.insertHistory(profileId = pid, sqlText = "SELECT 2", ok = true, executedAtMs = 2000, durationMs = 3, rowCount = 42)
        repo.insertHistory(profileId = pid, sqlText = "SELECT 3", ok = false, executedAtMs = 3000, durationMs = 4, rowCount = 0, errorMessage = "boom")
        val list = repo.listHistoryByProfile(pid, limit = 10)
        check(list.size == 2) { "应读回 2 条新历史" }
        check(list.first().sqlText == "SELECT 3" && !list.first().ok && list.first().rowCount == 0)
        check(list.last().sqlText == "SELECT 2" && list.last().ok && list.last().rowCount == 42)
        Logger.info("[db-store] v2→v3 迁移 + history insert/list PASS", "PASS")
        repo.clearHistoryForProfile(pid)
        check(repo.listHistoryByProfile(pid).isEmpty())
        Logger.info("[db-store] history clear PASS", "PASS")
    }
    Files.deleteIfExists(dbFile)
}
