package app.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import org.tinylog.Logger

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.state.ConsoleRunUi
import com.neoutils.highlight.compose.remember.rememberHighlight
import com.neoutils.highlight.compose.remember.rememberTextFieldValue
import com.neoutils.highlight.core.extension.textColor
import db.ConsoleRecord
import db.ConnectionProfile
import db.SqlHistoryRow
import jdbc.QueryExecutor
import jdbc.QueryResult
import jdbc.model.SchemaMeta
import tree.ConnUiStatus
import tree.TypeBadge

/** 每个单元格最窄 64dp / 最宽 320dp（字符数 → dp 估算，12sp monospace 约 0.6em/字符）。 */
private fun estWidth(chars: Int): Int = (chars * 7 + 20).coerceIn(64, 320)

/**
 * 右侧 SQL 工作台（控制台主导）。
 * 结构：Header →（当前控制台的数据源导航行 + 执行目标切换）→ 跨数据源控制台标签条
 * → 编辑器（当前控制台缓冲区）→ 执行条 → 结果区。未激活控制台时显示引导区。
 * 每个 tab 就是一个控制台（可来自不同数据源，各绑定自己的 .sql 文件）；纯展示组件，编排在 Main/ConsoleState。
 */
@Composable
fun SqlWorkspace(
    profile: ConnectionProfile?,
    status: ConnUiStatus,
    statusMessage: String?,
    profiles: List<ConnectionProfile>,
    onSelectProfile: (String) -> Unit,
    consoles: List<ConsoleRecord>,
    activeConsole: ConsoleRecord?,
    onSelectConsole: (ConsoleRecord) -> Unit,
    /** 在指定数据源下新建控制台（数据源 id）。 */
    onCreateConsoleAt: (String) -> Unit,
    onRenameConsole: (ConsoleRecord) -> Unit,
    onDeleteConsole: (ConsoleRecord) -> Unit,
    /** 未落盘改动控制台 id 集合（标签 ●）。 */
    dirtyConsoleIds: Set<String>,
    /** 激活控制台数据源的库/schema 列表（目标切换菜单；null = 未连接/未加载）。 */
    schemas: List<SchemaMeta>?,
    /** 数据源方言是否支持切换执行目标（SQLite 单文件不支持）。 */
    supportsTargetSwitch: Boolean,
    /** 激活控制台已选执行目标库/schema（"" = 连接默认）。 */
    targetSchema: String,
    onSelectTarget: (String) -> Unit,
    editorText: String,
    editorDirty: Boolean,
    onTextChange: (String) -> Unit,
    /** Ctrl+S 主动保存（异步写盘；Main 取当前控制台）。 */
    onSaveNow: () -> Unit = {},
    run: ConsoleRunUi,
    /** 执行请求：参数为编辑器当前选中片段（去首尾空白）；null = 无有效选中。 */
    onRun: (String?) -> Unit,
    onClear: () -> Unit,
    onExportCsv: () -> Unit,
    /** 取消当前执行（取消按钮 / Esc）。 */
    onCancelRun: () -> Unit,
    /** 全量导出：结果被截断时重新执行 SQL 导出全部行。 */
    onExportAllCsv: () -> Unit,
    /** 执行历史（当前数据源）。 */
    history: List<SqlHistoryRow>,
    onRefreshHistory: () -> Unit,
    onClearHistory: () -> Unit,
    onFillHistory: (String) -> Unit,
    /** 编辑器补全用数据源对象名（表/视图，已加载）。 */
    completionIdentifiers: List<String>,
    /** 复制文本（单元格 / INSERT 语句）→ 剪贴板 + Toast。参数：文本、Toast 文案。 */
    onCopyText: (String, String) -> Unit,
    onDisconnect: () -> Unit,
    /** 结果区显隐（Alt+D 由 Main 窗口根层统一接管，这里只读它布局）。 */
    resultsVisible: Boolean,
    onToggleResults: () -> Unit,
    isDark: Boolean,
    onToggleTheme: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showHistory by remember { mutableStateOf(false) }
    // 打开面板或切换数据源时刷新历史列表
    LaunchedEffect(showHistory, profile?.id) {
        if (showHistory) onRefreshHistory()
    }
    // 转置视图：仅展示层翻转；新一次执行 / 切换控制台时复位为原布局
    var transposed by remember { mutableStateOf(false) }
    LaunchedEffect(run.executing) {
        if (run.executing) transposed = false
    }
    LaunchedEffect(activeConsole?.id) {
        transposed = false
    }
    // —— 编辑区/结果区分隔 ——
    // 编辑区与结果区按比例分配剩余高度（resultFrac 归结果区）；拖动中部窄分隔条实时改比例
    // （像素差 / 内容区可用高换算，窗口缩放不改变已设比例）。
    var resultFrac by remember { mutableStateOf(0.5f) }
    // 内容区总高度（px，拖动换算用）
    var paneH by remember { mutableStateOf(0) }
    // 结果区显隐由 Main 窗口根层（Alt+D）控制；新执行结果到达时自动重新显示，
    // 避免隐藏状态下“跑完看不到结果”
    LaunchedEffect(run.result) {
        if (run.result != null && !resultsVisible) onToggleResults()
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // Ctrl+S：主动保存当前控制台（取消防抖，立即异步落盘）
                if (e.isCtrlPressed && e.key == Key.S) {
                    onSaveNow()
                    return@onPreviewKeyEvent true
                }
                // Ctrl+T 行列转制（仅在有可转置结果时消费，避免与其它用途冲突）
                if (e.isCtrlPressed && e.key == Key.T && canTranspose(run)) {
                    transposed = !transposed
                    return@onPreviewKeyEvent true
                }
                // Esc 取消执行（无论焦点在编辑器还是别处，预览阶段优先拦截）
                if (e.key == Key.Escape && run.executing) {
                    onCancelRun()
                    true
                } else {
                    false
                }
            },
    ) {
        HeaderBar(
            isDark = isDark,
            onToggleTheme = onToggleTheme,
            showHistoryButton = profile != null && activeConsole != null,
            historyOpen = showHistory,
            onToggleHistory = { showHistory = !showHistory },
        )
        Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.08f))
        val profilesById = remember(profiles) { profiles.associateBy { it.id } }
        if (profiles.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "还没有数据源。请在左侧新建连接档案，单击连接即可创建控制台开始写 SQL。",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            return
        }
        // 控制台主导：标签条固定显示全部数据源的控制台；已激活时上方展示该控制台绑定的数据源 + 执行目标。
        if (profile != null) {
            ConnectionNavBar(
                profile = profile,
                status = status,
                statusMessage = statusMessage,
                profiles = profiles,
                onSelectProfile = onSelectProfile,
                onDisconnect = onDisconnect,
                supportsTargetSwitch = supportsTargetSwitch,
                schemas = schemas,
                target = targetSchema,
                onSelectTarget = onSelectTarget,
            )
        }
        ConsoleTabBar(
            consoles = consoles,
            activeConsole = activeConsole,
            dirtyConsoleIds = dirtyConsoleIds,
            profilesById = profilesById,
            onSelectConsole = onSelectConsole,
            onCreateConsoleAt = onCreateConsoleAt,
            onRenameConsole = onRenameConsole,
            onDeleteConsole = onDeleteConsole,
        )
        Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.08f))
        if (activeConsole == null) {
            StarterPane(
                profiles = profiles,
                consoles = consoles,
                onCreateConsoleAt = onCreateConsoleAt,
            )
            return
        }
        // 编辑器状态：文本由外部权威（切换控制台/预览/清空），选区是本地瞬态
        var tfv by remember { mutableStateOf(TextFieldValue(editorText)) }
        LaunchedEffect(editorText) {
            if (tfv.text != editorText) tfv = TextFieldValue(editorText, TextRange(editorText.length))
        }
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onSizeChanged { paneH = it.height },
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 上半块：编辑器（吃满剩余高）+ 执行条；下半块：分隔条 + 结果区。
                    // 两块按 resultFrac 比例分剩余高，分隔条拖动即改比例。
                    Column(
                        modifier = Modifier
                            .weight(if (resultsVisible) 1f - resultFrac else 1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        EditorPane(
                            value = tfv,
                            dirty = editorDirty,
                            onValueChange = { v ->
                                val textChanged = v.text != tfv.text
                                tfv = v
                                // BasicTextField 在纯鼠标点击/光标移动时也会以新选区上报 onValueChange，
                                // 内容没变就不置脏、不触发自动保存（否则点一下编辑器就变成“未保存”）。
                                if (textChanged) onTextChange(v.text)
                            },
                            onCtrlEnter = { selectedSqlOf(tfv)?.let(onRun) },
                            completionIdentifiers = completionIdentifiers,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                        ExecBar(
                            executing = run.executing,
                            result = run.result,
                            error = run.error,
                            transposed = transposed,
                            onToggleTranspose = { transposed = !transposed },
                            onRun = { onRun(selectedSqlOf(tfv)) },
                            onCancel = onCancelRun,
                            onClear = onClear,
                            onExportCsv = onExportCsv,
                            onExportAllCsv = onExportAllCsv,
                            exportEnabled = exportEnabledFor(run),
                            enabled = status != ConnUiStatus.CONNECTING,
                        )
                    }
                    if (resultsVisible) {
                        // 拖动分隔条：像素差 / 可用高 → 比例增量；窗口缩放不改变已设比例。
                        ResultSplitter(
                            paneHeightPx = paneH,
                            onDragDeltaPx = { delta ->
                                resultFrac = (resultFrac + delta).coerceIn(MIN_RESULT_FRAC, MAX_RESULT_FRAC)
                            },
                        )
                        ResultPane(
                            result = run.result,
                            error = run.error,
                            transposed = transposed,
                            onCopyText = onCopyText,
                            modifier = Modifier.weight(resultFrac).fillMaxWidth(),
                        )
                    }
                }
            }
            if (showHistory) {
                HistoryPanel(
                    entries = history,
                    onFill = onFillHistory,
                    onClear = onClearHistory,
                    modifier = Modifier.width(256.dp).fillMaxHeight(),
                )
            }
        }
    }
}

