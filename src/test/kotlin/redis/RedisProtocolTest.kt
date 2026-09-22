package redis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Redis 控制台纯逻辑：命令切分 / 分词 / 回复渲染。 */
class RedisProtocolTest {

    @Test
    fun `split skips blanks and comments`() {
        assertEquals(
            listOf("GET a", "SET b 1"),
            RedisProtocol.splitCommands("GET a\n\n   # 注释\n SET b 1 \n"),
        )
    }

    @Test
    fun `tokenize handles quotes and escapes`() {
        assertEquals(listOf("SET", "k", "hello world"), RedisProtocol.tokenize("SET k \"hello world\""))
        assertEquals(listOf("SET", "k", "a\"b"), RedisProtocol.tokenize("SET k \"a\\\"b\""))
        assertEquals(listOf("SET", "k", "hello world"), RedisProtocol.tokenize("SET k 'hello world'"))
        assertEquals(listOf("SET", "k", ""), RedisProtocol.tokenize("SET k \"\""))
    }

    @Test
    fun `null and scalar replies`() {
        assertEquals(listOf("(nil)"), RedisProtocol.replyToResult("GET k", null, 0).rows.single())
        assertEquals(listOf("PONG"), RedisProtocol.replyToResult("PING", "PONG", 0).rows.single())
        assertEquals(listOf("5"), RedisProtocol.replyToResult("INCR k", 5L, 0).rows.single())
        assertEquals(listOf("x"), RedisProtocol.replyToResult("GET k", "x".toByteArray(), 0).rows.single())
    }

    @Test
    fun `flat list becomes one column`() {
        val r = RedisProtocol.replyToResult("LRANGE k 0 -1", listOf("a", "b", "c"), 0)
        assertEquals(listOf("值"), r.columns.map { it.name })
        assertEquals(listOf(listOf("a"), listOf("b"), listOf("c")), r.rows)
    }

    @Test
    fun `hgetall becomes two columns`() {
        val r = RedisProtocol.replyToResult("HGETALL h", listOf("f1", "v1", "f2", "v2"), 0)
        assertEquals(listOf("键", "值"), r.columns.map { it.name })
        assertEquals(listOf(listOf("f1", "v1"), listOf("f2", "v2")), r.rows)
    }

    @Test
    fun `map reply becomes two columns`() {
        val r = RedisProtocol.replyToResult("CONFIG GET x", mapOf("a" to "1"), 0)
        assertEquals(listOf("键", "值"), r.columns.map { it.name })
        assertEquals(listOf(listOf("a", "1")), r.rows)
    }

    @Test
    fun `nested list flattens inner elements`() {
        val r = RedisProtocol.replyToResult("XRANGE s - +", listOf(listOf("1-0", listOf("f", "v"))), 0)
        assertEquals(listOf("#", "值"), r.columns.map { it.name })
        assertEquals(listOf(listOf("0", "1-0 | f | v")), r.rows)
    }

    @Test
    fun `empty collection shows placeholder`() {
        assertEquals(listOf("(空)"), RedisProtocol.replyToResult("LRANGE k 0 -1", emptyList<String>(), 0).rows.single())
    }

    @Test
    fun `dangerous commands are flagged`() {
        assertEquals("FLUSHALL", RedisProtocol.dangerousCommand("FLUSHALL"))
        assertEquals("FLUSHDB", RedisProtocol.dangerousCommand("flushdb"))
        assertEquals("DEBUG SEGFAULT", RedisProtocol.dangerousCommand("DEBUG SEGFAULT"))
        assertEquals("SCRIPT FLUSH", RedisProtocol.dangerousCommand("script flush"))
        assertEquals(null, RedisProtocol.dangerousCommand("GET k"))
        assertEquals(null, RedisProtocol.dangerousCommand("SET FLUSHALL 1"))
        assertEquals(null, RedisProtocol.dangerousCommand(""))
    }

    @Test
    fun `command at caret picks current line`() {
        val text = "GET a\n# comment\nHGETALL h\n\nSET b 1"
        assertEquals("HGETALL h", RedisProtocol.commandAtCaret(text, text.indexOf("HGETALL") + 2))
        assertEquals("GET a", RedisProtocol.commandAtCaret(text, 1))
        assertNull(RedisProtocol.commandAtCaret(text, text.indexOf("# comment") + 1))
        assertNull(RedisProtocol.commandAtCaret("GET a\n\n", 7))
    }

    @Test
    fun `command at caret range keeps source offset`() {
        val text = "GET a\n  SET b 1\n# c"
        val (cmd, at) = RedisProtocol.commandAtCaretRange(text, text.indexOf("SET"))!!
        assertEquals("SET b 1", cmd)
        assertEquals(text.indexOf("SET"), at)
        assertNull(RedisProtocol.commandAtCaretRange(text, text.indexOf("# c")))
    }

    @Test
    fun `read only command classification`() {
        assertTrue(RedisProtocol.isReadOnlyCommand("GET a"))
        assertTrue(RedisProtocol.isReadOnlyCommand("hgetall h"))
        assertTrue(RedisProtocol.isReadOnlyCommand("SCAN 0 MATCH x*"))
        assertTrue(RedisProtocol.isReadOnlyCommand("INFO"))
        assertFalse(RedisProtocol.isReadOnlyCommand("SET a 1"))
        assertFalse(RedisProtocol.isReadOnlyCommand("DEL a"))
        assertFalse(RedisProtocol.isReadOnlyCommand("FLUSHALL"))
        assertFalse(RedisProtocol.isReadOnlyCommand("CONFIG GET maxmemory"))
        assertFalse(RedisProtocol.isReadOnlyCommand("MULTI"))
        assertFalse(RedisProtocol.isReadOnlyCommand("SOMENEWCMD x"))
    }
}
