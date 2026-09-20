package redis

import engine.model.QueryColumn
import engine.model.QueryResult
import i18n.I18n
import i18n.Str

/**
 * Redis 控制台文本 → 命令 / 结果 的纯逻辑（无 Jedis 依赖，可单测）。
 *
 * - 语句切分：**按行**（一行一条命令）；空行与 `#` 注释忽略。
 * - 命令分词：支持空格分隔、单/双引号、双引号内反斜杠转义。
 * - 回复渲染：把 Jedis 原始回复（`Object`）转成通用二维 [QueryResult]。
 */
object RedisProtocol {

    /** 切分控制台文本为多条命令（一行一条）。 */
    fun splitCommands(text: String): List<String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()

    /** 把一行命令拆成 [命令, 参数…]。 */
    fun tokenize(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quote: Char? = null
        var started = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quote != null -> when {
                    c == '\\' && quote == '"' && i + 1 < line.length -> {
                        sb.append(line[i + 1]); i++
                    }
                    c == quote -> quote = null
                    else -> sb.append(c)
                }
                c == '"' || c == '\'' -> {
                    quote = c; started = true
                }
                c.isWhitespace() -> {
                    if (started || sb.isNotEmpty()) {
                        out += sb.toString(); sb.clear(); started = false
                    }
                }
                else -> {
                    sb.append(c); started = true
                }
            }
            i++
        }
        if (started || sb.isNotEmpty()) out += sb.toString()
        return out
    }

    private val TWO_COLUMN_COMMANDS = setOf("HGETALL", "CONFIG", "XPENDING")

    /** 破坏性 / 危险命令的首词（执行前二次确认）。 */
    private val DANGEROUS_COMMANDS = setOf(
        "FLUSHALL", "FLUSHDB", "SHUTDOWN", "SWAPDB", "DEBUG", "SCRIPT", "REPLICAOF", "SLAVEOF",
    )

    /** 返回该命令的危险标识（前两个词，如 `DEBUG SEGFAULT`）；null = 安全。 */
    fun dangerousCommand(statement: String): String? {
        val tokens = tokenize(statement)
        val head = tokens.firstOrNull()?.uppercase() ?: return null
        if (head !in DANGEROUS_COMMANDS) return null
        return tokens.take(2).joinToString(" ") { it.uppercase() }
    }

    /** 把 Jedis 原始回复转成二维结果（[statement] 仅用于识别 HGETALL 等双列命令）。 */
    fun replyToResult(statement: String, reply: Any?, durationMs: Long): QueryResult {
        val head = tokenize(statement).firstOrNull()?.uppercase()
        val (columns, rows) = when (reply) {
            null -> listOf(nullColumn()) to listOf(listOf("(nil)"))
            is Map<*, *> -> listOf(nullColumn(I18n.t(Str.NounKey)), nullColumn(I18n.t(Str.NounValue))) to
                reply.entries.map { listOf(replyToString(it.key), replyToString(it.value)) }
            is Collection<*> -> collectionToRows(reply.toList(), head)
            else -> listOf(nullColumn()) to listOf(listOf(replyToString(reply)))
        }
        return QueryResult(statement, columns, rows, durationMs = durationMs)
    }

    private fun collectionToRows(items: List<Any?>, head: String?): Pair<List<QueryColumn>, List<List<String?>>> {
        if (items.isEmpty()) return listOf(nullColumn()) to listOf(listOf(I18n.t(Str.RedisEmptyValue)))
        if (items.all { it is Collection<*> }) {
            // 嵌套数组（如 XRANGE）：展示为「# | 值」（内层用 " | " 连接）
            val rows = items.mapIndexed { i, e ->
                listOf(i.toString(), (e as Collection<*>).joinToString(" | ") { replyToString(it) })
            }
            return listOf(nullColumn("#"), nullColumn()) to rows
        }
        if (head in TWO_COLUMN_COMMANDS && items.size % 2 == 0) {
            val rows = items.chunked(2).map { (k, v) -> listOf(replyToString(k), replyToString(v)) }
            return listOf(nullColumn(I18n.t(Str.NounKey)), nullColumn(I18n.t(Str.NounValue))) to rows
        }
        return listOf(nullColumn()) to items.map { listOf(replyToString(it)) }
    }

    /** 单个回复值 → 展示字符串（与 `engine.model.QueryResult` 单元格一致的字符串化）。 */
    fun replyToString(o: Any?): String = when (o) {
        null -> "(nil)"
        is ByteArray -> String(o, Charsets.UTF_8)
        is Boolean -> if (o) "true" else "false"
        is Collection<*> -> o.joinToString(" | ") { replyToString(it) }
        else -> o.toString()
    }

    private fun nullColumn(name: String = I18n.t(Str.NounValue)) = QueryColumn(name)
}
