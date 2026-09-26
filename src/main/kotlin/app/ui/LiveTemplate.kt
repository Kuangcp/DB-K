package app.ui

/*
 * Live Templates 纯逻辑（无 Compose / 无 IO 依赖，便于单测）。
 * 只负责：解析模板体 → 展开文本 + 占位符；内置模板表；展开会话与区间平移。
 */

/** 模板生效范围；首版只有 SQL，字段预留以便后续扩展（Redis 命令 / JSON DSL）。 */
enum class LiveTemplateContext(val token: String) { SQL("sql") }

/** 一条活模板：缩写 + 模板体（含 `$NAME$` / `$END$`）。 */
data class LiveTemplate(
    val abbreviation: String,
    val body: String,
    val description: String? = null,
    val context: LiveTemplateContext = LiveTemplateContext.SQL,
)

/** 命名占位符在展开文本中的区间；`end` 为 exclusive，空占位符时 `start == end`。 */
data class TemplateStop(val name: String, val start: Int, val end: Int)

/** 展开结果：去标记文本、命名的 Tab stop（按 start 升序）、`$END$` 落点。 */
data class TemplateExpansion(val text: String, val stops: List<TemplateStop>, val endOffset: Int)

private fun isPlaceholderName(name: String): Boolean =
    name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '_' }

/**
 * 解析模板体：
 * - `$NAME$` → 空占位符（Tab stop）；
 * - `$END$` → 最终光标位（不入 stops）；
 * - `$$` → 字面量 `$`；
 * - 未闭合或名字非法的 `$` → 当字面量处理（不抛异常）。
 * 无 `$END$` 时 endOffset = 文本末尾。
 */
fun expandTemplate(body: String): TemplateExpansion {
    val sb = StringBuilder()
    val stops = ArrayList<TemplateStop>()
    var endOffset = -1
    var i = 0
    while (i < body.length) {
        val c = body[i]
        if (c != '$') {
            sb.append(c); i++; continue
        }
        if (body.getOrNull(i + 1) == '$') {
            sb.append('$'); i += 2; continue
        }
        val close = body.indexOf('$', i + 1)
        val name = if (close < 0) null else body.substring(i + 1, close)
        if (name == null || !isPlaceholderName(name)) {
            sb.append('$'); i++; continue
        }
        if (name.equals("END", ignoreCase = true)) {
            endOffset = sb.length
        } else {
            stops.add(TemplateStop(name, sb.length, sb.length))
        }
        i = close + 1
    }
    if (endOffset < 0) endOffset = sb.length
    return TemplateExpansion(sb.toString(), stops, endOffset)
}

/** 内置模板（对齐 DataGrip SQL 默认 + db-k 常用）。注意 Kotlin 字符串里 `$` 必须转义。 */
fun defaultLiveTemplates(): List<LiveTemplate> = listOf(
    LiveTemplate("sel", "SELECT \$columns\$ FROM \$table\$"),
    LiveTemplate("selw", "SELECT \$columns\$ FROM \$table\$ WHERE \$condition\$"),
    LiveTemplate("selc", "SELECT count(*) FROM \$table\$ WHERE \$condition\$"),
    LiveTemplate("ins", "INSERT INTO \$table\$ (\$columns\$) VALUES (\$values\$)"),
    LiveTemplate("upd", "UPDATE \$table\$ SET \$column\$ = \$value\$ WHERE \$condition\$"),
    LiveTemplate("del", "DELETE FROM \$table\$ WHERE \$condition\$"),
    LiveTemplate("whe", "WHERE \$condition\$"),
    LiveTemplate("ob", "ORDER BY \$column\$"),
    LiveTemplate("gb", "GROUP BY \$column\$"),
    LiveTemplate("case", "CASE WHEN \$condition\$ THEN \$result\$ ELSE \$result\$ END"),
)

/**
 * 一次模板展开会话：`lastText` 为上次文本快照（供 [reconcileSession] 求 diff），
 * `anchorStart` 是整段插入文本的文档起点，`stops`/`endOffset` 均为文档坐标。
 */
data class TemplateSession(
    val lastText: String,
    val anchorStart: Int,
    val stops: List<TemplateStop>,
    val activeIndex: Int,
    val endOffset: Int,
)

