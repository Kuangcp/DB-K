package app.state

import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
