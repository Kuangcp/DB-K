package app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.state.ConsoleRunUi
import app.state.StatementOutcome
import app.i18n.t
import engine.model.QueryResult
import i18n.I18n
import i18n.Str

/**
 * 结果区顶部工具条（28dp）：左侧多语句结果 Tab，右侧状态文案 + 纯图标动作。
 * 无结果（也未执行）时调用方不渲染此条，中间只剩 5dp 可拖细线。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ResultToolbar(
    run: ConsoleRunUi,
    transposed: Boolean,
    exportEnabled: Boolean,
    enabled: Boolean,
    onSelectOutcome: (Int) -> Unit,
    onToggleTranspose: () -> Unit,
    onCommitEdits: () -> Unit,
    onClearAllEdits: () -> Unit,
    onRefreshResult: () -> Unit,
    canFetchMore: Boolean,
    onFetchMore: () -> Unit,
    editCount: Int,
    canCommit: Boolean,
    resultBusy: Boolean,
    canModifyRows: Boolean,
    selectedRow: Int?,
    selectedRowDeleted: Boolean,
    onInsertRow: () -> Unit,
    onToggleRowDelete: () -> Unit,
    onExport: () -> Unit,
    onCancelRun: () -> Unit,
) {
    val result = run.result
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 4.dp),
    ) {
        // 左侧：多语句 Tab（单语句不占位，状态文案已能表达结果）
        if (run.outcomes.size > 1) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            ) {
                run.outcomes.forEachIndexed { i, outcome ->
                    ResultChip(i, outcome, active = i == run.activeIndex, onClick = onSelectOutcome)
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        // 状态：执行中 / 单语句状态点 + 列×行·耗时（错误详情在结果区居中红字，这里只给状态点）
        val statusDot = singleResultStatus(run)
        if (run.executing) {
            CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp)
            Text(t(Str.EditorRunning), fontSize = 11.sp, color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f))
        } else if (run.error == null && result != null) {
            if (statusDot != null) {
                ResultStatusDot(statusDot)
                Spacer(Modifier.width(5.dp))
            }
            Text(
                if (editCount > 0) "${metaText(result, transposed)} · ${t(Str.ResultUncommitted, editCount)}"
                else metaText(result, transposed),
                fontSize = 11.sp,
                color = if (editCount > 0) MaterialTheme.colors.primary
                else MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                maxLines = 1,
            )
        } else if (statusDot == false) {
            // 单语句失败：只给红点（结果区已展示错误详情）
            ResultStatusDot(false)
        }
        Spacer(Modifier.width(4.dp))
        // 动作：纯图标 + 悬停 tooltip
        if (run.executing) {
            ResultIconButton(
                icon = DbIcons.Stop,
                description = t(Str.ResultCancel),
                enabled = enabled,
                danger = true,
                onClick = onCancelRun,
            )
        } else {
            // 结果编辑：撤销全部 / 提交（有未提交修改时高亮 + 徽标）/ 刷新当前 Tab
            if (editCount > 0) {
                ResultIconButton(
                    icon = DbIcons.Undo,
                    description = t(Str.ResultUndoAll, editCount),
                    enabled = !resultBusy,
                    onClick = onClearAllEdits,
                )
            }
            ResultIconButton(
                icon = DbIcons.Commit,
                description = if (editCount > 0) t(Str.ResultCommit, editCount) else t(Str.ResultCommitPlain),
                enabled = canCommit && editCount > 0 && !resultBusy,
                active = editCount > 0,
                badgeCount = editCount.takeIf { it > 0 },
                onClick = onCommitEdits,
            )
            ResultIconButton(
                icon = DbIcons.Refresh,
                description = t(Str.ResultRefresh),
                enabled = result != null && !resultBusy,
                onClick = onRefreshResult,
            )
            // 行级写操作（N3）：插入行 / 标记删除选中行（无主键/视图/转置时禁用）
            if (canModifyRows) {
                ResultIconButton(
                    icon = DbIcons.AddRow,
                    description = t(Str.ResultInsertRow),
                    enabled = !resultBusy,
                    onClick = onInsertRow,
                )
                ResultIconButton(
                    icon = DbIcons.DeleteRow,
                    description = when {
                        selectedRow == null -> t(Str.ResultDeleteRowNeedsSelection)
                        selectedRowDeleted -> t(Str.ResultUndoDeleteRow)
                        else -> t(Str.ResultMarkDeleteRow)
                    },
                    enabled = !resultBusy && selectedRow != null,
                    active = selectedRowDeleted,
                    onClick = onToggleRowDelete,
                )
            }
            if (canTranspose(run)) {
                ResultIconButton(
                    icon = DbIcons.Transpose,
                    description = if (transposed) t(Str.ResultRestoreTranspose) else t(Str.ResultTranspose),
                    active = transposed,
                    onClick = onToggleTranspose,
                )
            }
            if (canFetchMore) {
                ResultIconButton(
                    icon = DbIcons.FetchMore,
                    description = t(Str.ResultFetchMore),
                    enabled = !resultBusy,
                    active = true,
                    onClick = onFetchMore,
                )
            }
            ResultIconButton(
                icon = DbIcons.Download,
                description = t(Str.ResultExport),
                enabled = exportEnabled,
                onClick = onExport,
            )
        }
    }
}

/**
 * 单语句结果状态点：true=成功绿、false=失败红、null=不显示。
 * 仅单语句、非执行中、有结果或错误时显示；多语句由左侧芯片自带状态点，不重复。
 */