/**
 * 用 [body] 展开替换 [doc] 的 [replaceRange]（Kotlin 闭区间；空区间即插入），
 * 返回新文本与会话。命中第一个占位符时会话可直接导航；无占位符时 `activeIndex = -1`，
 * 调用方应只落光标到 `endOffset`、不保留会话。
 */
fun beginSession(doc: String, replaceRange: IntRange, body: String): Pair<String, TemplateSession> {
    val exp = expandTemplate(body)
    val from = replaceRange.first.coerceIn(0, doc.length)
    val to = (replaceRange.last + 1).coerceIn(from, doc.length)
    val newText = doc.substring(0, from) + exp.text + doc.substring(to)
    val session = TemplateSession(
        lastText = newText,
        anchorStart = from,
        stops = exp.stops.map { TemplateStop(it.name, it.start + from, it.end + from) },
        activeIndex = if (exp.stops.isEmpty()) -1 else 0,
        endOffset = exp.endOffset + from,
    )
    return newText to session
}

/** 当前活动占位符；无（空模板）返回 null。 */
fun activeStop(session: TemplateSession): TemplateStop? = session.stops.getOrNull(session.activeIndex)

/** 下一个占位符；已越过最后一个 → 返回 `(null, endOffset..endOffset)`，调用方结束会话。 */
fun sessionNext(session: TemplateSession): Pair<TemplateSession?, Pair<Int, Int>> {
    val next = session.activeIndex + 1
    return if (next in session.stops.indices) {
        val s = session.stops[next]
        session.copy(activeIndex = next) to (s.start to s.end)
    } else {
        null to (session.endOffset to session.endOffset)
    }
}

/** 上一个占位符；已在第一个或空模板 → 原地返回（selection 仍是当前 stop 或 endOffset）。 */
fun sessionPrev(session: TemplateSession): Pair<TemplateSession, Pair<Int, Int>> {
    if (session.stops.isEmpty()) return session to (session.endOffset to session.endOffset)
    val prev = (session.activeIndex - 1).coerceAtLeast(0)
    val s = session.stops[prev]
    return session.copy(activeIndex = prev) to (s.start to s.end)
}

/**
 * 文本变化后重算占位区间：对 old/new 求最长公共前缀 `p` 与最长公共后缀 `s`，得到改动区间
 * `[p, old.length - s)` 与长度差 `delta`，把停靠点边界按下述约定映射。返回 null 表示
 * 会话失效（模板锚点之前被删、或区间非法），调用方应清空会话。
 *
 * 边界映射：位于改动区间之前的原样保留；位于之后的整体平移；恰好落在区间边界时，
 * 起点贴到 `p`、终点贴到改动后的区间末尾——这样「在空占位符处输入」表现为占位符增长。
 */
fun reconcileSession(session: TemplateSession, newText: String): TemplateSession? {
    val old = session.lastText
    if (newText == old) return session

    var p = 0
    val maxP = minOf(old.length, newText.length)
    while (p < maxP && old[p] == newText[p]) p++

    var s = 0
    val maxS = minOf(old.length - p, newText.length - p)
    while (s < maxS && old[old.length - 1 - s] == newText[newText.length - 1 - s]) s++

    if (p < session.anchorStart) return null

    val oldEditEnd = old.length - s
    val delta = newText.length - old.length
    val newEditEnd = oldEditEnd + delta

    fun mapBoundary(pos: Int, isEnd: Boolean): Int = when {
        pos < p -> pos
        pos > oldEditEnd -> pos + delta
        isEnd -> (pos + delta).coerceIn(p, maxOf(p, newEditEnd))
        else -> p
    }

    val stops = session.stops.map { st ->
        val ns = mapBoundary(st.start, isEnd = false)
        val ne = mapBoundary(st.end, isEnd = true).coerceAtLeast(ns)
        TemplateStop(st.name, ns, ne)
    }
    if (stops.any { it.start < session.anchorStart || it.end < it.start }) return null
    return session.copy(
        lastText = newText,
        stops = stops,
        endOffset = mapBoundary(session.endOffset, isEnd = true).coerceAtLeast(session.anchorStart),
    )
}