private fun exportEnabledFor(run: ConsoleRunUi): Boolean {
    val res = run.result ?: return false
    return !run.executing && run.error == null && res.isQuery && res.rowCount > 0
}

/** 结果可转置：非执行中、无错误、查询结果且非空。 */
private fun canTranspose(run: ConsoleRunUi): Boolean {
    val res = run.result ?: return false
    return !run.executing && run.error == null && res.isQuery && res.rowCount > 0
}

/** 编辑器当前选中片段（去首尾空白）；无选中或选中空白 → null。 */
private fun selectedSqlOf(v: TextFieldValue): String? {
    val s = v.selection
    if (s.collapsed) return null
    // 反向选择（从下往上）时 start>end，必须取 min/max
    val from = minOf(s.start, s.end)
    val to = maxOf(s.start, s.end)
    val sub = v.text.substring(from, to)
    return sub.trim().takeIf { it.isNotEmpty() }
}

@Composable
private fun HeaderBar(
    isDark: Boolean,
    onToggleTheme: () -> Unit,
    showHistoryButton: Boolean,
    historyOpen: Boolean,
    onToggleHistory: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp),
    ) {
        Text("SQL 控制台", style = MaterialTheme.typography.subtitle2, color = MaterialTheme.colors.onSurface)
        Text(
            "每个数据源可建多个控制台，各绑定一个 .sql 文件",
            fontSize = 11.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.padding(start = 10.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        if (showHistoryButton) {
            IconButton(onClick = onToggleHistory, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = DbIcons.History,
                    contentDescription = if (historyOpen) "收起执行历史" else "打开执行历史",
                    tint = if (historyOpen) MaterialTheme.colors.primary
                    else MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        IconButton(onClick = onToggleTheme, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = if (isDark) DbIcons.Sun else DbIcons.Moon,
                contentDescription = if (isDark) "切换浅色主题" else "切换深色主题",
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

/** 绑定数据源导航行：展示当前控制台的数据源（点击可切到其它数据源），右侧是执行目标切换与断开。 */
@Composable
private fun ConnectionNavBar(
    profile: ConnectionProfile,
    status: ConnUiStatus,
    statusMessage: String?,
    profiles: List<ConnectionProfile>,
    onSelectProfile: (String) -> Unit,
    onDisconnect: () -> Unit,
    supportsTargetSwitch: Boolean,
    /** 该数据源的库/schema 列表（null = 未连接/未加载完成）。 */
    schemas: List<SchemaMeta>?,
    /** 当前控制台已选目标库/schema（"" = 连接默认）。 */
    target: String,
    onSelectTarget: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(38.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(5.dp))
                .clickable(enabled = profiles.size > 1) { menuOpen = true }
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            TypeBadge(profile.dbType)
            Text(
                profile.name,
                style = MaterialTheme.typography.body2,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 7.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (profiles.size > 1) {
                Icon(
                    Icons.Filled.ArrowDropDown, "切换数据源",
                    tint = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                profiles.forEach { p ->
                    DropdownMenuItem(onClick = {
                        menuOpen = false
                        if (p.id != profile.id) onSelectProfile(p.id)
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TypeBadge(p.dbType)
                            Text(
                                p.name,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(start = 8.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        StatusDot(status)
        Text(
            statusLabel(status),
            fontSize = 11.sp,
            color = statusColor(status),
            modifier = Modifier.padding(start = 4.dp),
        )
        if (status == ConnUiStatus.ERROR && statusMessage != null) {
            Text(
                statusMessage,
                fontSize = 11.sp,
                color = MaterialTheme.colors.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false),
            )
        }
        Spacer(Modifier.weight(1f))
        if (supportsTargetSwitch) {
            TargetSwitcher(
                enabled = status == ConnUiStatus.CONNECTED && schemas != null,
                loading = status == ConnUiStatus.CONNECTED && schemas == null,
                schemas = schemas.orEmpty(),
                target = target,
                onSelectTarget = onSelectTarget,
            )
        }
        if (status == ConnUiStatus.CONNECTED) {
            TextButton(onClick = onDisconnect) { Text("断开", fontSize = 12.sp) }
        }
    }
}

/**
 * 执行目标切换：每个控制台可选其数据源下的库/schema（PG 即 schema、MySQL 即库），
 * 下次执行前自动发出 USE / SET search_path 前导。"" = 默认（连接库/连接默认）。
 */
@Composable
private fun TargetSwitcher(
    enabled: Boolean,
    loading: Boolean,
    schemas: List<SchemaMeta>,
    target: String,
    onSelectTarget: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val label = if (target.isBlank()) "默认" else target
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .clickable(enabled = enabled) { open = true }
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(
            "目标",
            fontSize = 10.5.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.35f),
        )
        Text(
            if (loading) "加载中…" else label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = if (enabled) MaterialTheme.colors.onSurface.copy(alpha = 0.75f)
            else MaterialTheme.colors.onSurface.copy(alpha = 0.35f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 5.dp).widthIn(max = 150.dp),
        )
        Icon(
            Icons.Filled.ArrowDropDown, "切换执行目标库/Schema",
            tint = MaterialTheme.colors.onSurface.copy(alpha = if (enabled) 0.5f else 0.25f),
            modifier = Modifier.size(16.dp),
        )
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(onClick = {
            open = false
            if (target != "") onSelectTarget("")
        }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (target.isBlank()) "✓ " else "  ",
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.primary,
                )
                Text(
                    "默认（连接库/连接默认 schema）",
                    fontSize = 13.sp,
                    color = if (target.isBlank()) MaterialTheme.colors.primary
                    else MaterialTheme.colors.onSurface.copy(alpha = 0.75f),
                )
            }
        }
        schemas.forEach { s ->
            val name = s.displayName
            val selected = name.equals(target, ignoreCase = true)
            DropdownMenuItem(onClick = {
                open = false
                if (!selected) onSelectTarget(name)
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (selected) "✓ " else "  ",
                        fontSize = 12.sp,
                        color = MaterialTheme.colors.primary,
                    )
                    Text(
                        name,
                        fontSize = 13.sp,
                        color = if (selected) MaterialTheme.colors.primary
                        else MaterialTheme.colors.onSurface.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 控制台标签条（跨数据源）：固定显示全部档案的控制台，每个标签带数据源徽章（含 ● 未保存）；
 * 右侧「+」下拉选择在哪个数据源下新建。
 */
@Composable
private fun ConsoleTabBar(
    consoles: List<ConsoleRecord>,
    activeConsole: ConsoleRecord?,
    dirtyConsoleIds: Set<String>,
    profilesById: Map<String, ConnectionProfile>,
    onSelectConsole: (ConsoleRecord) -> Unit,
    onCreateConsoleAt: (String) -> Unit,
    onRenameConsole: (ConsoleRecord) -> Unit,
    onDeleteConsole: (ConsoleRecord) -> Unit,
) {
    var createMenuOpen by remember { mutableStateOf(false) }
    // 条内出现多个数据源时，标签额外显示所属数据源名，避免同类型两个库分不清
    val multiSource = consoles.map { it.connectionId }.distinct().size > 1
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(38.dp).padding(start = 12.dp, end = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
        ) {
            consoles.forEach { c ->
                ConsoleChip(
                    console = c,
                    profile = profilesById[c.connectionId],
                    showSourceTag = multiSource,
                    active = c.id == activeConsole?.id,
                    dirty = c.id in dirtyConsoleIds,
                    onSelect = { onSelectConsole(c) },
                    onRename = { onRenameConsole(c) },
                    onDelete = { onDeleteConsole(c) },
                )
                Spacer(Modifier.width(5.dp))
            }
        }
        Spacer(Modifier.width(6.dp))
        IconButton(
            onClick = { createMenuOpen = true },
            modifier = Modifier.size(24.dp),
        ) {
            Icon(
                Icons.Filled.Add, "新建控制台…",
                tint = MaterialTheme.colors.primary,
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(expanded = createMenuOpen, onDismissRequest = { createMenuOpen = false }) {
            profilesById.values.forEach { p ->
                DropdownMenuItem(onClick = {
                    createMenuOpen = false
                    onCreateConsoleAt(p.id)
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TypeBadge(p.dbType)
                        Text(
                            "在「${p.name}」新建控制台",
                            fontSize = 13.sp,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.85f),
                            modifier = Modifier.padding(start = 8.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsoleChip(
    console: ConsoleRecord,
    profile: ConnectionProfile?,
    showSourceTag: Boolean,
    active: Boolean,
    dirty: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val menu = listOf(
        ContextMenuItem("重命名控制台") { onRename() },
        ContextMenuItem("删除控制台") { onDelete() },
    )
    ContextMenuArea(items = { menu }) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (active) MaterialTheme.colors.primary.copy(alpha = 0.16f)
                    else MaterialTheme.colors.onSurface.copy(alpha = 0.045f),
                )
                .clickable(onClick = onSelect)
                .padding(start = if (profile != null) 5.dp else 9.dp, end = 9.dp, top = 4.dp, bottom = 4.dp),
        ) {
            if (profile != null) {
                TypeBadge(profile.dbType)
                Spacer(Modifier.width(6.dp))
            }
            if (dirty) {
                Text(
                    "●",
                    color = Color(0xFFFFB300), // 语义色：未保存（与状态点同源）
                    fontSize = 9.sp,
                    modifier = Modifier.padding(end = 3.dp),
                )
            }
            if (showSourceTag && profile != null) {
                Text(
                    "${profile.name} · ",
                    fontSize = 11.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                )
            }
            Text(
                console.name,
                fontSize = 12.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) MaterialTheme.colors.primary
                else MaterialTheme.colors.onSurface.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 引导区：还没有激活控制台时的空白态 —— 提示打开上方标签/在左侧树点数据源，并给出按数据源新建入口。 */
@Composable
private fun StarterPane(
    profiles: List<ConnectionProfile>,
    consoles: List<ConsoleRecord>,
    onCreateConsoleAt: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (consoles.isEmpty()) "还没有控制台" else "还没有打开的控制台",
            style = MaterialTheme.typography.subtitle1,
            color = MaterialTheme.colors.onSurface,
        )
        Text(
            if (consoles.isEmpty())
                "每个 tab 就是一个控制台（可来自不同数据源，各绑定自己的 .sql 文件）。\n" +
                    "点右上角「+」选择数据源新建，或在左侧树单击数据源连接。"
            else
                "每个 tab 就是一个控制台。点上方任一标签（徽章 = 所属数据源）即可打开，\n" +
                    "或点「+」再新建一个；在左侧树单击数据源连接也可直接进入。",
            fontSize = 12.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
        )
        if (profiles.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            profiles.forEach { p ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onCreateConsoleAt(p.id) }
                        .background(MaterialTheme.colors.onSurface.copy(alpha = 0.045f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    TypeBadge(p.dbType)
                    Text(
                        "在「${p.name}」新建控制台",
                        fontSize = 12.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.8f),
                        modifier = Modifier.padding(start = 7.dp),
                    )
                }
            }
        }
    }
}

/**
 * SQL 编辑器（语法高亮 + 自动补全）。
 *
 * 结构：外层自绘边框/底；左侧行号槽（Canvas 绘制、只画可视行、随滚动重绘）；内部
 * BasicTextField 消费带 span 高亮的 TextFieldValue（NeoUtils rememberHighlight +
 * rememberTextFieldValue 实时着色），自身 verticalScroll 滚动。行号/补全弹层共用同一份
 * TextMeasurer 排版结果，保证与编辑区逐行对齐（含自动换行）。
 * 文本/选区权威仍在上层 SqlWorkspace 持有的 tfv（受控），高亮是纯派生渲染。
 *
 * 自动补全：caret 位于标识符词内（且不在字符串/注释中）时按前缀匹配 [completionIdentifiers]
 * + SQL 关键字；Enter/Tab 上屏、↑/↓ 选择、Esc 关闭，也可鼠标点击。弹层颜色取自主题
 * （surface 底 + onSurface 文字 + primary 选中条），浅/深色均随主题。
 */
@Composable
private fun EditorPane(
    value: TextFieldValue,
    dirty: Boolean,
    onValueChange: (TextFieldValue) -> Unit,
    onCtrlEnter: () -> Unit,
    completionIdentifiers: List<String>,
    modifier: Modifier = Modifier,
) {
    val isDark = MaterialTheme.colors.isLight.not()
    val pal = sqlSyntaxPalette(isDark)
    val keywords = remember { sqlHighlightKeywords().distinct() }
    val highlightedValue = rememberHighlight {
        // 顺序：注释/字符串先注册（其内部关键字与数字不误染），再数字/关键字/标点。
        textColor { "--[^\n]*".toRegex().fully(pal.comment.toUiColor()) }
        textColor { "/\\*[\\s\\S]*?\\*/".toRegex().fully(pal.comment.toUiColor()) }
        textColor { "'(?:[^']|'')*'".toRegex().fully(pal.string.toUiColor()) }
        textColor { "\"(?:[^\"]|\"\")*\"".toRegex().fully(pal.string.toUiColor()) }
        textColor { "\\b\\d+(?:\\.\\d+)?\\b".toRegex().fully(pal.number.toUiColor()) }
        textColor {
            "\\b(${keywords.joinToString("|")})\\b"
                .toRegex(RegexOption.IGNORE_CASE)
                .fully(pal.keyword.toUiColor())
        }
        textColor { "[(),;.]".toRegex().fully(pal.punctuation.toUiColor()) }
    }.rememberTextFieldValue(value)

    val scroll = rememberScrollState()
    // 补全活性判定：本机 Compose Desktop 中 CoreTextField 的焦点在内部节点，外层 onFocusChanged
    // 收不到事件（实测输入时 focused 恒为 false）——改以 onValueChange（输入/光标移动/点击选区）
    // 作为“正在编辑”证据 + 4s 空闲看门狗自动退出。
    var editing by remember { mutableStateOf(false) }
    var lastEdit by remember { mutableStateOf(0L) }
    fun commitEdit(v: TextFieldValue) {
        lastEdit = System.currentTimeMillis()
        if (!editing) editing = true
        onValueChange(v)
    }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            if (editing && System.currentTimeMillis() - lastEdit > 4000) editing = false
        }
    }
    var boxW by remember { mutableStateOf(0) }
    var boxH by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val editorStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colors.onSurface,
    )
    val textMeasurer = rememberTextMeasurer()

    // ---- 补全派生状态：caret 词 → 候选 → 弹层 ----
    val sel = value.selection
    val caretActive = editing
    val word = if (!caretActive || !sel.collapsed) null else sqlCompletionWord(value.text, sel.start)
    val candidates = word?.let { completionCandidates(it.text, completionIdentifiers) }.orEmpty()
    val shown = candidates.take(MAX_COMPLETIONS)
    var selIdx by remember(shown) { mutableStateOf(0) }
    var dismissed by remember(word?.start, word?.end, shown.size) { mutableStateOf(false) }
    val popupOpen = shown.isNotEmpty() && !dismissed

    fun accept(c: String) {
        val w = word ?: return
        val newText = value.text.replaceRange(w.start, w.end, c)
        commitEdit(value.copy(text = newText, selection = TextRange(w.start + c.length)))
    }

    // 弹窗高度自适配：不超出编辑器可视高度（避免被下方执行条/结果区遮挡），至少 64dp
    val popupH: Dp = with(density) {
        ((boxH - 8f).coerceAtLeast(64f)).toDp().coerceAtMost(COMPLETION_H)
    }

    // ---- 行号槽 / 当前行高亮：文本布局（与编辑区同 style 同内宽，含自动换行）为唯一坐标来源 ----
    val content = value.text
    val gutterWpx = with(density) { GUTTER_W.toPx() }
    val textPadLPx = with(density) { 4.dp.toPx() }
    val textPadRPx = with(density) { 10.dp.toPx() }
    val textTopPx = with(density) { 8.dp.toPx() }
    val lineHpx = with(density) { 20.dp.toPx() }
    val textWpxInt = if (boxW > 0) (boxW - gutterWpx - textPadLPx - textPadRPx).toInt().coerceAtLeast(40) else 0
    val textLayout = if (textWpxInt > 40) {
        runCatching {
            textMeasurer.measure(
                AnnotatedString(content),
                style = editorStyle,
                constraints = Constraints(maxWidth = textWpxInt),
            )
        }.getOrNull()
    } else null
    // 物理行 → 其首个可视行的内容 Y（文本区坐标系内；含自动换行展开）。直接按每行起点偏移查
    // 排版行：TextLayout 本身包含换行产生的空行（含尾随换行后的最后一行），getLineForOffset
    // 对 offset == length 同样返回该空行的行号，因此无需对尾随换行做算术补偿。
    val lineTops: List<Float> = textLayout?.let { lay ->
        val starts = buildList {
            add(0)
            for (i in content.indices) if (content[i] == '\n') add(i + 1)
        }
        starts.map { off -> lay.getLineTop(lay.getLineForOffset(off.coerceIn(0, content.length))) }
    }.orEmpty()
    val gutterTops = lineTops.map { textTopPx + it }
    val curLineIdx =
        if (caretActive && sel.collapsed && content.isNotEmpty()) content.take(sel.start).count { it == '\n' } else -1

    // 当前行背景（随 caret 行的文本一并滚动/换行）——仅叠加 background，不动语法色 span
    val lineBgColor = MaterialTheme.colors.onSurface.copy(alpha = 0.06f)
    val displayValue: TextFieldValue =
        if (caretActive && sel.collapsed && content.isNotEmpty()) {
            val s = sel.start.coerceIn(0, content.length)
            val ls = content.lastIndexOf('\n', s - 1) + 1
            val leRaw = content.indexOf('\n', s)
            val le = if (leRaw < 0) content.length else leRaw
            val baseAnn = highlightedValue.annotatedString
            if (le > ls) {
                TextFieldValue(
                    annotatedString = AnnotatedString(
                        text = baseAnn.text,
                        spanStyles = baseAnn.spanStyles + listOf(
                            androidx.compose.ui.text.AnnotatedString.Range(SpanStyle(background = lineBgColor), ls, le),
                        ),
                        paragraphStyles = baseAnn.paragraphStyles,
                    ),
                    selection = value.selection,
                    composition = value.composition,
                )
            } else highlightedValue.copy(composition = value.composition)
        } else {
            highlightedValue.copy(composition = value.composition)
        }
    // 行号配色（主题派生）
    val gutterColor = MaterialTheme.colors.onSurface.copy(alpha = 0.35f)
    val gutterCurColor = MaterialTheme.colors.onSurface.copy(alpha = 0.95f)
    val gutterCurBg = MaterialTheme.colors.primary.copy(alpha = 0.16f)
    val numStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = gutterColor)
    val numCurStyle = TextStyle(
        fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = gutterCurColor,
        fontWeight = FontWeight.Medium,
    )

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colors.surface)
                .border(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                .onSizeChanged { boxW = it.width; boxH = it.height },
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // 行号槽：Canvas 只绘可视行，滚动时仅重绘（scroll.value 在 draw 内读取）
                Spacer(
                    modifier = Modifier
                        .width(GUTTER_W)
                        .fillMaxHeight()
                        .clipToBounds()
                        .drawBehind {
                            val tops = gutterTops
                            if (tops.isNotEmpty()) {
                                val h = size.height
                                val w = size.width
                                for (i in tops.indices) {
                                    val top = tops[i] - scroll.value
                                    if (top + lineHpx <= 0f) continue
                                    if (top >= h) break
                                    val cur = i == curLineIdx
                                    if (cur) {
                                        drawRoundRect(
                                            color = gutterCurBg,
                                            topLeft = Offset(3f, top + 2f),
                                            size = Size(w - 6f, lineHpx - 4f),
                                            cornerRadius = CornerRadius(4f, 4f),
                                        )
                                    }
                                    val m = textMeasurer.measure(
                                        AnnotatedString((i + 1).toString()),
                                        style = if (cur) numCurStyle else numStyle,
                                    )
                                    drawText(
                                        textLayoutResult = m,
                                        topLeft = Offset(
                                            w - m.size.width - 6f,
                                            top + ((lineHpx - m.size.height) / 2f).coerceAtLeast(0f),
                                        ),
                                    )
                                }
                            }
                        },
                )
                BasicTextField(
                    value = displayValue,
                    onValueChange = ::commitEdit,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = 4.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)
                        .verticalScroll(scroll)
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        if (e.isCtrlPressed && e.key == Key.Enter) {
                            // 选中 SQL 才执行；无选中什么都不做（禁止整段执行）
                            onCtrlEnter()
                            return@onPreviewKeyEvent true
                        }
                        // Ctrl+Space：显式唤起补全（Esc 关闭后可重新呼出）
                        if (e.isCtrlPressed && e.key == Key.Spacebar) {
                            dismissed = false
                            return@onPreviewKeyEvent true
                        }
                        if (popupOpen) {
                            when (e.key) {
                                Key.Tab, Key.Enter -> {
                                    val i = selIdx.coerceIn(0, shown.size - 1)
                                    accept(shown[i])
                                    true
                                }
                                Key.Escape -> { dismissed = true; true }
                                Key.DirectionDown -> { selIdx = (selIdx + 1) % shown.size; true }
                                Key.DirectionUp -> { selIdx = (selIdx - 1 + shown.size) % shown.size; true }
                                else -> false
                            }
                        } else {
                            false
                        }
                    },
                singleLine = false,
                cursorBrush = SolidColor(if (isDark) Color.White else Color.Black),
                textStyle = editorStyle,
                keyboardOptions = KeyboardOptions.Default,
                decorationBox = { innerTextField ->
                    Box {
                        if (value.text.isEmpty()) {
                            Text(
                                "输入 SQL…\n选中要执行的语句后 Ctrl+Enter（无选中不执行）",
                                fontSize = 12.sp,
                                lineHeight = 20.sp,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.35f),
                            )
                        }
                        innerTextField()
                    }
                },
            )
            }
            // 编辑状态提示（● = 有未落盘改动；Ctrl+S 立即保存）
            Text(
                if (dirty) "● 未保存 · Ctrl+S 保存" else "已保存到 .sql 文件",
                fontSize = 10.sp,
                color = if (dirty) MaterialTheme.colors.primary.copy(alpha = 0.75f)
                else MaterialTheme.colors.onSurface.copy(alpha = 0.3f),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 6.dp),
            )
        }
        // 补全弹层：位置 = caret 的真实排版位置（TextMeasurer 按内宽换行测出，避免覆盖正在输入的行）
        if (popupOpen) {
            val w = word ?: return@Box
            val gapPx = with(density) { 6.dp.toPx() }
            val caretRect = textLayout?.getCursorRect(sel.start.coerceIn(0, value.text.length))
            val textLeftPx = gutterWpx + textPadLPx
            val popW = with(density) { COMPLETION_W.toPx() }
            val popH = with(density) { popupH.toPx() }
            CompletionPopup(
                items = shown,
                selectedIndex = selIdx,
                onSelect = { i -> accept(shown[i]) },
                modifier = Modifier
                    .width(COMPLETION_W)
                    .height(popupH)
                    .offset {
                        val x = if (caretRect != null) {
                            (textLeftPx + caretRect.left).toInt().coerceIn(0, (boxW - popW.toInt()).coerceAtLeast(0))
                        } else {
                            val before = value.text.substring(0, w.start)
                            val colNo = w.start - (before.lastIndexOf('\n') + 1)
                            (textLeftPx + colNo * 7.8f).toInt().coerceIn(0, (boxW - popW.toInt()).coerceAtLeast(0))
                        }
                        val caretTop = if (caretRect != null) textTopPx + caretRect.top - scroll.value else textTopPx - scroll.value
                        val caretBottom = if (caretRect != null) caretTop + caretRect.height else caretTop + lineHpx
                        // 下移一格再放：弹窗顶部低于 caret 行下一行的行底，确保不压住当前输入行与紧随其后的行
                        val rowH = if (caretRect != null) caretRect.height else lineHpx
                        val y = when {
                            caretBottom + rowH + gapPx + popH <= boxH -> (caretBottom + rowH + gapPx).toInt()
                            caretTop - gapPx - popH >= 0 -> (caretTop - gapPx - popH).toInt()
                            else -> (boxH - popH.toInt()).coerceAtLeast(0)
                        }
                        IntOffset(x, y)
                    },
            )
        }
    }
}

private val GUTTER_W = 40.dp
private val COMPLETION_W = 300.dp
private val COMPLETION_H = 176.dp
private const val MAX_COMPLETIONS = 60

/** 补全候选弹层：主题化小面板，键盘选中的高亮 + 鼠标点击上屏。 */
@Composable
private fun CompletionPopup(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .shadow(6.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colors.surface)
            .border(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.25f), RoundedCornerShape(8.dp)),
    ) {
        Text(
            "补全 ${items.size} 项 · Enter 上屏 · Esc 关闭",
            fontSize = 10.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        )
        Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.08f))
        val listState = rememberLazyListState()
        // 键盘选择越出可视区时跟随滚动
        LaunchedEffect(selectedIndex) {
            if (items.isNotEmpty() && selectedIndex in items.indices) {
                listState.animateScrollToItem(selectedIndex)
            }
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(items) { index, item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(index) }
                        .background(
                            if (index == selectedIndex) MaterialTheme.colors.primary.copy(alpha = 0.16f)
                            else Color.Transparent,
                        )
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                ) {
                    Text(
                        item,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.9f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ExecBar(
    executing: Boolean,
    result: QueryResult?,
    error: String?,
    transposed: Boolean,
    onToggleTranspose: () -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
    onExportCsv: () -> Unit,
    onExportAllCsv: () -> Unit,
    exportEnabled: Boolean,
    enabled: Boolean,
) {
    val canTranspose = result != null && result.isQuery && result.rowCount > 0
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Button(onClick = onRun, enabled = enabled && !executing) {
            Text(if (executing) "执行中…" else "执行 (Ctrl+Enter)", fontSize = 13.sp)
        }
        if (executing) {
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onCancel, enabled = enabled) {
                Text("取消 (Esc)", fontSize = 12.sp, color = MaterialTheme.colors.error)
            }
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onClear, enabled = !executing) { Text("清空", fontSize = 12.sp) }
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onExportCsv, enabled = exportEnabled) {
            Text("导出 CSV", fontSize = 12.sp)
        }
        // 结果被截断时提供全量导出（重新执行 SQL，不受 1000 行上限）
        if (exportEnabled && result?.truncated == true) {
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onExportAllCsv, enabled = enabled) {
                Text("导出全量 CSV", fontSize = 12.sp, color = MaterialTheme.colors.primary)
            }
        }
        // 行列转置开关（Ctrl+T 等效）
        if (!executing && canTranspose && error == null) {
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onToggleTranspose) {
                Text(
                    if (transposed) "还原布局 (Ctrl+T)" else "转置 (Ctrl+T)",
                    fontSize = 12.sp,
                    color = if (transposed) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(alpha = 0.75f),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (executing) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(
                " 执行中…",
                fontSize = 12.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
            )
        } else if (error != null) {
            Text("执行出错", fontSize = 12.sp, color = MaterialTheme.colors.error)
        } else if (result != null) {
            Text(
                metaText(result, transposed),
                fontSize = 12.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
            )
        }
    }
}

/** 结果区占上下两块剩余高的比例下限/上限（拖动 clamp 用）。 */
private const val MIN_RESULT_FRAC = 0.15f
private const val MAX_RESULT_FRAC = 0.85f

/**
 * 编辑区/结果区分隔条：12dp 热区整条可上下拖动（悬停 N/S 双向箭头光标），
 * 中央一条浅色短线作视觉提示。拖动像素按内容区可用高换算成比例增量后由上层累加。
 */
@Composable
private fun ResultSplitter(
    paneHeightPx: Int,
    onDragDeltaPx: (Float) -> Unit,
) {
    // 固定开销：上下 padding 10+10、外侧 spacedBy 两处 8、本条高 12 —— 不算入比例换算基数
    val chromePx = LocalDensity.current.run { 48.dp.toPx() }
    val resizeCursor = remember {
        PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.N_RESIZE_CURSOR))
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(12.dp)
            .pointerHoverIcon(resizeCursor)
            .pointerInput(paneHeightPx) {
                detectVerticalDragGestures { _, dragAmount ->
                    val free = (paneHeightPx - chromePx).coerceAtLeast(1f)
                    // 屏幕 y 向下为正：向上拖（负值）要让结果区变大（上边界上移），故取反
                    onDragDeltaPx(-dragAmount / free)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(64.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colors.onSurface.copy(alpha = 0.15f)),
        )
    }
}

private fun metaText(result: QueryResult, transposed: Boolean): String {
    val ms = "${result.durationMs} ms"
    return when {
        result.affectedRows != null -> "已更新 ${result.affectedRows} 行 · $ms"
        result.rowCount == 0 -> "查询完成 · 0 行 · $ms"
        transposed -> {
            // 转置视图：C 列 → C 行，外加一列“列名”标签列
            "已转置（Ctrl+T 还原）· ${result.columns.size} 行 × ${result.rowCount + 1} 列 · $ms"
        }
        else -> {
            val truncated = if (result.truncated) "（截断）" else ""
            "${result.columns.size} 列 × ${result.rowCount} 行$truncated · $ms"
        }
    }
}

@Composable
private fun ResultPane(
    result: QueryResult?,
    error: String?,
    transposed: Boolean,
    onCopyText: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colors.surface),
    ) {
        when {
            error != null -> CenteredHint(error, isError = true)
            result == null -> CenteredHint("执行 SELECT 后在此查看结果表格；可导出 CSV", isError = false)
            result.isQuery && result.rowCount == 0 -> CenteredHint("查询完成：0 行", isError = false)
            result.isQuery -> Column(modifier = Modifier.fillMaxSize()) {
                if (result.truncated) TruncationBanner()
                ResultTable(
                    result = result,
                    transposed = transposed,
                    onCopyText = onCopyText,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
            else -> CenteredHint("语句执行成功（非查询，未产生结果集）", isError = false)
        }
    }
}

/** 截断醒目提示：结果被 QueryExecutor.MAX_ROWS 截断时显示在表格上方。 */
@Composable
private fun TruncationBanner() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFB300).copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFFFFB300)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "结果超过 ${QueryExecutor.MAX_ROWS} 行，表格只显示前 ${QueryExecutor.MAX_ROWS} 行（已截断）。" +
                "「导出全量 CSV」会重新执行并导出全部行。",
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun CenteredHint(text: String, isError: Boolean) {
    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            fontSize = 12.sp,
            color = if (isError) MaterialTheme.colors.error
            else MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
        )
    }
}

