package tree

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ui.DbIcons
import db.ConnectionProfile
import db.DbType
import db.FolderRow
import jdbc.model.ObjectKind
import jdbc.model.displayNoun
import jdbc.model.isPreviewable

/** 行上下文动作（闭包已绑定具体行）。 */
class RowActions(
    val onAddConnectionAt: () -> Unit = {},
    val onRenameFolder: () -> Unit = {},
    val onDeleteFolder: () -> Unit = {},
    val onEditConnection: () -> Unit = {},
    val onDeleteConnection: () -> Unit = {},
    /** CONNECTION 行（未连接/失败）：连接该数据源。 */
    val onConnect: () -> Unit = {},
    val onDisconnect: () -> Unit = {},
    /** CONNECTION 行（已连接）：重取该数据源目录元数据并刷新磁盘缓存。 */
    val onRefreshMetadata: () -> Unit = {},
    val onCopyName: () -> Unit = {},
    val onCopyQuery: () -> Unit = {},
    /** CONNECTION 行：打开/激活该数据源的控制台。 */
    val onOpenConsole: () -> Unit = {},
    /** DB_OBJECT 行：在控制台里预览（SELECT 前 200 行）。 */
    val onPreviewTable: () -> Unit = {},
)

/**
 * 左侧树：文件夹 → 连接 → 库(schema) → 对象组 → 表/视图/触发器。
 * 扁平化渲染：每行一个 [TreeRowInfo]，缩进按 depth。
 *
 * 交互约定：单击 = 选中；连接行未连接/连接失败时**双击 = 连接**（上层负责连接与懒加载库列表，
 * 连接后自动展开）；已连接后双击 = 展开/收起；可展开行双击同箭头走 onToggleExpand。
 * 右键按行类型给菜单（连接行右键菜单里的“连接/重新连接”是双击的兜底入口）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DbTreeSidebar(
    rows: List<TreeRowInfo>,
    selectedKey: String?,
    onSelectRow: (String?) -> Unit,
    /** 展开/收起一行（FOLDER/CONNECTION/SCHEMA；连接未连接时上层据此发起连接）。 */
    onToggleExpand: (TreeRowInfo) -> Unit,
    onDisconnectConnection: (ConnectionProfile) -> Unit = {},
    onRefreshMetadata: (ConnectionProfile) -> Unit = {},
    onCopyName: (TreeRowInfo) -> Unit = {},
    onCopyQuery: (TreeRowInfo) -> Unit = {},
    onAddFolder: () -> Unit = {},
    onAddConnectionAt: (String?) -> Unit = {},
    onRenameFolder: (FolderRow) -> Unit = {},
    onDeleteFolder: (FolderRow) -> Unit = {},
    onEditConnection: (ConnectionProfile) -> Unit = {},
    onDeleteConnection: (ConnectionProfile) -> Unit = {},
    onRefresh: () -> Unit = {},
    /** 连接行：打开/激活该数据源控制台。 */
    onOpenConsoleForProfile: (ConnectionProfile) -> Unit = {},
    /** DB_OBJECT（表/视图）：双击或菜单触发预览。 */
    onPreviewObject: (TreeRowInfo) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SidebarToolbar(onAddFolder, { onAddConnectionAt(null) }, onRefresh)
        if (rows.isEmpty()) {
            EmptyTreeHint(onAddFolder, { onAddConnectionAt(null) })
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(rows, key = { it.key }) { row ->
                    TreeRowView(
                        row = row,
                        selected = row.key == selectedKey,
                        canExpand = when (row.kind) {
                            TreeRowKind.FOLDER -> row.childCount > 0
                            TreeRowKind.CONNECTION -> row.connStatus == ConnUiStatus.CONNECTED
                            TreeRowKind.SCHEMA -> true
                            TreeRowKind.OBJECT_GROUP -> row.childCount > 0
                            else -> false
                        },
                        onSelect = { onSelectRow(row.key) },
                        onToggle = { onToggleExpand(row) },
                        actions = RowActions(
                            onConnect = { onToggleExpand(row) },
                            onAddConnectionAt = { onAddConnectionAt(row.folderId) },
                            onRenameFolder = { onRenameFolder(FolderRow(id = row.folderId ?: "", name = row.name)) },
                            onDeleteFolder = { onDeleteFolder(FolderRow(id = row.folderId ?: "", name = row.name)) },
                            onEditConnection = { row.profile?.let(onEditConnection) },
                            onDeleteConnection = { row.profile?.let(onDeleteConnection) },
                            onDisconnect = { row.profile?.let(onDisconnectConnection) },
                            onRefreshMetadata = { row.profile?.let(onRefreshMetadata) },
                            onCopyName = { onCopyName(row) },
                            onCopyQuery = { onCopyQuery(row) },
                            onOpenConsole = { row.profile?.let(onOpenConsoleForProfile) },
                            onPreviewTable = { onPreviewObject(row) },
                        ),
                    )
                }
            }
        }
    }
}

