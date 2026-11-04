package app.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.RadioButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.i18n.t
import app.ui.FramelessDialog
import app.ui.ThemeSpec
import i18n.I18n
import i18n.Lang
import i18n.Str
import sync.ConflictChoice
import sync.ConflictItem
import sync.ConflictPreview
import sync.SyncEntityKind

/** 冲突项的显示名：实体类型 + 人类可读名（如「数据源 · prod-pg」）。 */
fun conflictLabel(item: ConflictItem, language: Lang): String {
    val kind = I18n.t(language, kindKey(item.kind))
    val name = when (val preview = item.remote) {
        is ConflictPreview.Connection -> preview.name
        is ConflictPreview.Named -> preview.label
        is ConflictPreview.Sql, is ConflictPreview.Unknown -> item.id.orEmpty()
    }.ifBlank { item.path.substringAfterLast('/') }
    return "$kind · $name"
}

private fun kindKey(kind: SyncEntityKind): Str = when (kind) {
    SyncEntityKind.CONNECTION -> Str.SyncKindConnection
    SyncEntityKind.FOLDER -> Str.SyncKindFolder
    SyncEntityKind.WORKSPACE -> Str.SyncKindWorkspace
    SyncEntityKind.CONSOLE_META -> Str.SyncKindConsoleMeta
    SyncEntityKind.CONSOLE_BODY -> Str.SyncKindConsoleBody
    SyncEntityKind.SETTINGS -> Str.SyncKindSettings
    SyncEntityKind.UNKNOWN -> Str.SyncKindUnknown
}

/**
 * 冲突某一侧的摘要行（渲染到 UI）。**永不含密码**——连接的预览只带 name/dbType/host/port/db/user。
 * 空字节（该侧已删除）与无法预览分别显示为专门文案。
 */
fun conflictPreviewLines(preview: ConflictPreview, language: Lang): List<String> = when (preview) {
    is ConflictPreview.Connection -> buildList {
        add("${preview.dbType} · ${preview.host}:${preview.port}")
        if (preview.database.isNotBlank()) add(preview.database)
        preview.user?.takeIf { it.isNotBlank() }?.let { add(it) }
    }

    is ConflictPreview.Sql -> listOf(I18n.t(language, Str.SyncConflictSqlLines, preview.lineCount)) +
        preview.head

    is ConflictPreview.Named -> listOf(preview.label)
    is ConflictPreview.Unknown -> listOf(
        if (preview.sizeBytes == 0) {
            I18n.t(language, Str.SyncConflictDeleted)
        } else {
            I18n.t(language, Str.SyncConflictNoPreview)
        },
    )
}

/** 每一项都已经选好一侧才允许确认。 */
fun allChosen(items: List<ConflictItem>, choices: Map<String, ConflictChoice>): Boolean =
    items.isNotEmpty() && items.all { it.path in choices }

/**
 * 冲突取舍弹窗：一次列出全部冲突，左右（本地/远端）预览，逐项选择保留哪一侧。
 * 「取消」= 放弃整次拉取（工作树回到拉取前，本地数据不变）。
 */
@Composable
fun SyncConflictDialog(
    items: List<ConflictItem>,
    theme: ThemeSpec,
    language: Lang,
    onConfirm: (Map<String, ConflictChoice>) -> Unit,
    onCancel: () -> Unit,
) {
    if (items.isEmpty()) return
    var selectedPath by remember(items) { mutableStateOf(items.first().path) }
    var choices by remember(items) { mutableStateOf<Map<String, ConflictChoice>>(emptyMap()) }
    val selected = items.firstOrNull { it.path == selectedPath } ?: items.first()
    val canConfirm = allChosen(items, choices)

    FramelessDialog(
        title = I18n.t(language, Str.SyncConflictTitle),
        width = 900.dp,
        height = 580.dp,
        colors = theme.colors,
        language = language,
        onCloseRequest = onCancel,
    ) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // 左：冲突项列表
            Column(
                modifier = Modifier
                    .width(280.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colors.surface)
                    .padding(vertical = 6.dp),
            ) {
                LazyColumn {
                    items(items, key = { it.path }) { item ->
                        val isSelected = item.path == selected.path
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isSelected) MaterialTheme.colors.primary.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent,
                                )
                                .clickable { selectedPath = item.path }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                conflictLabel(item, language),
                                style = MaterialTheme.typography.body2,
                                color = MaterialTheme.colors.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (item.path in choices) {
                                Text(
                                    if (choices[item.path] == ConflictChoice.LOCAL) "L" else "R",
                                    style = MaterialTheme.typography.caption,
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                }
            }
            Divider(
                modifier = Modifier.width(1.dp).fillMaxHeight(),
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.12f),
            )
            // 右：两侧预览 + 选择
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    conflictLabel(selected, language),
                    style = MaterialTheme.typography.subtitle2,
                    color = MaterialTheme.colors.onSurface,
                )
                PreviewSide(
                    title = t(Str.SyncConflictKeepLocal),
                    lines = conflictPreviewLines(selected.local, language),
                    selected = choices[selected.path] == ConflictChoice.LOCAL,
                    onSelect = { choices = choices + (selected.path to ConflictChoice.LOCAL) },
                )
                PreviewSide(
                    title = t(Str.SyncConflictKeepRemote),
                    lines = conflictPreviewLines(selected.remote, language),
                    selected = choices[selected.path] == ConflictChoice.REMOTE,
                    onSelect = { choices = choices + (selected.path to ConflictChoice.REMOTE) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { choices = items.associate { it.path to ConflictChoice.LOCAL } }) {
                        Text(t(Str.SyncConflictChooseAll) + " · " + t(Str.SyncConflictKeepLocal))
                    }
                    TextButton(onClick = { choices = items.associate { it.path to ConflictChoice.REMOTE } }) {
                        Text(t(Str.SyncConflictChooseAll) + " · " + t(Str.SyncConflictKeepRemote))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (!canConfirm) {
                Text(
                    t(Str.SyncConflictAllChosen),
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text(t(Str.CommonCancel)) }
            TextButton(enabled = canConfirm, onClick = { onConfirm(choices) }) {
                Text(t(Str.CommonOk))
            }
        }
    }
}

@Composable
private fun PreviewSide(
    title: String,
    lines: List<String>,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (selected) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(alpha = 0.2f),
                RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onSelect)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onSelect)
            Spacer(Modifier.width(6.dp))
            Text(
                title,
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurface,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(start = 40.dp),
        ) {
            Column {
                lines.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.caption,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.8f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
