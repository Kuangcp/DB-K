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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isShiftPressed
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
import androidx.compose.ui.text.style.TextDecoration
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
import app.settings.ShortcutCommand
import app.dialog.CellViewerDialog
import app.dialog.EditCellDialog
import app.dialog.TextViewerDialog
import com.neoutils.highlight.compose.remember.rememberHighlight
import com.neoutils.highlight.compose.remember.rememberTextFieldValue
import db.ConsoleRecord
import db.ConnectionProfile
import db.SqlHistoryRow
import engine.EditorLanguage
import engine.model.QueryResult
import engine.model.SchemaMeta
import jdbc.CellValue
import jdbc.QueryExecutor
import tree.ConnUiStatus
import tree.TypeBadge

/** 每个单元格最窄 64dp / 最宽 320dp（字符数 → dp 估算，12sp monospace 约 0.6em/字符）。 */
private fun estWidth(chars: Int): Int = (chars * 7 + 20).coerceIn(64, 320)

/** 手动拖动列宽的上下限（dp）。 */
private const val MIN_COL_WIDTH = 40
private const val MAX_COL_WIDTH = 1600

/** 结果表行号 gutter 宽（dp，表头与数据行共用）。 */
private const val RESULT_GUTTER_DP = 44

/** 结果网格单元格渲染预览上限：单元格本身可能很大（大到 1M 字符），直接交给 Compose 排版会拖垮内存。 */
private const val RESULT_CELL_PREVIEW_CHARS = 512

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
    /** consoleId → 未提交结果修改数（标签上提示）。 */
    pendingEditCounts: Map<String, Int> = emptyMap(),
    /** 激活控制台数据源的库/schema 列表（目标切换菜单；null = 未连接/未加载）。 */
    schemas: List<SchemaMeta>?,
    /** 数据源方言是否支持切换执行目标（SQLite 单文件不支持）。 */
    supportsTargetSwitch: Boolean,
    /** 执行目标切换器的标签（SQL = 「目标」；Redis DB = 「DB」）。 */
    targetLabel: String = "目标",
    /** 是否显示「默认（连接库）」选项（Redis DB 不需要）。 */
    targetAllowDefault: Boolean = true,
    /** 激活控制台已选执行目标库/schema（"" = 连接默认）。 */
    targetSchema: String,
    onSelectTarget: (String) -> Unit,
    editorText: String,
    editorDirty: Boolean,
    onTextChange: (String) -> Unit,
    /** 待插入编辑器的文本（预览 / 历史 SQL）：在激活控制台光标/选区处插入，不覆盖草稿。 */
    insertRequest: String?,
    /** 编辑器消费 insertRequest 后回调清空，保证同一文本可再次触发。 */
    onInsertRequestConsumed: () -> Unit,
    /** 请求向编辑器插入文本（历史面板「插入到当前控制台」）。 */
    onRequestInsert: (String) -> Unit,
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
    /** 提交结果单元格的未提交修改（参数化 UPDATE + 单事务）。 */
    onCommitEdits: () -> Unit = {},
    /** 丢弃当前结果的全部未提交修改。 */
    onClearAllEdits: () -> Unit = {},
    /** 刷新当前结果 Tab（重新执行该语句）。 */
    onRefreshResult: () -> Unit = {},
    /** 「取更多」当前是否可用（结果被截断且原查询不含分页子句）。 */
    canFetchMore: Boolean = false,
    /** 取更多：按方言注入分页，把新行追加到当前结果（不重跑原查询）。 */
    onFetchMore: () -> Unit = {},
    /** 未提交写操作（单元格修改 / 待插入行 / 待删除行），原始坐标）。 */
    resultEdits: ResultEdits = ResultEdits.EMPTY,
    /** 当前结果的可编辑计划（null = 只读：视图/无主键/表达式）。 */
    editPlan: EditPlan? = null,
    /** 有提交/刷新在执行中。 */
    resultBusy: Boolean = false,
    /** 暂存一格修改。 */
    onCellEdit: (CellKey, CellValue) -> Unit = { _, _ -> },
    /** 撤销一格修改。 */
    onClearCellEdit: (CellKey) -> Unit = {},
    /** 追加一个待插入行。 */
    onInsertRow: () -> Unit = {},
    /** 移除某个待插入行。 */
    onRemoveInsertRow: (Long) -> Unit = {},
    /** 填写待插入行的一列（结果列下标）。 */
    onInsertCellEdit: (Long, Int, CellValue) -> Unit = { _, _, _ -> },
    /** 清除待插入行一列的填写（恢复为未填，走库默认值）。 */
    onClearInsertCell: (Long, Int) -> Unit = { _, _ -> },
    /** 标记/撤销某原始行的待删除状态。 */
    onToggleRowDelete: (Int) -> Unit = {},
    onExport: () -> Unit,
    /** 取消当前执行（取消按钮 / Esc）。 */
    onCancelRun: () -> Unit,
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
    /** 是否启用 SQL 补全（非 SQL 后端如 Redis 关掉，避免弹 SQL 关键字）。 */
    completionEnabled: Boolean = true,
    /** 编辑器语言：决定高亮（SQL / JSON DSL / Redis 命令）。 */
    editorLanguage: EditorLanguage = EditorLanguage.SQL,
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
    // 当前快捷键表（业务命令可配置；基础编辑键固定）。读取一次，供根级 onPreviewKeyEvent 匹配。
    val keymap = LocalKeymap.current
    var showHistory by remember { mutableStateOf(false) }
    // 双击历史条目：弹窗查看完整 SQL（复用通用文本查看器，按 SQL 高亮）
    var historyView by remember { mutableStateOf<SqlHistoryRow?>(null) }
    // 打开面板或切换数据源时刷新历史列表
    LaunchedEffect(showHistory, profile?.id) {
        if (showHistory) onRefreshHistory()
    }
    // 转置视图：仅展示层翻转；新一次执行 / 切换控制台时复位为原布局
    var transposed by remember { mutableStateOf(false) }
    // N2：编辑器动作（格式化 / 查找替换）。findReplaceOpen 控制查找栏显隐；
    // formatRef 由下方控制台区块赋值（那里才能访问编辑器权威状态 tfv）。
    var findReplaceOpen by remember { mutableStateOf(false) }
    val formatRef = remember { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(run.executing) {
        if (run.executing) transposed = false
    }
    LaunchedEffect(activeConsole?.id) {
        transposed = false
        findReplaceOpen = false
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
                // 基础编辑键（固定）：保存当前控制台（取消防抖，立即异步落盘）
                if (keymap.matches(ShortcutCommand.SAVE_CONSOLE, e)) {
                    onSaveNow()
                    return@onPreviewKeyEvent true
                }
                // 基础编辑键（固定）：Ctrl+F / Ctrl+H 打开查找替换
                if (keymap.matches(ShortcutCommand.FIND_REPLACE, e) && activeConsole != null) {
                    findReplaceOpen = true
                    return@onPreviewKeyEvent true
                }
                // 业务功能：格式化（整段或选区）
                if (keymap.matches(ShortcutCommand.FORMAT_SQL, e) && activeConsole != null && formatRef.value != null) {
                    formatRef.value?.invoke()
                    return@onPreviewKeyEvent true
                }
                // 业务功能：行列转置（仅在有可转置结果时消费，避免与其它用途冲突）
                if (keymap.matches(ShortcutCommand.TRANSPOSE, e) && canTranspose(run)) {
                    transposed = !transposed
                    return@onPreviewKeyEvent true
                }
                // 业务功能：刷新当前结果 Tab（有未提交修改时由 Main 先弹确认）
                if (keymap.matches(ShortcutCommand.REFRESH_RESULT, e) && !run.executing && run.result != null) {
                    onRefreshResult()
                    return@onPreviewKeyEvent true
                }
                // 基础交互键（固定）：Esc 取消执行（无论焦点在编辑器还是别处，预览阶段优先拦截）
                if (keymap.matches(ShortcutCommand.CANCEL_RUN, e) && run.executing) {
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
            showEditorActions = activeConsole != null,
            findOpen = findReplaceOpen,
            onFormatSql = { formatRef.value?.invoke() },
            onOpenFindReplace = { findReplaceOpen = !findReplaceOpen },
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
                targetLabel = targetLabel,
                targetAllowDefault = targetAllowDefault,
                schemas = schemas,
                target = targetSchema,
                onSelectTarget = onSelectTarget,
            )
        }
        ConsoleTabBar(
            consoles = consoles,
            activeConsole = activeConsole,
            dirtyConsoleIds = dirtyConsoleIds,
            pendingEditCounts = pendingEditCounts,
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
        // 预览 / 历史 SQL 插入：在光标/选区处插入（有选区则替换之），不覆盖整段草稿；
        // 光标落到插入内容之后。在此处做是因为 tfv 是编辑器的权威状态。
        LaunchedEffect(insertRequest) {
            val snippet = insertRequest ?: return@LaunchedEffect
            onInsertRequestConsumed()
            val (newText, caret) = insertSnippetAtCaret(
                cur = tfv.text,
                selStart = tfv.selection.start,
                selEnd = tfv.selection.end,
                snippet = snippet,
            )
            tfv = TextFieldValue(newText, TextRange(caret))
            onCaretChange(consoleId, caret, caret)
            onTextChange(newText)
        }
        // N2：格式化 = 有选区只格式化选区，否则整段；只调空白/关键字大小写，语义不变。
        val doFormat: () -> Unit = {
            val fsel = tfv.selection
            val newText: String
            val caret: Int
            if (!fsel.collapsed) {
                val from = minOf(fsel.start, fsel.end).coerceIn(0, tfv.text.length)
                val to = maxOf(fsel.start, fsel.end).coerceIn(0, tfv.text.length)
                val formatted = formatSql(tfv.text.substring(from, to)).trimEnd('\n')
                newText = tfv.text.substring(0, from) + formatted + tfv.text.substring(to)
                caret = from
            } else {
                newText = formatSql(tfv.text).trimEnd('\n')
                caret = tfv.selection.start.coerceIn(0, newText.length)
            }
            tfv = TextFieldValue(newText, TextRange(caret))
            onCaretChange(consoleId, caret, caret)
            onTextChange(newText)
        }
        SideEffect { formatRef.value = doFormat }
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
                        completionEnabled = completionEnabled,
                        editorLanguage = editorLanguage,
                        columnCatalog = columnCatalog,
                        schemas = schemas.orEmpty(),
                        defaultSchema = schemas?.firstOrNull { it.displayName == targetSchema },
                        profile = profile,
                        editorSettings = editorSettings,
                        findOpen = findReplaceOpen,
                        onCloseFind = { findReplaceOpen = false },
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
                            onCommitEdits = onCommitEdits,
                            onClearAllEdits = onClearAllEdits,
                            onRefreshResult = onRefreshResult,
                            canFetchMore = canFetchMore,
                            onFetchMore = onFetchMore,
                            editCount = resultEdits.count,
                            canCommit = editPlan != null,
                            resultBusy = resultBusy,
                            resultEdits = resultEdits,
                            editPlan = editPlan,
                            canModifyRows = editPlan != null && !transposed,
                            onCellEdit = onCellEdit,
                            onClearCellEdit = onClearCellEdit,
                            onInsertRow = onInsertRow,
                            onRemoveInsertRow = onRemoveInsertRow,
                            onInsertCellEdit = onInsertCellEdit,
                            onClearInsertCell = onClearInsertCell,
                            onToggleRowDelete = onToggleRowDelete,
                            onExport = onExport,
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
            extraAction = if (activeConsole != null) {
                "插入到当前控制台" to {
                    onRequestInsert(row.sqlText)
                    historyView = null
                }
            } else null,
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

/**
 * 在 [cur] 的 [selStart]..[selEnd] 选区处插入 [snippet]（有选区则替换之，其余文本原样保留），
 * 返回新文本与插入内容之后的光标位置。仅在行尾且紧跟非空白字符时补一个换行，避免与上句粘连。
 * 历史 SQL「插入到当前控制台」用（注意：不覆盖整段草稿）。
 */
internal fun insertSnippetAtCaret(cur: String, selStart: Int, selEnd: Int, snippet: String): Pair<String, Int> {
    val from = minOf(selStart, selEnd).coerceIn(0, cur.length)
    val to = maxOf(selStart, selEnd).coerceIn(0, cur.length)
    // 仅在「行尾」插入且紧邻非空白字符时补一个换行，避免与上一句粘连；行中插入保持原样
    val prev = cur.getOrNull(from - 1)
    val next = cur.getOrNull(to)
    val atLineEnd = next == null || next == '\n'
    val lead = if (prev != null && !prev.isWhitespace() && atLineEnd) "\n" else ""
    val insert = lead + snippet
    return (cur.substring(0, from) + insert + cur.substring(to)) to (from + insert.length)
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
    /** 有激活控制台时显示编辑器动作（格式化 / 查找替换）。 */
    showEditorActions: Boolean = false,
    findOpen: Boolean = false,
    onFormatSql: () -> Unit = {},
    onOpenFindReplace: () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp),
    ) {
        if (showEditorActions) {
            IconButton(onClick = onFormatSql, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = DbIcons.Format,
                    contentDescription = "格式化 SQL（Ctrl+Alt+L）",
                    tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.size(17.dp),
                )
            }
            IconButton(onClick = onOpenFindReplace, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = DbIcons.FindReplace,
                    contentDescription = "查找替换（Ctrl+F / Ctrl+H）",
                    tint = if (findOpen) MaterialTheme.colors.primary
                    else MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.size(17.dp),
                )
            }
        }
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
    /** 执行目标切换器标签（SQL = 「目标」；Redis DB = 「DB」）。 */
    targetLabel: String,
    /** 是否显示「默认（连接库）」选项。 */
    targetAllowDefault: Boolean,
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
                title = targetLabel,
                allowDefault = targetAllowDefault,
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
    title: String,
    allowDefault: Boolean,
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
                title,
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
            if (allowDefault) {
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
    pendingEditCounts: Map<String, Int>,
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
                    pendingEdits = pendingEditCounts[c.id] ?: 0,
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
    pendingEdits: Int,
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
            if (pendingEdits > 0) {
                // 语义色：该控制台有未提交的结果修改（数量）
                Text(
                    "✦$pendingEdits",
                    color = Color(0xFFFFB300),
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
    completionEnabled: Boolean = true,
    /** 编辑器语言：决定语法高亮（SQL 关键字 / JSON DSL）。 */
    editorLanguage: EditorLanguage = EditorLanguage.SQL,
    columnCatalog: ColumnCatalog?,
    schemas: List<SchemaMeta>,
    defaultSchema: SchemaMeta?,
    profile: ConnectionProfile?,
    editorSettings: EditorSettings,
    /** N2：查找替换栏显隐（由 SqlWorkspace 控制）。 */
    findOpen: Boolean = false,
    onCloseFind: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 快捷键表：编辑器内的执行/补全用可配置键，其余为语义固定的编辑交互。
    val keymap = LocalKeymap.current
    val isDark = MaterialTheme.colors.isLight.not()
    val keywords = remember { sqlHighlightKeywords().distinct() }
    // isDark / editorLanguage 作 key：主题切换或后端变化时重建 Highlight，否则记住的旧色板不会刷新。
    val highlightedValue = rememberHighlight(isDark, editorLanguage) {
        when (editorLanguage) {
            EditorLanguage.JSON -> applyJsonHighlightRules(jsonSyntaxPalette(isDark))
            else -> applySqlHighlightRules(sqlSyntaxPalette(isDark), keywords)
        }
    }.rememberTextFieldValue(value)

    val scroll = rememberScrollState()

    // N2 查找替换的替换动作会写 editing（替换后不弹补全），故提前声明；补全活性判定见下。
    var editing by remember { mutableStateOf(false) }

    // ---- N2 查找替换：纯逻辑在 SqlFindReplace.kt，这里只管状态与交互 ----
    var findText by remember(consoleId) { mutableStateOf("") }
    var replaceText by remember(consoleId) { mutableStateOf("") }
    var useRegex by remember(consoleId) { mutableStateOf(false) }
    var caseSensitive by remember(consoleId) { mutableStateOf(false) }
    val findOptions = FindOptions(regex = useRegex, caseSensitive = caseSensitive)
    val findHits = remember(value.text, findText, useRegex, caseSensitive) {
        findMatches(value.text, findText, findOptions)
    }
    var activeMatch by remember { mutableStateOf(-1) }
    LaunchedEffect(findHits) {
        activeMatch = when {
            findHits.isEmpty() -> -1
            activeMatch < 0 -> -1
            activeMatch >= findHits.size -> findHits.size - 1
            else -> activeMatch
        }
    }
    var navTick by remember { mutableStateOf(0) }
    // 打开查找时若有选区，用选中文本预填（“选区查找”）；只取首行且限长
    LaunchedEffect(findOpen) {
        if (findOpen && findText.isEmpty()) {
            val s = value.selection
            if (!s.collapsed) {
                val from = minOf(s.start, s.end).coerceIn(0, value.text.length)
                val to = maxOf(s.start, s.end).coerceIn(0, value.text.length)
                findText = value.text.substring(from, to).lineSequence().firstOrNull().orEmpty().take(200)
            }
        }
    }

    fun selectHit(index: Int) {
        if (findHits.isEmpty()) return
        val idx = ((index % findHits.size) + findHits.size) % findHits.size
        activeMatch = idx
        val r = findHits[idx]
        onValueChange(value.copy(selection = TextRange(r.first, r.last + 1)))
        navTick++
    }

    fun replaceCurrentHit() {
        val r = findHits.getOrNull(activeMatch) ?: return
        val caret = r.first + replaceText.length
        editing = false
        onValueChange(value.copy(text = replaceRange(value.text, r, replaceText), selection = TextRange(caret)))
    }

    fun replaceAllHits() {
        val (newText, count) = replaceAll(value.text, findText, findOptions, replaceText)
        if (count == 0) return
        val caret = value.selection.start.coerceIn(0, newText.length)
        editing = false
        onValueChange(TextFieldValue(newText, TextRange(caret)))
    }
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
    // JSON 模式（Elasticsearch DSL）：走 [esDslSuggestions]，不走 SQL 词法/补全
    val jsonMode = editorLanguage == EditorLanguage.JSON
    val qualified = if (canComplete && !jsonMode) sqlQualifiedPrefix(value.text, sel.start) else null
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
    val sqlWord: CompletionWord? = when {
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
        sqlWord != null -> completionItems(
            word = sqlWord.text,
            columns = columnItems,
            aliases = aliasItems,
            functions = functionItems,
            objects = objectItems,
            includeKeywords = completionEnabled && qualified == null && (!forceComplete || sqlWord.text.isNotEmpty()),
        )
        else -> emptyList()
    }
    val sqlShown = candidates.take(MAX_COMPLETIONS)

    // ---- ES JSON DSL 补全：字段来自目标索引 `_mapping`（异步预取，未命中先出键/枚举）----
    val dslIndex = if (jsonMode) {
        esDslIndexName(value.text) ?: profile?.database?.trim()?.takeIf { it.isNotEmpty() }
    } else null
    val dslSchema = defaultSchema ?: schemas.firstOrNull()
    val dslFields = if (jsonMode && dslIndex != null && profile != null) {
        columnCatalog?.peek(profile.id, dslSchema, dslIndex)?.map { it.name }.orEmpty()
    } else emptyList()
    val dslSuggestion = if (jsonMode && canComplete) {
        esDslSuggestions(value.text, sel.start, dslFields, completionIdentifiers)
    } else null
    // 空前缀只在显式 Ctrl+Space 时列上下文（与 SQL 一致，避免回车被弹层截走）
    val dslWord = dslSuggestion?.word?.takeIf { forceComplete || it.text.isNotEmpty() }
    val word: CompletionWord? = if (jsonMode) dslWord else sqlWord
    val shown: List<CompletionItem> =
        if (jsonMode) (if (dslWord != null) dslSuggestion.items else emptyList()) else sqlShown
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
        if (jsonMode) return@LaunchedEffect
        val p = profile ?: return@LaunchedEffect
        val catalog = columnCatalog ?: return@LaunchedEffect
        if (fetchRefs.isEmpty()) return@LaunchedEffect
        catalog.ensure(p, fetchRefs.map { (r, s) -> ColumnCatalog.ColumnRef(s, r.table) })
    }

    // ES：DSL 里的目标索引（或默认索引）确定后预取其 `_mapping` 字段；防抖 + 只认已存在的索引，
    // 避免边敲索引名边打一堆映射请求。
    LaunchedEffect(jsonMode, consoleId, profile?.id, dslIndex) {
        if (!jsonMode) return@LaunchedEffect
        val p = profile ?: return@LaunchedEffect
        val catalog = columnCatalog ?: return@LaunchedEffect
        val idx = dslIndex ?: return@LaunchedEffect
        val known = completionIdentifiers.any { it.equals(idx, ignoreCase = true) } ||
            idx == p.database.trim()
        if (!known) return@LaunchedEffect
        kotlinx.coroutines.delay(300)
        catalog.ensure(p, listOf(ColumnCatalog.ColumnRef(dslSchema, idx)))
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

    // N2 查找导航：把当前命中行滚到视口中间（navTick 每次“下一个/上一个”自增触发）
    LaunchedEffect(navTick) {
        if (navTick == 0) return@LaunchedEffect
        val lay = textLayout ?: return@LaunchedEffect
        val off = value.selection.start.coerceIn(0, content.length)
        val top = textTopPx + lay.getLineTop(lay.getLineForOffset(off))
        val viewport = (boxH - 2f * textTopPx).coerceAtLeast(lineHpx)
        val target = (top + lineHpx / 2f - viewport / 2f).coerceAtLeast(0f)
        scroll.scrollTo(target.toInt())
    }

    // 拖拽选区自动滚动循环：指针在边缘区时持续滚动，并把选区焦点移到边缘所在文本位置
    // （固定端 = 开始拖拽时远离指针的那一端，锁在 scrollAnchor 里）。
    val currentValue = rememberUpdatedState(value)
    // 指针坐标 → 文本 offset（含滚动偏移；夹在排版范围内，避免指针拖到窗口外时坐标失控）。
    // 做成随组合更新的 lambda，供「边缘自动滚动循环」与「指针旁路」共用同一套换算。
    val offsetAtPointer = rememberUpdatedState<(Offset) -> Int?>(
        { p ->
            val lay = textLayout
            if (lay == null) {
                null
            } else {
                val clampedY = p.y.coerceIn(textTopPx, (boxH - textTopPx).coerceAtLeast(textTopPx + 1f))
                val textY = (clampedY - textTopPx + scroll.value).coerceIn(0f, lay.size.height.toFloat())
                val textX = (p.x - gutterWpx - textPadLPx).coerceIn(0f, textWpxInt.toFloat())
                lay.getOffsetForPosition(Offset(textX, textY))
            }
        },
    )
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
            val focusOff = offsetAtPointer.value(p) ?: break
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
                                        val pos = e.changes.firstOrNull()?.position
                                        dragPointer = pos
                                        // 一旦自动滚动接管过选区（scrollAnchor 已锁定），后续拖拽
                                        // 也由我们用同一锚点驱动：否则 BasicTextField 内部锚点会被
                                        // 之前的合成选区带偏，反向拖动时把「刚才的边」当成起始行。
                                        val anchor = scrollAnchor
                                        if (pos != null && anchor != null) {
                                            offsetAtPointer.value(pos)?.let { focusOff ->
                                                val curSel = currentValue.value.selection
                                                val ns = TextRange(
                                                    minOf(anchor, focusOff),
                                                    maxOf(anchor, focusOff),
                                                )
                                                if (ns != curSel) {
                                                    onValueChange(currentValue.value.copy(selection = ns))
                                                }
                                            }
                                        }
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
                        if (keymap.matches(ShortcutCommand.EXECUTE, e)) {
                            // 选中 SQL 才执行；无选中什么都不做（禁止整段执行）
                            onCtrlEnter()
                            return@onPreviewKeyEvent true
                        }
                        // 基础编辑键（固定）：Ctrl+Space 显式唤起补全（Esc 关闭后可重新呼出；空前缀也列出上下文列/表）
                        if (keymap.matches(ShortcutCommand.COMPLETE, e)) {
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
                                if (jsonMode) {
                                    "输入 ES JSON DSL，如 {\"index\":\"my-index\",\"query\":{\"match_all\":{}}}\nCtrl+Space 补全键/查询类型/字段名"
                                } else {
                                    "输入 SQL…\n选中要执行的语句后 Ctrl+Enter（无选中不执行）"
                                },
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
        // N2 查找替换栏：浮在编辑器右上（不占正文空间）
        if (findOpen) {
            FindReplaceBar(
                findText = findText,
                onFindTextChange = { findText = it },
                replaceText = replaceText,
                onReplaceTextChange = { replaceText = it },
                regex = useRegex,
                onRegexChange = { useRegex = it },
                caseSensitive = caseSensitive,
                onCaseSensitiveChange = { caseSensitive = it },
                matchCount = findHits.size,
                activeIndex = activeMatch,
                onPrev = { selectHit(if (activeMatch < 0) findHits.size - 1 else activeMatch - 1) },
                onNext = { selectHit(if (activeMatch < 0) 0 else activeMatch + 1) },
                onReplace = { replaceCurrentHit() },
                onReplaceAll = { replaceAllHits() },
                onClose = onCloseFind,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 14.dp),
            )
        }
    }
}

/**
 * N2 查找替换栏：浮在编辑器右上的紧凑面板。
 * 查找框 Enter=下一个、Shift+Enter=上一个、Esc=关闭；`Aa`=区分大小写、`.*`=正则。
 */
@Composable
private fun FindReplaceBar(
    findText: String,
    onFindTextChange: (String) -> Unit,
    replaceText: String,
    onReplaceTextChange: (String) -> Unit,
    regex: Boolean,
    onRegexChange: (Boolean) -> Unit,
    caseSensitive: Boolean,
    onCaseSensitiveChange: (Boolean) -> Unit,
    matchCount: Int,
    activeIndex: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val findFocus = remember { FocusRequester() }
    // 打开即聚焦查找框（面板仅 findOpen 时进入组合，故每次打开都会重新聚焦）
    LaunchedEffect(Unit) { runCatching { findFocus.requestFocus() } }
    val iconTint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
    Column(
        modifier = modifier
            .width(432.dp)
            .shadow(8.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colors.surface)
            .border(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FindInputField(
                value = findText,
                onValueChange = onFindTextChange,
                placeholder = "查找",
                focus = findFocus,
                onKey = { e ->
                    when {
                        e.key == Key.Enter -> {
                            if (e.isShiftPressed) onPrev() else onNext()
                            true
                        }
                        e.key == Key.Escape -> { onClose(); true }
                        else -> false
                    }
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            FindToggleChip("Aa", caseSensitive) { onCaseSensitiveChange(!caseSensitive) }
            Spacer(Modifier.width(4.dp))
            FindToggleChip(".*", regex) { onRegexChange(!regex) }
            Spacer(Modifier.width(6.dp))
            Text(
                if (matchCount == 0) "0/0" else "${activeIndex + 1}/$matchCount",
                fontSize = 11.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.widthIn(min = 34.dp),
            )
            IconButton(onClick = onPrev, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Filled.KeyboardArrowUp, "上一个", tint = iconTint, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onNext, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Filled.KeyboardArrowDown, "下一个", tint = iconTint, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onClose, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Filled.Close, "关闭查找", tint = iconTint, modifier = Modifier.size(16.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FindInputField(
                value = replaceText,
                onValueChange = onReplaceTextChange,
                placeholder = "替换为",
                focus = null,
                onKey = null,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            FindSmallButton("替换") { onReplace() }
            Spacer(Modifier.width(4.dp))
            FindSmallButton("全部替换") { onReplaceAll() }
        }
    }
}

@Composable
private fun FindInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    focus: FocusRequester?,
    onKey: ((KeyEvent) -> Boolean)?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(26.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colors.background)
            .border(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.22f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colors.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colors.primary),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
                .then(
                    if (onKey != null) {
                        Modifier.onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown) onKey(e) else false
                        }
                    } else {
                        Modifier
                    },
                ),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            fontSize = 12.sp,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.35f),
                        )
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
private fun FindToggleChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(28.dp)
            .height(26.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) MaterialTheme.colors.primary.copy(alpha = 0.22f) else Color.Transparent)
            .border(
                1.dp,
                if (active) MaterialTheme.colors.primary.copy(alpha = 0.55f)
                else MaterialTheme.colors.onSurface.copy(alpha = 0.2f),
                RoundedCornerShape(4.dp),
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.onSurface.copy(alpha = if (active) 0.95f else 0.55f),
        )
    }
}

