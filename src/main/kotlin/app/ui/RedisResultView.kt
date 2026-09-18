package app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dialog.JsonTreeState
import app.dialog.JsonTreeView
import app.dialog.viewerScrollbarStyle
import app.state.ConsoleRunUi
import app.state.RedisKeyMeta
import app.state.StatementOutcome
import engine.model.QueryColumn
import engine.model.QueryResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import redis.RedisProtocol

/**
 * Redis 结果视图：与关系型 [ResultTabs]（网格 + 编辑）分开的一套渲染组件。
 * 执行/状态模型仍复用 [ConsoleRunUi]/[QueryResult]（一套模型），这里只按 Redis 语义渲染：
 * 标量 / 键值对 / 成员列表 / 有序集合 / JSON 树 / 任意原生命令的文本回退。
 */
@Composable
fun RedisResultView(
    run: ConsoleRunUi,
    /** 最近一次双击预览的键元数据（key / type / TTL）；普通命令执行为 null。 */
    redisMeta: RedisKeyMeta?,
    onSelectOutcome: (Int) -> Unit,
    onRefreshResult: () -> Unit,
    onCancelRun: () -> Unit,
    onExport: () -> Unit,
    onCopyText: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val result = run.result
    Column(modifier = modifier) {
        if (run.outcomes.isNotEmpty() || run.executing) {
            RedisResultToolbar(
                run = run,
                result = result,
                onSelectOutcome = onSelectOutcome,
                onRefreshResult = onRefreshResult,
                onCancelRun = onCancelRun,
                onExport = onExport,
            )
            Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.08f))
        }
        RedisResultPane(
            run = run,
            redisMeta = redisMeta,
            onCopyText = onCopyText,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

/** Redis 结果工具条：多语句 chip + 状态 + 刷新/导出/取消（刻意不收关系型编辑/转置/取更多）。 */
@Composable
private fun RedisResultToolbar(
    run: ConsoleRunUi,
    result: QueryResult?,
    onSelectOutcome: (Int) -> Unit,
    onRefreshResult: () -> Unit,
    onCancelRun: () -> Unit,
    onExport: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 4.dp),
    ) {
        if (run.outcomes.size > 1) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            ) {
                run.outcomes.forEachIndexed { i, outcome ->
                    RedisOutcomeChip(i, outcome, active = i == run.activeIndex, onClick = onSelectOutcome)
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (run.executing) {
            CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp)
            Text(" 执行中…", fontSize = 11.sp, color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f))
        } else if (run.error == null && result != null) {
            Text(
                redisMetaText(result),
                fontSize = 11.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(4.dp))
        if (run.executing) {
            RedisIconButton(
                icon = DbIcons.Stop,
                description = "取消执行 (Esc)",
                danger = true,
                onClick = onCancelRun,
            )
        } else {
            RedisIconButton(
                icon = DbIcons.Refresh,
                description = "刷新 (F5)",
                enabled = result != null,
                onClick = onRefreshResult,
            )
            RedisIconButton(
                icon = DbIcons.Download,
                description = "导出结果（CSV / JSON / SQL INSERT / Excel）",
                enabled = result != null && result.isQuery && result.rowCount > 0,
                onClick = onExport,
            )
        }
    }
}

