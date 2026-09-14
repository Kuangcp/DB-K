package app.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ContentAlpha
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.settings.Keymap
import app.settings.ShortcutCommand
import app.settings.ShortcutScope
import app.ui.KeyCaptureEffect

/** 冲突警示用的语义红（与全局状态点同源）。 */
private val ConflictRed = Color(0xFFE53935)

/**
 * 设置窗口「快捷键」分区：只列**扩展业务功能**（[ShortcutCommand.configurable]）的命令，
 * 按作用域分组；基础编辑键（保存 / 复制粘贴 / 方向导航 / 补全）固定、不在此处。
 *
 * 点组合键胶囊进入录制态（[KeyCaptureEffect]，AWT 级捕获）；每行「重置」恢复单条默认；
 * 底部「全部恢复默认」；同作用域冲突红色提示但不阻断保存。
 */
@Composable
fun ShortcutSettingsSection(
    keymap: Keymap,
    onKeymapChange: (Keymap) -> Unit,
) {
    var recording by remember { mutableStateOf<ShortcutCommand?>(null) }
    val conflicts = keymap.conflicts()
    val conflicted = remember(conflicts) {
        buildSet {
            conflicts.forEach {
                add(it.first)
                add(it.second)
            }
        }
    }

    KeyCaptureEffect(
        active = recording != null,
        onChord = { chord ->
            recording?.let { onKeymapChange(keymap.withChord(it, chord)) }
            recording = null
        },
        onCancel = { recording = null },
        onClear = {
            recording?.let { onKeymapChange(keymap.unbind(it)) }
            recording = null
        },
    )

    Text(
        "业务功能快捷键",
        style = MaterialTheme.typography.subtitle2,
        color = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium),
    )
    Text(
        "只有扩展业务功能可自定义；保存、复制粘贴、方向导航等基础编辑键固定不可改。" +
            "点组合键胶囊即可录制新键（需含 Ctrl/Alt，或使用 F1–F12）。",
        style = MaterialTheme.typography.body2,
        color = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium),
    )

    for (scope in ShortcutScope.entries) {
        val commands = ShortcutCommand.configurableCommands.filter { it.scope == scope }
        if (commands.isEmpty()) continue
        Text(
            scope.label,
            style = MaterialTheme.typography.subtitle2,
            color = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium),
            modifier = Modifier.padding(top = 4.dp),
        )
        commands.forEach { command ->
            ShortcutRow(
                command = command,
                keymap = keymap,
                recording = recording,
                conflicted = command in conflicted,
                onStartRecording = { recording = command },
                onReset = { onKeymapChange(keymap.resetCommand(command)) },
            )
        }
    }

    conflicts.forEach { conflict ->
        Text(
            "⚠「${conflict.first.label}」与「${conflict.second.label}」冲突：${conflict.chord.format()}",
            style = MaterialTheme.typography.body2,
            color = ConflictRed,
        )
    }

    TextButton(
        onClick = {
            recording = null
            onKeymapChange(keymap.resetAll())
        },
    ) {
        Text("全部恢复默认")
    }

    recording?.let { command ->
        Text(
            "正在录制「${command.label}」：按下新的组合键；Esc 取消，Delete / Backspace 清除绑定。",
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.primary,
        )
    }
}

@Composable
private fun ShortcutRow(
    command: ShortcutCommand,
    keymap: Keymap,
    recording: ShortcutCommand?,
    conflicted: Boolean,
    onStartRecording: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            command.label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface,
        )
        if (recording == command) {
            ChordChip(label = "按下组合键…", active = true, warning = false, onClick = {})
        } else {
            val chords = keymap.chordsOf(command)
            if (chords.isEmpty()) {
                ChordChip(label = "未绑定", active = false, warning = conflicted, onClick = onStartRecording)
            } else {
                chords.forEach { chord ->
                    ChordChip(
                        label = chord.format(),
                        active = false,
                        warning = conflicted,
                        onClick = onStartRecording,
                    )
                }
            }
        }
        TextButton(onClick = onReset, enabled = !keymap.isDefault(command)) {
            Text("重置", fontSize = 12.sp)
        }
    }
}

@Composable
private fun ChordChip(
    label: String,
    active: Boolean,
    warning: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = when {
        warning -> ConflictRed
        active -> MaterialTheme.colors.primary
        else -> MaterialTheme.colors.onSurface.copy(alpha = 0.25f)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colors.surface)
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = if (warning) ConflictRed else MaterialTheme.colors.onSurface,
        )
    }
}
