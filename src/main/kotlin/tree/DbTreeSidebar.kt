package tree

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.i18n.t
import app.ui.DbIcons
import db.ConnectionProfile
import db.ConsoleRecord
import db.DbType
import db.FolderRow
import engine.Protocol
import engine.model.DbObjectMeta
import engine.model.ObjectKind
import engine.model.isPreviewable
import engine.model.nounKey
import i18n.I18n
import i18n.Str

/** 行上下文动作（闭包已绑定具体行）。 */
class RowActions(
    val onAddConnectionAt: () -> Unit = {},
    val onAddFolderAt: () -> Unit = {},
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
    /** CONNECTION 行：打开/激活该数据源的控制台（无控制台时直接建首个并打开）。 */
    val onOpenConsole: () -> Unit = {},
    /** CONNECTION 行：把搜索范围限定为该数据源（右键「在此数据源中搜索」）。 */
    val onSearchInProfile: () -> Unit = {},
    /** CONNECTION 行：该数据源的已有控制台（右键「打开控制台」级联子菜单用）。 */
    val consoles: List<ConsoleRecord> = emptyList(),
    /** 当前激活控制台 id（级联子菜单里标「✓」）。 */
    val activeConsoleId: String? = null,
    /** CONNECTION 行：激活某个已存在的控制台。 */
    val onOpenConsoleRecord: (ConsoleRecord) -> Unit = {},
    /** CONNECTION 行：为该数据源新建一个控制台。 */
    val onCreateConsole: () -> Unit = {},
    /** DB_OBJECT 行：在控制台里预览（SELECT 前 200 行）。 */
    val onPreviewTable: () -> Unit = {},
    /** DB_OBJECT 行（表/视图/物化视图）：浮窗查看对象定义 DDL。 */
    val onViewDdl: () -> Unit = {},
    /** FILTER 行：切换 DB（Redis）。 */
    val onSelectDb: (String) -> Unit = {},
    /** FILTER 行：切换 key 类型过滤（null = 全部）。 */
    val onSelectKeyType: (String?) -> Unit = {},
    /** FILTER 行：key pattern 变化（调用方防抖）。 */
    val onKeyPatternChange: (String) -> Unit = {},
    /** LOAD_MORE 行：「继续扫描」下一页。 */
    val onLoadMore: () -> Unit = {},
)

/** 每层缩进宽度（dp）与行首/行尾内边距。
 *  层级最深 4（文件夹→连接→库→组→对象），旧值 16dp 把对象名挤到右侧；12dp 更紧凑。 */
private const val INDENT_PER_DEPTH_DP = 12
private const val ROW_START_PAD_DP = 6