/** 结果网格：列宽按表头与最多前 300 行采样估算；表头与数据共用横向滚动。
 * 交互：右键单元格可「复制单元格值 / 复制本行 → INSERT」（不做单击复制，避免动不动污染剪贴板）。
 * [transposed]=true 时仅展示行列转制视图（复制交互随之作用于转置后的网格；
 * “本行 → INSERT”在转置视图下无意义故隐藏）。
 */
@Composable
private fun ResultTable(
    result: QueryResult,
    transposed: Boolean,
    onCopyText: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = if (transposed) transposeResult(result) else result
    val cols = view.columns
    val widths = IntArray(cols.size) { c ->
        var w = cols[c].name.length
        val sample = minOf(view.rows.size, 300)
        for (r in 0 until sample) {
            val cell = view.rows[r][c]
            val len = cell?.length ?: 5 // (NULL)
            if (len > w) w = len
        }
        estWidth(w)
    }
    // 横向滚动用共享 ScrollState + horizontalScroll（表头与每一行都读同一偏移）——
    // 不能用多个 LazyRow 共享 LazyListState：虚拟化列表各自测量，滚动条驱动时只有
    // 最近测量那一个响应，会出现“只有内容滚、表头不动”的错位。
    val hScroll = rememberScrollState()
    val vScroll = rememberLazyListState()
    // 每行可生成的 INSERT（仅原布局；复杂查询/无法定表时 null）
    val tableName = extractTableName(result.sql)
    val insertSqls: List<String?> = if (transposed) view.rows.map { null }
    else result.rows.map { row -> rowToInsertSql(result.sql, result.columns.map { it.name }, row) }
    // 结果表格滚动条：列多/行多时可见可拖，横向条与表头/各行同步
    val scrollbarStyle = ScrollbarStyle(
        minimalHeight = 24.dp,
        thickness = 10.dp,
        shape = RoundedCornerShape(5.dp),
        hoverDurationMillis = 300,
        unhoverColor = MaterialTheme.colors.onSurface.copy(alpha = 0.20f),
        hoverColor = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
    )
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colors.onSurface.copy(alpha = 0.06f))
                .horizontalScroll(hScroll),
        ) {
            RowHeaderCell("", 44)
            cols.forEachIndexed { c, col ->
                RowHeaderCell(col.name, widths[c])
            }
        }
        Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.1f))
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // 右侧/底部预留滚动条厚度，避免遮挡最后一列与最后一行
            Column(modifier = Modifier.fillMaxSize().padding(end = scrollbarStyle.thickness, bottom = scrollbarStyle.thickness)) {
                LazyColumn(state = vScroll, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(view.rows) { index, row ->
                        val insertSql = insertSqls.getOrNull(index)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (index % 2 == 1) MaterialTheme.colors.onSurface.copy(alpha = 0.025f)
                                    else Color.Transparent,
                                )
                                .horizontalScroll(hScroll),
                        ) {
                            DataCell("${index + 1}", 44, mono = false, muted = true)
                            row.forEachIndexed { c, v ->
                                val colName = view.columns[c].name
                                DataCell(
                                    value = v,
                                    width = widths[c],
                                    menuItems = buildList {
                                        add(
                                            ContextMenuItem("复制单元格值") {
                                                copyCellValue(onCopyText, v, colName)
                                            },
                                        )
                                        if (insertSql != null) {
                                            add(
                                                ContextMenuItem("复制本行 → INSERT") {
                                                    onCopyText(
                                                        insertSql,
                                                        "已复制本行 → INSERT（表 ${tableName ?: "?"}）",
                                                    )
                                                },
                                            )
                                        }
                                    },
                                )
                            }
                        }
                        Divider(
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.05f),
                            modifier = Modifier.padding(start = 44.dp),
                        )
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(vScroll),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(bottom = scrollbarStyle.thickness),
                style = scrollbarStyle,
            )
            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(hScroll),
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(end = scrollbarStyle.thickness),
                style = scrollbarStyle,
            )
        }
    }
}

