package redis

import db.ConnectionProfile
import db.DbType
import engine.EditorLanguage
import engine.Protocol
import engine.model.DbObjectMeta
import engine.model.ObjectKind
import engine.model.SchemaMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RedisSession 的纯属性（不建连）：协议、能力位、对象组、会话目标与预览命令。 */
class RedisSessionTest {

    private fun profile(db: String = "3") = ConnectionProfile(
        id = "r1", name = "redis", dbType = DbType.REDIS, host = "localhost", port = 6379, database = db,
    )

    @Test
    fun `is a redis session with expected capabilities`() {
        val s = RedisSession(profile())
        assertEquals(Protocol.REDIS, s.protocol)
        assertEquals(EditorLanguage.REDIS_COMMAND, s.capabilities.editorLanguage)
        assertFalse(s.capabilities.editableResult)
        assertFalse(s.capabilities.sqlCompletion)
        assertFalse(s.capabilities.objectDdl)
        assertTrue(s.capabilities.objectPreview)
        assertTrue(s.capabilities.sessionContext)
        assertTrue(s.capabilities.lazyObjectGroups)
        assertEquals(listOf(ObjectKind.KEY), s.objectGroups())
        assertFalse(s.isOpen)
    }

    @Test
    fun `session context selects the namespace db`() {
        val s = RedisSession(profile())
        assertEquals("SELECT 0", s.sessionContextSql(SchemaMeta(null, "db0")))
        assertEquals("SELECT 7", s.sessionContextSql(SchemaMeta(null, "db7")))
    }

    @Test
    fun `preview command follows key type`() {
        val s = RedisSession(profile())
        fun p(type: String?) = s.previewQuery(null, DbObjectMeta("k", ObjectKind.KEY, detail = type))
        assertEquals("GET k", p("string"))
        assertEquals("HGETALL k", p("hash"))
        assertEquals("LRANGE k 0 99", p("list"))
        assertEquals("SMEMBERS k", p("set"))
        assertEquals("ZRANGE k 0 99 WITHSCORES", p("zset"))
        assertEquals("XRANGE k - +", p("stream"))
        assertEquals("TYPE k", p(null))
    }
}