/** 树右侧滚动条样式：主题色半透明，深/浅色下都可见（与结果表格/查看器一致）。 */
@Composable
private fun treeScrollbarStyle(): ScrollbarStyle = ScrollbarStyle(
    minimalHeight = 24.dp,
    thickness = 8.dp,
    shape = RoundedCornerShape(4.dp),
    hoverDurationMillis = 300,
    unhoverColor = MaterialTheme.colors.onSurface.copy(alpha = 0.20f),
    hoverColor = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
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
    /** 左侧树搜索关键字（空 = 关闭搜索，按原展开态渲染）。 */
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    /** 搜索范围：null = 全部数据源；否则只看该 profile（数据源级）。 */
    searchScopeId: String? = null,
    /** 范围下拉项：null → 全部，其余为 (profileId, 显示名)。 */
    scopeOptions: List<Pair<String?, String>> = emptyList(),
    onSearchScopeChange: (String?) -> Unit = {},
    /** CONNECTION 行右键「在此数据源中搜索」：上层据此设定范围。 */
    onSearchInProfile: (ConnectionProfile) -> Unit = {},
    /** 展开/收起一行（FOLDER/CONNECTION/SCHEMA；连接未连接时上层据此发起连接）。 */
    onToggleExpand: (TreeRowInfo) -> Unit,
    onDisconnectConnection: (ConnectionProfile) -> Unit = {},
    onRefreshMetadata: (ConnectionProfile) -> Unit = {},
    onCopyName: (TreeRowInfo) -> Unit = {},
    onCopyQuery: (TreeRowInfo) -> Unit = {},
    onAddFolder: () -> Unit = {},
    /** 在某文件夹下新建子文件夹（null = 根级）。 */
    onAddFolderAt: (String?) -> Unit = {},
    onAddConnectionAt: (String?) -> Unit = {},
    onRenameFolder: (FolderRow) -> Unit = {},
    onDeleteFolder: (FolderRow) -> Unit = {},
    onEditConnection: (ConnectionProfile) -> Unit = {},
    onDeleteConnection: (ConnectionProfile) -> Unit = {},
    onRefresh: () -> Unit = {},
    /** 连接行：打开/激活该数据源控制台（无控制台时建首个）。 */
    onOpenConsoleForProfile: (ConnectionProfile) -> Unit = {},
    /** 某数据源（connection id）已有的控制台列表（右键「打开控制台」级联子菜单）。 */
    consolesForProfile: (String) -> List<ConsoleRecord> = { emptyList() },
    /** 当前激活控制台 id（级联子菜单里标 ✓）。 */
    activeConsoleId: String? = null,
    /** 连接行：激活指定的已有控制台。 */
    onOpenConsoleRecord: (ConsoleRecord) -> Unit = {},
    /** 连接行：为该数据源新建控制台。 */
    onCreateConsoleForProfile: (ConnectionProfile) -> Unit = {},
    /** DB_OBJECT（表/视图）：双击或菜单触发预览。 */
    onPreviewObject: (TreeRowInfo) -> Unit = {},
    /** DB_OBJECT（表/视图/物化视图）：浮窗查看定义 DDL（Ctrl+Q 同源）。 */
    onViewObjectDef: (TreeRowInfo) -> Unit = {},
    /** 数据源导出（不含密码，AES 口令加密）。 */
    onExportProfiles: () -> Unit = {},
    /** 数据源导出（含明文密码，AES 口令加密；调用方先弹口令框并提示风险）。 */
    onExportProfilesWithPasswords: () -> Unit = {},
    /** 数据源导入（解密 + 合并，不覆盖现有）。 */
    onImportProfiles: () -> Unit = {},
    /** 过滤条：切换 DB（Redis）。 */
    onSelectDb: (ConnectionProfile, String) -> Unit = { _, _ -> },
    /** 过滤条：切换 key 类型过滤（null = 全部）。 */
    onSelectKeyType: (ConnectionProfile, String?) -> Unit = { _, _ -> },
    /** 过滤条：key pattern 变化（调用方防抖后再扫）。 */
    onKeyPatternChange: (ConnectionProfile, String) -> Unit = { _, _ -> },
    /** 「继续扫描」：拉取下一页键（Redis `SCAN` 游标）。 */
    onLoadMoreObjects: (ConnectionProfile) -> Unit = {},
    /** 拖拽落下：应用移动/排序，返回是否成功。 */
    onApplyTreeDrop: (TreeDragPayload, TreeDropTarget) -> Boolean = { _, _ -> false },
    modifier: Modifier = Modifier,
) {
    val trimmedQuery = searchQuery.trim()
    val listState = rememberLazyListState()
    // 「在此数据源中搜索」后聚焦输入框（由右键动作递增触发）
    val searchFocus = remember { FocusRequester() }
    var focusSearchRequest by remember { mutableStateOf(0) }
    LaunchedEffect(focusSearchRequest) {
        if (focusSearchRequest > 0) runCatching { searchFocus.requestFocus() }
    }
    // 搜索自身命中的行（按展示顺序）；用于计数 / Enter 跳转 / 当前项高亮。
    val matchKeys = remember(rows, trimmedQuery) {
        if (trimmedQuery.isEmpty()) {
            emptyList()
        } else {
            rows.filter { treeRowSelfMatches(it, trimmedQuery) }.map { it.key }
        }
    }
    var activeMatch by remember(trimmedQuery) { mutableStateOf(0) }
    val activeIndex = if (matchKeys.isEmpty()) -1 else activeMatch.coerceIn(0, matchKeys.size - 1)
    val activeKey = matchKeys.getOrNull(activeIndex)
    // 只在「当前命中项」变化时滚动（不能以 rows/matchKeys 列表实例为 key：
    // 上层每次重组都会产出新列表，否则会反复触发滚动动画、抢走用户手动滚动）。
    LaunchedEffect(activeKey) {
        val key = activeKey ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it.key == key }
        if (index >= 0) listState.animateScrollToItem((index - 2).coerceAtLeast(0))
    }

    // ── 拖拽落点解析（同 api-x：注册行边界 → 按指针根坐标 + 上下半区解析） ──
    val dragEnabled = trimmedQuery.isEmpty()
    val dropRegistry = remember { DropZoneRegistry() }
    var treeDragPayload by remember { mutableStateOf<TreeDragPayload?>(null) }
    var treeDragPointerRoot by remember { mutableStateOf(Offset.Zero) }
    val hoveredDrop by remember {
        derivedStateOf {
            resolveDrop(treeDragPayload, dropRegistry.zones, treeDragPointerRoot)
        }
    }
    val onTreeDragStart: (TreeDragPayload, Offset) -> Unit = { payload, rootPos ->
        treeDragPayload = payload
        treeDragPointerRoot = rootPos
    }
    val onTreeDragMove: (Offset) -> Unit = { rootPos -> treeDragPointerRoot = rootPos }
    val onTreeDragEnd: () -> Unit = {
        val p = treeDragPayload
        val hit = resolveDrop(p, dropRegistry.zones, treeDragPointerRoot)
        treeDragPayload = null
        if (p != null && hit != null) {
            onApplyTreeDrop(p, hit.target)
        }
    }
    val dragActive = treeDragPayload != null && dragEnabled

    Column(modifier = modifier) {
        SidebarToolbar(
            onAddFolder = onAddFolder,
            onAddConnection = { onAddConnectionAt(null) },
            onRefresh = onRefresh,
            onExportProfiles = onExportProfiles,
            onExportProfilesWithPasswords = onExportProfilesWithPasswords,
            onImportProfiles = onImportProfiles,
        )
        if (rows.isNotEmpty() || trimmedQuery.isNotEmpty()) {
            TreeSearchBar(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                matchCount = matchKeys.size,
                activeIndex = activeIndex,
                scopeId = searchScopeId,
                scopeOptions = scopeOptions,
                onScopeChange = onSearchScopeChange,
                focusRequester = searchFocus,
                onNavigate = { delta ->
                    if (matchKeys.isNotEmpty()) {
                        val next = ((activeIndex + delta) % matchKeys.size + matchKeys.size) % matchKeys.size
                        activeMatch = next
                        onSelectRow(matchKeys[next])
                    }
                },
            )
        }
        if (rows.isEmpty()) {
            if (trimmedQuery.isNotEmpty()) NoMatchHint(trimmedQuery) else EmptyTreeHint(onAddFolder, { onAddConnectionAt(null) })
        } else {
            // 右侧挂常驻滚动条（上千张表必需）；列表预留出滚动条宽度，避免行内容/选中底色压到条下
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(end = 8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    userScrollEnabled = !dragActive,
                ) {
                    items(rows, key = { it.key }) { row ->
                        val dropIndicator = if (hoveredDrop?.rowKey == row.key) hoveredDrop!!.indicator else DropIndicator.None
                        val dropDesc: RowDropDesc? = when {
                            !dragEnabled -> null
                            row.kind == TreeRowKind.FOLDER -> RowDropDesc.FolderDesc(row.folderId ?: "", row.parentFolderId, row.folderIndex ?: 0)
                            row.kind == TreeRowKind.CONNECTION && row.profile != null -> RowDropDesc.ConnectionDesc(row.profile.id, row.folderId, row.connectionIndex ?: 0)
                            else -> null
                        }
                        val dragPayload: TreeDragPayload? = when {
                            !dragEnabled -> null
                            row.kind == TreeRowKind.FOLDER -> TreeDragPayload.Folder(row.folderId ?: "")
                            row.kind == TreeRowKind.CONNECTION && row.profile != null -> TreeDragPayload.Connection(row.profile.id, row.folderId)
                            else -> null
                        }
                        TreeRowView(
                            row = row,
                            selected = row.key == selectedKey,
                            activeMatch = activeKey != null && row.key == activeKey,
                            highlight = trimmedQuery,
                            canExpand = row.expandable && when (row.kind) {
                                TreeRowKind.FOLDER -> row.childCount > 0
                                TreeRowKind.CONNECTION -> row.connStatus == ConnUiStatus.CONNECTED
                                TreeRowKind.SCHEMA -> true
                                TreeRowKind.OBJECT_GROUP -> row.childCount > 0
                                TreeRowKind.KEY_NAMESPACE -> row.childCount > 0
                                else -> false
                            },
                            onSelect = { onSelectRow(row.key) },
                            onToggle = { onToggleExpand(row) },
                            dropIndicator = dropIndicator,
                            dropDesc = dropDesc,
                            dragPayload = dragPayload,
                            dropRegistry = dropRegistry,
                            onTreeDragStart = onTreeDragStart,
                            onTreeDragMove = onTreeDragMove,
                            onTreeDragEnd = onTreeDragEnd,
                            actions = RowActions(
                                onConnect = { onToggleExpand(row) },
                                onAddConnectionAt = { onAddConnectionAt(row.folderId) },
                                onAddFolderAt = { onAddFolderAt(row.folderId) },
                                onRenameFolder = { onRenameFolder(FolderRow(id = row.folderId ?: "", name = row.name)) },
                                onDeleteFolder = { onDeleteFolder(FolderRow(id = row.folderId ?: "", name = row.name)) },
                                onEditConnection = { row.profile?.let(onEditConnection) },
                                onDeleteConnection = { row.profile?.let(onDeleteConnection) },
                                onDisconnect = { row.profile?.let(onDisconnectConnection) },
                                onRefreshMetadata = { row.profile?.let(onRefreshMetadata) },
                                onCopyName = { onCopyName(row) },
                                onCopyQuery = { onCopyQuery(row) },
                                onOpenConsole = { row.profile?.let(onOpenConsoleForProfile) },
                                onSearchInProfile = {
                                    val p = row.profile
                                    if (p != null) {
                                        focusSearchRequest++
                                        onSearchInProfile(p)
                                    }
                                },
                                consoles = if (row.kind == TreeRowKind.CONNECTION) {
                                    row.profile?.let { consolesForProfile(it.id) }.orEmpty()
                                } else {
                                    emptyList()
                                },
                                activeConsoleId = activeConsoleId,
                                onOpenConsoleRecord = onOpenConsoleRecord,
                                onCreateConsole = { row.profile?.let(onCreateConsoleForProfile) },
                                onPreviewTable = { onPreviewObject(row) },
                                onViewDdl = { onViewObjectDef(row) },
                                onSelectDb = { db -> row.profile?.let { onSelectDb(it, db) } },
                                onSelectKeyType = { t -> row.profile?.let { onSelectKeyType(it, t) } },
                                onKeyPatternChange = { p -> row.profile?.let { onKeyPatternChange(it, p) } },
                                onLoadMore = { row.profile?.let(onLoadMoreObjects) },
                            ),
                        )
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    style = treeScrollbarStyle(),
                )
                val showRootDrop = dragActive &&
                    treeDragPayload is TreeDragPayload.Connection &&
                    (treeDragPayload as TreeDragPayload.Connection).fromFolderId != null
                if (showRootDrop) {
                    RootDropStrip(
                        dropIndicator = if (hoveredDrop?.rowKey == RowDropDesc.RootDesc.rowKey) hoveredDrop!!.indicator else DropIndicator.None,
                        dropRegistry = dropRegistry,
                    )
                }
            }
        }
    }
}

