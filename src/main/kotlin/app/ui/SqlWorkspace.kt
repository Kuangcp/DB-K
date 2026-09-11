package app.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import org.tinylog.Logger
import kotlinx.coroutines.launch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isPrimaryPressed
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
import app.state.ColumnCatalog
import app.state.ConsoleRunUi
import app.state.StatementOutcome
import app.settings.EditorSettings
import app.dialog.TextViewerDialog
import com.neoutils.highlight.compose.remember.rememberHighlight
import com.neoutils.highlight.compose.remember.rememberTextFieldValue
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

/** 手动拖动列宽的上下限（dp）。 */
private const val MIN_COL_WIDTH = 40
private const val MAX_COL_WIDTH = 1600

/** 结果表行号 gutter 宽（dp，表头与数据行共用）。 */
private const val RESULT_GUTTER_DP = 44

/** 单列结果 / 转置后只剩一个值列时，值列自适应加宽的上限（dp，避免超长字段把列撑成巨宽）。 */
private const val MAX_FLEX_COL_WIDTH = 600

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
    /** 关闭控制台标签（仅隐藏，保留 .sql；可从数据源右键重新打开）。 */
    onCloseConsole: (ConsoleRecord) -> Unit = {},
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
    /** 读某控制台上次的光标/选区（重启后恢复焦点所在行）。 */
    caretOf: (String) -> Pair<Int, Int>,
    /** 记录控制台光标/选区（内存即时生效，防抖落库）。 */
    onCaretChange: (String, Int, Int) -> Unit,
    /** Ctrl+S 主动保存（异步写盘；Main 取当前控制台）。 */
    onSaveNow: () -> Unit = {},
    run: ConsoleRunUi,
    /** 执行请求：参数为编辑器当前选中片段（去首尾空白）；null = 无有效选中。 */
    onRun: (String?) -> Unit,
    /** 结果区多语句 Tab 切换（选中第 index 条语句结果）。 */
    onSelectOutcome: (Int) -> Unit,
    onExportCsv: () -> Unit,
    /** 取消当前执行（取消按钮 / Esc）。 */
    onCancelRun: () -> Unit,
    /** 全量导出：结果被截断时重新执行 SQL 导出全部行。 */
    onExportAllCsv: () -> Unit,
    /** 执行历史（当前数据源）。 */
    history: List<SqlHistoryRow>,
    onRefreshHistory: () -> Unit,
    onClearHistory: () -> Unit,
    /** 编辑器补全用数据源对象名（表/视图，已加载）。 */
    completionIdentifiers: List<String>,
    /** 编辑器列补全用对象清单（表/视图名 + 所属 schema，与 [completionIdentifiers] 同源）。 */
    completionTables: List<CompletionTable> = emptyList(),
    /** 编辑器补全用函数/过程/聚合名（PG 等能探测到的数据源；其余为空）。 */
    completionFunctions: List<String> = emptyList(),
    /** 列元数据会话缓存（异步回填；null = 不做列补全）。 */
    columnCatalog: ColumnCatalog? = null,
    /** 复制文本（单元格 / INSERT 语句）→ 剪贴板 + Toast。参数：文本、Toast 文案。 */
    onCopyText: (String, String) -> Unit,
    onDisconnect: () -> Unit,
    /** 结果区显隐（Alt+D 由 Main 窗口根层统一接管，这里只读它布局）。 */
    resultsVisible: Boolean,
    onToggleResults: () -> Unit,
    isDark: Boolean,
    onToggleTheme: () -> Unit,
    /** 编辑器外观设置（字体族 / 字号）。 */
    editorSettings: EditorSettings,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showHistory by remember { mutableStateOf(false) }
    // 双击历史条目：弹窗查看完整 SQL（复用通用文本查看器，按 SQL 高亮）
    var historyView by remember { mutableStateOf<SqlHistoryRow?>(null) }
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
    LaunchedEffect(run.activeIndex) {
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
    LaunchedEffect(run.executing) {
        if (!run.executing && run.outcomes.any { it.isQuery } && !resultsVisible) onToggleResults()
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
            onOpenSettings = onOpenSettings,
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
            onCloseConsole = onCloseConsole,
        )
        Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.08f))
        if (activeConsole == null) {
            StarterPane(
                profiles = profiles,
                onCreateConsoleAt = onCreateConsoleAt,
            )
            return
        }
        // 每个控制台记住光标/选区（持久化在 consoles.caret_start/caret_end）：
        // 切回（含重启）时恢复上次焦点所在行，而不是总跳到文末。
        val consoleId = activeConsole.id
        val savedCaret = caretOf(consoleId)
        // 编辑器状态：文本由外部权威（切换控制台/预览/清空），选区本地瞬态。
        // 用 remember(consoleId) 在「组合期同步」重建，这样切控制台时选区已是记忆值，
        // 子层 LaunchedEffect(consoleId) 的“滚到光标行”不会读到切换前的旧值。
        var tfv by remember(consoleId) {
            mutableStateOf(
                TextFieldValue(
                    editorText,
                    TextRange(
                        savedCaret.first.coerceIn(0, editorText.length),
                        savedCaret.second.coerceIn(0, editorText.length),
                    ),
                ),
            )
        }
        LaunchedEffect(editorText) {
            if (tfv.text != editorText) {
                val v = TextFieldValue(editorText, TextRange(editorText.length))
                tfv = v
                onCaretChange(consoleId, v.selection.start, v.selection.end)
            }
        }
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onSizeChanged { paneH = it.height },
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    // 编辑器吃满剩余高，结果区按 resultFrac 分配；中间只隔一根 5dp 可拖细线，不留空隙。
                    // 执行动作/状态/多语句 tabs 全部收在结果区顶部一条 28dp 工具条里（无结果时不显示）。
                    EditorPane(
                        value = tfv,
                        consoleId = consoleId,
                        dirty = editorDirty,
                        onValueChange = { v ->
                            val textChanged = v.text != tfv.text
                            tfv = v
                            // 记录本控制台最后的光标/选区（点击、输入、选择都更新；内存即时、防抖落库）
                            onCaretChange(consoleId, v.selection.start, v.selection.end)
                            // BasicTextField 在纯鼠标点击/光标移动时也会以新选区上报 onValueChange，
                            // 内容没变就不置脏、不触发自动保存（否则点一下编辑器就变成“未保存”）。
                            if (textChanged) onTextChange(v.text)
                        },
                        onCtrlEnter = { selectedSqlOf(tfv)?.let(onRun) },
                        completionIdentifiers = completionIdentifiers,
                        completionTables = completionTables,
                        completionFunctions = completionFunctions,
                        columnCatalog = columnCatalog,
                        schemas = schemas.orEmpty(),
                        defaultSchema = schemas?.firstOrNull { it.displayName == targetSchema },
                        profile = profile,
                        editorSettings = editorSettings,
                        modifier = Modifier
                            .weight(if (resultsVisible) 1f - resultFrac else 1f)
                            .fillMaxWidth(),
                    )
                    if (resultsVisible) {
                        // 拖动分隔条：像素差 / 可用高 → 比例增量；窗口缩放不改变已设比例。
                        ResultSplitter(
                            paneHeightPx = paneH,
                            onDragDeltaPx = { delta ->
                                resultFrac = (resultFrac + delta).coerceIn(MIN_RESULT_FRAC, MAX_RESULT_FRAC)
                            },
                        )
                        ResultTabs(
                            run = run,
                            transposed = transposed,
                            exportEnabled = exportEnabledFor(run),
                            enabled = status != ConnUiStatus.CONNECTING,
                            onSelectOutcome = onSelectOutcome,
                            onToggleTranspose = { transposed = !transposed },
                            onExportCsv = onExportCsv,
                            onExportAllCsv = onExportAllCsv,
                            onCancelRun = onCancelRun,
                            onCopyText = onCopyText,
                            modifier = Modifier.weight(resultFrac).fillMaxWidth(),
                        )
                    }
                }
            }
            if (showHistory) {
                HistoryPanel(
                    entries = history,
                    onView = { historyView = it },
                    onClear = onClearHistory,
                    modifier = Modifier.width(256.dp).fillMaxHeight(),
                )
            }
        }
    }
    historyView?.let { row ->
        TextViewerDialog(
            title = "历史 SQL",
            content = row.sqlText,
            onDismiss = { historyView = null },
            onCopy = onCopyText,
            highlightSql = true,
            subtitle = historyEntryMeta(row),
            copyToast = "已复制历史 SQL",
        )
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
    onOpenSettings: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp),
    ) {
        // 左侧留白：后续在此放更多工具 icon（标题文字已去掉）
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
        IconButton(onClick = onOpenSettings, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "设置",
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
    // DropdownMenu 的定位锤点是「与它同一父布局」的节点；本组件自身不产生布局节点，
    // 若把锤点 Row 与 DropdownMenu 直接放进父 Row，弹层会按整行（全宽）换算 → 跑到左上角。
    // 用 Box 把锤点与弹层包在一起，弹层即贴着「目标」按钮弹出。
    Box {
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
    onCloseConsole: (ConsoleRecord) -> Unit,
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
                    onClose = { onCloseConsole(c) },
                )
                Spacer(Modifier.width(5.dp))
            }
        }
        Spacer(Modifier.width(6.dp))
        // 同上：IconButton 与 DropdownMenu 必须在同一 Box 内，否则弹层按整行定位。
        Box {
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
    onClose: () -> Unit,
) {
    val menu = listOf(
        ContextMenuItem("重命名控制台") { onRename() },
        ContextMenuItem("关闭控制台") { onClose() },
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
    onCreateConsoleAt: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "还没有打开的控制台",
            style = MaterialTheme.typography.subtitle1,
            color = MaterialTheme.colors.onSurface,
        )
        Text(
            "每个 tab 就是一个控制台（可来自不同数据源，各绑定自己的 .sql 文件）。\n" +
                "在左侧树右键数据源 →「打开控制台」可选择已有控制台并重新打开；\n" +
                "也可点下面按钮/标签条「+」新建。",
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
    /** 所属控制台 id：仅用作 LaunchedEffect 键——切换控制台时把视口滚到恢复的光标行。 */
    consoleId: String,
    dirty: Boolean,
    onValueChange: (TextFieldValue) -> Unit,
    onCtrlEnter: () -> Unit,
    completionIdentifiers: List<String>,
    completionTables: List<CompletionTable>,
    completionFunctions: List<String>,
    columnCatalog: ColumnCatalog?,
    schemas: List<SchemaMeta>,
    defaultSchema: SchemaMeta?,
    profile: ConnectionProfile?,
    editorSettings: EditorSettings,
    modifier: Modifier = Modifier,
) {
    val isDark = MaterialTheme.colors.isLight.not()
    val keywords = remember { sqlHighlightKeywords().distinct() }
    // isDark 作 key：主题切换时重建 Highlight，否则记住的旧色板不会刷新。
    val highlightedValue = rememberHighlight(isDark) {
        applySqlHighlightRules(sqlSyntaxPalette(isDark), keywords)
    }.rememberTextFieldValue(value)

    val scroll = rememberScrollState()
    // 拖拽选区自动滚动：指针停在上/下边缘时持续滚动并同步延伸选区（多行大块选择必需）。
    // 编辑器是「BasicTextField + 外层 verticalScroll」结构，BasicTextField 不知道外层滚动，
    // 不会自己滚；所以在父 Box 上旁路观察指针（Final pass，不干涉文本域自身选区逻辑）。
    var dragActive by remember { mutableStateOf(false) }
    var dragPointer by remember { mutableStateOf<Offset?>(null) }
    var scrollAnchor by remember { mutableStateOf<Int?>(null) }
    // 补全活性判定：本机 Compose Desktop 中 CoreTextField 的焦点在内部节点，外层 onFocusChanged
    // 收不到事件（实测输入时 focused 恒为 false）。且 BasicTextField 在纯鼠标点击/移动光标时
    // 也会以新选区上报 onValueChange——因此**只有文本真正变化**（敲字/删除/粘贴）才激活补全，
    // 点击与光标移动立即关闭；再用 4s 空闲看门狗收尾。
    var editing by remember { mutableStateOf(false) }
    var lastEdit by remember { mutableStateOf(0L) }
    // 显式 Ctrl+Space 唤起：允许空前缀（列出上下文列/表）；任意编辑/移动光标后复位
    var forceComplete by remember { mutableStateOf(false) }
    fun commitEdit(v: TextFieldValue) {
        val typed = v.text != value.text
        lastEdit = System.currentTimeMillis()
        editing = typed
        forceComplete = false
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
        fontFamily = editorFontFamily(editorSettings.fontFamilyName),
        fontSize = editorSettings.fontSizeSp.sp,
        lineHeight = EditorSettings.lineHeightSp(editorSettings.fontSizeSp).sp,
        color = MaterialTheme.colors.onSurface,
    )
    // 行号槽字号跟随编辑器字号（旧固定 11sp ≈ 13sp * 0.85）
    val gutterFontFamily = editorStyle.fontFamily
    val gutterFontSize = (editorSettings.fontSizeSp * 0.85f).sp
    val gutterLineHeight = editorStyle.lineHeight
    val textMeasurer = rememberTextMeasurer()

    // ---- 补全派生状态：caret 词/限定符/星号 → 语句上下文（含 CTE/子查询）→ 候选 → 弹层 ----
    val sel = value.selection
    val caretActive = editing
    val canComplete = caretActive && sel.collapsed
    val qualified = if (canComplete) sqlQualifiedPrefix(value.text, sel.start) else null
    // 解析 caret 所在语句（只看括号深度 0；只取已写完的表名，避免边敲边查元数据）
    val prepared = if (canComplete) buildPreparedScope(value.text, sel.start) else null
    val cteColumns = prepared?.scope?.cteColumns.orEmpty()
    val allRefs: List<Pair<TableRef, SchemaMeta?>> = prepared?.let { p ->
        p.scope.tables
            .filter { it.isComplete(p.stmt.length, p.caretInStmt) }
            .map { ref ->
                ref to if (ref.derived || ref.cte) null
                else resolveTableRef(ref, completionTables, schemas, defaultSchema)
            }
    }.orEmpty()
    // select-list 的 `*` 可展开为 FROM 表列（仅 Ctrl+Space 显式唤起：避免自动弹层劫持 Enter）
    val starPos = if (canComplete && qualified == null && forceComplete) {
        selectStarBeforeCaret(value.text, sel.start)
    } else {
        null
    }
    val word: CompletionWord? = when {
        qualified != null -> CompletionWord(qualified.wordStart, qualified.wordEnd, qualified.wordText)
        starPos != null -> CompletionWord(starPos, sel.start, "*")
        canComplete -> sqlCompletionWord(value.text, sel.start)
            ?: if (forceComplete && sqlCompletionAllowed(value.text, sel.start)) {
                CompletionWord(sel.start, sel.start, "")
            } else null
        else -> null
    }
    // 限定符模式只取匹配该限定符（别名/表名/schema.表）的列；非限定模式取全部 FROM 表列
    val neededRefs = if (qualified != null) {
        allRefs.filter { (ref, _) -> ref.matchesQualifier(qualified.qualifier) }
    } else {
        allRefs
    }
    val columnItems = neededRefs.flatMap { (ref, sch) ->
        when {
            ref.derived -> emptyList()
            ref.cte -> cteColumns[ref.table.lowercase()].orEmpty()
                .map { CompletionItem(it, "CTE", CompletionKind.COLUMN) }
            else -> columnCatalog?.peek(profile?.id.orEmpty(), sch, ref.table).orEmpty()
                .map { CompletionItem(it.name, it.typeName, CompletionKind.COLUMN) }
        }
    }
    val aliasItems = if (qualified != null) emptyList() else allRefs.mapNotNull { (ref, _) ->
        ref.alias?.let { CompletionItem(it, if (ref.derived) "子查询" else ref.table, CompletionKind.ALIAS) }
    }
    val functionItems = if (qualified != null) emptyList() else completionFunctions.map {
        CompletionItem(it, "函数", CompletionKind.FUNCTION)
    }
    val objectItems = if (qualified != null) emptyList() else completionIdentifiers.map { name ->
        CompletionItem(
            name,
            completionTables.firstOrNull { it.name == name }?.schema?.displayName,
            CompletionKind.TABLE,
        )
    }
    // `*` 展开：仅星号场景生效，单个候选项，上屏写列清单
    val expandItems = if (starPos != null && columnItems.isNotEmpty()) {
        listOf(
            CompletionItem(
                text = "*",
                detail = "展开为 ${columnItems.size} 列",
                kind = CompletionKind.EXPAND,
                insertText = columnItems.joinToString(", ") { it.text },
            ),
        )
    } else {
        emptyList()
    }
    val candidates = when {
        starPos != null -> expandItems
        word != null -> completionItems(
            word = word.text,
            columns = columnItems,
            aliases = aliasItems,
            functions = functionItems,
            objects = objectItems,
            includeKeywords = qualified == null && (!forceComplete || word.text.isNotEmpty()),
        )
        else -> emptyList()
    }
    val shown = candidates.take(MAX_COMPLETIONS)
    var selIdx by remember(shown) { mutableStateOf(0) }
    var dismissed by remember(word?.start, word?.end, shown.size) { mutableStateOf(false) }
    val popupOpen = shown.isNotEmpty() && !dismissed

    fun accept(item: CompletionItem) {
        val w = word ?: return
        val insert = item.insertText ?: item.text
        val newText = value.text.replaceRange(w.start, w.end, insert)
        commitEdit(value.copy(text = newText, selection = TextRange(w.start + insert.length)))
        // 接受后不自动重开（避免刚上屏又弹下一批候选），等下一次敲键或 Ctrl+Space
        editing = false
    }

    // 元数据未命中则异步拉取（回填快照 → 重组合出候选）；键变化即取消旧请求。
    // 真实表才拉列（派生表/CTE 不查库）；表名写完即预取，不要求 caret 在 SELECT 列表。
    val fetchRefs = allRefs.filter { (ref, _) -> !ref.derived && !ref.cte }
    val prefetchKey = fetchRefs.joinToString("|") { (r, s) -> "${s?.key ?: ""}#${r.table.lowercase()}" }
    LaunchedEffect(consoleId, profile?.id, prefetchKey) {
        val p = profile ?: return@LaunchedEffect
        val catalog = columnCatalog ?: return@LaunchedEffect
        if (fetchRefs.isEmpty()) return@LaunchedEffect
        catalog.ensure(p, fetchRefs.map { (r, s) -> ColumnCatalog.ColumnRef(s, r.table) })
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
    val lineHpx = with(density) { gutterLineHeight.toDp().toPx() }
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

    // 切换/恢复控制台：把视口滚到恢复后的光标行（父层已在组合期恢复选区，此处 value 已是记忆位置）。
    // 目标为「光标行居中」，首/尾行由 ScrollState 自动夹到 0..max 而自然贴顶/贴底；
    // 键里必须带「排版是否就绪」：启动时编辑区首次组合 boxW=0，取不到内宽 → textLayout=null，
    // 此时只能提前返回；尺寸量出来（键 false→true）后再跑一次，否则首个控制台永远不做恢复。
    LaunchedEffect(consoleId, textLayout != null) {
        val lay = textLayout ?: return@LaunchedEffect
        val off = sel.start.coerceIn(0, content.length)
        val top = textTopPx + lay.getLineTop(lay.getLineForOffset(off))
        // 让记忆行大致落在可视区中间；目标值越界时由 ScrollState 自行夹到 0..max，
        // 因此文件首/尾的行会自然贴顶/贴底展示，不会出现滚动不到位的空白。
        val viewport = (boxH - 2f * textTopPx).coerceAtLeast(lineHpx)
        val target = (top + lineHpx / 2f - viewport / 2f).coerceAtLeast(0f)
        scroll.scrollTo(target.toInt())
    }

    // 拖拽选区自动滚动循环：指针在边缘区时持续滚动，并把选区焦点移到边缘所在文本位置
    // （固定端 = 开始拖拽时远离指针的那一端，锁在 scrollAnchor 里）。
    val currentValue = rememberUpdatedState(value)
    val edgeZonePx = with(density) { 30.dp.toPx() }
    val scrollDir = when {
        !dragActive || dragPointer == null -> 0
        dragPointer!!.y < textTopPx + edgeZonePx -> -1
        dragPointer!!.y > boxH - textTopPx - edgeZonePx -> 1
        else -> 0
    }
    LaunchedEffect(scrollDir, dragActive) {
        if (!dragActive || scrollDir == 0) return@LaunchedEffect
        while (true) {
            val p = dragPointer ?: break
            // 越靠边滚得越快：基础 8px/帧 + 越界量的一部分，上限 48px/帧
            val overshoot = if (scrollDir < 0) {
                (textTopPx + edgeZonePx - p.y).coerceAtLeast(0f)
            } else {
                (p.y - (boxH - textTopPx - edgeZonePx)).coerceAtLeast(0f)
            }
            scroll.dispatchRawDelta(scrollDir * (8f + overshoot * 0.6f).coerceAtMost(48f))
            val lay = textLayout ?: break
            // 指针 y → 文本排版坐标（加滚动偏移）；夹在可视区内，避免滚出后坐标失控
            val clampedY = p.y.coerceIn(textTopPx, (boxH - textTopPx).coerceAtLeast(textTopPx + 1f))
            val textY = (clampedY - textTopPx + scroll.value)
                .coerceIn(0f, lay.size.height.toFloat())
            val textX = (p.x - gutterWpx - textPadLPx).coerceIn(0f, textWpxInt.toFloat())
            val focusOff = lay.getOffsetForPosition(Offset(textX, textY))
            val curSel = currentValue.value.selection
            val anchor = scrollAnchor ?: (if (scrollDir < 0) curSel.max else curSel.min)
            scrollAnchor = anchor
            val ns = TextRange(minOf(anchor, focusOff), maxOf(anchor, focusOff))
            if (ns != curSel) onValueChange(currentValue.value.copy(selection = ns))
            kotlinx.coroutines.delay(16)
        }
    }

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
    val numStyle = TextStyle(fontFamily = gutterFontFamily, fontSize = gutterFontSize, color = gutterColor)
    val numCurStyle = TextStyle(
        fontFamily = gutterFontFamily, fontSize = gutterFontSize, color = gutterCurColor,
        fontWeight = FontWeight.Medium,
    )

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colors.surface)
                .border(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                .onSizeChanged { boxW = it.width; boxH = it.height }
                .pointerInput(Unit) {
                    // 旁路观察拖拽指针（Final pass：文本域已在本 pass 前处理完选区，仅读取不消费）
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Final)
                            when (e.type) {
                                PointerEventType.Press -> if (e.buttons.isPrimaryPressed) {
                                    dragActive = true
                                    scrollAnchor = null
                                    dragPointer = e.changes.firstOrNull()?.position
                                }
                                PointerEventType.Move -> if (dragActive) {
                                    if (e.buttons.isPrimaryPressed) {
                                        dragPointer = e.changes.firstOrNull()?.position
                                    } else {
                                        // 兜底：在窗口外松开时 Release 可能丢失，用无按键 Move 复位
                                        dragActive = false
                                        dragPointer = null
                                        scrollAnchor = null
                                    }
                                }
                                PointerEventType.Release -> {
                                    dragActive = false
                                    dragPointer = null
                                    scrollAnchor = null
                                }
                                else -> {}
                            }
                        }
                    }
                },
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
                        // Ctrl+Space：显式唤起补全（Esc 关闭后可重新呼出；空前缀也列出上下文列/表）
                        if (e.isCtrlPressed && e.key == Key.Spacebar) {
                            // 显式唤起：允许空前缀（列/表）；未敲字也行，顺便标记“正在编辑”并续期看门狗
                            editing = true
                            lastEdit = System.currentTimeMillis()
                            forceComplete = true
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
                                fontSize = editorStyle.fontSize,
                                lineHeight = editorStyle.lineHeight,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.35f),
                            )
                        }
                        innerTextField()
                    }
                },
            )
            }
            // 右侧纵向滚动条（文本区 end padding 已留 10dp，不会遮字）
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scroll),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(vertical = 8.dp, horizontal = 3.dp),
                style = dbScrollbarStyle(),
            )
            // 编辑状态提示（● = 有未落盘改动；Ctrl+S 立即保存）
            Text(
                if (dirty) "● 未保存 · Ctrl+S 保存" else "已保存到 .sql 文件",
                fontSize = 10.sp,
                color = if (dirty) MaterialTheme.colors.primary.copy(alpha = 0.75f)
                else MaterialTheme.colors.onSurface.copy(alpha = 0.3f),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 6.dp),
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

