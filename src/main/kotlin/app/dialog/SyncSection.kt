package app.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.i18n.t
import app.state.SyncState
import app.sync.SyncSettings
import i18n.Str
import sync.SyncFailure
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 同步状态文本（key + 参数），UI 层用 `t(key, *args)` 渲染。 */
data class SyncStatusText(val key: Str, val args: List<Any> = emptyList())

/** 「云同步」分区的动作回调（Main 用 suspend 服务实现它们）。 */
data class SyncActions(
    val onSaveSettings: (SyncSettings) -> Unit,
    val onSubmitPassphrase: (String) -> Unit,
    val onClearPassphrase: () -> Unit,
    /** 每个动作都携带表单当前值：先保存配置再执行动作，避免「改了 URL 马上点拉取」用到旧配置。 */
    val onChangePassphrase: (settings: SyncSettings, old: String, new: String) -> Unit,
    val onClone: (settings: SyncSettings, passphrase: String) -> Unit,
    val onPull: (settings: SyncSettings) -> Unit,
    val onPush: (settings: SyncSettings) -> Unit,
)

/**
 * 本地目录展示：位于 dataDir 内时显示相对名（默认 `sync`），否则显示原样路径。
 * 让用户一眼看出「同步仓库就在应用数据目录里」。
 */
fun relativeLocalDirLabel(dir: String, dataDir: Path): String {
    val raw = dir.trim()
    if (raw.isEmpty()) return "sync"
    val path = runCatching { Paths.get(raw) }.getOrNull() ?: return raw
    if (!path.isAbsolute) return raw
    val base = dataDir.toAbsolutePath().normalize()
    val target = path.toAbsolutePath().normalize()
    return if (target.startsWith(base)) base.relativize(target).toString() else target.toString()
}

/** 时间戳 → 本地时区 `yyyy-MM-dd HH:mm`。 */
fun formatSyncTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .format(Instant.ofEpochMilli(epochMillis).atZone(zone))

/**
 * 最近一次同步结果的展示文本；还没同步过 → null（UI 不显示结果行）。
 * 失败分类 → 对应错误 key；成功 → 完成文案。
 */
fun syncStatusText(state: SyncState): SyncStatusText? {
    val result = state.lastResult ?: return null
    result.failure?.let { failure ->
        return when (failure) {
            SyncFailure.NOT_CONFIGURED -> SyncStatusText(Str.SyncErrorNotConfigured)
            SyncFailure.GIT_MISSING -> SyncStatusText(Str.SyncErrorGitMissing)
            SyncFailure.WRONG_PASSPHRASE -> SyncStatusText(Str.SyncErrorWrongPassphrase)
            SyncFailure.PUSH_REJECTED -> SyncStatusText(Str.SyncErrorPushRejected)
            SyncFailure.EMPTY_LOCAL_GUARD -> SyncStatusText(Str.SyncErrorEmptyLocalGuard)
            SyncFailure.GIT_FAILED -> SyncStatusText(Str.SyncErrorGitFailed, listOf(result.detail.orEmpty()))
            SyncFailure.IO_FAILED -> SyncStatusText(Str.SyncErrorIoFailed, listOf(result.detail.orEmpty()))
        }
    }
    if (result.detail == "conflicts") return SyncStatusText(Str.SyncResultConflicts)
    return SyncStatusText(Str.SyncResultOk)
}