// ---------------- 树右键菜单（自绘单弹层，支持向右级联子菜单） ----------------

/** 树右键菜单项：普通动作 / 级联子菜单 / 分隔线。 */
sealed interface TreeMenuItem {
    class Action(
        val label: String,
        val enabled: Boolean = true,
        val onClick: () -> Unit,
    ) : TreeMenuItem

    class Submenu(val label: String, val items: List<TreeMenuItem>) : TreeMenuItem

    object Separator : TreeMenuItem
}

private val MENU_COL_W = 208.dp

/** 行右键菜单；空则不弹。 */
private fun rowMenu(row: TreeRowInfo, actions: RowActions): List<TreeMenuItem> =
    when (row.kind) {
        TreeRowKind.FOLDER -> listOf(
            TreeMenuItem.Action(I18n.t(Str.TreeMenuNewConnectionHere)) { actions.onAddConnectionAt() },
            TreeMenuItem.Action(I18n.t(Str.TreeMenuNewSubfolder)) { actions.onAddFolderAt() },
            TreeMenuItem.Action(I18n.t(Str.FolderRenameTitle)) { actions.onRenameFolder() },
            TreeMenuItem.Action(I18n.t(Str.ConfirmDeleteFolderTitle)) { actions.onDeleteFolder() },
        )
        TreeRowKind.CONNECTION -> {
            val items = buildList {
                when (row.connStatus) {
                    ConnUiStatus.DISCONNECTED -> add(TreeMenuItem.Action(I18n.t(Str.TreeMenuConnect)) { actions.onConnect() })
                    ConnUiStatus.CONNECTING -> add(TreeMenuItem.Action(I18n.t(Str.TreeMenuConnecting), enabled = false) {})
                    ConnUiStatus.ERROR -> add(TreeMenuItem.Action(I18n.t(Str.TreeMenuReconnect)) { actions.onConnect() })
                    ConnUiStatus.CONNECTED -> {
                        add(TreeMenuItem.Action(I18n.t(Str.TreeMenuDisconnect)) { actions.onDisconnect() })
                        add(TreeMenuItem.Action(I18n.t(Str.TreeMenuRefreshMetadata)) { actions.onRefreshMetadata() })
                    }
                    null -> {}
                }
            }
            items + listOf(
                consoleMenuItem(actions),
                TreeMenuItem.Action(I18n.t(Str.TreeMenuSearchInProfile)) { actions.onSearchInProfile() },
                TreeMenuItem.Action(I18n.t(Str.TreeMenuEditConnection)) { actions.onEditConnection() },
                TreeMenuItem.Action(I18n.t(Str.TreeMenuDeleteConnection)) { actions.onDeleteConnection() },
            )
        }
        TreeRowKind.DB_OBJECT -> {
            val obj = row.dbObject ?: return emptyList()
            val kind = obj.kind
            if (kind == ObjectKind.TRIGGER) {
                return listOf(TreeMenuItem.Action(I18n.t(Str.TreeMenuCopyTriggerName)) { actions.onCopyName() })
            }
            val isSql = (row.profile?.dbType?.protocol ?: Protocol.JDBC) == Protocol.JDBC
            val isEs = row.profile?.dbType?.protocol == Protocol.ELASTICSEARCH
            buildList {
                add(TreeMenuItem.Action(I18n.t(Str.TreeMenuCopyNounName, I18n.t(kind.nounKey))) { actions.onCopyName() })
                if (kind.isPreviewable()) {
                    add(
                        TreeMenuItem.Action(
                            when {
                                isSql -> I18n.t(Str.TreeMenuPreviewRows)
                                isEs -> I18n.t(Str.TreeMenuPreviewDsl)
                                else -> I18n.t(Str.TreeMenuViewKey)
                            },
                        ) { actions.onPreviewTable() },
                    )
                    add(
                        TreeMenuItem.Action(
                            when {
                                isSql -> I18n.t(Str.TreeMenuCopyPreviewQuery)
                                isEs -> I18n.t(Str.TreeMenuCopyDslQuery)
                                else -> I18n.t(Str.TreeMenuCopyViewCommand)
                            },
                        ) { actions.onCopyQuery() },
                    )
                    if (isSql) add(TreeMenuItem.Action(I18n.t(Str.TreeMenuViewDefinition)) { actions.onViewDdl() })
                    if (isEs) add(TreeMenuItem.Action(I18n.t(Str.TreeMenuViewMapping)) { actions.onViewDdl() })
                }
            }
        }
        else -> emptyList()
    }

