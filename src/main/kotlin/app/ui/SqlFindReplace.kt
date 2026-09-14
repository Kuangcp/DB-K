package app.ui

/**
 * 编辑器查找 / 替换（N2）纯逻辑：不依赖 compose，便于单测。
 * 查找返回**不重叠**的命中区间（按出现顺序）；替换在字符串上完成。
 */

data class FindOptions(
    val regex: Boolean = false,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
)

private fun regexOptions(caseSensitive: Boolean): Set<RegexOption> =
    if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)

/** 查找全部命中（不重叠、按出现顺序）。query 为空 / 正则非法时返回空表。 */
fun findMatches(text: String, query: String, options: FindOptions): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    if (options.regex) {
        val regex = runCatching { Regex(query, regexOptions(options.caseSensitive)) }.getOrNull()
            ?: return emptyList()
        if (regex.pattern.isEmpty()) return emptyList()
        if (options.wholeWord) {
            return regex.findAll(text).map { it.range }.filter { isWholeWord(text, it) }.toList()
        }
        return regex.findAll(text).map { it.range }.toList()
    }
    val out = ArrayList<IntRange>()
    var from = 0
    while (from <= text.length - query.length) {
        val idx = text.indexOf(query, from, ignoreCase = !options.caseSensitive)
        if (idx < 0) break
        val range = idx until idx + query.length
        if (!options.wholeWord || isWholeWord(text, range)) out += range
        from = idx + query.length
    }
    return out
}

/** 命中区间是否整词（两侧不是标识符字符）。 */
private fun isWholeWord(text: String, range: IntRange): Boolean {
    val before = text.getOrNull(range.first - 1)
    val after = text.getOrNull(range.last + 1)
    fun wordChar(c: Char?) = c != null && (c.isLetterOrDigit() || c == '_')
    return !wordChar(before) && !wordChar(after)
}

/** 替换 [range] 处文本（按已知区间替换，不做正则分组展开）。 */
fun replaceRange(text: String, range: IntRange, replacement: String): String {
    if (range.first < 0 || range.last + 1 > text.length) return text
    return text.substring(0, range.first) + replacement + text.substring(range.last + 1)
}

/**
 * 全部替换；返回新文本与替换次数。
 * 正则模式支持 `$1` 等分组引用（Kotlin [Regex.replace] 语义）；非法正规则原样返回。
 */
fun replaceAll(text: String, query: String, options: FindOptions, replacement: String): Pair<String, Int> {
    if (query.isEmpty()) return text to 0
    if (options.regex) {
        val regex = runCatching { Regex(query, regexOptions(options.caseSensitive)) }.getOrNull()
            ?: return text to 0
        val count = findMatches(text, query, options).size
        if (count == 0) return text to 0
        return regex.replace(text, replacement) to count
    }
    val matches = findMatches(text, query, options)
    if (matches.isEmpty()) return text to 0
    val sb = StringBuilder(text)
    // 从后往前替换，避免前面替换后区间偏移
    matches.asReversed().forEach { sb.replace(it.first, it.last + 1, replacement) }
    return sb.toString() to matches.size
}