/** 设置窗口「云同步」分区。 */
@Composable
fun SyncPanel(state: SyncState, actions: SyncActions) {
    val dataDir = remember { db.AppPaths.dataDirectory() }
    var remoteUrl by remember { mutableStateOf(state.settings.remoteUrl) }
    var localDir by remember { mutableStateOf(state.settings.localDir) }
    var branch by remember { mutableStateOf(state.settings.branch) }
    var autoPushAfterPull by remember { mutableStateOf(state.settings.autoPushAfterPull) }
    var autoPushOnExit by remember { mutableStateOf(state.settings.autoPushOnExit) }
    var passphraseDialog by remember { mutableStateOf<PassphraseDialogMode?>(null) }
    var clonePending by remember { mutableStateOf(false) }

    // 外部（拉取/换口令）更新了配置时同步到表单，避免显示旧值。
    LaunchedEffect(state.settings) {
        remoteUrl = state.settings.remoteUrl
        localDir = state.settings.localDir
        branch = state.settings.branch
        autoPushAfterPull = state.settings.autoPushAfterPull
        autoPushOnExit = state.settings.autoPushOnExit
    }

    fun formSettings(): SyncSettings = state.settings.copy(
        remoteUrl = remoteUrl.trim(),
        localDir = localDir.trim().ifEmpty { "sync" },
        branch = branch.trim().ifEmpty { "main" },
        autoPushAfterPull = autoPushAfterPull,
        autoPushOnExit = autoPushOnExit,
    )

    fun saveForm() {
        actions.onSaveSettings(formSettings())
    }

    val busy = state.running
    val canPull = !busy && state.gitAvailable && state.configured && state.passphraseSet

    Text(
        t(Str.SettingsSectionSync),
        style = MaterialTheme.typography.subtitle2,
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
    )

    SyncTextField(
        value = remoteUrl,
        onValueChange = { remoteUrl = it },
        label = t(Str.SyncRemoteLabel),
        enabled = !busy,
        onCommit = ::saveForm,
    )
    Text(
        t(Str.SyncRemoteHint),
        style = MaterialTheme.typography.caption,
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SyncTextField(
            value = localDir,
            onValueChange = { localDir = it },
            label = t(Str.SyncLocalDirLabel),
            enabled = !busy,
            modifier = Modifier.weight(2f),
            onCommit = ::saveForm,
            trailingHint = relativeLocalDirLabel(localDir, dataDir),
        )
        SyncTextField(
            value = branch,
            onValueChange = { branch = it },
            label = t(Str.SyncBranchLabel),
            enabled = !busy,
            modifier = Modifier.weight(1f),
            onCommit = ::saveForm,
        )
    }

    // 口令状态 + 操作
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${t(Str.SyncPassphraseLabel)}：",
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface,
        )
        Text(
            t(if (state.passphraseSet) Str.SyncPassphraseSet else Str.SyncPassphraseUnset),
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.weight(1f))
        TextButton(
            enabled = !busy && state.configured,
            onClick = { passphraseDialog = PassphraseDialogMode.Enter },
        ) { Text(t(Str.SyncPassphrasePromptTitle)) }
        TextButton(
            enabled = !busy && state.passphraseSet,
            onClick = { passphraseDialog = PassphraseDialogMode.Change },
        ) { Text(t(Str.SyncPassphraseChange)) }
        TextButton(enabled = !busy && state.passphraseSet, onClick = actions.onClearPassphrase) {
            Text(t(Str.SyncPassphraseClear))
        }
    }
    Text(
        t(Str.SyncPassphraseStoredLocally),
        style = MaterialTheme.typography.caption,
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButtonLike(
            label = t(Str.SyncCloneAndImport),
            enabled = !busy && state.configured,
            onClick = {
                clonePending = true
                passphraseDialog = PassphraseDialogMode.Enter
            },
        )
        OutlinedButtonLike(label = t(Str.SyncPull), enabled = canPull, onClick = { actions.onPull(formSettings()) })
        OutlinedButtonLike(label = t(Str.SyncPush), enabled = canPull, onClick = { actions.onPush(formSettings()) })
        if (busy) {
            Text(
                t(Str.SyncRunning),
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
            )
        }
    }

    if (!state.gitAvailable) {
        Text(
            t(Str.SyncErrorGitMissing),
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface,
        )
    }
    if (!state.configured) {
        Text(
            t(Str.SyncNotConfigured),
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        ToggleRow(t(Str.SyncAutoPushAfterPull), autoPushAfterPull, enabled = !busy) {
            autoPushAfterPull = it
            saveForm()
        }
        ToggleRow(t(Str.SyncAutoPushOnExit), autoPushOnExit, enabled = !busy) {
            autoPushOnExit = it
            saveForm()
        }
    }

    val lastAt = state.settings.lastSyncAtMs
    Text(
        if (lastAt > 0) t(Str.SyncLastAt, formatSyncTime(lastAt)) else t(Str.SyncNever),
        style = MaterialTheme.typography.caption,
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
    )
    syncStatusText(state)?.let { status ->
        Text(
            t(status.key, *status.args.toTypedArray()),
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface,
        )
    }
    (state.lastResult?.skippedConsoles ?: 0).takeIf { it > 0 }?.let { skipped ->
        Text(
            t(Str.SyncResultSkipped, skipped),
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
        )
    }
    Text(
        t(Str.SyncHintCiphertext),
        style = MaterialTheme.typography.caption,
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
    )

    passphraseDialog?.let { mode ->
        SyncPassphraseDialog(
            mode = mode,
            onSubmit = { old, new ->
                passphraseDialog = null
                when {
                    mode == PassphraseDialogMode.Change -> actions.onChangePassphrase(formSettings(), old, new)
                    clonePending -> {
                        clonePending = false
                        actions.onClone(formSettings(), new)
                    }
                    else -> actions.onSubmitPassphrase(new)
                }
            },
            onDismiss = {
                passphraseDialog = null
                clonePending = false
            },
        )
    }
}