@Composable
private fun RedisOutcomeChip(index: Int, outcome: StatementOutcome, active: Boolean, onClick: (Int) -> Unit) {
    val dot = when {
        outcome.error != null -> Color(0xFFE53935)
        outcome.isQuery -> MaterialTheme.colors.primary
        else -> Color(0xFF9E9E9E)
    }
    val suffix = when {
        !outcome.ok -> " ✕"
        outcome.isQuery -> " · ${outcome.result?.rowCount ?: 0} 行"
        else -> " · ${outcome.result?.affectedRows ?: 0} 行"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(end = 4.dp)
            .clip(RoundedCornerShape(5.dp))
            .clickable { onClick(index) }
            .background(if (active) MaterialTheme.colors.primary.copy(alpha = 0.14f) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(dot))
        Spacer(Modifier.width(5.dp))
        Text(
            "结果 ${index + 1}",
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
        )
        Text(suffix, fontSize = 10.5.sp, color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RedisIconButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> MaterialTheme.colors.onSurface.copy(alpha = 0.25f)
        danger -> MaterialTheme.colors.error
        else -> MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
    }
    TooltipArea(
        tooltip = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(MaterialTheme.colors.surface)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(description, fontSize = 11.sp, color = MaterialTheme.colors.onSurface)
            }
        },
        delayMillis = 500,
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(24.dp)) {
            Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun RedisResultPane(
    run: ConsoleRunUi,
    redisMeta: RedisKeyMeta?,
    onCopyText: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colors.surface),
    ) {
        val result = run.result
        val error = run.error
        when {
            run.executing -> RedisCenteredHint("执行中…")
            error != null -> RedisCenteredHint(error, isError = true)
            result == null -> RedisCenteredHint(
                "双击左侧 Redis 键直接查看值，或在控制台执行 Redis 命令",
            )
            result.affectedRows != null -> RedisCenteredHint(
                if (result.affectedRows == 0) "命令执行成功"
                else "命令执行成功（影响 ${result.affectedRows} 行）",
            )
            else -> {
                val cmd = RedisProtocol.tokenize(result.sql).firstOrNull()?.uppercase()
                val singleValue = result.rowCount == 1 && result.columns.size == 1
                when {
                    cmd == "GET" -> RedisScalarView(result, redisMeta, onCopyText, allowJson = true)
                    cmd == "HGETALL" -> RedisKvView(result, redisMeta, onCopyText)
                    cmd == "LRANGE" || cmd == "SMEMBERS" -> RedisMembersView(result, redisMeta, onCopyText)
                    cmd == "ZRANGE" -> RedisZSetView(result, redisMeta, onCopyText)
                    singleValue -> RedisScalarView(result, redisMeta, onCopyText, allowJson = false)
                    else -> RedisTextFallback(result, onCopyText)
                }
            }
        }
    }
}

