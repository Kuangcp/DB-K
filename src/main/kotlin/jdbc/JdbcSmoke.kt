package jdbc

import db.ConnectionProfile
import db.ConnectionsRepository
import db.DbType
import app.ui.completionCandidates
import app.ui.extractTableName
import app.ui.rowToInsertSql
import app.ui.sqlCompletionWord
import app.ui.transposeResult
import org.tinylog.Logger
import jdbc.model.ObjectKind
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

    smokeEditorUtils()
    Logger.info("smoke result: {}", "SQLite + H2 + cancel + db-store(vault/meta-cache) + editor-utils PASS (ClickHouse driver load OK)")
}

/** 编辑器补全 / 转置 / 行转 INSERT 的纯逻辑自检（SqlEditing.kt）。 */
private fun smokeEditorUtils() {
    // caret 词提取：普通标识符、字符串/注释内不触发、纯数字不触发
    val word = sqlCompletionWord("SELECT * FROM acc\nWHERE id = 1", 17)
    check(word != null && word.text == "acc" && word.start == 14 && word.end == 17)
    check(sqlCompletionWord("SELECT 'ab' x", 9) == null) // 引号内
    check(sqlCompletionWord("SELECT -- ab\nc", 11) == null) // 行注释内
    check(sqlCompletionWord("SELECT 1", 8) == null) // 纯数字
    // 候选：表名优先于关键字，长度不长于前缀的不出现
    val cands = completionCandidates("acc", listOf("account", "accounts", "address", "acc"))
    check(cands == listOf("account", "accounts"))
    check(completionCandidates("sele", emptyList()).first() == "SELECT")

    // 转置：2 列 × 2 行 → 首列“列名” + 行1/行2 两列，两行原列
    val base = QueryResult(
        sql = "SELECT * FROM t",
        columns = listOf(QueryColumn("a"), QueryColumn("b")),
        rows = listOf(listOf("1", "x"), listOf("2", "y")),
    )
    val tr = transposeResult(base)
    check(tr.columns.map { it.name } == listOf("列名", "行 1", "行 2"))
    check(tr.rows == listOf(listOf("a", "1", "2"), listOf("b", "x", "y")))

    // 行 → INSERT：引号翻倍、NULL 直写；无法定表时返回 null
    val ins = rowToInsertSql(
        "SELECT * FROM users WHERE id = 1",
        listOf("id", "name", "note"),
        listOf("1", "O'Reilly", null),
    )
    check(ins == "INSERT INTO users (id, name, note) VALUES ('1', 'O''Reilly', NULL);")
    check(rowToInsertSql("SELECT a FROM (SELECT 1 AS a) sub", listOf("a"), listOf("1")) == null)
    check(extractTableName("SELECT * FROM \"PUBLIC\".\"account\" WHERE 1=1") == "\"PUBLIC\".\"account\"")
    Logger.info("[editor-utils] completion/transpose/insert PASS", "PASS")
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
        // 类型分组：SQLite 只应产出表/视图/触发器三组，且各组 count 与访问器一致
        check(objects.objects.keys == setOf(ObjectKind.TABLE, ObjectKind.VIEW, ObjectKind.TRIGGER))
        check(objects.forKind(ObjectKind.TABLE).size == objects.tables.size)
        check(objects.total == objects.tables.size + objects.views.size + objects.triggers.size)
        Logger.info("[SQLite] previewSql: {}", dialect.previewSelect(schemas.first(), "users"))

        // QueryExecutor：SELECT 读行、非查询 update 影响行数
        val q1 = QueryExecutor.execute(conn, "SELECT id, name FROM users ORDER BY id")
        check(q1.isQuery && q1.columns.size == 2 && q1.rows.size == 0)
        val q2 = QueryExecutor.execute(conn, "INSERT INTO users(name) VALUES ('probe')")
        check(!q2.isQuery && q2.affectedRows == 1)
        val q3 = QueryExecutor.execute(conn, "SELECT count(*) AS n FROM users")
        check(q3.isQuery && q3.rows.single().single() == "1")
        // 关键字识别：换行/注释/括号开头不得误判为非查询（回归：CK 的 SELECT\n  多列语句）
        check(QueryExecutor.isQueryLike("SELECT\n    table, count() AS n FROM system.parts"))
        check(QueryExecutor.isQueryLike("select\n\t* from t"))
        check(QueryExecutor.isQueryLike("-- 注释\nselect 1"))
        check(QueryExecutor.isQueryLike("/* c */ SELECT 1"))
        check(QueryExecutor.isQueryLike("(select 1) union all (select 2)"))
        check(QueryExecutor.isQueryLike("VALUES (1)"))
        check(!QueryExecutor.isQueryLike("INSERT INTO users(name) VALUES ('x')"))
        check(!QueryExecutor.isQueryLike("update users set name = 'x'"))
        val q4 = QueryExecutor.execute(conn, "SELECT\n    count(*) AS n\nFROM users")
        check(q4.isQuery && q4.rows.single().single() == "1")
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
        check(objects.objects.keys == setOf(ObjectKind.TABLE, ObjectKind.VIEW))
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
            // v2 时代的 consoles 表（无 target 列；v4 迁移会补上）
            st.execute("CREATE TABLE consoles (id TEXT PRIMARY KEY NOT NULL, connection_id TEXT NOT NULL REFERENCES connections(id) ON DELETE CASCADE, name TEXT NOT NULL, file_path TEXT NOT NULL DEFAULT '', sort_order INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
            st.execute("CREATE INDEX idx_sql_history_at ON sql_history(executed_at_ms)")
            st.executeUpdate("INSERT INTO schema_migrations(version) VALUES (1),(2)")
            st.execute("INSERT INTO connections(id, name, db_type, sort_order, created_at, updated_at) VALUES ('c-old','旧档案','SQLITE',0,1000,1000)")
            st.execute("INSERT INTO consoles(id, connection_id, name, file_path, sort_order, created_at, updated_at) VALUES ('cc-smoke','c-old','smoke-控制台','',0,1000,1000)")
            // 存量明文密码行：P3 仓库初始化时应被原地转密（enc:v1:…）
            st.execute("INSERT INTO connections(id, name, db_type, user_name, password, sort_order, created_at, updated_at) VALUES ('c-legacy','旧明文','SQLITE','legacy-user','plain-hunter2',1,1000,1000)")
            st.execute("INSERT INTO sql_history(id, profile_id, sql_text, executed_at_ms, duration_ms) VALUES ('h-old','c-old','SELECT 1',1000,5)")
        }
    }
    ConnectionsRepository(dbFile).use { repo ->
        // v3 迁移后旧行可读（默认 ok=1 / row_count=0）
        val old = repo.listHistoryByProfile("c-old")
        check(old.size == 1) { "旧行应保留在历史中" }
        check(old.single().ok && old.single().sqlText == "SELECT 1")

        // v4 迁移后 consoles 带 target（旧行默认 ""），set/get 回环
        check(repo.listConsoles("c-old").any { it.id == "cc-smoke" && it.target == "" }) {
            "旧 consoles 行应补上 target=''"
        }
        check(repo.getConsole("cc-smoke")!!.filePath == "")
        repo.setConsoleTarget("cc-smoke", "main")
        check(repo.getConsole("cc-smoke")!!.target == "main") { "setConsoleTarget 应生效" }
        Logger.info("[db-store] v4 consoles.target 迁移/读写 PASS", "PASS")

        // P3 密码落盘加密：
        // a) 旧明文行在仓库初始化时被自动迁移为密文，读回仍为原文
        // b) 新写入的密码落盘即为密文（不在盘上留明文），读回可解
        val keyFile = dbFile.resolveSibling("secret.key")
        check(Files.isRegularFile(keyFile)) { "密钥文件应随 dataDir 创建" }
        val permsOk = runCatching { Files.getPosixFilePermissions(keyFile) }.getOrNull()?.let { perms ->
            perms.containsAll(listOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            )) && perms.none { it.name.startsWith("GROUP") || it.name.startsWith("OTHERS") }
        } ?: true
        check(permsOk) { "密钥文件应为 600 权限" }
        val rawLegacy = rawPassword(dbFile, "c-legacy")
        check(rawLegacy != null && rawLegacy.startsWith("enc:v1:")) { "旧明文应已转密，实际=$rawLegacy" }
        check(repo.getConnection("c-legacy")!!.password == "plain-hunter2") { "迁移后读回应等于原文" }
        check(repo.getConnection("c-old")!!.password == null) { "无密码连接不受影响" }

        // 新档案 + 新格式历史：成功（带行数）/ 失败（带错误）
        val pid = repo.createConnection(
            ConnectionProfile(id = "smoke-new", name = "新档案", dbType = DbType.SQLITE, database = "x.db",
                user = "smoke-user", password = "s3cret!?"),
        )
        val rawNew = rawPassword(dbFile, pid)
        check(rawNew != null && rawNew.startsWith("enc:v1:") && !rawNew.contains("s3cret")) { "新密码落盘必须加密，实际=$rawNew" }
        check(repo.getConnection(pid)!!.password == "s3cret!?") { "加密读写回环失败" }
        repo.insertHistory(profileId = pid, sqlText = "SELECT 2", ok = true, executedAtMs = 2000, durationMs = 3, rowCount = 42)
        repo.insertHistory(profileId = pid, sqlText = "SELECT 3", ok = false, executedAtMs = 3000, durationMs = 4, rowCount = 0, errorMessage = "boom")
        val list = repo.listHistoryByProfile(pid, limit = 10)
        check(list.size == 2) { "应读回 2 条新历史" }
        check(list.first().sqlText == "SELECT 3" && !list.first().ok && list.first().rowCount == 0)
        check(list.last().sqlText == "SELECT 2" && list.last().ok && list.last().rowCount == 42)
        Logger.info("[db-store] v2→v3 迁移 + history insert/list PASS", "PASS")
        repo.clearHistoryForProfile(pid)
        check(repo.listHistoryByProfile(pid).isEmpty())
        Logger.info("[db-store] vault 加密/迁移 PASS", "PASS")
        repo.clearHistoryForProfile(pid)
        check(repo.listHistoryByProfile(pid).isEmpty())
        Logger.info("[db-store] history clear PASS", "PASS")

        // v5：meta_cache 迁移 + 目录元数据磁盘缓存读写（JSON 含枚举 key / 可空字段）
        val cacheProfile = repo.getConnection(pid)!!
        val mc = db.MetaCache(dbFile)
        val schemas = listOf(
            jdbc.model.SchemaMeta("smoke", null),
            jdbc.model.SchemaMeta("aux", "public"),
        )
        val objects = mapOf(
            schemas[0].key to jdbc.model.SchemaObjects(mapOf(
                ObjectKind.TABLE to listOf(
                    jdbc.model.DbObjectMeta("t1", ObjectKind.TABLE),
                    jdbc.model.DbObjectMeta("t2", ObjectKind.TABLE),
                ),
            )),
            schemas[1].key to jdbc.model.SchemaObjects(mapOf(
                ObjectKind.TABLE to listOf(jdbc.model.DbObjectMeta("accounts", ObjectKind.TABLE)),
                ObjectKind.MATERIALIZED_VIEW to listOf(jdbc.model.DbObjectMeta("mv1", ObjectKind.MATERIALIZED_VIEW)),
                ObjectKind.SEQUENCE to listOf(jdbc.model.DbObjectMeta("seq1", ObjectKind.SEQUENCE)),
                ObjectKind.TRIGGER to listOf(jdbc.model.DbObjectMeta("trg_ins", ObjectKind.TRIGGER, "accounts")),
            )),
        )
        mc.save(cacheProfile, schemas, objects)
        val hit = mc.load(cacheProfile)
        check(hit != null && hit.schemas == schemas && hit.objects == objects && !hit.stale) {
            "缓存读写回环失败"
        }
        // URL 身份变了 → 指纹失配，视为未命中（不喂错库的数据）
        check(mc.load(cacheProfile.copy(host = "other-host")) == null) { "URL 变化应失配" }
        Logger.info("[db-store] v5 meta_cache 迁移/读写 PASS", "PASS")

        // 删除连接档案 → 级联清掉缓存行
        mc.save(cacheProfile, schemas, objects)
        repo.deleteConnection(pid)
        check(mc.load(cacheProfile) == null) { "删除档案后缓存行应被清理" }
        Logger.info("[db-store] meta_cache 删除级联 PASS", "PASS")
    }
    Files.deleteIfExists(dbFile)
}

/** 直读某连接行落盘密码（绕过仓库解密层，验证盘上形态）。 */
private fun rawPassword(dbFile: Path, id: String): String? {
    return DriverManager.getConnection("jdbc:sqlite:$dbFile").use { raw ->
        raw.prepareStatement("SELECT password FROM connections WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }
}