/** 行右键菜单；空则不弹。 */@Composable
private fun rowMenu(row: TreeRowInfo, actions: RowActions): List<ContextMenuItem> =
    when (row.kind) {
        TreeRowKind.FOLDER -> listOf(
            ContextMenuItem("在此新建连接") { actions.onAddConnectionAt() },
            ContextMenuItem("重命名文件夹") { actions.onRenameFolder() },
            ContextMenuItem("删除文件夹") { actions.onDeleteFolder() },
        )
        TreeRowKind.CONNECTION -> {
            val items = buildList {
                when (row.connStatus) {
                    ConnUiStatus.DISCONNECTED -> add(ContextMenuItem("连接") { actions.onConnect() })
                    ConnUiStatus.CONNECTING -> add(ContextMenuItem("连接中…", enabled = false) {})
                    ConnUiStatus.ERROR -> add(ContextMenuItem("重新连接") { actions.onConnect() })
                    ConnUiStatus.CONNECTED -> {
                        add(ContextMenuItem("断开连接") { actions.onDisconnect() })
                        add(ContextMenuItem("刷新元数据缓存") { actions.onRefreshMetadata() })
                    }
                    null -> {}
                }
            }
            items + listOf(
                ContextMenuItem("打开控制台") { actions.onOpenConsole() },
                ContextMenuItem("编辑连接") { actions.onEditConnection() },
                ContextMenuItem("删除连接档案") { actions.onDeleteConnection() },
            )
        }
        TreeRowKind.DB_OBJECT -> {
            val obj = row.dbObject ?: return emptyList()
            val kind = obj.kind
            if (kind == ObjectKind.TRIGGER) {
                return listOf(ContextMenuItem("复制触发器名") { actions.onCopyName() })
            }
            buildList {
                add(ContextMenuItem("复制${kind.displayNoun}名") { actions.onCopyName() })
                if (kind.isPreviewable()) {
                    add(ContextMenuItem("预览（前 100 行）") { actions.onPreviewTable() })
                    add(ContextMenuItem("复制查询（SELECT 预览）") { actions.onCopyQuery() })
                }
            }
        }
        else -> emptyList()
    }

@Composable
private fun SidebarToolbar(
    onAddFolder: () -> Unit,
    onAddConnection: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("连接管理", style = MaterialTheme.typography.subtitle2, color = MaterialTheme.colors.onBackground)
        Spacer(Modifier.weight(1f))
        ToolPill(icon = { Icon(DbIcons.Folder, null, Modifier.size(13.dp)) }, label = "文件夹", onClick = onAddFolder)
        Spacer(Modifier.width(2.dp))
        ToolPill(icon = { Icon(DbIcons.Database, null, Modifier.size(13.dp)) }, label = "连接", onClick = onAddConnection)
        Spacer(Modifier.width(2.dp))
        ToolPill(icon = { Icon(Icons.Filled.Refresh, null, Modifier.size(13.dp)) }, label = null, onClick = onRefresh)
    }
}

@Composable
private fun ToolPill(
    icon: @Composable () -> Unit,
    label: String?,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        icon()
        if (label != null) {
            Text(
                label,
                fontSize = 11.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.65f),
                modifier = Modifier.padding(start = 3.dp),
            )
        }
    }
}