internal fun singleResultStatus(run: ConsoleRunUi): Boolean? {
    if (run.executing) return null
    if (run.outcomes.size > 1) return null
    run.error?.let { return false }
    return if (run.result != null) true else null
}

/** 单语句状态点：6dp 圆，语义色成功绿 / 失败红（只做色点，不承载文字）。 */
@Composable
private fun ResultStatusDot(ok: Boolean) {
    Box(Modifier.size(6.dp).clip(CircleShape).background(if (ok) Color(0xFF43A047) else Color(0xFFE53935)))
}

/** 多语句结果切换芯片：状态点 + 结果序号 + 行数/失败标记。 */
@Composable
private fun ResultChip(index: Int, outcome: StatementOutcome, active: Boolean, onClick: (Int) -> Unit) {
    // 语义色：成功绿 / 失败红（仅作状态点，不承载文字）
    val dot = if (outcome.ok) Color(0xFF43A047) else Color(0xFFE53935)
    val suffix = when {
        !outcome.ok -> " ✕"
        outcome.isQuery -> t(Str.ResultRowSuffix, outcome.result?.rowCount ?: 0)
        else -> t(Str.ResultRowSuffix, outcome.result?.affectedRows ?: 0)
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
        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(5.dp))
        Text(
            t(Str.ResultTabLabel, index + 1),
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
        )
        Text(suffix, fontSize = 10.5.sp, color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f))
    }
}

/** 结果工具条图标按钮：24dp 热区、15dp 图标，悬停 500ms 显示 tooltip。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    active: Boolean = false,
    danger: Boolean = false,
    badgeCount: Int? = null,
) {
    val tint = when {
        !enabled -> MaterialTheme.colors.onSurface.copy(alpha = 0.25f)
        danger -> MaterialTheme.colors.error
        active -> MaterialTheme.colors.primary
        else -> MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
    }
    TooltipArea(
        tooltip = {
            Box(
                modifier = Modifier
                    .shadow(4.dp, RoundedCornerShape(5.dp))
                    .clip(RoundedCornerShape(5.dp))
                    .background(MaterialTheme.colors.surface)
                    .border(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.15f), RoundedCornerShape(5.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(description, fontSize = 11.sp, color = MaterialTheme.colors.onSurface)
            }
        },
        delayMillis = 500,
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(24.dp)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(15.dp))
                if (badgeCount != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(13.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colors.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (badgeCount > 99) "99+" else "$badgeCount",
                            fontSize = 8.sp,
                            color = MaterialTheme.colors.onPrimary,
                        )
                    }
                }
            }
        }
    }
}

private fun metaText(result: QueryResult, transposed: Boolean): String {
    val ms = "${result.durationMs} ms"
    return when {
        result.affectedRows != null -> I18n.t(Str.ResultUpdated, result.affectedRows, ms)
        result.rowCount == 0 -> I18n.t(Str.ResultQueryZero, ms)
        transposed -> {
            // 转置视图：C 列 → C 行，外加一列“列名”标签列
            I18n.t(Str.ResultTransposed, result.columns.size, result.rowCount + 1, ms)
        }
        else -> {
            val truncated = if (result.truncated) I18n.t(Str.ResultTruncatedMark) else ""
            I18n.t(Str.ResultColsRows, result.columns.size, result.rowCount, truncated, ms)
        }
    }
}
