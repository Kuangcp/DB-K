package app.state

import db.ConnectionProfile
import db.DbType
import engine.Protocol
import es.ElasticsearchSession
import jdbc.LiveConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import redis.RedisSession

/** 多协议入口：按 dbType.protocol 选实现、按协议切分语句。 */
class SessionFactoryTest {

    private fun profile(type: DbType) = ConnectionProfile(id = "p1", name = "n", dbType = type)

    @Test
    fun `creates backend implementation by protocol`() {
        assertTrue(SessionFactory.create(profile(DbType.SQLITE)) is LiveConnection)
        assertTrue(SessionFactory.create(profile(DbType.POSTGRES)) is LiveConnection)
        assertTrue(SessionFactory.create(profile(DbType.REDIS)) is RedisSession)
        assertTrue(SessionFactory.create(profile(DbType.ELASTICSEARCH)) is ElasticsearchSession)
    }

    @Test
    fun `elasticsearch is a non-jdbc json backend`() {
        val session = SessionFactory.create(profile(DbType.ELASTICSEARCH))
        assertEquals(Protocol.ELASTICSEARCH, session.protocol)
        assertEquals(engine.EditorLanguage.JSON, session.capabilities.editorLanguage)
        assertEquals(false, session.capabilities.editableResult)
        assertEquals(false, session.capabilities.sqlCompletion)
        assertTrue(session.capabilities.fetchMore)
    }

    @Test
    fun `splits statements per protocol`() {
        assertEquals(
            listOf("SELECT 1", "SELECT 2"),
            SessionFactory.splitStatements(profile(DbType.SQLITE), "SELECT 1; SELECT 2"),
        )
        assertEquals(
            listOf("GET a", "SET b 1"),
            SessionFactory.splitStatements(profile(DbType.REDIS), "GET a\n\n# c\nSET b 1"),
        )
        assertEquals(
            listOf("{ \"index\": \"i\" }"),
            SessionFactory.splitStatements(profile(DbType.ELASTICSEARCH), "{ \"index\": \"i\" }"),
        )
    }

    @Test
    fun `splits statement ranges per protocol`() {
        val sqlite = "SELECT 1; SELECT 2"
        assertEquals(
            listOf(0..7, 10..17),
            SessionFactory.splitStatementRanges(profile(DbType.SQLITE), sqlite),
        )
        // Redis：每行一条命令；空行/注释行不产出
        val redisText = "GET a\n\n# c\nSET b 1"
        val rr = SessionFactory.splitStatementRanges(profile(DbType.REDIS), redisText)
        assertEquals(listOf("GET a", "SET b 1"), rr.map { redisText.substring(it.first, it.last + 1) })
        // ES：整段一个报体
        val esText = "{ \"index\": \"i\" }"
        assertEquals(
            listOf(0..esText.lastIndex),
            SessionFactory.splitStatementRanges(profile(DbType.ELASTICSEARCH), esText),
        )
        assertEquals(emptyList(), SessionFactory.splitStatementRanges(profile(DbType.ELASTICSEARCH), "  \n"))
        // ranges 与 splitStatements 产物逐条一致
        assertEquals(
            SessionFactory.splitStatements(profile(DbType.REDIS), redisText),
            rr.map { redisText.substring(it.first, it.last + 1) },
        )
    }
}