private enum class PassphraseDialogMode { Enter, Change }

/**
 * 单行口令输入（Enter 提交；Esc/取消关闭）。
 * 用 AlertDialog 是因为内容固定且不滚动（AGENTS「组件选型」允许）。
 */
@Composable
private fun SyncPassphraseDialog(
    mode: PassphraseDialogMode,
    onSubmit: (old: String, new: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val ok = if (mode == PassphraseDialogMode.Change) next.isNotEmpty() else next.isNotEmpty()
    val confirm = { if (ok) onSubmit(current, next) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                t(
                    if (mode == PassphraseDialogMode.Change) Str.SyncPassphraseChangeTitle
                    else Str.SyncPassphrasePromptTitle,
                ),
            )
        },
        text = {
            Column {
                Text(
                    t(Str.SyncPassphrasePrompt),
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                )
                if (mode == PassphraseDialogMode.Change) {
                    Text(
                        t(Str.SyncReencryptHint),
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (mode == PassphraseDialogMode.Change) {
                    OutlinedTextField(
                        value = current,
                        onValueChange = { current = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent(submitOnEnter(current.isNotEmpty() && next.isNotEmpty(), confirm)),
                        label = { Text(t(Str.SyncPassphraseOld)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = syncFieldColors(),
                    )
                }
                OutlinedTextField(
                    value = next,
                    onValueChange = { next = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .onPreviewKeyEvent(submitOnEnter(ok, confirm)),
                    label = {
                        Text(
                            t(
                                if (mode == PassphraseDialogMode.Change) Str.SyncPassphraseNew
                                else Str.SyncPassphraseLabel,
                            ),
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = syncFieldColors(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = confirm) { Text(t(Str.CommonOk)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(Str.CommonCancel)) } },
    )
}

@Composable
private fun SyncTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onCommit: () -> Unit,
    trailingHint: String? = null,
) {
    val focusManager = LocalFocusManager.current
    var hadFocus by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { st ->
                    if (hadFocus && !st.isFocused) onCommit()
                    hadFocus = st.isFocused
                }
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                        onCommit()
                        focusManager.clearFocus()
                        true
                    } else {
                        false
                    }
                },
            label = { Text(label) },
            singleLine = true,
            enabled = enabled,
            colors = TextFieldDefaults.outlinedTextFieldColors(
                textColor = MaterialTheme.colors.onSurface,
                cursorColor = MaterialTheme.colors.primary,
                focusedBorderColor = MaterialTheme.colors.primary,
                unfocusedBorderColor = MaterialTheme.colors.onSurface.copy(alpha = 0.25f),
                focusedLabelColor = MaterialTheme.colors.primary,
                unfocusedLabelColor = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
            ),
        )
        if (trailingHint != null) {
            Text(
                trailingHint,
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface,
        )
    }
}

/** 与设置窗口其它按钮一致：描边文本按钮（主题色，避免 Material Button 的默认底色）。 */
@Composable
private fun OutlinedButtonLike(label: String, enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled) 0.7f else 0.35f
    Row(
        modifier = Modifier
            .background(Color.Transparent)
            .border(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface.copy(alpha = alpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 口令框统一配色（主题色，深浅色都可读）。 */
@Composable
private fun syncFieldColors() = TextFieldDefaults.outlinedTextFieldColors(
    textColor = MaterialTheme.colors.onSurface,
    cursorColor = MaterialTheme.colors.primary,
    focusedBorderColor = MaterialTheme.colors.primary,
    unfocusedBorderColor = MaterialTheme.colors.onSurface.copy(alpha = 0.25f),
    focusedLabelColor = MaterialTheme.colors.primary,
    unfocusedLabelColor = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
)
