package jdbc

import db.DbType
import jdbc.model.ObjectKind
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
    fun `quoteIdent uses brackets for sql server`() {
        assertEquals("[x]", SqlServerDialect.quoteIdent("x"))
        assertEquals("[a]]b]", SqlServerDialect.quoteIdent("a]b"))
        assertEquals("\"HR\"", OracleDialect.quoteIdent("HR"))
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
        assertEquals(
            "ALTER SESSION SET CURRENT_SCHEMA = \"HR\"",
            OracleDialect.sessionContextSql(SchemaMeta(null, "HR")),
        )
        // SQL Server 无会话级 schema 切换
        assertNull(SqlServerDialect.sessionContextSql(SchemaMeta(null, "dbo")))
        // SQLite 无 schema 语义，不切换
        assertNull(SQLiteDialect.sessionContextSql(SchemaMeta(null, "main")))
    }

    @Test
    fun `supportsTargetSwitch is false only for sqlite`() {
        assertFalse(SQLiteDialect.supportsTargetSwitch)
        assertFalse(SqlServerDialect.supportsTargetSwitch)
        assertTrue(PostgresDialect.supportsTargetSwitch)
        assertTrue(MySqlDialect.supportsTargetSwitch)
        assertTrue(H2Dialect.supportsTargetSwitch)
        assertTrue(ClickHouseDialect.supportsTargetSwitch)
        assertTrue(OracleDialect.supportsTargetSwitch)
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
        // SQL Server：TOP；Oracle：FETCH FIRST
        assertEquals(
            "SELECT TOP 100 * FROM [dbo].[t]",
            SqlServerDialect.previewSelect(SchemaMeta(null, "dbo"), "t"),
        )
        assertEquals(
            "SELECT * FROM \"HR\".\"EMP\" FETCH FIRST 100 ROWS ONLY",
            OracleDialect.previewSelect(SchemaMeta(null, "HR"), "EMP"),
        )
    }

    @Test
    fun `postgres relkind prokind mapping and lazy flag`() {
        assertEquals(ObjectKind.MATERIALIZED_VIEW, relationKindOf("m"))
        assertEquals(ObjectKind.VIEW, relationKindOf("v"))
        assertEquals(ObjectKind.SEQUENCE, relationKindOf("S"))
        assertEquals(ObjectKind.TABLE, relationKindOf("r"))
        assertEquals(ObjectKind.TABLE, relationKindOf("p"))
        assertEquals(ObjectKind.TABLE, relationKindOf("f"))
        assertEquals(ObjectKind.AGGREGATE, routineKindOf("a"))
        assertEquals(ObjectKind.ROUTINE, routineKindOf("f"))
        assertEquals(ObjectKind.ROUTINE, routineKindOf(null))
        assertTrue(PostgresDialect.lazyObjectGroups)
        assertFalse(SQLiteDialect.lazyObjectGroups)
        assertFalse(MySqlDialect.lazyObjectGroups)
    }

    @Test
    fun `registry maps all eight types to singletons`() {
        assertSame(PostgresDialect, DialectRegistry.forType(DbType.POSTGRES))
        assertSame(MySqlDialect, DialectRegistry.forType(DbType.MYSQL))
        assertSame(MariaDbDialect, DialectRegistry.forType(DbType.MARIADB))
        assertSame(SQLiteDialect, DialectRegistry.forType(DbType.SQLITE))
        assertSame(H2Dialect, DialectRegistry.forType(DbType.H2))
        assertSame(ClickHouseDialect, DialectRegistry.forType(DbType.CLICKHOUSE))
        assertSame(SqlServerDialect, DialectRegistry.forType(DbType.SQLSERVER))
        assertSame(OracleDialect, DialectRegistry.forType(DbType.ORACLE))
        // 外部驱动标记只给 SQL Server / Oracle
        assertEquals(setOf(DbType.SQLSERVER, DbType.ORACLE), DbType.entries.filter { it.externalDriver }.toSet())
    }
}
