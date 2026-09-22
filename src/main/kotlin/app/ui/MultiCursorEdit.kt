package app.ui

/**
 * 多光标列编辑的纯逻辑（无 Compose 依赖，便于单测）。
 *
 * 编辑器（SqlEditorPane）在 `Alt+Shift+↑/↓` 时维护一组 [CursorSel]（空 = 单光标）。
 * 这里只负责：
 * - 按「同列」在相邻行定位新光标；
 * - `Shift+←/→` 同时移动所有光标的 head；
 * - 把一次输入（字符 / Backspace / Delete / Enter）批量应用到所有光标，
 *   返回新文本与折叠后的新光标位置。
 */

/** 单个光标/选区。anchor 固定端，head 移动端（与 VSCode 语义一致）。 */
data class CursorSel(val anchor: Int, val head: Int) {
    val start: Int get() = minOf(anchor, head)
    val end: Int get() = maxOf(anchor, head)
    val collapsed: Boolean get() = anchor == head
}

/** 一次多光标编辑操作。 */
sealed interface MultiEditOp {
    data class Insert(val char: Char) : MultiEditOp
    data object Backspace : MultiEditOp
    data object Delete : MultiEditOp

    /** Ctrl+Backspace：按词块（含分隔符）向左删除。 */
    data object BackspaceWord : MultiEditOp

    /** Ctrl+Delete：按词块（含分隔符）向右删除。 */
    data object DeleteWord : MultiEditOp
}

/** 词字符：与编辑器标识符一致（字母/数字/下划线/$）；其余视为分隔符。 */
private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

/** Ctrl+Backspace 的删除起点：先向左跳过非词字符，再跳过词字符。 */
internal fun wordStartBefore(text: String, pos: Int): Int {
    var i = pos.coerceIn(0, text.length)
    while (i > 0 && !isWordChar(text[i - 1])) i--
    while (i > 0 && isWordChar(text[i - 1])) i--
    return i
}

/** Ctrl+Delete 的删除终点：先向右跳过非词字符，再跳过词字符。 */
internal fun wordEndAfter(text: String, pos: Int): Int {
    var i = pos.coerceIn(0, text.length)
    while (i < text.length && !isWordChar(text[i])) i++
    while (i < text.length && isWordChar(text[i])) i++
    return i
}

/** 多光标批量编辑结果：新文本 + 折叠后的新光标（顺序与输入一致）。 */
data class MultiEditResult(val text: String, val cursors: List<CursorSel>)

/** 所有物理行的起始偏移（含文末换行后的最后一个空行）。 */
internal fun lineStarts(text: String): List<Int> {
    val starts = mutableListOf(0)
    for (i in text.indices) if (text[i] == '\n') starts.add(i + 1)
    return starts
}

/**
 * 在 [offset] 所在行的上（delta=-1）或下（delta=+1）一行，取同列的偏移。
 * 目标行更短时夹到行尾；没有目标行返回 null。
 */
fun addCursorUpDown(text: String, offset: Int, delta: Int): Int? {
    val o = offset.coerceIn(0, text.length)
    val starts = lineStarts(text)
    val curLineStart = text.lastIndexOf('\n', o - 1) + 1
    val lineIndex = starts.indexOf(curLineStart).takeIf { it >= 0 } ?: return null
    val targetIndex = lineIndex + delta
    if (targetIndex !in starts.indices) return null
    val targetStart = starts[targetIndex]
    val targetEnd = text.indexOf('\n', targetStart).let { if (it < 0) text.length else it }
    val column = o - curLineStart
    return (targetStart + column).coerceIn(targetStart, targetEnd)
}

/** 所有光标的 head 同时平移 [delta]，夹在 `0..textLength`。 */
fun shiftCursors(cursors: List<CursorSel>, delta: Int, textLength: Int): List<CursorSel> =
    cursors.map { it.copy(head = (it.head + delta).coerceIn(0, textLength)) }

private data class Edit(
    val start: Int,
    val end: Int,
    val insert: String,
    val newOffsetBase: Int,
)

/**
 * 把 [op] 同时应用到每个光标（选区折叠时作用于光标处，非折叠时作用于选区）。
 * 从右到左应用编辑，避免左侧偏移被右侧编辑干扰；最后再把每个光标的新偏移
 * 按「位于其左侧的所有编辑」做一次位移修正。
 */
fun applyMultiCursorEdit(text: String, cursors: List<CursorSel>, op: MultiEditOp): MultiEditResult {
    if (cursors.isEmpty()) return MultiEditResult(text, emptyList())

    val edits = cursors.map { cursor ->
        val s = cursor.start
        val e = cursor.end
        when (op) {
            is MultiEditOp.Insert -> Edit(s, e, op.char.toString(), s + 1)
            MultiEditOp.Backspace -> if (cursor.collapsed) {
                if (s == 0) Edit(0, 0, "", 0) else Edit(s - 1, s, "", s - 1)
            } else {
                Edit(s, e, "", s)
            }
            MultiEditOp.Delete -> if (cursor.collapsed) {
                if (e >= text.length) Edit(text.length, text.length, "", text.length)
                else Edit(s, s + 1, "", s)
            } else {
                Edit(s, e, "", s)
            }
            MultiEditOp.BackspaceWord -> if (cursor.collapsed) {
                val ws = wordStartBefore(text, s)
                Edit(ws, s, "", ws)
            } else {
                Edit(s, e, "", s)
            }
            MultiEditOp.DeleteWord -> if (cursor.collapsed) {
                val we = wordEndAfter(text, s)
                Edit(s, we, "", s)
            } else {
                Edit(s, e, "", s)
            }
        }
    }

    val sb = StringBuilder(text)
    // 从右到左：右侧编辑不会影响左侧区间
    for (edit in edits.sortedByDescending { it.start }) {
        sb.replace(edit.start, edit.end, edit.insert)
    }
    val newText = sb.toString()

    val newCursors = edits.map { edit ->
        var offset = edit.newOffsetBase
        for (other in edits) {
            if (other.start < edit.start) {
                offset += other.insert.length - (other.end - other.start)
            }
        }
        CursorSel(offset, offset)
    }
    return MultiEditResult(newText, newCursors)
}