/** 补全候选弹层：主题化小面板，左侧类别色点 + 右侧详情（列类型/别名指向）；键盘选中高亮 + 鼠标点击上屏。 */
@Composable
private fun CompletionPopup(
    items: List<CompletionItem>,
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
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(completionKindColor(item.kind)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        item.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.9f),
                        modifier = Modifier.weight(1f),
                    )
                    if (item.detail != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            item.detail,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
                        )
                    }
                }
            }
        }
    }
}

/** 补全类别语义点色（只做色点，文字仍走主题色）。 */
private fun completionKindColor(kind: CompletionKind): Color = when (kind) {
    CompletionKind.COLUMN -> Color(0xFF5586E4)
    CompletionKind.ALIAS -> Color(0xFF9E9E9E)
    CompletionKind.FUNCTION -> Color(0xFF7E57C2)
    CompletionKind.TABLE -> Color(0xFF26A69A)
    CompletionKind.KEYWORD -> Color(0xFFFFB300)
    CompletionKind.EXPAND -> Color(0xFF43A047)
}

/**
 * 结果区顶部工具条（28dp）：左侧多语句结果 Tab，右侧状态文案 + 纯图标动作。
 * 无结果（也未执行）时调用方不渲染此条，中间只剩 5dp 可拖细线。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultToolbar(
    run: ConsoleRunUi,
    transposed: Boolean,
    exportEnabled: Boolean,
    enabled: Boolean,
    onSelectOutcome: (Int) -> Unit,
    onToggleTranspose: () -> Unit,
    onExportCsv: () -> Unit,
    onExportAllCsv: () -> Unit,
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
        // 状态：执行中 / 列×行·耗时（错误时结果区已居中红字，不重复）
        if (run.executing) {
            CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp)
            Text(" 执行中…", fontSize = 11.sp, color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f))
        } else if (run.error == null && result != null) {
            Text(
                metaText(result, transposed),
                fontSize = 11.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(4.dp))
        // 动作：纯图标 + 悬停 tooltip
        if (run.executing) {
            ResultIconButton(
                icon = DbIcons.Stop,
                description = "取消执行 (Esc)",
                enabled = enabled,
                danger = true,
                onClick = onCancelRun,
            )
        } else {
            if (canTranspose(run)) {
                ResultIconButton(
                    icon = DbIcons.Transpose,
                    description = if (transposed) "还原行列 (Ctrl+T)" else "转置行列 (Ctrl+T)",
                    active = transposed,
                    onClick = onToggleTranspose,
                )
            }
            ResultIconButton(
                icon = DbIcons.Download,
                description = "导出 CSV",
                enabled = exportEnabled,
                onClick = onExportCsv,
            )
            // 结果被截断时提供全量导出（重新执行 SQL，不受 1000 行上限）
            if (exportEnabled && result?.truncated == true) {
                ResultIconButton(
                    icon = DbIcons.Database,
                    description = "导出全量 CSV（重新执行，不受 1000 行上限）",
                    onClick = onExportAllCsv,
                )
            }
        }
    }
}

/** 多语句结果切换芯片：状态点 + 结果序号 + 行数/失败标记。 */
@Composable
private fun ResultChip(index: Int, outcome: StatementOutcome, active: Boolean, onClick: (Int) -> Unit) {
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
        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
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
            Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(15.dp))
        }
    }
}

