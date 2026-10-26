package db

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectionUriTest {

    @Test
    fun postgresFullUri() {
        val p = ConnectionProfile(
            id = "1", name = "pg", dbType = DbType.POSTGRES, host = "db.example.com",
            port = 5432, database = "mydb", user = "admin", password = "secret123"
        )
        assertEquals("postgresql://admin:secret123@db.example.com:5432/mydb", p.connectionUri())
    }

    @Test
    fun mysqlOnlyUsername() {
        val p = ConnectionProfile(
            id = "2", name = "mysql", dbType = DbType.MYSQL, host = "10.0.0.5",
            port = 3306, database = "testdb", user = "root", password = ""
        )
        assertEquals("mysql://root@10.0.0.5:3306/testdb", p.connectionUri())
    }

    @Test
    fun mariadbMysqlProtocol() {
        val p = ConnectionProfile(
            id = "3", name = "mariadb", dbType = DbType.MARIADB, host = "localhost",
            port = 3307, database = "dev", user = "user1", password = "pw"
        )
        assertEquals("mysql://user1:pw@localhost:3307/dev", p.connectionUri())
    }

    @Test
    fun redisWithPassword() {
        val p = ConnectionProfile(
            id = "4", name = "redis", dbType = DbType.REDIS, host = "cache.local",
            port = 6380, database = "3", password = "r3dis!pass"
        )
        // Redis :pass@host format; : stays unencoded in userinfo (per redis URI spec)
        assertEquals("redis://:r3dis!pass@cache.local:6380?db=3", p.connectionUri())
    }

    @Test
    fun redisNoPassword() {
        val p = ConnectionProfile(
            id = "5", name = "redis-no-pw", dbType = DbType.REDIS, host = "192.168.1.10",
            port = 6379, database = "0"
        )
        assertEquals("redis://192.168.1.10:6379?db=0", p.connectionUri())
    }

    @Test
    fun sqlServerJdbcStyle() {
        val p = ConnectionProfile(
            id = "6", name = "sqlsrv", dbType = DbType.SQLSERVER, host = "win-srv",
            port = 1433, database = "Production", user = "sa", password = "str0ngP@ss",
            extraParams = "trustServerCertificate=true"
        )
        assertEquals("sqlserver://sa:str0ngP%40ss@win-srv:1433;databaseName=Production;trustServerCertificate=true", p.connectionUri())
    }

    @Test
    fun sqlitePathUnix() {
        val path = "/opt/data/demo.db"
        val p = ConnectionProfile(
            id = "7", name = "sqlite", dbType = DbType.SQLITE, database = path
        )
        assertEquals("sqlite:///$path", p.connectionUri())
    }

    @Test
    fun h2LocalFile() {
        val p = ConnectionProfile(
            id = "8", name = "h2local", dbType = DbType.H2LOCAL, database = "~/mydata"
        )
        assertEquals("h2:file:~/mydata", p.connectionUri())
    }

    @Test
    fun h2TcpRemote() {
        val p = ConnectionProfile(
            id = "9", name = "h2tcp", dbType = DbType.H2, host = "h2-server.local",
            port = 9092, database = "remote", user = "admin", password = "h2pwd"
        )
        assertEquals("h2:tcp://admin:h2pwd@h2-server.local:9092/remote", p.connectionUri())
    }

    @Test
    fun clickhouseFullUri() {
        val p = ConnectionProfile(
            id = "10", name = "clickhouse", dbType = DbType.CLICKHOUSE, host = "ch-node1",
            port = 8123, database = "analytics", user = "default", password = "chpass"
        )
        assertEquals("clickhouse://default:chpass@ch-node1:8123/analytics", p.connectionUri())
    }

    @Test
    fun elasticSearchHttpDefault() {
        val p = ConnectionProfile(
            id = "11", name = "es", dbType = DbType.ELASTICSEARCH, host = "elastic.cluster",
            port = 9200, database = "logs", user = "elastic", password = "es-api-key-123"
        )
        assertEquals("http://elastic:es-api-key-123@elastic.cluster:9200", p.connectionUri())
    }

    @Test
    fun elasticSearchHttpsFromExtraParams() {
        val p = ConnectionProfile(
            id = "12", name = "es-https", dbType = DbType.ELASTICSEARCH, host = "secure-es.io",
            port = 443, extraParams = "scheme=https&path=/es",
            user = "admin", password = "key123"
        )
        assertEquals("https://admin:key123@secure-es.io:443", p.connectionUri())
    }

    @Test
    fun noCredentialsReturnsEmptyForPostgres() {
        val p = ConnectionProfile(
            id = "13", name = "empty", dbType = DbType.POSTGRES, host = "host",
            port = 5432, database = "db"
        )
        assertEquals("", p.connectionUri())
    }
}
