package jdbc

import db.DbType
import jdbc.model.SchemaMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DbDialectTest {

    @Test
    fun `quoteIdent escapes double quotes per dialect`() {
        // 默认实现：双引号包裹，内部双引号翻倍
        assertEquals("\"a\"\"b\"", PostgresDialect.quoteIdent("a\"b"))
        assertEquals("\"x\"", H2Dialect.quoteIdent("x"))
        assertEquals("\"x\"", SQLiteDialect.quoteIdent("x"))
    }

    @Test
    fun `quoteIdent uses backticks for mysql mariadb clickhouse`() {
        assertEquals("`a``b`", MySqlDialect.quoteIdent("a`b"))
        assertEquals("`a``b`", MariaDbDialect.quoteIdent("a`b"))
        assertEquals("`x`", ClickHouseDialect.quoteIdent("x"))
    }

    @Test
    fun `isSystemSchemaName is case-insensitive and pg-prefixed`() {
        assertTrue("pg_catalog".isSystemSchemaName())
        assertTrue("PG_TOAST".isSystemSchemaName())
        assertTrue("pg_temp_1".isSystemSchemaName())
        assertTrue("pg_whatever".isSystemSchemaName())
        assertTrue("information_schema".isSystemSchemaName())
        assertTrue("INFORMATION_SCHEMA".isSystemSchemaName())
        assertTrue("sys".isSystemSchemaName())
        assertTrue("system".isSystemSchemaName())
        assertFalse("public".isSystemSchemaName())
        assertFalse("myapp".isSystemSchemaName())
    }

    @Test
    fun `sessionContextSql per dialect`() {
        assertEquals(
            "SET search_path TO \"my schema\"",
            PostgresDialect.sessionContextSql(SchemaMeta(null, "my schema")),
        )
        assertNull(PostgresDialect.sessionContextSql(SchemaMeta(null, null)))
        assertEquals("USE `mydb`", MySqlDialect.sessionContextSql(SchemaMeta("mydb", null)))
        assertEquals("USE `mydb`", MariaDbDialect.sessionContextSql(SchemaMeta("mydb", null)))
        assertEquals("USE `mydb`", ClickHouseDialect.sessionContextSql(SchemaMeta("mydb", null)))
        assertEquals("SET SCHEMA \"PUBLIC\"", H2Dialect.sessionContextSql(SchemaMeta(null, "PUBLIC")))
        // SQLite 无 schema 语义，不切换
        assertNull(SQLiteDialect.sessionContextSql(SchemaMeta(null, "main")))
    }

    @Test
    fun `supportsTargetSwitch is false only for sqlite`() {
        assertFalse(SQLiteDialect.supportsTargetSwitch)
        assertTrue(PostgresDialect.supportsTargetSwitch)
        assertTrue(MySqlDialect.supportsTargetSwitch)
        assertTrue(H2Dialect.supportsTargetSwitch)
        assertTrue(ClickHouseDialect.supportsTargetSwitch)
    }

    @Test
    fun `previewSelect quotes schema and object`() {
        // SQLite：schema 为 main 时不加前缀
        assertEquals(
            "SELECT * FROM \"users\" LIMIT 100",
            SQLiteDialect.previewSelect(SchemaMeta(null, "main"), "users"),
        )
        assertEquals(
            "SELECT * FROM \"public\".\"account\" LIMIT 100",
            PostgresDialect.previewSelect(SchemaMeta(null, "public"), "account"),
        )
        assertEquals(
            "SELECT * FROM `mydb`.`t` LIMIT 100",
            MySqlDialect.previewSelect(SchemaMeta("mydb", null), "t"),
        )
        // 无 schema/catalog 时只有对象名
        assertEquals(
            "SELECT * FROM \"t\" LIMIT 100",
            PostgresDialect.previewSelect(SchemaMeta(null, null), "t"),
        )
    }

    @Test
    fun `registry maps all six types to singletons`() {
        assertSame(PostgresDialect, DialectRegistry.forType(DbType.POSTGRES))
        assertSame(MySqlDialect, DialectRegistry.forType(DbType.MYSQL))
        assertSame(MariaDbDialect, DialectRegistry.forType(DbType.MARIADB))
        assertSame(SQLiteDialect, DialectRegistry.forType(DbType.SQLITE))
        assertSame(H2Dialect, DialectRegistry.forType(DbType.H2))
        assertSame(ClickHouseDialect, DialectRegistry.forType(DbType.CLICKHOUSE))
    }
}
