package app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DropPathsTest {

    @Test
    fun `file uri becomes absolute file and decodes escapes`() {
        val f = dragUriToFile("file:/home/zk/proj/doc/sql/0917_1.5.8_dualExpert.sql")
        assertEquals("/home/zk/proj/doc/sql/0917_1.5.8_dualExpert.sql", f?.path)
        assertEquals("/home/zk/a b.sql", dragUriToFile("file:/home/zk/a%20b.sql")?.path)
    }

    @Test
    fun `bare path falls back`() {
        assertEquals("/tmp/x.sql", dragUriToFile("/tmp/x.sql")?.path)
    }

    @Test
    fun `blank returns null`() {
        assertNull(dragUriToFile("   "))
    }
}