@Composable
private fun EmptyTreeHint(onAddFolder: () -> Unit, onAddConnection: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(DbIcons.Database, null, tint = MaterialTheme.colors.onSurface.copy(alpha = 0.3f), modifier = Modifier.size(32.dp))
        Text(
            "还没有连接档案",
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "先新建文件夹分组，再添加数据库连接",
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(modifier = Modifier.padding(top = 12.dp)) {
            ToolPill(icon = { Icon(DbIcons.Folder, null, Modifier.size(13.dp)) }, label = "新建文件夹", onClick = onAddFolder)
            Spacer(Modifier.width(8.dp))
            ToolPill(icon = { Icon(Icons.Filled.Add, null, Modifier.size(13.dp)) }, label = "新建连接", onClick = onAddConnection)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeRowView(
    row: TreeRowInfo,
    selected: Boolean,
    canExpand: Boolean,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
    actions: RowActions,
) {
    val doubleTapMs = LocalViewConfiguration.current.doubleTapTimeoutMillis
    var lastClickMs by remember { mutableStateOf(0L) }
    val menu = rowMenu(row, actions)
    // 组行可点击（单击选中、箭头/双击展开折叠），仅占位符行不可交互
    val clickable = row.kind != TreeRowKind.PLACEHOLDER

    val baseModifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 4.dp, vertical = 1.dp)
        .clip(RoundedCornerShape(4.dp))
        .background(if (selected) MaterialTheme.colors.primary.copy(alpha = 0.16f) else Color.Transparent)
        .clickable(enabled = clickable) {
            val now = System.currentTimeMillis()
            val double = lastClickMs != 0L && now - lastClickMs < doubleTapMs
            lastClickMs = if (double) 0L else now
            when {
                // 双击连接行：未连接/失败 → 连接（CONNECTING 忽略，避免重复触发）
                double && row.kind == TreeRowKind.CONNECTION && row.connStatus != ConnUiStatus.CONNECTED ->
                    if (row.connStatus == ConnUiStatus.CONNECTING) onSelect() else onToggle()
                // 双击表/视图/物化视图 → 预览；双击可展开行 → 展开/收起；其余对象无预览语义
                double && row.kind == TreeRowKind.DB_OBJECT && row.dbObject?.kind?.isPreviewable() == true ->
                    actions.onPreviewTable()
                double && canExpand -> onToggle()
                else -> onSelect()
            }
        }
        .padding(start = 6.dp + (row.depth * 16).dp, end = 6.dp)
        .padding(vertical = if (row.kind == TreeRowKind.OBJECT_GROUP) 1.dp else 3.dp)

    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            when (row.kind) {
                TreeRowKind.FOLDER -> {
                    if (canExpand) ExpandArrow(row.expanded, onToggle) else Spacer(Modifier.width(16.dp))
                    Icon(
                        DbIcons.Folder, null,
                        tint = MaterialTheme.colors.primary.copy(alpha = 0.75f),
                        modifier = Modifier.size(15.dp),
                    )
                    RowName(row.name, Modifier.weight(1f), 13.sp)
                    if (!row.expanded && row.childCount > 0) CountBadge(row.childCount)
                }
                TreeRowKind.CONNECTION -> {
                    // 连接入口：未连接/失败时双击整行连接（箭头位仅占位保持对齐，无丑按钮）
                    if (canExpand) ExpandArrow(row.expanded, onToggle) else Spacer(Modifier.width(16.dp))
                    row.profile?.let { TypeBadge(it.dbType) }
                    RowName(row.name, Modifier.padding(start = 5.dp).weight(1f), 13.sp)
                    if (!row.expanded && row.childCount > 0) CountBadge(row.childCount)
                    when (row.connStatus) {
                        ConnUiStatus.CONNECTING -> CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp)
                        ConnUiStatus.CONNECTED -> StatusDot(Color(0xFF43A047))
                        ConnUiStatus.ERROR -> StatusDot(Color(0xFFE53935))
                        else -> {}
                    }
                }
                TreeRowKind.SCHEMA -> {
                    if (canExpand) ExpandArrow(row.expanded, onToggle) else Spacer(Modifier.width(16.dp))
                    Icon(
                        DbIcons.Database, null,
                        tint = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.size(14.dp),
                    )
                    RowName(row.name, Modifier.padding(start = 5.dp).weight(1f), 13.sp)
                    if (!row.expanded && row.childCount > 0) CountBadge(row.childCount)
                }
                TreeRowKind.OBJECT_GROUP -> {
                    if (canExpand) ExpandArrow(row.expanded, onToggle) else Spacer(Modifier.width(16.dp))
                    Text(
                        "${row.name} (${row.childCount})",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.62f),
                        letterSpacing = 0.4.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 2.dp),
                    )
                }
                TreeRowKind.DB_OBJECT -> {
                    ObjectKindBadge(row.dbObject?.kind)
                    RowName(row.name, Modifier.padding(start = 5.dp).weight(1f, fill = false), 12.5.sp)
                    row.dbObject?.tableName?.let {
                        Text(
                            "on $it",
                            fontSize = 10.sp,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.38f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
                TreeRowKind.PLACEHOLDER -> {
                    when (row.placeholderKind) {
                        PlaceholderKind.LOADING -> CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp)
                        PlaceholderKind.ERROR -> Text("⚠", fontSize = 10.sp, color = Color(0xFFE53935))
                        else -> Spacer(Modifier.size(11.dp))
                    }
                    Text(
                        row.name,
                        fontSize = 11.sp,
                        color = when (row.placeholderKind) {
                            PlaceholderKind.ERROR -> Color(0xFFE53935)
                            PlaceholderKind.INFO -> MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                            else -> MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                        },
                        maxLines = 3,
                        modifier = Modifier.padding(start = 6.dp).weight(1f),
                    )
                }
            }
        }
    }

    if (menu.isEmpty()) {
        Box(modifier = baseModifier) { content() }
    } else {
        ContextMenuArea(items = { menu }) { Box(modifier = baseModifier) { content() } }
    }
}

