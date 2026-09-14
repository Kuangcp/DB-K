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
}