/** 结果区占上下两块剩余高的比例下限/上限（拖动 clamp 用）。 */
private const val MIN_RESULT_FRAC = 0.15f
private const val MAX_RESULT_FRAC = 0.85f

/**
 * 编辑区/结果区分隔条：12dp 热区整条可上下拖动（悬停 N/S 双向箭头光标），
 * 中央一条 1dp 浅色线作视觉提示（悬停/拖动时加粗高亮）。拖动像素按内容区可用高换算成比例增量后由上层累加。
 */
@Composable
private fun ResultSplitter(
    paneHeightPx: Int,
    onDragDeltaPx: (Float) -> Unit,
) {
    // 固定开销：上下 padding 8+8、本条高 5 —— 不算入比例换算基数
    val chromePx = LocalDensity.current.run { 21.dp.toPx() }
    val resizeCursor = remember {
        PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.N_RESIZE_CURSOR))
    }
    var hovering by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(5.dp)
            .pointerHoverIcon(resizeCursor)
            // 悬停高亮：Enter/Exit 由独立 pointerInput 观察，不与拖动手势冲突
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        when (awaitPointerEvent().type) {
                            PointerEventType.Enter -> hovering = true
                            PointerEventType.Exit -> hovering = false
                            else -> Unit
                        }
                    }
                }
            }
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
                .fillMaxWidth()
                .height(if (hovering) 3.dp else 1.dp)
                .background(
                    if (hovering) MaterialTheme.colors.primary.copy(alpha = 0.7f)
                    else MaterialTheme.colors.onSurface.copy(alpha = 0.12f),
                ),
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