/** 「打开控制台」：无控制台时直接建首个并打开；否则给出向右级联（控制台列表 + 新建）。 */
private fun consoleMenuItem(actions: RowActions): TreeMenuItem {
    if (actions.consoles.isEmpty()) {
        return TreeMenuItem.Action(I18n.t(Str.TreeMenuOpenConsole)) { actions.onOpenConsole() }
    }
    return TreeMenuItem.Submenu(
        I18n.t(Str.TreeMenuOpenConsole),
        buildList {
            actions.consoles.forEach { c ->
                val mark = if (c.id == actions.activeConsoleId) "  ✓" else ""
                add(TreeMenuItem.Action(c.name + mark) { actions.onOpenConsoleRecord(c) })
            }
            add(TreeMenuItem.Separator)
            add(TreeMenuItem.Action(I18n.t(Str.TreeMenuNewConsole)) { actions.onCreateConsole() })
        },
    )
}

/**
 * 自绘上下文菜单：单个 [Popup]，根列 + 向右展开的子列共用同一弹层。
 * 不用嵌套 DropdownMenu —— 避免子弹层夺焦时父弹层被 dismiss（无法实测验证的环境下更稳）。
 */
@Composable
private fun TreeContextMenu(
    items: List<TreeMenuItem>,
    clickOffset: Offset,
    onDismiss: () -> Unit,
) {
    var openSub by remember { mutableStateOf<Int?>(null) }
    val sub = openSub?.let { items.getOrNull(it) as? TreeMenuItem.Submenu }
    val lineColor = MaterialTheme.colors.onSurface.copy(alpha = 0.1f)
    Popup(
        onDismissRequest = onDismiss,
        popupPositionProvider = remember(clickOffset) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    // 菜单左上角落在点击点；越界时贴窗口边
                    val x = (anchorBounds.left + clickOffset.x.toInt())
                        .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                    val y = (anchorBounds.top + clickOffset.y.toInt())
                        .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
                    return IntOffset(x, y)
                }
            }
        },
        properties = PopupProperties(focusable = true),
    ) {
        Row(
            modifier = Modifier
                .shadow(8.dp, RoundedCornerShape(7.dp))
                .clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colors.surface)
                .border(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.22f), RoundedCornerShape(7.dp)),
        ) {
            MenuColumn(items, openSub, { openSub = it }, onDismiss)
            if (sub != null) {
                MenuColumn(
                    items = sub.items,
                    openSub = null,
                    onOpenSub = {},
                    onDismiss = onDismiss,
                    modifier = Modifier.drawBehind {
                        drawLine(lineColor, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 1f)
                    },
                )
            }
        }
    }
}

@Composable
private fun MenuColumn(
    items: List<TreeMenuItem>,
    openSub: Int?,
    onOpenSub: (Int?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.width(MENU_COL_W).padding(vertical = 4.dp)) {
        items.forEachIndexed { i, item ->
            when (item) {
                is TreeMenuItem.Action -> MenuRow(
                    label = item.label,
                    enabled = item.enabled,
                    arrow = false,
                    highlight = false,
                    onHover = { onOpenSub(null) },
                    onClick = {
                        onDismiss()
                        item.onClick()
                    },
                )
                is TreeMenuItem.Submenu -> MenuRow(
                    label = item.label,
                    enabled = true,
                    arrow = true,
                    highlight = openSub == i,
                    onHover = { onOpenSub(i) },
                    onClick = { onOpenSub(if (openSub == i) null else i) },
                )
                TreeMenuItem.Separator -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .height(1.dp)
                        .background(MaterialTheme.colors.onSurface.copy(alpha = 0.1f)),
                )
            }
        }
    }
}

