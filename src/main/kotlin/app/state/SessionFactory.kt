package app.state

import db.ConnectionProfile
import engine.DataSourceSession
import engine.Protocol
import es.ElasticsearchSession
import jdbc.LiveConnection
import jdbc.splitSqlStatements
import redis.RedisProtocol
import redis.RedisSession

/**
 * 按连接档案的协议创建会话实现（多协议入口）。
 * `app` 层唯一知道所有后端实现的地方，`engine` 契约层保持无实现依赖。
 */
object SessionFactory {

    fun create(profile: ConnectionProfile): DataSourceSession = when (profile.dbType.protocol) {
        Protocol.JDBC -> LiveConnection(profile)
        Protocol.REDIS -> RedisSession(profile)
        Protocol.ELASTICSEARCH -> ElasticsearchSession(profile)
    }

    /** 按协议切分控制台文本为多条语句：SQL 按 `;`；Redis 按行；ES 整段一个报体。 */
    fun splitStatements(profile: ConnectionProfile, text: String): List<String> =
        when (profile.dbType.protocol) {
            Protocol.JDBC -> splitSqlStatements(text)
            Protocol.REDIS -> RedisProtocol.splitCommands(text)
            Protocol.ELASTICSEARCH -> listOf(text)
        }
}
