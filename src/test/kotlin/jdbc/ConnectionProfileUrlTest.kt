package jdbc

import db.ConnectionProfile
import db.DbType
import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectionProfileUrlTest {

    private fun profile(
        dbType: DbType,
        host: String = "localhost",
        port: Int = 0,
        database: String = "mydb",
        extraParams: String = "",
    ) = ConnectionProfile(
        id = "t", name = "t", dbType = dbType,
        host = host, port = port, database = database, extraParams = extraParams,
    )

    @Test
    fun `postgres falls back to default port`() {
        assertEquals(
            "jdbc:postgresql://localhost:5432/mydb",
            profile(DbType.POSTGRES).urlPreview(),
        )
        assertEquals(
            "jdbc:postgresql://h:5433/db",
            profile(DbType.POSTGRES, host = "h", port = 5433, database = "db").urlPreview(),
        )
    }

    @Test
    fun `mysql and mariadb use default port 3306`() {
        assertEquals("jdbc:mysql://localhost:3306/mydb", profile(DbType.MYSQL).urlPreview())
        assertEquals("jdbc:mariadb://localhost:3306/mydb", profile(DbType.MARIADB).urlPreview())
    }

    @Test
    fun `sqlite ignores query params`() {
        assertEquals(
            "jdbc:sqlite:/tmp/a.db",
            profile(DbType.SQLITE, database = "/tmp/a.db", extraParams = "mode=ro").urlPreview(),
        )
    }

    @Test
    fun `h2 file mode vs tcp mode`() {
        assertEquals(
            "jdbc:h2:/tmp/a",
            profile(DbType.H2, host = "", database = "/tmp/a").urlPreview(),
        )
        assertEquals(
            "jdbc:h2:tcp://h:9092/db",
            profile(DbType.H2, host = "h", database = "db").urlPreview(),
        )
    }

    @Test
    fun `h2local always builds file url and ignores params`() {
        assertEquals(
            "jdbc:h2:/tmp/local",
            profile(
                DbType.H2LOCAL,
                host = "ignored",
                database = "/tmp/local",
                extraParams = "DB_CLOSE_DELAY=-1",
            ).urlPreview(),
        )
    }

    @Test
    fun `clickhouse injects compress=0 unless user set it`() {
        assertEquals(
            "jdbc:clickhouse://localhost:8123/default?compress=0",
            profile(DbType.CLICKHOUSE, database = "default").urlPreview(),
        )
        assertEquals(
            "jdbc:clickhouse://h:8123/db?compress=1",
            profile(DbType.CLICKHOUSE, host = "h", database = "db", extraParams = "compress=1").urlPreview(),
        )
        // 大小写不敏感识别用户已设 compress
        assertEquals(
            "jdbc:clickhouse://h:8123/db?COMPRESS=1",
            profile(DbType.CLICKHOUSE, host = "h", database = "db", extraParams = "COMPRESS=1").urlPreview(),
        )
        // 其它参数时 compress=0 前置
        assertEquals(
            "jdbc:clickhouse://h:8123/db?compress=0&secure=true",
            profile(DbType.CLICKHOUSE, host = "h", database = "db", extraParams = "secure=true").urlPreview(),
        )
    }

    @Test
    fun `extra params strip leading question and ampersand`() {
        assertEquals(
            "jdbc:postgresql://localhost:5432/mydb?a=1&b=2",
            profile(DbType.POSTGRES, extraParams = "?a=1&b=2").urlPreview(),
        )
        assertEquals(
            "jdbc:postgresql://localhost:5432/mydb?sslmode=require",
            profile(DbType.POSTGRES, extraParams = "sslmode=require").urlPreview(),
        )
    }

    @Test
    fun `sql server uses databaseName and semicolon params`() {
        assertEquals(
            "jdbc:sqlserver://localhost:1433;databaseName=mydb",
            profile(DbType.SQLSERVER).urlPreview(),
        )
        assertEquals(
            "jdbc:sqlserver://h:1433;databaseName=db;encrypt=false;trustServerCertificate=true",
            profile(
                DbType.SQLSERVER, host = "h", database = "db",
                extraParams = "?encrypt=false&trustServerCertificate=true",
            ).urlPreview(),
        )
    }

    @Test
    fun `oracle uses thin service form and question params`() {
        assertEquals(
            "jdbc:oracle:thin:@localhost:1521/mydb",
            profile(DbType.ORACLE).urlPreview(),
        )
        assertEquals(
            "jdbc:oracle:thin:@h:1521/XEPDB1?oracle.net.ssl_version=1.2",
            profile(DbType.ORACLE, host = "h", database = "XEPDB1", extraParams = "oracle.net.ssl_version=1.2").urlPreview(),
        )
    }
}
