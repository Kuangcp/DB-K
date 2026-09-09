package jdbc

import db.ConnectionProfile
import db.DbType
import org.tinylog.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * JDBC 层自检（无需 UI）：gradle smokeJdbc
 * 用临时 SQLite/H2 建示例库 → 方言探测 → 打印树结构，验证方言与懒加载数据通路。
 */
fun main() {
    val dir = Files.createTempDirectory("dbk-smoke")
    Logger.info("smoke dir: {}", dir)

    smokeSqlite(dir)
    smokeH2(dir)

    Logger.info("smoke result: {}", "SQLite + H2 PASS")
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
