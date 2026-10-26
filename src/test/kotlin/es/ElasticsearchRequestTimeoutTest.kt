package es

import db.ConnectionProfile
import db.DbType
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ElasticsearchRequestTimeoutTest {

    private fun profile(seconds: Int) =
        ConnectionProfile(id = "e1", name = "e", dbType = DbType.ELASTICSEARCH, queryTimeoutSeconds = seconds)

    @Test
    fun `configured timeout becomes request timeout`() {
        assertEquals(Duration.ofSeconds(45), esRequestTimeout(profile(45)))
    }

    @Test
    fun `zero means no request timeout`() {
        assertNull(esRequestTimeout(profile(0)))
    }
}
