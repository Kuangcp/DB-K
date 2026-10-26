package engine

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueryTimeoutTest {

    @Test
    fun `normalize falls back to default and clamps`() {
        assertEquals(20, QueryTimeout.normalize(null))
        assertEquals(20, QueryTimeout.normalize(20))
        assertEquals(0, QueryTimeout.normalize(0))
        assertEquals(0, QueryTimeout.normalize(-5))
        assertEquals(300, QueryTimeout.normalize(300))
        assertEquals(86_400, QueryTimeout.normalize(999_999))
    }

    @Test
    fun `redis socket timeout is millis and zero stays unlimited`() {
        assertEquals(0, QueryTimeout.socketTimeoutMillis(0))
        assertEquals(30_000, QueryTimeout.socketTimeoutMillis(30))
    }

    @Test
    fun `es request timeout is null when disabled`() {
        assertNull(QueryTimeout.httpTimeout(0))
        assertEquals(Duration.ofSeconds(45), QueryTimeout.httpTimeout(45))
    }
}