/** 键元数据 + 执行命令的顶部说明条。 */
@Composable
private fun RedisKeyHeader(meta: RedisKeyMeta?, sql: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (meta != null) {
                Text(
                    meta.key,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                meta.type?.let {
                    Text(
                        "  ·  $it",
                        fontSize = 11.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                        maxLines = 1,
                    )
                }
                Text(
                    "  ·  TTL ${redisTtlLabel(meta.ttlSeconds)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                )
            } else {
                Text(
                    sql,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (meta != null) {
            Text(
                sql,
                fontSize = 10.5.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 复制按钮：紧凑文本按钮，避免 material-icons-core 缺少 copy 图标。 */
@Composable
private fun CopyTextButton(text: String, label: String, onCopyText: (String, String) -> Unit) {
    Text(
        "复制",
        fontSize = 10.5.sp,
        color = MaterialTheme.colors.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable { onCopyText(text, label) }
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/** 标量值（GET / TYPE / TTL / STRLEN…）：整块等宽文本 + 复制；GET 的 JSON 值可切 JSON 树。 */
@Composable
private fun RedisScalarView(
    result: QueryResult,
    meta: RedisKeyMeta?,
    onCopyText: (String, String) -> Unit,
    allowJson: Boolean,
) {
    val value = result.rows.firstOrNull()?.firstOrNull() ?: ""
    val json = if (allowJson) remember(value) { parseJson(value) } else null
    var jsonMode by remember(result.sql) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        RedisKeyHeader(meta, result.sql)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
        ) {
            if (json != null) {
                Text(
                    "文本",
                    fontSize = 11.sp,
                    color = if (!jsonMode) MaterialTheme.colors.primary
                    else MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { jsonMode = false }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
                Text(
                    "JSON 树",
                    fontSize = 11.sp,
                    color = if (jsonMode) MaterialTheme.colors.primary
                    else MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { jsonMode = true }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            CopyTextButton(value, "已复制值", onCopyText)
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (json != null && jsonMode) {
                JsonTreeView(json, remember(value) { JsonTreeState() }, Modifier.fillMaxSize())
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 10.dp, end = 10.dp, bottom = 6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colors.onSurface.copy(alpha = 0.04f))
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    Text(
                        value,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.9f),
                    )
                }
            }
        }
    }
}

/** 键值对（HGETALL）：字段 + 值，逐行可复制值。 */
@Composable
private fun RedisKvView(
    result: QueryResult,
    meta: RedisKeyMeta?,
    onCopyText: (String, String) -> Unit,
) {
    RedisTwoColumnRows(
        result = result,
        meta = meta,
        onCopyText = onCopyText,
        labelOf = { _, row -> row.getOrNull(0) ?: "" },
        valueOf = { _, row -> row.getOrNull(1) },
    )
}

/** 成员列表（LRANGE / SMEMBERS）：序号 + 成员，逐行可复制。 */
@Composable
private fun RedisMembersView(
    result: QueryResult,
    meta: RedisKeyMeta?,
    onCopyText: (String, String) -> Unit,
) {
    RedisTwoColumnRows(
        result = result,
        meta = meta,
        onCopyText = onCopyText,
        labelOf = { i, _ -> (i + 1).toString() },
        valueOf = { _, row -> row.firstOrNull() },
    )
}

/** 有序集合（ZRANGE WITHSCORES）：member / score 两列；不带 WITHSCORES 时按成员列表。 */
@Composable
private fun RedisZSetView(
    result: QueryResult,
    meta: RedisKeyMeta?,
    onCopyText: (String, String) -> Unit,
) {
    val withScores = result.sql.contains("WITHSCORES", ignoreCase = true)
    if (!withScores) {
        RedisMembersView(result, meta, onCopyText)
        return
    }
    val pairs = result.rows.map { it.firstOrNull() ?: "" }.chunked(2)
        .map { chunk -> listOf(chunk.getOrNull(0) ?: "", chunk.getOrNull(1) ?: "") }
    // 用一份临时 QueryResult 复用两列渲染（不碰原对象，只读展示）
    val zsetResult = result.copy(
        columns = listOf(
            QueryColumn("member"),
            QueryColumn("score"),
        ),
        rows = pairs,
    )
    RedisTwoColumnRows(zsetResult, meta, onCopyText, labelOf = { _, row -> row.getOrNull(0) ?: "" }, valueOf = { _, row -> row.getOrNull(1) })
}

/** 通用两列行渲染（键值对 / 成员列表 / zset 复用）。 */
@Composable
private fun RedisTwoColumnRows(
    result: QueryResult,
    meta: RedisKeyMeta?,
    onCopyText: (String, String) -> Unit,
    labelOf: (Int, List<String?>) -> String,
    valueOf: (Int, List<String?>) -> String?,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        RedisKeyHeader(meta, result.sql)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(start = 10.dp, end = 4.dp),
            ) {
                itemsIndexed(result.rows, key = { i, _ -> i }) { i, row ->
                    val label = labelOf(i, row)
                    val value = valueOf(i, row) ?: ""
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    ) {
                        Text(
                            label,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 200.dp).padding(end = 8.dp),
                        )
                        Text(
                            value,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.92f),
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                        )
                        CopyTextButton(value, "已复制值", onCopyText)
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                style = viewerScrollbarStyle(),
            )
        }
    }
}

/** 任意原生命令的文本回退：等宽文本 + 复制全部（避免为 Redis 重做一套关系型网格）。 */
@Composable
private fun RedisTextFallback(result: QueryResult, onCopyText: (String, String) -> Unit) {
    val text = remember(result) { resultToText(result) }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                result.sql,
                fontSize = 12.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            CopyTextButton(text, "已复制结果", onCopyText)
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, bottom = 6.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colors.onSurface.copy(alpha = 0.04f))
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.9f),
            )
        }
    }
}

@Composable
private fun RedisCenteredHint(text: String, isError: Boolean = false) {
    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            fontSize = 12.sp,
            color = if (isError) MaterialTheme.colors.error
            else MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
        )
    }
}

private fun redisMetaText(result: QueryResult): String {
    val ms = "${result.durationMs} ms"
    return when {
        result.affectedRows != null -> "已执行 · $ms"
        result.rowCount == 0 -> "查询完成 · 0 行 · $ms"
        result.rowCount == 1 && result.columns.size == 1 -> "1 个值 · $ms"
        else -> "${result.columns.size} 列 × ${result.rowCount} 行 · $ms"
    }
}

private fun redisTtlLabel(seconds: Long?): String = when {
    seconds == null || seconds < 0 -> "永久"
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m"
    seconds < 86400 -> "${seconds / 3600}h"
    else -> "${seconds / 86400}d"
}

private fun parseJson(value: String): JsonElement? {
    val t = value.trim()
    if (!(t.startsWith("{") || t.startsWith("["))) return null
    return runCatching { Json.parseToJsonElement(t) }.getOrNull()
}

private fun resultToText(result: QueryResult): String = buildString {
    append(result.columns.joinToString(" | ") { it.name })
    append('\n')
    result.rows.forEach { row ->
        append(row.joinToString(" | ") { it ?: "(nil)" })
        append('\n')
    }
}
