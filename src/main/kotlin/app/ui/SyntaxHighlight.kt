package app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle

/**
 * 无正则的语法高亮（SQL / JSON）：逐字符扫描出 token 区间。
 *
 * **为什么不用正则**：`('(?:[^']|'')*')`、`("(?:\\.|[^"\\])*")` 这类 `(a|b)*` 在 java.util.regex 里
 * 是**按重复次数递归**的（栈帧 `Pattern$GroupTail.match`），单个字符串字面量超过约 5k 字符就
 * `StackOverflowError`。事故：把一段带长字符串的 JSON 粘进单元格后提交 → 高亮在 UI 线程炸掉、
 * 应用卡死（2026-09-16 日志满屏 `java.base/java.util.regex.Pattern$GroupTail.match`）。
 * 扫描器 O(n)、栈深常量，1MB 正文也安全。
 *
 * 语义与原正则一致（leftmost-first、整段消费不回扫）：字符串/注释内的关键字不再被着色，
 * `-- don't` 的撇号不会开启字符串，`'-- x'` 的 `--` 不会当注释。
 */

private fun span(start: Int, end: Int, color: Color) =
    AnnotatedString.Range(SpanStyle(color = color), start, end)

private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

/** SQL 高亮区间：字符串 / 注释 / 数字 / 关键字 / 标点，其余不着色。 */
internal fun sqlHighlightSpans(
    text: String,
    pal: SqlSyntaxPalette,
    keywords: Set<String> = sqlHighlightKeywordSet(),
): List<AnnotatedString.Range<SpanStyle>> {
    val out = ArrayList<AnnotatedString.Range<SpanStyle>>()
    val n = text.length
    var i = 0
    while (i < n) {
        val c = text[i]
        when {
            // 单引号 / 双引号字符串（'' 与 "" 为转义）
            c == '\'' || c == '"' -> {
                val start = i
                i++
                while (i < n) {
                    val d = text[i]
                    if (d == c) {
                        if (i + 1 < n && text[i + 1] == c) i += 2 else { i++; break }
                    } else {
                        i++
                    }
                }
                out += span(start, i, pal.string)
            }
            // -- 行注释
            c == '-' && i + 1 < n && text[i + 1] == '-' -> {
                val start = i
                val nl = text.indexOf('\n', i)
                i = if (nl < 0) n else nl
                out += span(start, i, pal.comment)
            }
            // /* */ 块注释
            c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                val start = i
                val close = text.indexOf("*/", i + 2)
                i = if (close < 0) n else close + 2
                out += span(start, i, pal.comment)
            }
            // 数字（\d+(\.\d+)?）
            c.isDigit() -> {
                val start = i
                while (i < n && text[i].isDigit()) i++
                if (i + 1 < n && text[i] == '.' && text[i + 1].isDigit()) {
                    i++
                    while (i < n && text[i].isDigit()) i++
                }
                out += span(start, i, pal.number)
            }
            // 标识符：整词比对关键字（等价于 \b(?:kw)\b，且不会命中更长标识符的一部分）
            isWordChar(c) -> {
                val start = i
                i++
                while (i < n && isWordChar(text[i])) i++
                if (keywords.contains(text.substring(start, i).uppercase())) {
                    out += span(start, i, pal.keyword)
                }
            }
            c == '(' || c == ')' || c == ',' || c == ';' || c == '.' -> {
                out += span(i, i + 1, pal.punctuation)
                i++
            }
            else -> i++
        }
    }
    return out
}

/** JSON 高亮区间：字段名 / 字符串值 / 数字 / 布尔 / null / 标点。 */
internal fun jsonHighlightSpans(
    text: String,
    pal: JsonSyntaxPalette,
): List<AnnotatedString.Range<SpanStyle>> {
    val out = ArrayList<AnnotatedString.Range<SpanStyle>>()
    val n = text.length
    var i = 0
    while (i < n) {
        val c = text[i]
        when {
            // 字符串（\\ 转义）：后随（可含空白）冒号的算字段名
            c == '"' -> {
                val start = i
                i++
                while (i < n) {
                    val d = text[i]
                    if (d == '\\') i += 2
                    else if (d == '"') { i++; break }
                    else i++
                }
                var j = i
                while (j < n && text[j].isWhitespace()) j++
                out += span(start, i, if (j < n && text[j] == ':') pal.key else pal.string)
            }
            c == ':' || c == '{' || c == '}' || c == '[' || c == ']' || c == ',' -> {
                out += span(i, i + 1, pal.punctuation)
                i++
            }
            (c.isDigit() || (c == '-' && i + 1 < n && text[i + 1].isDigit())) -> {
                val start = i
                if (text[i] == '-') i++
                while (i < n && text[i].isDigit()) i++
                if (i < n && text[i] == '.') {
                    val dot = i
                    i++
                    if (i < n && text[i].isDigit()) {
                        while (i < n && text[i].isDigit()) i++
                    } else {
                        i = dot
                    }
                }
                if (i < n && (text[i] == 'e' || text[i] == 'E')) {
                    val exp = i
                    i++
                    if (i < n && (text[i] == '+' || text[i] == '-')) i++
                    if (i < n && text[i].isDigit()) {
                        while (i < n && text[i].isDigit()) i++
                    } else {
                        i = exp
                    }
                }
                out += span(start, i, pal.number)
            }
            isWordChar(c) -> {
                val start = i
                i++
                while (i < n && isWordChar(text[i])) i++
                when (text.substring(start, i)) {
                    "true", "false" -> out += span(start, i, pal.boolean)
                    "null" -> out += span(start, i, pal.nullLiteral)
                    else -> {}
                }
            }
            else -> i++
        }
    }
    return out
}

/** 高亮区间贴到 [androidx.compose.ui.text.input.TextFieldValue]（BasicTextField 消费带 span 的文本）。 */
internal fun androidx.compose.ui.text.input.TextFieldValue.withHighlightSpans(
    spans: List<AnnotatedString.Range<SpanStyle>>,
): androidx.compose.ui.text.input.TextFieldValue =
    androidx.compose.ui.text.input.TextFieldValue(
        AnnotatedString(text, spanStyles = spans),
        selection,
        composition,
    )
