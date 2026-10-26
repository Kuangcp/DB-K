package engine

import java.time.Duration

/**
 * 连接级「查询超时」语义（`0` = 不限时）与其到各后端取值形式的唯一映射。
 * 三后端与连接编辑弹窗都从这里取常量/换算，避免各写一份魔数。
 *
 * 落点：JDBC `Statement.setQueryTimeout`（秒，`0` = 无限制）、Redis Jedis socket 读超时（毫秒，
 * `0` = 无限等待）、Elasticsearch `HttpRequest.timeout`（`0` = 不设请求超时，HttpClient 默认无限）。
 */
object QueryTimeout {
    const val DEFAULT_SECONDS = 20
    const val MAX_SECONDS = 86_400

    /** 建连超时与查询超时解耦：Jedis / ES 都用它，避免 `0` 让 TCP 建连也无限等。 */
    const val CONNECT_TIMEOUT_MS = 10_000

    /** 把用户输入/存量值收敛到合法区间；null（输入框清空、旧数据缺失）= 默认值。 */
    fun normalize(seconds: Int?): Int = (seconds ?: DEFAULT_SECONDS).coerceIn(0, MAX_SECONDS)

    fun socketTimeoutMillis(seconds: Int): Int = normalize(seconds) * 1000

    /** null = 不设请求超时。 */
    fun httpTimeout(seconds: Int): Duration? =
        normalize(seconds).takeIf { it > 0 }?.let { Duration.ofSeconds(it.toLong()) }
}
