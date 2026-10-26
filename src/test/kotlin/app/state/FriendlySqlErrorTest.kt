package app.state

import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FriendlySqlErrorTest {

    @Test
    fun `sql exception with error code uses first line`() {
        assertEquals(
            "错误码 123: table not found",
            friendlySqlError(SQLException("table not found\nline2", "42S02", 123)),
        )
    }

    @Test
    fun `sql exception without error code omits prefix`() {
        assertEquals(
            "table not found",
            friendlySqlError(SQLException("table not found", "42S02")),
        )
    }

    @Test
    fun `plain throwable uses message and truncates`() {
        assertEquals("boom", friendlySqlError(RuntimeException("boom")))
        val long = "x".repeat(300)
        assertEquals(long.take(180), friendlySqlError(RuntimeException(long)))
    }

    @Test
    fun `null becomes unknown error`() {
        assertEquals("未知错误", friendlySqlError(null))
    }

    @Test
    fun `sql timeout becomes a readable message with the configured seconds`() {
        val err = java.sql.SQLTimeoutException("ERROR: canceling statement due to user request", "57014")
        assertEquals("查询超过 20 秒未完成，已取消", friendlySqlError(err, timeoutSeconds = 20))
        // 未传秒数（例如非执行路径）→ 退回原有 SQLException 文案
        assertTrue(friendlySqlError(err).contains("canceling statement"))
    }

    @Test
    fun `socket and http timeouts are recognized through the cause chain`() {
        val redis = RuntimeException(java.net.SocketTimeoutException("Read timed out"))
        assertEquals("查询超过 5 秒未完成，已取消", friendlySqlError(redis, timeoutSeconds = 5))

        val es = java.util.concurrent.CompletionException(
            java.net.http.HttpTimeoutException("request timed out"),
        )
        assertEquals("查询超过 5 秒未完成，已取消", friendlySqlError(es, timeoutSeconds = 5))
    }

    @Test
    fun `real connection loss keeps its own message`() {
        val lost = java.sql.SQLNonTransientConnectionException("connection reset")
        assertEquals(friendlySqlError(lost), friendlySqlError(lost, timeoutSeconds = 20))
    }
}
