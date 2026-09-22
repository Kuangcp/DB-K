package app.state

/**
 * 执行语句区间 → 控制台正文行号的映射（gutter 行状态锚定用，纯逻辑）。
 *
 * - 语句区间相对「本次执行的本地文本」（选中片段等）；[anchor] 为该本地文本起点在
 *   控制台正文中的 offset（null = 执行目标不在正文里，如预览命令 → 不锚定行）。
 * - 结果与 [ranges] 一一对应；无法锚定的位置为 null。
 */

/** offset 所在物理行（0-based）；offset 夹在 `[0, text.length]`。 */
fun lineIndexAt(text: String, offset: Int): Int {
    val o = offset.coerceIn(0, text.length)
    var line = 0
    var i = 0
    while (i < o) {
        if (text[i] == '\n') line++
        i++
    }
    return line
}

/** 物理行数（至少 1；空文本也算 1 行）。 */
fun lineCountOf(text: String): Int = text.count { it == '\n' } + 1

/** 一行的聚合标记：一行多语句取最严重状态（FAILED > RUNNING > PENDING > SKIPPED > OK），[count] = 该行语句数。 */
data class LineMarker(val status: RunStatus, val count: Int)

/** 把逐语句进度聚合到物理行（`line == null` 不落标记）。 */
fun aggregateLineMarkers(progress: List<StatementProgress>): Map<Int, LineMarker> {
    if (progress.isEmpty()) return emptyMap()
    val byLine = LinkedHashMap<Int, MutableList<RunStatus>>()
    for (p in progress) {
        val line = p.line ?: continue
        byLine.getOrPut(line) { mutableListOf() }.add(p.status)
    }
    return byLine.mapValues { (_, statuses) -> LineMarker(aggregateStatus(statuses), statuses.size) }
}

private fun aggregateStatus(statuses: List<RunStatus>): RunStatus = when {
    statuses.any { it == RunStatus.FAILED } -> RunStatus.FAILED
    statuses.any { it == RunStatus.RUNNING } -> RunStatus.RUNNING
    statuses.any { it == RunStatus.PENDING } -> RunStatus.PENDING
    statuses.all { it == RunStatus.SKIPPED } -> RunStatus.SKIPPED
    else -> RunStatus.OK
}

/** 把相对本地文本的 [ranges] 平移到正文 [text] 坐标并映射为行号；[anchor] 为 null 全部返回 null。 */
fun statementLines(text: String, ranges: List<IntRange>, anchor: Int?): List<Int?> {
    if (anchor == null) return ranges.map { null }
    return ranges.map { r ->
        val start = anchor + r.first
        if (start in 0..text.length) lineIndexAt(text, start) else null
    }
}
