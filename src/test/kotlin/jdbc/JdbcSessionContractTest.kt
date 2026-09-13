package jdbc

import db.ConnectionProfile
import db.DbType
import engine.DataSourceSession
import engine.EditorLanguage
import engine.Protocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** N4 契约：JDBC 运行时满足 `engine.DataSourceSession`，能力位与方言一致。 */
class JdbcSessionContractTest {

    private fun profile(type: DbType) = ConnectionProfile(
        id = "p1", name = "conn", dbType = type, database = "x",
    )

    @Test
    fun `live connection is a jdbc data source session`() {
        val session: DataSourceSession = LiveConnection(profile(DbType.SQLITE))
        assertEquals("p1", session.profileId)
        assertEquals(Protocol.JDBC, session.protocol)
        assertEquals(EditorLanguage.SQL, session.capabilities.editorLanguage)
        assertTrue(session.capabilities.editableResult)
        assertTrue(session.capabilities.sqlCompletion)
        assertTrue(session.capabilities.objectDdl)
        assertTrue(session is EditableSession)
        assertFalse(session.isOpen) // 懒建：未 open 前不算已连接
    }

    @Test
    fun `capabilities mirror dialect traits`() {
        val pg = LiveConnection(profile(DbType.POSTGRES)).capabilities
        assertTrue(pg.lazyObjectGroups)
        assertTrue(pg.sessionContext) // PG 支持 search_path 切换

        val sqlite = LiveConnection(profile(DbType.SQLITE)).capabilities
        assertFalse(sqlite.lazyObjectGroups)
        assertFalse(sqlite.sessionContext) // 单文件无目标切换语义

        val sqlserver = LiveConnection(profile(DbType.SQLSERVER)).capabilities
        assertFalse(sqlserver.sessionContext)
    }
}
