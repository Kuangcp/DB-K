package db

import kotlin.test.Test
import kotlin.test.assertEquals

/** `DbType.category` 分组：固化连接弹窗左栏的分类意图。 */
class DbTypeCategoryTest {

    @Test
    fun `local databases are file based`() {
        assertEquals(DbCategory.LOCAL, DbType.SQLITE.category)
        assertEquals(DbCategory.LOCAL, DbType.H2LOCAL.category)
    }

    @Test
    fun `non relational databases are the non jdbc backends`() {
        assertEquals(DbCategory.NON_RELATIONAL, DbType.REDIS.category)
        assertEquals(DbCategory.NON_RELATIONAL, DbType.ELASTICSEARCH.category)
    }

    @Test
    fun `remaining types are relational`() {
        assertEquals(DbCategory.RELATIONAL, DbType.POSTGRES.category)
        assertEquals(DbCategory.RELATIONAL, DbType.MYSQL.category)
        assertEquals(DbCategory.RELATIONAL, DbType.MARIADB.category)
        assertEquals(DbCategory.RELATIONAL, DbType.H2.category)
        assertEquals(DbCategory.RELATIONAL, DbType.CLICKHOUSE.category)
        assertEquals(DbCategory.RELATIONAL, DbType.SQLSERVER.category)
        assertEquals(DbCategory.RELATIONAL, DbType.ORACLE.category)
    }

    @Test
    fun `every type is categorized exactly once`() {
        val byCategory = DbType.entries.groupBy { it.category }
        // 三个分类都有内容，且分组并集 = 全部类型（无漏网、无重复）
        assertEquals(setOf(DbCategory.LOCAL, DbCategory.RELATIONAL, DbCategory.NON_RELATIONAL), byCategory.keys)
        val grouped = byCategory.values.flatten()
        assertEquals(DbType.entries.toSet(), grouped.toSet())
        assertEquals(DbType.entries.size, grouped.size)
    }
}