@Composable
private fun MenuRow(
    label: String,
    enabled: Boolean,
    arrow: Boolean,
    highlight: Boolean,
    onHover: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (highlight) MaterialTheme.colors.primary.copy(alpha = 0.16f) else Color.Transparent)
            // hover 即展开子菜单（点击也能展开，兼容键盘/无 hover）
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type == PointerEventType.Enter) onHover()
                    }
                }
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            fontSize = 12.5.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = if (enabled) 0.85f else 0.35f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (arrow) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
private fun SidebarToolbar(
    onAddFolder: () -> Unit,
    onAddConnection: () -> Unit,
    onRefresh: () -> Unit,
    onExportProfiles: () -> Unit,
    onExportProfilesWithPasswords: () -> Unit,
    onImportProfiles: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(t(Str.TreeTitleConnectionManagement), style = MaterialTheme.typography.subtitle2, color = MaterialTheme.colors.onBackground)
        Spacer(Modifier.weight(1f))
        ToolPill(icon = { Icon(DbIcons.Folder, null, Modifier.size(13.dp)) }, label = t(Str.TreePillFolder), onClick = onAddFolder)
        Spacer(Modifier.width(2.dp))
        ToolPill(icon = { Icon(DbIcons.Database, null, Modifier.size(13.dp)) }, label = t(Str.TreeMenuConnect), onClick = onAddConnection)
        Spacer(Modifier.width(2.dp))
        ToolPill(icon = { Icon(Icons.Filled.Refresh, null, Modifier.size(13.dp)) }, label = null, onClick = onRefresh)
        Spacer(Modifier.width(2.dp))
        ArchiveMenu(
            onExportProfiles = onExportProfiles,
            onExportProfilesWithPasswords = onExportProfilesWithPasswords,
            onImportProfiles = onImportProfiles,
        )
    }
}

/** 连接档案导出/导入的溢出菜单（工具栏空间有限，收进“⋮”）。 */
@Composable
private fun ArchiveMenu(
    onExportProfiles: () -> Unit,
    onExportProfilesWithPasswords: () -> Unit,
    onImportProfiles: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToolPill(
            icon = { Icon(Icons.Filled.MoreVert, null, Modifier.size(14.dp)) },
            label = null,
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                onClick = { expanded = false; onExportProfiles() },
            ) { Text(t(Str.TreeMenuExportNoPassword), fontSize = 13.sp) }
            DropdownMenuItem(
                onClick = { expanded = false; onExportProfilesWithPasswords() },
            ) { Text(t(Str.TreeMenuExportWithPassword), fontSize = 13.sp) }
            DropdownMenuItem(
                onClick = { expanded = false; onImportProfiles() },
            ) { Text(t(Str.TreeMenuImport), fontSize = 13.sp) }
        }
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

/** 左侧树搜索框：范围（全部/某数据源）+ 过滤 + 高亮 + Enter/↑↓ 在命中项间跳转。 */
@Composable
private fun TreeSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    activeIndex: Int,
    scopeId: String?,
    scopeOptions: List<Pair<String?, String>>,
    onScopeChange: (String?) -> Unit,
    focusRequester: FocusRequester,
    onNavigate: (Int) -> Unit,
) {
    var scopeOpen by remember { mutableStateOf(false) }
    val scopeLabel = scopeOptions.firstOrNull { it.first == scopeId }?.second ?: t(Str.TreeSearchScopeAll)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 8.dp, top = 4.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colors.onSurface.copy(alpha = 0.06f))
            .padding(horizontal = 6.dp),
    ) {
        // 搜索范围：数据源级（右键连接行「在此数据源中搜索」也会设它）
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .widthIn(max = 96.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .clickable { scopeOpen = true }
                    .padding(horizontal = 2.dp, vertical = 4.dp),
            ) {
                Icon(
                    DbIcons.Database, null,
                    tint = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    scopeLabel,
                    fontSize = 11.sp,
                    color = if (scopeId == null) {
                        MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colors.primary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 3.dp).weight(1f, fill = false),
                )
                Icon(
                    Icons.Filled.KeyboardArrowDown, null,
                    tint = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.size(12.dp),
                )
            }
            DropdownMenu(expanded = scopeOpen, onDismissRequest = { scopeOpen = false }) {
                scopeOptions.forEach { (id, name) ->
                    DropdownMenuItem(onClick = { scopeOpen = false; onScopeChange(id) }) {
                        Text(
                            name,
                            fontSize = 12.sp,
                            color = if (id == scopeId) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(15.dp).background(MaterialTheme.colors.onSurface.copy(alpha = 0.12f)))
        Icon(
            Icons.Filled.Search, null,
            tint = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.size(13.dp),
        )
        Box(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
            if (query.isEmpty()) {
                Text(
                    t(Str.TreeSearchPlaceholder),
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.35f),
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colors.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colors.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .focusRequester(focusRequester)
                    // 单行 BasicTextField 会吞方向键/回车，必须 preview 拦截：回车/↓=下一项，Shift+回车/↑=上一项，Esc=清空
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.Enter, Key.NumPadEnter -> { onNavigate(if (e.isShiftPressed) -1 else 1); true }
                            Key.DirectionDown -> { onNavigate(1); true }
                            Key.DirectionUp -> { onNavigate(-1); true }
                            Key.Escape -> { onQueryChange(""); true }
                            else -> false
                        }
                    },
            )
        }
        if (query.isNotEmpty()) {
            Text(
                if (matchCount == 0) t(Str.TreeSearchNoMatch) else "${activeIndex + 1}/$matchCount",
                fontSize = 10.5.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                maxLines = 1,
                modifier = Modifier.padding(end = 2.dp),
            )
            Icon(
                Icons.Filled.Close, t(Str.TreeSearchClear),
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
                modifier = Modifier.size(13.dp).clickable { onQueryChange("") },
            )
        }
    }
}