/** 复制单元格值：NULL 复制为空串（与 CSV 导出规则一致），Toast 文案带预览。 */
private fun copyCellValue(onCopyText: (String, String) -> Unit, v: String?, colName: String) {
    if (v == null) {
        onCopyText("", "已复制（NULL → 空串），列 $colName")
    } else {
        val preview = if (v.length > 28) v.take(28) + "…" else v
        onCopyText(v, "已复制单元格（列 $colName）：$preview")
    }
}

@Composable
private fun RowHeaderCell(text: String, width: Int) {
    Box(
        modifier = Modifier.width(width.dp).height(30.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.75f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun DataCell(
    value: String?,
    width: Int,
    mono: Boolean = true,
    muted: Boolean = false,
    menuItems: List<ContextMenuItem> = emptyList(),
) {
    val content: @Composable () -> Unit = {
        if (value == null) {
            Text(
                "(NULL)",
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                fontSize = 12.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.3f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        } else {
            Text(
                value,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                fontSize = 12.sp,
                color = if (muted) MaterialTheme.colors.onSurface.copy(alpha = 0.45f)
                else MaterialTheme.colors.onSurface.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
    val base = Modifier.width(width.dp).height(26.dp)
    if (menuItems.isEmpty()) {
        Box(modifier = base, contentAlignment = Alignment.CenterStart) { content() }
    } else {
        // 仅右键菜单可复制（不做单击复制）
        ContextMenuArea(items = { menuItems }) {
            Box(modifier = base, contentAlignment = Alignment.CenterStart) {
                content()
            }
        }
    }
}

internal fun statusLabel(status: ConnUiStatus): String = when (status) {
    ConnUiStatus.DISCONNECTED -> "未连接"
    ConnUiStatus.CONNECTING -> "连接中…"
    ConnUiStatus.CONNECTED -> "已连接"
    ConnUiStatus.ERROR -> "连接失败"
}

internal fun statusColor(status: ConnUiStatus): Color = when (status) {
    ConnUiStatus.DISCONNECTED -> Color(0xFF9E9E9E)
    ConnUiStatus.CONNECTING -> Color(0xFFFFB300)
    ConnUiStatus.CONNECTED -> Color(0xFF43A047)
    ConnUiStatus.ERROR -> Color(0xFFE53935)
}

@Composable
internal fun StatusDot(status: ConnUiStatus) {
    Box(
        modifier = Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(statusColor(status)),
    )
}