@Composable
private fun FindSmallButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(26.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colors.primary.copy(alpha = 0.12f))
            .clickable { onClick() }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colors.onSurface.copy(alpha = 0.85f))
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
        // 状态：执行中 / 列×行·耗时（错误时结果区已居中红字，不重复）
        if (run.executing) {
            CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp)
            Text(" 执行中…", fontSize = 11.sp, color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f))
        } else if (run.error == null && result != null) {
            Text(
                if (editCount > 0) "${metaText(result, transposed)} · $editCount 处未提交"
                else metaText(result, transposed),
                fontSize = 11.sp,
                color = if (editCount > 0) MaterialTheme.colors.primary
                else MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
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
            // 结果编辑：撤销全部 / 提交（有未提交修改时高亮 + 徽标）/ 刷新当前 Tab
            if (editCount > 0) {
                ResultIconButton(
                    icon = DbIcons.Undo,
                    description = "撤销全部 $editCount 处修改",
                    enabled = !resultBusy,
                    onClick = onClearAllEdits,
                )
            }
            ResultIconButton(
                icon = DbIcons.Commit,
                description = if (editCount > 0) "提交 $editCount 处修改" else "提交修改",
                enabled = canCommit && editCount > 0 && !resultBusy,
                active = editCount > 0,
                badgeCount = editCount.takeIf { it > 0 },
                onClick = onCommitEdits,
            )
            ResultIconButton(
                icon = DbIcons.Refresh,
                description = "刷新查询结果 (F5)",
                enabled = result != null && !resultBusy,
                onClick = onRefreshResult,
            )
            // 行级写操作（N3）：插入行 / 标记删除选中行（无主键/视图/转置时禁用）
            if (canModifyRows) {
                ResultIconButton(
                    icon = DbIcons.AddRow,
                    description = "插入行（提交时写入）",
                    enabled = !resultBusy,
                    onClick = onInsertRow,
                )
                ResultIconButton(
                    icon = DbIcons.DeleteRow,
                    description = when {
                        selectedRow == null -> "删除行（请先选中一行）"
                        selectedRowDeleted -> "撤销删除该行"
                        else -> "标记删除选中行（提交时从库删除）"
                    },
                    enabled = !resultBusy && selectedRow != null,
                    active = selectedRowDeleted,
                    onClick = onToggleRowDelete,
                )
            }
            if (canTranspose(run)) {
                ResultIconButton(
                    icon = DbIcons.Transpose,
                    description = if (transposed) "还原行列 (Ctrl+T)" else "转置行列 (Ctrl+T)",
                    active = transposed,
                    onClick = onToggleTranspose,
                )
            }
            if (canFetchMore) {
                ResultIconButton(
                    icon = DbIcons.FetchMore,
                    description = "取更多（按方言分页追加下一页）",
                    enabled = !resultBusy,
                    active = true,
                    onClick = onFetchMore,
                )
            }
            ResultIconButton(
                icon = DbIcons.Download,
                description = "导出结果（CSV / JSON / SQL INSERT / Excel）",
                enabled = exportEnabled,
                onClick = onExport,
            )
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
    onCommitEdits: () -> Unit,
    onClearAllEdits: () -> Unit,
    onRefreshResult: () -> Unit,
    canFetchMore: Boolean,
    onFetchMore: () -> Unit,
    editCount: Int,
    canCommit: Boolean,
    resultBusy: Boolean,
    resultEdits: ResultEdits,
    editPlan: EditPlan?,
    canModifyRows: Boolean,
    onCellEdit: (CellKey, CellValue) -> Unit,
    onClearCellEdit: (CellKey) -> Unit,
    onInsertRow: () -> Unit,
    onRemoveInsertRow: (Long) -> Unit,
    onInsertCellEdit: (Long, Int, CellValue) -> Unit,
    onClearInsertCell: (Long, Int) -> Unit,
    onToggleRowDelete: (Int) -> Unit,
    onExport: () -> Unit,
    onCancelRun: () -> Unit,
    onCopyText: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 结果网格当前选中的原始行（行级操作按钮据此定位）；结果 / Tab 变化时清空
    var selectedRow by remember(run.activeIndex, run.result?.sql, run.result?.rows?.size) {
        mutableStateOf<Int?>(null)
    }
    Column(modifier = modifier) {
        if (run.outcomes.isNotEmpty() || run.executing) {
            ResultToolbar(
                run = run,
                transposed = transposed,
                exportEnabled = exportEnabled,
                enabled = enabled,
                onSelectOutcome = onSelectOutcome,
                onToggleTranspose = onToggleTranspose,
                onCommitEdits = onCommitEdits,
                onClearAllEdits = onClearAllEdits,
                onRefreshResult = onRefreshResult,
                canFetchMore = canFetchMore,
                onFetchMore = onFetchMore,
                editCount = editCount,
                canCommit = canCommit,
                resultBusy = resultBusy,
                canModifyRows = canModifyRows,
                selectedRow = selectedRow,
                selectedRowDeleted = selectedRow != null && selectedRow in resultEdits.deletes,
                onInsertRow = onInsertRow,
                onToggleRowDelete = { selectedRow?.let(onToggleRowDelete) },
                onExport = onExport,
                onCancelRun = onCancelRun,
            )
            Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.08f))
        }
        ResultPane(
            result = run.result,
            error = run.error,
            transposed = transposed,
            resultEdits = resultEdits,
            editPlan = editPlan,
            canModifyRows = canModifyRows,
            selectedRow = selectedRow,
            onSelectedRowChange = { selectedRow = it },
            onCellEdit = onCellEdit,
            onClearCellEdit = onClearCellEdit,
            onInsertRow = onInsertRow,
            onRemoveInsertRow = onRemoveInsertRow,
            onInsertCellEdit = onInsertCellEdit,
            onClearInsertCell = onClearInsertCell,
            onToggleRowDelete = onToggleRowDelete,
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
    resultEdits: ResultEdits,
    editPlan: EditPlan?,
    canModifyRows: Boolean,
    selectedRow: Int?,
    onSelectedRowChange: (Int?) -> Unit,
    onCellEdit: (CellKey, CellValue) -> Unit,
    onClearCellEdit: (CellKey) -> Unit,
    onInsertRow: () -> Unit,
    onRemoveInsertRow: (Long) -> Unit,
    onInsertCellEdit: (Long, Int, CellValue) -> Unit,
    onClearInsertCell: (Long, Int) -> Unit,
    onToggleRowDelete: (Int) -> Unit,
    onCopyText: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colors.surface),
    ) {
        // 客户端排序/筛选视图（N1）：随结果 SQL 重置，不重跑 SQL
        var viewSpec by remember(result?.sql) { mutableStateOf(ResultViewSpec()) }
        when {
            error != null -> CenteredHint(error, isError = true)
            result == null -> CenteredHint("执行 SELECT 后在此查看结果表格；可导出 CSV / JSON / SQL / Excel", isError = false)
            result.isQuery && result.rowCount == 0 && resultEdits.inserts.isEmpty() ->
                CenteredHint("查询完成：0 行", isError = false)
            result.isQuery -> Column(modifier = Modifier.fillMaxSize()) {
                if (result.truncated) TruncationBanner()
                ResultTable(
                    result = result,
                    transposed = transposed,
                    resultEdits = resultEdits,
                    editPlan = editPlan,
                    canModifyRows = canModifyRows,
                    selectedRow = selectedRow,
                    onSelectedRowChange = onSelectedRowChange,
                    viewSpec = viewSpec,
                    onViewSpecChange = { viewSpec = it },
                    onCellEdit = onCellEdit,
                    onClearCellEdit = onClearCellEdit,
                    onInsertRow = onInsertRow,
                    onRemoveInsertRow = onRemoveInsertRow,
                    onInsertCellEdit = onInsertCellEdit,
                    onClearInsertCell = onClearInsertCell,
                    onToggleRowDelete = onToggleRowDelete,
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
            "结果已截断（达到 ${QueryExecutor.MAX_ROWS} 行上限或单次结果内存上限）。可用工具栏「取更多」继续追加，" +
                "或用「导出」选「全量流式」重新执行并导出全部行。",
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

/** 行内编辑目标：已有结果格 / 待插入行的一格。 */
private sealed interface EditTarget {
    data class Cell(val row: Int, val col: Int) : EditTarget
    data class Insert(val id: Long, val col: Int) : EditTarget
}

/** 对话框编辑请求（长值/多行/NULL）：定位 + 标题 + 初始值。 */
private sealed interface EditRequest {
    val title: String
    val value: String?

    data class Cell(val key: CellKey, override val title: String, override val value: String?) : EditRequest
    data class Insert(val id: Long, val col: Int, override val title: String, override val value: String?) : EditRequest
}

@Composable
private fun ResultTable(
    result: QueryResult,
    transposed: Boolean,
    resultEdits: ResultEdits,
    editPlan: EditPlan?,
    canModifyRows: Boolean,
    selectedRow: Int?,
    onSelectedRowChange: (Int?) -> Unit,
    viewSpec: ResultViewSpec,
    onViewSpecChange: (ResultViewSpec) -> Unit,
    onCellEdit: (CellKey, CellValue) -> Unit,
    onClearCellEdit: (CellKey) -> Unit,
    onInsertRow: () -> Unit,
    onRemoveInsertRow: (Long) -> Unit,
    onInsertCellEdit: (Long, Int, CellValue) -> Unit,
    onClearInsertCell: (Long, Int) -> Unit,
    onToggleRowDelete: (Int) -> Unit,
    onCopyText: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 当前快捷键表：结果网格的复制单元格走注册表（基础键，固定），方向键等仍为模态导航。
    val keymap = LocalKeymap.current
    // 单元格大段文本查看器（双击 / 右键「查看完整内容」）
    var viewer by remember { mutableStateOf<CellView?>(null) }
    // Ctrl 按下状态（结果表内跟踪）：Ctrl+双击 = 进入编辑；普通双击 = 查看
    var ctrlDown by remember { mutableStateOf(false) }
    // 正在行内编辑的目标 + 草稿
    var editing by remember(result.sql, result.rows.size, transposed) { mutableStateOf<EditTarget?>(null) }
    var editDraft by remember { mutableStateOf("") }
    // 长值/多行/NULL → 对话框编辑
    var dialogEdit by remember { mutableStateOf<EditRequest?>(null) }
    // 暂存修改叠到展示值上（仅值变化，尺寸不变）
    val edited = remember(result, resultEdits.cells) { applyEdits(result, resultEdits.cells) }
    // 视图层（N1）：排序/筛选只改行顺序与可见行，不重跑 SQL；rowView 保留回原始行的映射
    val rowView = remember(edited, viewSpec) { buildResultView(edited, viewSpec) }
    val displayedRows = remember(edited, rowView) { rowView.rowOrder.map { edited.rows[it] } }
    val view = if (transposed) transposeResult(edited.copy(rows = displayedRows)) else edited.copy(rows = displayedRows)
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
    // 列宽：默认按内容采样估算；用户拖表头分隔线可覆盖。
    // 非转置：列数不随排序/筛选变化，列宽保持稳定；转置：列数 = 展示行数 + 1，故随筛选重建。
    val transposedRows = if (transposed) view.rows.size else 0
    val baseWidths = remember(result.sql, result.columns, transposed, transposedRows) {
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
    val widths = remember(result.sql, result.columns, transposed, transposedRows) {
        mutableStateListOf<Int>().apply { addAll(baseWidths) }
    }
    // 单列结果 / 转置后只剩一个值列（列名 + 行 1）时，让该值列自适应吃掉右侧空白（有上限）。
    val widenCol = when {
        transposed && cols.size == 2 -> 1
        !transposed && cols.size == 1 -> 0
        else -> -1
    }
    // 用户手动拖过列宽后不再自动加宽（按列记忆）
    val manualCols = remember(result.sql, result.columns, transposed, transposedRows) {
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
    fun cellText(r: Int, c: Int): String? = view.rows.getOrNull(r)?.getOrNull(c)

    fun editableAt(r: Int, c: Int): Boolean {
        val plan = editPlan ?: return false
        val orig = displayToOriginal(rowView.rowOrder, result.columns.size, transposed, r, c) ?: return false
        if (orig.row in resultEdits.deletes) return false // 待删除行不可再改格
        if (plan.columnAt(orig.col) == null) return false
        // 展示值被截断的单元格禁止编辑（否则会把截断内容写回库）
        return !QueryExecutor.isTruncatedCell(result.rows.getOrNull(orig.row)?.getOrNull(orig.col))
    }

    fun beginEdit(r: Int, c: Int) {
        if (!editableAt(r, c)) return
        editDraft = cellText(r, c).orEmpty()
        sel.value = CellSel(r, c)
        editing = EditTarget.Cell(r, c)
    }

    /** 待插入行一格：无历史值可查看，双击/右键直接进入编辑。 */
    fun beginInsertEdit(id: Long, c: Int) {
        if (editPlan?.columnAt(c) == null) return
        val ins = resultEdits.inserts.firstOrNull { it.id == id } ?: return
        editDraft = ins.values[c]?.raw.orEmpty()
        editing = EditTarget.Insert(id, c)
    }

    fun commitCellDraft(row: Int, c: Int) {
        val orig = displayToOriginal(rowView.rowOrder, result.columns.size, transposed, row, c) ?: return
        val text = editDraft
        val shown = cellText(row, c)
        // 与当前显示值一致（含 NULL/空串语义）→ 不产生修改
        if (text == shown.orEmpty() && !(text.isEmpty() && shown == null)) return
        val original = result.rows.getOrNull(orig.row)?.getOrNull(orig.col)
        // 改回原始值 → 撤销暂存
        if (text == original.orEmpty() && !(text.isEmpty() && original == null)) {
            onClearCellEdit(orig)
            return
        }
        onCellEdit(orig, CellValue(text))
    }

    fun commitInsertDraft(id: Long, c: Int) {
        val ins = resultEdits.inserts.firstOrNull { it.id == id } ?: return
        if (editPlan?.columnAt(c) == null) return
        // 未填过的格子提交空串 → 保持未填（走库默认值）；已填过则可清成空串
        if (editDraft.isEmpty() && c !in ins.values) return
        onInsertCellEdit(id, c, CellValue(editDraft))
    }

    fun commitEditDraft() {
        val e = editing ?: return
        editing = null
        when (e) {
            is EditTarget.Cell -> commitCellDraft(e.row, e.col)
            is EditTarget.Insert -> commitInsertDraft(e.id, e.col)
        }
    }

    fun cancelEditDraft() {
        editing = null
    }

    // 把当前选中行（原始坐标）上报给工具条，供行级操作（删除/撤销）定位
    val selSnapshot = sel.value
    LaunchedEffect(selSnapshot, transposed, rowView.rowOrder) {
        onSelectedRowChange(
            selSnapshot?.let {
                displayToOriginal(rowView.rowOrder, result.columns.size, transposed, it.row, it.col)?.row
            },
        )
    }

    val onKey: (KeyEvent) -> Boolean = { e ->
        if (e.key == Key.CtrlLeft || e.key == Key.CtrlRight) {
            ctrlDown = e.type == KeyEventType.KeyDown
            false
        } else if (e.type != KeyEventType.KeyDown) {
            false
        } else {
            when {
                editing != null && e.key == Key.Escape -> { cancelEditDraft(); true }
                // 编辑中：其余按键（含方向键/Ctrl+C）交给文本框，不要劫持光标移动与复制
                editing != null -> false
                keymap.matches(ShortcutCommand.COPY_CELL, e) -> {
                    sel.value
                        ?.takeIf { it.row in view.rows.indices && it.col in cols.indices }
                        ?.let { s -> copyCellValue(onCopyText, view.rows[s.row][s.col], cols[s.col].name) }
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
    // 每行可生成的 INSERT（仅原布局；复杂查询/无法定表时 null）。
    // 不预生成 SQL：大字段下逐行拼 INSERT 会翻倍内存；点击时按需生成。
    val tableName = extractTableName(result.sql)
    val canInsertRow = !transposed && tableName != null
    // 含截断单元格的原始行：不能据此复制 INSERT（否则会把截断内容写回库）
    val truncatedRows: Set<Int> = buildSet {
        result.rows.forEachIndexed { idx, r -> if (r.any { QueryExecutor.isTruncatedCell(it) }) add(idx) }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { tableWidthPx = it.width }
            .focusRequester(focusRequester)
            .focusable()
            .onFocusChanged { if (!it.isFocused) ctrlDown = false }
            .onPreviewKeyEvent(onKey),
    ) {
        ResultFilterBar(
            spec = viewSpec,
            onSpecChange = onViewSpecChange,
            shownRows = rowView.rowCount,
            totalRows = rowView.totalRows,
        )
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
                    ResultHeaderCell(
                        name = col.name,
                        width = widths[c],
                        highlighted = sel.value?.col == c,
                        sort = viewSpec.sorts.firstOrNull { it.column == c },
                        sortRank = viewSpec.sorts.indexOfFirst { it.column == c }.takeIf { it > 0 },
                        filterText = viewSpec.filters[c],
                        onToggleSort = {
                            onViewSpecChange(viewSpec.toggleSort(c))
                        },
                        onFilterChange = { text ->
                            val next = viewSpec.filters.toMutableMap()
                            if (text.isEmpty()) next.remove(c) else next[c] = text
                            onViewSpecChange(viewSpec.copy(filters = next))
                        },
                    )
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
                        val rowSelected = sel.value?.row == index
                        val origRow = rowView.rowOrder.getOrNull(index)
                        val deleted = origRow != null && origRow in resultEdits.deletes
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    when {
                                        deleted -> Color(0xFFE53935).copy(alpha = 0.08f)
                                        rowSelected -> MaterialTheme.colors.primary.copy(alpha = 0.10f)
                                        index % 2 == 1 -> MaterialTheme.colors.onSurface.copy(alpha = 0.025f)
                                        else -> Color.Transparent
                                    },
                                )
                                .horizontalScroll(hScroll),
                        ) {
                            RowHeaderCell(
                                if (deleted) "✕" else "${index + 1}",
                                RESULT_GUTTER_DP,
                                highlighted = rowSelected,
                            )
                            row.forEachIndexed { c, v ->
                                val colName = view.columns[c].name
                                val cellView = v?.let { CellView("$colName · 第 ${index + 1} 行", it) }
                                val origKey = displayToOriginal(rowView.rowOrder, result.columns.size, transposed, index, c)
                                val isEditable = editableAt(index, c)
                                val pending = origKey != null && resultEdits.cells.containsKey(origKey)
                                val isEditing = editing == EditTarget.Cell(index, c)
                                DataCell(
                                    value = v,
                                    width = widths[c],
                                    selected = sel.value == CellSel(index, c),
                                    pending = pending,
                                    deleted = deleted,
                                    onSelect = {
                                        val cur = editing
                                        if (cur != null && cur != EditTarget.Cell(index, c)) commitEditDraft()
                                        sel.value = CellSel(index, c)
                                        focusRequester.requestFocus()
                                    },
                                    editor = if (isEditing) {
                                        {
                                            CellEditor(
                                                value = editDraft,
                                                onValueChange = { editDraft = it },
                                                onCommit = { commitEditDraft() },
                                                onCancel = { cancelEditDraft() },
                                            )
                                        }
                                    } else null,
                                    onDoubleClick = when {
                                        isEditable -> {
                                            {
                                                if (ctrlDown) {
                                                    ctrlDown = false
                                                    // 长值/多行/NULL → 对话框；短值 → 行内编辑
                                                    if (v == null || v.length > 60 || v.contains('\n')) {
                                                        origKey?.let {
                                                            dialogEdit = EditRequest.Cell(
                                                                it,
                                                                "${result.columns[it.col].name} · 第 ${it.row + 1} 行",
                                                                v,
                                                            )
                                                        }
                                                    } else {
                                                        beginEdit(index, c)
                                                    }
                                                } else {
                                                    cellView?.let { cv -> viewer = cv }
                                                }
                                            }
                                        }
                                        else -> cellView?.let { cv -> { viewer = cv } }
                                    },
                                    menuItems = buildList {
                                        add(
                                            ContextMenuItem("复制单元格值") {
                                                copyCellValue(onCopyText, v, colName)
                                            },
                                        )
                                        if (cellView != null) {
                                            add(ContextMenuItem("查看完整内容") { viewer = cellView })
                                        }
                                        if (canInsertRow && origRow != null && origRow !in truncatedRows) {
                                            add(
                                                ContextMenuItem("复制本行 → INSERT") {
                                                    val insertSql = rowToInsertSql(
                                                        result.sql,
                                                        result.columns.map { it.name },
                                                        result.rows[origRow],
                                                    )
                                                    if (insertSql != null) {
                                                        onCopyText(insertSql, "已复制本行 → INSERT（表 $tableName）")
                                                    }
                                                },
                                            )
                                        }
                                        if (isEditable && !deleted) {
                                            add(ContextMenuItem("编辑单元格（Ctrl+双击）") { beginEdit(index, c) })
                                            add(
                                                ContextMenuItem("在对话框中编辑…") {
                                                    origKey?.let {
                                                        dialogEdit = EditRequest.Cell(
                                                            it,
                                                            "${result.columns[it.col].name} · 第 ${it.row + 1} 行",
                                                            v,
                                                        )
                                                    }
                                                },
                                            )
                                            add(
                                                ContextMenuItem("置为 NULL") {
                                                    origKey?.let { onCellEdit(it, CellValue(null)) }
                                                },
                                            )
                                        }
                                        if (pending) {
                                            add(
                                                ContextMenuItem("撤销此单元格修改") {
                                                    onClearCellEdit(origKey)
                                                },
                                            )
                                        }
                                        if (canModifyRows && origRow != null) {
                                            add(
                                                ContextMenuItem(if (deleted) "撤销删除该行" else "标记删除该行（主键定位）") {
                                                    onToggleRowDelete(origRow)
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
                    // 待插入行（仅非转置视图）：直接编辑，提交时生成 INSERT（未填列走库默认值）
                    if (!transposed) {
                        items(resultEdits.inserts, key = { it.id }) { ins ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colors.primary.copy(alpha = 0.06f))
                                    .horizontalScroll(hScroll),
                            ) {
                                RowHeaderCell("＋", RESULT_GUTTER_DP, highlighted = false)
                                cols.forEachIndexed { c, _ ->
                                    val filled = ins.values[c]
                                    val isEditable = editPlan?.columnAt(c) != null
                                    val isEditing = editing == EditTarget.Insert(ins.id, c)
                                    DataCell(
                                        value = filled?.raw,
                                        width = widths[c],
                                        pending = filled != null,
                                        nullText = if (c in ins.values) "(NULL)" else "未填",
                                        onSelect = {
                                            val cur = editing
                                            if (cur != null && cur != EditTarget.Insert(ins.id, c)) commitEditDraft()
                                        },
                                        editor = if (isEditing) {
                                            {
                                                CellEditor(
                                                    value = editDraft,
                                                    onValueChange = { editDraft = it },
                                                    onCommit = { commitEditDraft() },
                                                    onCancel = { cancelEditDraft() },
                                                )
                                            }
                                        } else null,
                                        onDoubleClick = if (isEditable) {
                                            { beginInsertEdit(ins.id, c) }
                                        } else null,
                                        menuItems = buildList {
                                            if (isEditable) {
                                                add(ContextMenuItem("编辑此格") { beginInsertEdit(ins.id, c) })
                                                add(
                                                    ContextMenuItem("在对话框中编辑…") {
                                                        dialogEdit = EditRequest.Insert(
                                                            ins.id, c,
                                                            "${result.columns.getOrNull(c)?.name ?: "列"} · 待插入行",
                                                            filled?.raw,
                                                        )
                                                    },
                                                )
                                                add(
                                                    ContextMenuItem("置为 NULL") {
                                                        onInsertCellEdit(ins.id, c, CellValue(null))
                                                    },
                                                )
                                                if (c in ins.values) {
                                                    add(
                                                        ContextMenuItem("清除此格填写（走库默认值）") {
                                                            onClearInsertCell(ins.id, c)
                                                        },
                                                    )
                                                }
                                            }
                                            add(ContextMenuItem("移除该待插入行") { onRemoveInsertRow(ins.id) })
                                        },
                                    )
                                }
                            }
                            Divider(
                                color = MaterialTheme.colors.primary.copy(alpha = 0.15f),
                                modifier = Modifier.padding(start = RESULT_GUTTER_DP.dp),
                            )
                        }
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
    dialogEdit?.let { request ->
        EditCellDialog(
            title = "编辑单元格 · ${request.title}",
            initial = request.value,
            onDismiss = { dialogEdit = null },
            onConfirm = { value ->
                dialogEdit = null
                when (request) {
                    is EditRequest.Cell -> {
                        val original = result.rows.getOrNull(request.key.row)?.getOrNull(request.key.col)
                        val raw = value.raw
                        // 改回原始值 → 撤销暂存；其余（含空串）写入 overlay
                        if (raw == original.orEmpty() && !(raw.isNullOrEmpty() && original == null)) {
                            onClearCellEdit(request.key)
                        } else {
                            onCellEdit(request.key, value)
                        }
                    }
                    is EditRequest.Insert -> onInsertCellEdit(request.id, request.col, value)
                }
            },
        )
    }
    viewer?.let { v ->
        CellViewerDialog(
            title = v.title,
            content = v.content,
            onDismiss = { viewer = null },
            onCopy = onCopyText,
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
    } else if (QueryExecutor.isTruncatedCell(v)) {
        onCopyText(v, "已复制单元格（列 $colName）：内容过大已截断，仅复制了显示部分")
    } else {
        val preview = if (v.length > 28) v.take(28) + "…" else v
        onCopyText(v, "已复制单元格（列 $colName）：$preview")
    }
}

/** 结果区筛选条：左侧顶部快速过滤（所有列包含），右侧「命中 N / 共 M」与清除全部。 */
@Composable
private fun ResultFilterBar(
    spec: ResultViewSpec,
    onSpecChange: (ResultViewSpec) -> Unit,
    shownRows: Int,
    totalRows: Int,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.surface)
            .padding(start = 8.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .width(240.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colors.onSurface.copy(alpha = 0.05f))
                .padding(horizontal = 7.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = spec.quickFilter,
                onValueChange = { onSpecChange(spec.copy(quickFilter = it)) },
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colors.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colors.primary),
                modifier = Modifier.fillMaxWidth(),
            )
            if (spec.quickFilter.isEmpty()) {
                Text(
                    "快速过滤（所有列）",
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.35f),
                )
            }
        }
        if (spec.quickFilter.isNotEmpty()) {
            Spacer(Modifier.width(2.dp))
            IconButton(
                onClick = { onSpecChange(spec.copy(quickFilter = "")) },
                modifier = Modifier.size(20.dp),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "清除快速过滤",
                    tint = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            if (shownRows != totalRows) "命中 $shownRows / 共 $totalRows 行" else "共 $totalRows 行",
            fontSize = 11.sp,
            color = if (shownRows != totalRows) MaterialTheme.colors.primary
            else MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
        )
        Spacer(Modifier.weight(1f))
        if (spec.filterCount > 0) {
            Text(
                "筛选 ${spec.filterCount} 条",
                fontSize = 11.sp,
                color = MaterialTheme.colors.primary,
            )
            Spacer(Modifier.width(4.dp))
            TextButton(
                onClick = { onSpecChange(ResultViewSpec()) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            ) {
                Text("清除全部", fontSize = 11.sp)
            }
        }
    }
    Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.06f))
}

/**
 * 可排序/筛选的表头单元格：点击切换排序（升→降→无），右侧漏斗图标弹出列筛选输入框。
 * 列筛选输入语法同 [parseColumnFilter]（`=x` 精确、`!=x`、`>x`、`~regex`，默认包含）。
 */
@Composable
private fun ResultHeaderCell(
    name: String,
    width: Int,
    highlighted: Boolean,
    sort: SortSpec?,
    sortRank: Int?,
    filterText: String?,
    onToggleSort: () -> Unit,
    onFilterChange: (String) -> Unit,
) {
    var filterOpen by remember { mutableStateOf(false) }
    var draft by remember(filterText, filterOpen) { mutableStateOf(filterText.orEmpty()) }
    val filterFocus = remember { FocusRequester() }
    LaunchedEffect(filterOpen) { if (filterOpen) runCatching { filterFocus.requestFocus() } }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .width(width.dp)
            .height(30.dp)
            .background(if (highlighted) MaterialTheme.colors.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onToggleSort)
            .padding(start = 8.dp, end = 10.dp),
    ) {
        Text(
            name,
            fontSize = 11.sp,
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.SemiBold,
            color = if (highlighted) MaterialTheme.colors.primary
            else MaterialTheme.colors.onSurface.copy(alpha = 0.75f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (sort != null) {
            Text(
                if (sort.ascending) "▲" else "▼",
                fontSize = 8.sp,
                color = MaterialTheme.colors.primary,
                modifier = Modifier.padding(start = 3.dp),
            )
            if (sortRank != null) {
                Text(
                    "${sortRank + 1}",
                    fontSize = 7.5.sp,
                    color = MaterialTheme.colors.primary.copy(alpha = 0.8f),
                    modifier = Modifier.padding(start = 1.dp),
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Box(contentAlignment = Alignment.Center) {
            IconButton(
                onClick = { filterOpen = true },
                modifier = Modifier.size(16.dp),
            ) {
                Icon(
                    DbIcons.Filter,
                    contentDescription = "筛选「$name」",
                    tint = if (!filterText.isNullOrEmpty()) MaterialTheme.colors.primary
                    else MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(12.dp),
                )
            }
            DropdownMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }) {
                Column(modifier = Modifier.width(228.dp).padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(
                        "筛选「$name」",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colors.onSurface,
                    )
                    Text(
                        "= 精确 · != 不等 · > >= < <= 比较 · ~ 正则 · 默认包含",
                        fontSize = 10.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .height(26.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colors.onSurface.copy(alpha = 0.06f))
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        BasicTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colors.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colors.primary),
                            modifier = Modifier.fillMaxWidth().focusRequester(filterFocus),
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(
                            onClick = { onFilterChange(""); filterOpen = false },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) { Text("清除", fontSize = 12.sp) }
                        TextButton(
                            onClick = { onFilterChange(draft); filterOpen = false },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) { Text("应用", fontSize = 12.sp) }
                    }
                }
            }
        }
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
    pending: Boolean = false,
    deleted: Boolean = false,
    /** value == null 时的占位文本（待插入行未填列用「未填」，区别于显式 NULL）。 */
    nullText: String = "(NULL)",
    onSelect: () -> Unit = {},
    mono: Boolean = true,
    muted: Boolean = false,
    onDoubleClick: (() -> Unit)? = null,
    editor: (@Composable () -> Unit)? = null,
    menuItems: List<ContextMenuItem> = emptyList(),
) {
    val content: @Composable () -> Unit = {
        if (value == null) {
            Text(
                nullText,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                fontSize = 12.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.3f),
                textDecoration = if (deleted) TextDecoration.LineThrough else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        } else {
            // 只渲染前缀预览：完整值仍在模型中（查看/复制走原字符串），避免 Compose 按巨大段落排版
            val shown = if (value.length > RESULT_CELL_PREVIEW_CHARS) {
                value.take(RESULT_CELL_PREVIEW_CHARS) + "…"
            } else {
                value
            }
            Text(
                shown,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                fontSize = 12.sp,
                color = when {
                    deleted -> MaterialTheme.colors.onSurface.copy(alpha = 0.35f)
                    muted -> MaterialTheme.colors.onSurface.copy(alpha = 0.45f)
                    else -> MaterialTheme.colors.onSurface.copy(alpha = 0.9f)
                },
                textDecoration = if (deleted) TextDecoration.LineThrough else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
    // 手势块只捕获一次 lambda：经 rememberUpdatedState 取最新回调，避免每次重组合重启手势
    val currentSelect = rememberUpdatedState(onSelect)
    val currentDoubleClick = rememberUpdatedState(onDoubleClick)
    val background = when {
        selected -> MaterialTheme.colors.primary.copy(alpha = 0.18f)
        pending -> Color(0xFFFFB300).copy(alpha = 0.20f)
        else -> Color.Transparent
    }
    // 行内编辑时不要挂选中/双击手势，避免与文本框抢指针
    val gesture = if (editor == null) {
        Modifier.pointerInput(Unit) {
            detectTapGestures(
                onPress = { currentSelect.value() },
                onDoubleTap = { currentDoubleClick.value?.invoke() },
            )
        }
    } else {
        Modifier
    }
    val base = Modifier
        .width(width.dp)
        .height(26.dp)
        .background(background)
        .then(
            if (selected) Modifier.border(1.dp, MaterialTheme.colors.primary.copy(alpha = 0.85f))
            else Modifier,
        )
        .then(gesture)
    when {
        editor != null -> Box(modifier = base, contentAlignment = Alignment.CenterStart) { editor() }
        menuItems.isEmpty() -> Box(modifier = base, contentAlignment = Alignment.CenterStart) { content() }
        else -> {
            // 仅右键菜单可复制（不做单击复制）
            ContextMenuArea(items = { menuItems }) {
                Box(modifier = base, contentAlignment = Alignment.CenterStart) {
                    content()
                }
            }
        }
    }
}

/**
 * 结果单元格行内编辑器：Enter 提交、Esc 取消、失焦提交（点其他单元格/工具条按钮同样生效）。
 * 受控：草稿由 [ResultTable] 持有，便于“点其它格先提交当前格”。
 */
@Composable
private fun CellEditor(
    value: String,
    onValueChange: (String) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 2.dp)
            .border(1.dp, MaterialTheme.colors.primary.copy(alpha = 0.9f)),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colors.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colors.primary),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 5.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { st ->
                    if (st.isFocused) {
                        hadFocus = true
                    } else if (hadFocus) {
                        hadFocus = false
                        onCommit()
                    }
                }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) {
                        false
                    } else {
                        when (e.key) {
                            Key.Enter, Key.NumPadEnter -> { onCommit(); true }
                            Key.Escape -> { onCancel(); true }
                            else -> false
                        }
                    }
                },
        )
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
