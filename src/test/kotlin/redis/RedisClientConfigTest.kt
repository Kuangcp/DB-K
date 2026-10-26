package redis

import db.ConnectionProfile
import db.DbType
import kotlin.test.Test
import kotlin.test.assertEquals

class RedisClientConfigTest {

    private fun profile(seconds: Int) =
        ConnectionProfile(id = "r1", name = "r", dbType = DbType.REDIS, queryTimeoutSeconds = seconds)

    @Test
    fun `configured timeout becomes socket timeout`() {
        val cfg = redisClientConfig(profile(300))
        assertEquals(300_000, cfg.socketTimeoutMillis)
        assertEquals(10_000, cfg.connectionTimeoutMillis)
    }

    @Test
    fun `zero means unlimited socket wait but connect still bounded`() {
        val cfg = redisClientConfig(profile(0))
        assertEquals(0, cfg.socketTimeoutMillis)
        assertEquals(10_000, cfg.connectionTimeoutMillis)
    }
}
