package app.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.i18n.t
import app.state.ExternalMissingExitRequest
import app.state.PickProfileRequest
import db.ConnectionProfile
import i18n.Str
import tree.TypeBadge

/** 批量打开外部文件时选择数据源（记忆优先；仅未关联的走这里）。 */
@Composable
fun PickProfileDialog(request: PickProfileRequest, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(request.defaultProfileId ?: request.profiles.firstOrNull()?.id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(Str.ExternalPickSourceTitle)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    t(Str.ExternalPickSourceHint),
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                )
                Text(
                    request.names.joinToString("，"),
                    fontSize = 11.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
                request.profiles.forEach { p ->
                    ProfileRow(p, p.id == selected) { selected = p.id }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != null,
                onClick = { selected?.let(request.onSubmit) },
            ) { Text(t(Str.ExternalPickSourceConfirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t(Str.CommonCancel)) }
        },
    )
}

@Composable
private fun ProfileRow(profile: ConnectionProfile, active: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) MaterialTheme.colors.primary.copy(alpha = 0.15f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        TypeBadge(profile.dbType)
        Text(
            profile.name,
            fontSize = 13.sp,
            color = MaterialTheme.colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** 退出时处理「外部文件已丢失且脏」。 */
@Composable
fun ExternalMissingExitDialog(request: ExternalMissingExitRequest, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(Str.ExternalExitTitle)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    t(Str.ExternalExitMessage, request.consoles.size),
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.8f),
                )
                request.consoles.forEach {
                    Text(
                        "• ${it.filePath}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.End) {
                TextButton(onClick = request.onCancel) { Text(t(Str.ExternalExitCancel)) }
                TextButton(onClick = request.onDiscard) { Text(t(Str.ExternalExitDiscard)) }
                TextButton(onClick = request.onSaveAsEach) { Text(t(Str.ExternalExitSaveAsEach)) }
                TextButton(onClick = request.onRebuildAll) { Text(t(Str.ExternalExitRebuildAll)) }
            }
        },
    )
}