@Composable
private fun RowName(name: String, modifier: Modifier, fontSize: androidx.compose.ui.unit.TextUnit) {
    Text(
        name,
        fontSize = fontSize,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.92f),
        modifier = modifier,
    )
}

@Composable
private fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .padding(start = 6.dp)
            .size(7.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color),
    )
}

@Composable
private fun CountBadge(count: Int) {
    Text(
        count.toString(),
        fontSize = 9.5.sp,
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.42f),
        modifier = Modifier.padding(start = 6.dp),
    )
}

@Composable
private fun ExpandArrow(expanded: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .size(width = 16.dp, height = 18.dp)
            .clip(RoundedCornerShape(3.dp))
            .clickable(onClick = onClick),
    ) {
        Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = if (expanded) "收起" else "展开",
            tint = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.size(16.dp),
        )
    }
}

/** 对象类型色徽章（表=蓝 T / 视图=青 V / 物化视图=紫 MV / 触发器=橙 TR / 序列=绿 SQ …）。 */
@Composable
fun ObjectKindBadge(kind: ObjectKind?) {
    if (kind == null) return
    val (text, color) = when (kind) {
        ObjectKind.TABLE -> "T" to Color(0xFF5586E4)
        ObjectKind.VIEW -> "V" to Color(0xFF26A69A)
        ObjectKind.MATERIALIZED_VIEW -> "MV" to Color(0xFF9575CD)
        ObjectKind.TRIGGER -> "TR" to Color(0xFFEF8A28)
        ObjectKind.SEQUENCE -> "SQ" to Color(0xFF43A047)
        ObjectKind.ROUTINE -> "FN" to Color(0xFF26C6DA)
        ObjectKind.AGGREGATE -> "AG" to Color(0xFFEC407A)
        ObjectKind.OPERATOR -> "OP" to Color(0xFF8D6E63)
        ObjectKind.TYPE -> "TY" to Color(0xFF5C6BC0)
        ObjectKind.OPERATOR_CLASS -> "OC" to Color(0xFF78909C)
        ObjectKind.OPERATOR_FAMILY -> "OF" to Color(0xFFD84315)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 17.dp, height = 13.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color),
    ) {
        Text(text, color = Color.White, fontSize = 7.5.sp, maxLines = 1)
    }
}

/** 数据库类型徽章：底色圆角方块 + 白字短名。 */
@Composable
fun TypeBadge(dbType: DbType) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(17.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color(dbType.badgeColor)),
    ) {
        Text(
            dbType.badge,
            color = Color.White,
            fontSize = 8.sp,
            maxLines = 1,
        )
    }
}