/** 搜索无命中时的空态（区别于“还没有连接档案”）。 */
@Composable
private fun NoMatchHint(query: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Search, null,
            tint = MaterialTheme.colors.onSurface.copy(alpha = 0.3f),
            modifier = Modifier.size(26.dp),
        )
        Text(
            t(Str.TreeSearchNoObject, query),
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            t(Str.TreeSearchHint),
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.padding(top = 2.dp),
        )
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
            t(Str.TreeEmptyNoConnection),
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            t(Str.TreeEmptyHint),
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(modifier = Modifier.padding(top = 12.dp)) {
            ToolPill(icon = { Icon(DbIcons.Folder, null, Modifier.size(13.dp)) }, label = t(Str.FolderCreateTitle), onClick = onAddFolder)
            Spacer(Modifier.width(8.dp))
            ToolPill(icon = { Icon(Icons.Filled.Add, null, Modifier.size(13.dp)) }, label = t(Str.TreePillNewConnection), onClick = onAddConnection)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeRowView(
    row: TreeRowInfo,
    selected: Boolean,
    activeMatch: Boolean,
    highlight: String,
    canExpand: Boolean,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
    actions: RowActions,
    dropIndicator: DropIndicator = DropIndicator.None,
    dropDesc: RowDropDesc? = null,
    dragPayload: TreeDragPayload? = null,
    dropRegistry: DropZoneRegistry = remember { DropZoneRegistry() },
    onTreeDragStart: (TreeDragPayload, Offset) -> Unit = { _, _ -> },
    onTreeDragMove: (Offset) -> Unit = {},
    onTreeDragEnd: () -> Unit = {},
) {
    val doubleTapMs = LocalViewConfiguration.current.doubleTapTimeoutMillis
    var lastClickMs by remember { mutableStateOf(0L) }
    val menu = rowMenu(row, actions)
    // 组行可点击（单击选中、箭头/双击展开折叠）；占位符与过滤条不可整行点击（控件自己处理）
    val clickable = row.kind != TreeRowKind.PLACEHOLDER && row.kind != TreeRowKind.FILTER

    // 仅 FOLDER / CONNECTION 行参与拖拽：注册落点矩形 + 拖动手势
    val rowLc = remember(row.key) { LayoutCoordsHolder() }
    val desc = dropDesc
    val payload = dragPayload
    if (desc != null && payload != null) {
        DisposableEffect(desc.rowKey) {
            onDispose { dropRegistry.removeKey(desc.rowKey) }
        }
    }
    val zoneModifier = if (desc != null && payload != null) {
        Modifier.onGloballyPositioned { lc ->
            rowLc.coords = lc
            dropRegistry.sync(desc.rowKey, lc.boundsInRoot(), desc)
        }
    } else {
        Modifier
    }
    val gestureModifier = if (desc != null && payload != null) {
        Modifier.pointerInput(payload, desc) {
            detectDragGestures(
                onDragStart = { offset -> rowLc.coords?.localToRoot(offset)?.let { onTreeDragStart(payload, it) } },
                onDrag = { change, _ ->
                    change.consume()
                    rowLc.coords?.localToRoot(change.position)?.let(onTreeDragMove)
                },
                onDragEnd = { onTreeDragEnd() },
                onDragCancel = { onTreeDragEnd() },
            )
        }
    } else {
        Modifier
    }

    val baseModifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 4.dp, vertical = 1.dp)
        .clip(RoundedCornerShape(4.dp))
        .background(
            when {
                dropIndicator == DropIndicator.Into -> MaterialTheme.colors.primary.copy(alpha = 0.14f)
                activeMatch -> MaterialTheme.colors.primary.copy(alpha = 0.30f)
                selected -> MaterialTheme.colors.primary.copy(alpha = 0.16f)
                else -> Color.Transparent
            },
        )
        .clickable(enabled = clickable) {
            val now = System.currentTimeMillis()
            val double = lastClickMs != 0L && now - lastClickMs < doubleTapMs
            lastClickMs = if (double) 0L else now
            when {
                // 单击「继续扫描」：拉下一页键
                row.kind == TreeRowKind.LOAD_MORE -> actions.onLoadMore()
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
        .padding(start = (ROW_START_PAD_DP + row.depth * INDENT_PER_DEPTH_DP).dp, end = 6.dp)
        .padding(vertical = if (row.kind == TreeRowKind.OBJECT_GROUP) 1.dp else 3.dp)

    val content: @Composable () -> Unit = content@{
        if (row.kind == TreeRowKind.FILTER) {
            FilterBar(row, actions)
            return@content
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            when (row.kind) {
                TreeRowKind.FOLDER -> {
                    if (canExpand) ExpandArrow(row.expanded, onToggle) else Spacer(Modifier.width(16.dp))
                    Icon(
                        DbIcons.Folder, null,
                        tint = MaterialTheme.colors.primary.copy(alpha = 0.75f),
                        modifier = Modifier.size(15.dp),
                    )
                    RowName(row.name, Modifier.weight(1f), 13.sp, highlight = highlight)
                    if (!row.expanded && row.childCount > 0) CountBadge(row.childCount)
                }
                TreeRowKind.CONNECTION -> {
                    // 连接入口：未连接/失败时双击整行连接（箭头位仅占位保持对齐，无丑按钮）
                    if (canExpand) ExpandArrow(row.expanded, onToggle) else Spacer(Modifier.width(16.dp))
                    row.profile?.let { TypeBadge(it.dbType) }
                    RowName(row.name, Modifier.padding(start = 5.dp).weight(1f), 13.sp, highlight = highlight)
                    if (!row.expanded && row.childCount > 0) CountBadge(row.childCount)
                    // 搜索命中且未连接：结果来自本地缓存，标「缓存」+ 灰点，避免与实时结果混淆
                    val cachedHit = highlight.isNotEmpty() && row.connStatus != ConnUiStatus.CONNECTED
                    if (cachedHit) {
                        Text(
                            t(Str.TreeCache),
                            fontSize = 9.5.sp,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                    when (row.connStatus) {
                        ConnUiStatus.CONNECTING -> CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp)
                        ConnUiStatus.CONNECTED -> StatusDot(Color(0xFF43A047))
                        ConnUiStatus.ERROR -> StatusDot(Color(0xFFE53935))
                        ConnUiStatus.DISCONNECTED -> if (cachedHit) StatusDot(Color(0xFF9E9E9E))
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
                    RowName(row.name, Modifier.padding(start = 5.dp).weight(1f), 13.sp, highlight = highlight)
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
                TreeRowKind.KEY_NAMESPACE -> {
                    if (canExpand) ExpandArrow(row.expanded, onToggle) else Spacer(Modifier.width(16.dp))
                    Icon(
                        DbIcons.Folder, null,
                        tint = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.size(15.dp),
                    )
                    RowName(row.name, Modifier.padding(start = 5.dp).weight(1f), 13.sp, highlight = highlight)
                    if (!row.expanded && row.childCount > 0) CountBadge(row.childCount)
                }
                TreeRowKind.DB_OBJECT -> {
                    ObjectKindBadge(row.dbObject?.kind)
                    // 名称 + 「on <所属表>」（TRIGGER）放进同一个占满剩余宽度的内层 Row：
                    // 名称 fill=false 短则自然宽、长则吃到「on …」之前的全部空间。
                    // 旧写法是名称 weight(1f,fill=false) + 外层 Spacer(weight(1f))，两个加权子项
                    // 平分空间 → 名称只到一半就省略号、右半边被隐形 Spacer 占着（看着像被阴影挡住）。
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        RowName(row.name, Modifier.padding(start = 5.dp).weight(1f, fill = false), 12.5.sp, highlight = highlight)
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
                        keySuffix(row.dbObject)?.let {
                            Text(
                                it,
                                fontSize = 10.sp,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.38f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }
                TreeRowKind.LOAD_MORE -> {
                    Text(
                        "↻",
                        fontSize = 11.sp,
                        color = MaterialTheme.colors.primary.copy(alpha = 0.8f),
                        modifier = Modifier.padding(start = 3.dp),
                    )
                    Text(
                        row.name,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colors.primary.copy(alpha = 0.85f),
                        maxLines = 1,
                        modifier = Modifier.padding(start = 5.dp),
                    )
                }
                TreeRowKind.FILTER -> Unit // 由 content 开头的 early-return 渲染
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
        Box(modifier = zoneModifier.then(baseModifier).then(gestureModifier)) {
            content()
            DropBar(dropIndicator)
        }
    } else {
        // 右键菜单：记录点击点，在行内弹单层自绘菜单（支持「打开控制台」向右级联）
        var menuOpen by remember { mutableStateOf(false) }
        var menuAt by remember { mutableStateOf(Offset.Zero) }
        Box(
            modifier = zoneModifier
                .then(baseModifier)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            // Initial pass：先于同链上的 clickable（Main）拿到右键，避免被它消费
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed) {
                                menuAt = e.changes.firstOrNull()?.position ?: Offset.Zero
                                menuOpen = true
                                e.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
                .then(gestureModifier),
        ) {
            content()
            DropBar(dropIndicator)
            if (menuOpen) {
                TreeContextMenu(items = menu, clickOffset = menuAt, onDismiss = { menuOpen = false })
            }
        }
    }
}

/** 拖拽落点指示条：插入到行顶/行底。 */
@Composable
private fun BoxScope.DropBar(indicator: DropIndicator) {
    if (indicator == DropIndicator.InsertBefore || indicator == DropIndicator.InsertAfter) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .align(if (indicator == DropIndicator.InsertBefore) Alignment.TopCenter else Alignment.BottomCenter)
                .background(MaterialTheme.colors.primary.copy(alpha = 0.75f)),
        )
    }
}

/** 拖拽连接时的「移到根级」落点（列表底部常驻条，仅拖拽中显示）。 */
@Composable
private fun BoxScope.RootDropStrip(
    dropIndicator: DropIndicator,
    dropRegistry: DropZoneRegistry,
) {
    val lcHolder = remember { LayoutCoordsHolder() }
    val desc = RowDropDesc.RootDesc
    DisposableEffect(desc.rowKey) {
        onDispose { dropRegistry.removeKey(desc.rowKey) }
    }
    val highlighted = dropIndicator == DropIndicator.Into
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .onGloballyPositioned { lc ->
                lcHolder.coords = lc
                dropRegistry.sync(desc.rowKey, lc.boundsInRoot(), desc)
            }
            .clip(RoundedCornerShape(5.dp))
            .background(
                if (highlighted) MaterialTheme.colors.primary.copy(alpha = 0.16f)
                else MaterialTheme.colors.onSurface.copy(alpha = 0.08f),
            )
            .border(
                1.dp,
                if (highlighted) MaterialTheme.colors.primary.copy(alpha = 0.6f)
                else MaterialTheme.colors.onSurface.copy(alpha = 0.18f),
                RoundedCornerShape(5.dp),
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            t(Str.TreeMenuMoveToRoot),
            fontSize = 11.5.sp,
            color = if (highlighted) MaterialTheme.colors.primary
                else MaterialTheme.colors.onSurface.copy(alpha = 0.65f),
        )
    }
}

@Composable
private fun RowName(
    name: String,
    modifier: Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit,
    highlight: String = "",
) {
    val baseColor = MaterialTheme.colors.onSurface.copy(alpha = 0.92f)
    if (highlight.isBlank()) {
        Text(
            name,
            fontSize = fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = baseColor,
            modifier = modifier,
        )
        return
    }
    // 命中子串加底色（主题色低透明），深浅色下都可读；不改变其它字符色。
    val highlightColor = MaterialTheme.colors.primary.copy(alpha = 0.35f)
    val annotated = remember(name, highlight, highlightColor) {
        buildAnnotatedString {
            append(name)
            highlightRanges(name, highlight).forEach { range ->
                addStyle(
                    SpanStyle(background = highlightColor, fontWeight = FontWeight.Medium),
                    range.first,
                    range.last + 1,
                )
            }
        }
    }
    Text(
        annotated,
        fontSize = fontSize,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = baseColor,
        modifier = modifier,
    )
}

/** 大小写不敏感地找出 [query] 在 [text] 中的所有区间（可重叠前向推进）。 */
private fun highlightRanges(text: String, query: String): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    val out = mutableListOf<IntRange>()
    var from = 0
    while (from <= text.length - query.length) {
        val at = text.indexOf(query, from, ignoreCase = true)
        if (at < 0) break
        out += at until (at + query.length)
        from = at + query.length
    }
    return out
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
            contentDescription = if (expanded) t(Str.TreeCollapse) else t(Str.TreeExpand),
            tint = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.size(16.dp),
        )
    }
}

/** Redis key 行后缀：`类型 · TTL`（非 KEY 对象返回 null）。 */
private fun keySuffix(obj: DbObjectMeta?): String? {
    if (obj == null || obj.kind != ObjectKind.KEY) return null
    val type = obj.detail ?: "?"
    val ttl = obj.ttlSeconds?.let(::ttlLabel) ?: I18n.t(Str.TreeTtlForever)
    return "$type · $ttl"
}

private fun ttlLabel(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m"
    seconds < 86400 -> "${seconds / 3600}h"
    else -> "${seconds / 86400}d"
}

/** key 类型过滤选项（首项 = 全部）。getter 每次重算，语言切换后重新取值。 */
private val KEY_TYPE_OPTIONS: List<String>
    get() = listOf(I18n.t(Str.TreeSearchScopeAll), "string", "hash", "list", "set", "zset", "stream")

/** Redis 过滤条：DB 下拉 + 类型下拉 + key pattern 搜索（`SCAN MATCH`）。 */
@Composable
private fun FilterBar(row: TreeRowInfo, actions: RowActions) {
    val filter = row.filter ?: return
    val allLabel = t(Str.TreeSearchScopeAll)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterDropdown(label = "DB", value = filter.db, options = filter.dbOptions, onSelect = actions.onSelectDb)
            Spacer(Modifier.width(6.dp))
            FilterDropdown(
                label = t(Str.TreeKeyTypeLabel),
                value = filter.type ?: allLabel,
                options = KEY_TYPE_OPTIONS,
                onSelect = { actions.onSelectKeyType(if (it == allLabel) null else it) },
            )
        }
        // "*" 是内部“无过滤”表示，UI 上以空 + 占位提示呈现
        val display = if (filter.pattern == "*") "" else filter.pattern
        var text by remember(row.key, filter.db) { mutableStateOf(display) }
        LaunchedEffect(display) { if (text != display) text = display }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 3.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colors.onSurface.copy(alpha = 0.06f))
                .padding(horizontal = 6.dp),
        ) {
            Icon(
                Icons.Filled.Search, null,
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
                modifier = Modifier.size(13.dp),
            )
            BasicTextField(
                value = text,
                onValueChange = {
                    text = it
                    actions.onKeyPatternChange(it)
                },
                singleLine = true,
                textStyle = TextStyle(fontSize = 11.5.sp, color = MaterialTheme.colors.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colors.primary),
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 4.dp),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                t(Str.TreeKeyPatternPlaceholder),
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.35f),
                                maxLines = 1,
                            )
                        }
                        inner()
                    }
                },
            )
            if (text.isNotEmpty()) {
                Icon(
                    Icons.Filled.Close, t(Str.TreeClear),
                    tint = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
                    modifier = Modifier.size(13.dp).clickable {
                        text = ""
                        actions.onKeyPatternChange("")
                    },
                )
            }
        }
    }
}

