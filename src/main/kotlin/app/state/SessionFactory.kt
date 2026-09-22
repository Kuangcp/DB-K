package app.state

import db.ConnectionProfile
import engine.DataSourceSession
import engine.Protocol
import es.ElasticsearchSession
import jdbc.LiveConnection
import jdbc.splitSqlStatementRanges
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

    /**
     * 与 [splitStatements] 同一规则，但保留每条语句在 [text] 中的字符区间（含首尾索引），
     * 供 gutter 把执行状态锚定到行。空文本/仅空白的 ES 文本返回空。
     * `text.substring(r.first, r.last + 1)` 逐条等于 [splitStatements] 的产物。
     */
    fun splitStatementRanges(profile: ConnectionProfile, text: String): List<IntRange> =
        when (profile.dbType.protocol) {
            Protocol.JDBC -> splitSqlStatementRanges(text)
            Protocol.REDIS -> redisCommandRanges(text)
            Protocol.ELASTICSEARCH -> if (text.isBlank()) emptyList() else listOf(0..text.lastIndex)
        }

    private fun redisCommandRanges(text: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var lineStart = 0
        while (lineStart <= text.length) {
            val nl = text.indexOf('\n', lineStart)
            val lineEnd = if (nl < 0) text.length else nl
            var s = lineStart
            var e = lineEnd - 1
            while (s < lineEnd && text[s].isWhitespace()) s++
            while (e >= s && text[e].isWhitespace()) e--
            if (e >= s && text[s] != '#') out += s..e
            if (nl < 0) break
            lineStart = nl + 1
        }
        return out
    }
}