/** 结果表 / 编辑器共用的纵向滚动条样式（主题派生色，深色下可见）。 */
@Composable
private fun dbScrollbarStyle(): ScrollbarStyle = ScrollbarStyle(
    minimalHeight = 24.dp,
    thickness = 10.dp,
    shape = RoundedCornerShape(5.dp),
    hoverDurationMillis = 300,
    unhoverColor = MaterialTheme.colors.onSurface.copy(alpha = 0.20f),
    hoverColor = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
)

/** 结果区：顶部一条 28dp 工具条（多语句 Tab + 状态 + 图标动作）+ 结果表格；无结果时不显示工具条。 */
@Composable
private fun ResultTabs(
    run: ConsoleRunUi,
    transposed: Boolean,
    exportEnabled: Boolean,
    enabled: Boolean,
    onSelectOutcome: (Int) -> Unit,
    onToggleTranspose: () -> Unit,
    onExportCsv: () -> Unit,
    onExportAllCsv: () -> Unit,
    onCancelRun: () -> Unit,
    onCopyText: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (run.outcomes.isNotEmpty() || run.executing) {
            ResultToolbar(
                run = run,
                transposed = transposed,
                exportEnabled = exportEnabled,
                enabled = enabled,
                onSelectOutcome = onSelectOutcome,
                onToggleTranspose = onToggleTranspose,
                onExportCsv = onExportCsv,
                onExportAllCsv = onExportAllCsv,
                onCancelRun = onCancelRun,
            )
            Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.08f))
        }
        ResultPane(
            result = run.result,
            error = run.error,
            transposed = transposed,
            onCopyText = onCopyText,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
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
            else -> CenteredHint(
                if (result.affectedRows != null) "已更新 ${result.affectedRows} 行"
                else "语句执行成功（非查询，未产生结果集）",
                isError = false,
            )
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
/** 结果表内单元格坐标（行列均基于当前展示视图：转置后为转置坐标）。 */
private data class CellSel(val row: Int, val col: Int)

@Composable
private fun ResultTable(
    result: QueryResult,
    transposed: Boolean,
    onCopyText: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 单元格大段文本查看器（双击 / 右键「查看完整内容」）
    var viewer by remember { mutableStateOf<CellView?>(null) }
    val view = if (transposed) transposeResult(result) else result
    val cols = view.columns
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // 横向滚动用共享 ScrollState + horizontalScroll（表头与每一行都读同一偏移）——
    // 不能用多个 LazyRow 共享 LazyListState：虚拟化列表各自测量，滚动条驱动时只有
    // 最近测量那一个响应，会出现“只有内容滚、表头不动”的错位。
    val hScroll = rememberScrollState()
    val vScroll = rememberLazyListState()
    // 结果表格滚动条：列多/行多时可见可拖，横向条与表头/各行同步
    val scrollbarStyle = dbScrollbarStyle()
    // 列宽：默认按内容采样估算；用户拖表头分隔线可覆盖（列数/内容/布局变化时重建）。
    // 用轻量键避免每次重组合都对整表行做深比较（拖动时会高频重组合）。
    val baseWidths = remember(result.sql, result.columns, result.rows.size, transposed) {
        List(cols.size) { c ->
            var w = cols[c].name.length
            val sample = minOf(view.rows.size, 300)
            for (r in 0 until sample) {
                val len = view.rows[r][c]?.length ?: 5 // (NULL)
                if (len > w) w = len
            }
            estWidth(w)
        }
    }
    val widths = remember(result.sql, result.columns, result.rows.size, transposed) {
        mutableStateListOf<Int>().apply { addAll(baseWidths) }
    }
    // 单列结果 / 转置后只剩一个值列（列名 + 行 1）时，让该值列自适应吃掉右侧空白（有上限）。
    val widenCol = when {
        transposed && cols.size == 2 -> 1
        !transposed && cols.size == 1 -> 0
        else -> -1
    }
    // 用户手动拖过列宽后不再自动加宽（按列记忆）
    val manualCols = remember(result.sql, result.rows.size, transposed) {
        mutableStateListOf<Boolean>().apply { repeat(cols.size) { add(false) } }
    }
    var tableWidthPx by remember { mutableStateOf(0) }
    val scrollbarDp = scrollbarStyle.thickness.value
    val availDp = with(density) { tableWidthPx.toDp().value }
    LaunchedEffect(availDp, widenCol, result.sql, transposed) {
        if (widenCol in cols.indices && availDp > 0f && !manualCols[widenCol]) {
            val others = cols.indices.filter { it != widenCol }.sumOf { widths[it] }
            val slack = availDp - RESULT_GUTTER_DP - scrollbarDp - others
            widths[widenCol] = slack
                .coerceIn(baseWidths[widenCol].toFloat(), MAX_FLEX_COL_WIDTH.toFloat())
                .toInt()
        }
    }
    // 点选 / 方向键选中的单元格（换结果或转置即清空）
    val sel = remember(result.sql, result.rows.size, transposed) { mutableStateOf<CellSel?>(null) }
    val focusRequester = remember { FocusRequester() }
    val onMove = { dr: Int, dc: Int ->
        if (view.rows.isNotEmpty() && cols.isNotEmpty()) {
            val cur = sel.value ?: CellSel(0, 0)
            val nr = (cur.row + dr).coerceIn(0, view.rows.size - 1)
            val nc = (cur.col + dc).coerceIn(0, cols.size - 1)
            sel.value = CellSel(nr, nc)
            scope.launch {
                // 垂直：目标行不在可视区才滚（避免每次移动都跳回顶部）
                if (vScroll.layoutInfo.visibleItemsInfo.none { it.index == nr }) {
                    vScroll.animateScrollToItem(nr)
                }
                // 水平：把目标列滚进视口
                val x0 = with(density) { (RESULT_GUTTER_DP + widths.take(nc).sum()).dp.toPx() }
                val x1 = x0 + with(density) { widths[nc].dp.toPx() }
                val vp = hScroll.viewportSize
                when {
                    x0 < hScroll.value -> hScroll.animateScrollTo(x0.toInt())
                    x1 > hScroll.value + vp -> hScroll.animateScrollTo((x1 - vp).toInt())
                }
            }
        }
    }
    val onKey: (KeyEvent) -> Boolean = { e ->
        if (e.type != KeyEventType.KeyDown) {
            false
        } else {
            when {
                e.isCtrlPressed && e.key == Key.C -> {
                    sel.value?.let { s -> copyCellValue(onCopyText, view.rows[s.row][s.col], cols[s.col].name) }
                    true
                }
                e.key == Key.DirectionUp -> { onMove(-1, 0); true }
                e.key == Key.DirectionDown -> { onMove(1, 0); true }
                e.key == Key.DirectionLeft -> { onMove(0, -1); true }
                e.key == Key.DirectionRight -> { onMove(0, 1); true }
                else -> false
            }
        }
    }
    // 每行可生成的 INSERT（仅原布局；复杂查询/无法定表时 null）
    val tableName = extractTableName(result.sql)
    val insertSqls: List<String?> = if (transposed) view.rows.map { null }
    else result.rows.map { row -> rowToInsertSql(result.sql, result.columns.map { it.name }, row) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { tableWidthPx = it.width }
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent(onKey),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colors.onSurface.copy(alpha = 0.06f))
                .horizontalScroll(hScroll),
        ) {
            RowHeaderCell("", RESULT_GUTTER_DP)
            cols.forEachIndexed { c, col ->
                // 表头单元格 + 右缘拖拽把手（覆盖式，不占布局宽，保证与数据行水平对齐）
                Box {
                    RowHeaderCell(col.name, widths[c], highlighted = sel.value?.col == c)
                    ColumnResizeHandle(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        onDragStart = { manualCols[c] = true },
                        onDrag = { deltaDp ->
                            widths[c] = (widths[c] + deltaDp.toInt()).coerceIn(MIN_COL_WIDTH, MAX_COL_WIDTH)
                        },
                    )
                }
            }
        }
        Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.1f))
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // 右侧/底部预留滚动条厚度，避免遮挡最后一列与最后一行
            Column(modifier = Modifier.fillMaxSize().padding(end = scrollbarStyle.thickness, bottom = scrollbarStyle.thickness)) {
                LazyColumn(state = vScroll, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(view.rows) { index, row ->
                        val insertSql = insertSqls.getOrNull(index)
                        val rowSelected = sel.value?.row == index
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    when {
                                        rowSelected -> MaterialTheme.colors.primary.copy(alpha = 0.10f)
                                        index % 2 == 1 -> MaterialTheme.colors.onSurface.copy(alpha = 0.025f)
                                        else -> Color.Transparent
                                    },
                                )
                                .horizontalScroll(hScroll),
                        ) {
                            RowHeaderCell("${index + 1}", RESULT_GUTTER_DP, highlighted = rowSelected)
                            row.forEachIndexed { c, v ->
                                val colName = view.columns[c].name
                                val cellView = v?.let { CellView("$colName · 第 ${index + 1} 行", it) }
                                DataCell(
                                    value = v,
                                    width = widths[c],
                                    selected = sel.value == CellSel(index, c),
                                    onSelect = {
                                        sel.value = CellSel(index, c)
                                        focusRequester.requestFocus()
                                    },
                                    onDoubleClick = cellView?.let { cv -> { viewer = cv } },
                                    menuItems = buildList {
                                        add(
                                            ContextMenuItem("复制单元格值") {
                                                copyCellValue(onCopyText, v, colName)
                                            },
                                        )
                                        if (cellView != null) {
                                            add(ContextMenuItem("查看完整内容") { viewer = cellView })
                                        }
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
                            modifier = Modifier.padding(start = RESULT_GUTTER_DP.dp),
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
    viewer?.let { v ->
        TextViewerDialog(
            title = v.title,
            content = v.content,
            onDismiss = { viewer = null },
            onCopy = onCopyText,
            copyToast = "已复制单元格内容",
        )
    }
}

/** 单元格大段文本查看器请求（"列名 · 第 N 行" + 原文）。 */
private data class CellView(val title: String, val content: String)

/**
 * 结果表头的列宽拖拽把手：覆盖在表头单元格右缘（不占布局宽，避免与数据行错位）。
 * 水平拖动改列宽（dp），拖动中握把加粗高亮；鼠标悬停显示水平缩放光标。
 */
@Composable
private fun ColumnResizeHandle(
    onDrag: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onDragStart: () -> Unit = {},
) {
    val resizeCursor = remember {
        PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.E_RESIZE_CURSOR))
    }
    val density = LocalDensity.current
    // 手势协程只捕获一次 lambda，需经 rememberUpdatedState 拿最新值
    // （结果/列变化时 widths 会被重建，旧引用会写到已废弃的列表上）
    val currentOnDrag = rememberUpdatedState(onDrag)
    val currentOnDragStart = rememberUpdatedState(onDragStart)
    var active by remember { mutableStateOf(false) }
    // 子 dp 拖拽累积：hidpi 下单次事件可能不足 1dp，先攒够整 dp 再上报，避免小拖无反应
    var acc by remember { mutableStateOf(0f) }
    Box(
        modifier = modifier
            .width(9.dp)
            .height(30.dp)
            .pointerHoverIcon(resizeCursor)
            .pointerInput(density) {
                detectDragGestures(
                    onDragStart = {
                        active = true
                        acc = 0f
                        currentOnDragStart.value()
                    },
                    onDragEnd = { active = false },
                    onDragCancel = { active = false },
                ) { change, dragAmount ->
                    change.consume()
                    acc += dragAmount.x / density.density
                    val whole = acc.toInt()
                    if (whole != 0) {
                        currentOnDrag.value(whole.toFloat())
                        acc -= whole
                    }
                }
            },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(if (active) 2.dp else 1.dp)
                .background(
                    if (active) MaterialTheme.colors.primary
                    else MaterialTheme.colors.onSurface.copy(alpha = 0.18f),
                ),
        )
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
private fun RowHeaderCell(text: String, width: Int, highlighted: Boolean = false) {
    Box(
        modifier = Modifier
            .width(width.dp)
            .height(30.dp)
            .background(if (highlighted) MaterialTheme.colors.primary.copy(alpha = 0.12f) else Color.Transparent),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text,
            fontSize = 11.sp,
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.SemiBold,
            color = if (highlighted) MaterialTheme.colors.primary
            else MaterialTheme.colors.onSurface.copy(alpha = 0.75f),
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
    selected: Boolean = false,
    onSelect: () -> Unit = {},
    mono: Boolean = true,
    muted: Boolean = false,
    onDoubleClick: (() -> Unit)? = null,
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
    // 手势块只捕获一次 lambda：经 rememberUpdatedState 取最新回调，避免每次重组合重启手势
    val currentSelect = rememberUpdatedState(onSelect)
    val currentDoubleClick = rememberUpdatedState(onDoubleClick)
    val base = Modifier
        .width(width.dp)
        .height(26.dp)
        .background(if (selected) MaterialTheme.colors.primary.copy(alpha = 0.18f) else Color.Transparent)
        .then(
            if (selected) Modifier.border(1.dp, MaterialTheme.colors.primary.copy(alpha = 0.85f))
            else Modifier,
        )
        // 单击按下即选中（不等双击判定）；双击开大字段查看器（无内容时不动作）
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = { currentSelect.value() },
                onDoubleTap = { currentDoubleClick.value?.invoke() },
            )
        }
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