/** 紧凑下拉（过滤条用）：左侧小标签 + 当前值 + 下箭头。 */
@Composable
private fun FilterDropdown(label: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colors.onSurface.copy(alpha = 0.06f))
                .clickable { open = true }
                .padding(start = 6.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        ) {
            Text(label, fontSize = 10.sp, color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f))
            Spacer(Modifier.width(3.dp))
            Text(value, fontSize = 11.5.sp, color = MaterialTheme.colors.onSurface, maxLines = 1)
            Icon(
                Icons.Filled.KeyboardArrowDown, null,
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.size(13.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { opt ->
                DropdownMenuItem(onClick = { open = false; onSelect(opt) }) {
                    Text(
                        opt,
                        fontSize = 12.sp,
                        color = if (opt == value) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface,
                    )
                }
            }
        }
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
        ObjectKind.KEY -> "K" to Color(0xFFD82C20)
        ObjectKind.INDEX -> "IX" to Color(0xFF00897B)
        ObjectKind.ALIAS -> "AL" to Color(0xFF8E24AA)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 17.dp, height = 13.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color),
    ) {
        Text(
            text,
            color = Color.White,
            fontSize = 7.5.sp,
            // 固定尺寸徽章必须显式给 lineHeight：否则继承 MaterialTheme body1 的 24sp 行高，
            // 行盒远高于 13dp 徽章，被 clip 后只剩字头（中下部被遮）。
            lineHeight = 9.sp,
            maxLines = 1,
        )
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
            // 同 ObjectKindBadge：固定 17dp 徽章显式压行高，避免继承 24sp 行高后垂直偏移。
            lineHeight = 10.sp,
            maxLines = 1,
        )
    }
}
