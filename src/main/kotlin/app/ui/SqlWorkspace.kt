package app.ui

import kotlinx.coroutines.flow.drop
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import app.state.ColumnCatalog
import app.state.ConsoleRunUi
import app.state.RedisKeyMeta
import app.settings.EditorSettings
import app.settings.ShortcutCommand
import app.dialog.TextViewerDialog
import app.i18n.t
import db.ConsoleRecord
import db.ConnectionProfile
import db.SqlHistoryRow
import engine.EditorLanguage
import engine.Protocol
import engine.model.SchemaMeta
import i18n.I18n
import i18n.Str
import jdbc.CellValue
import tree.ConnUiStatus

/**
 * 右侧 SQL 工作台（控制台主导）。
 * 结构：Header →（当前控制台的数据源导航行 + 执行目标切换）→ 跨数据源控制台标签条
 * → 编辑器（当前控制台缓冲区）→ 执行条 → 结果区。未激活控制台时显示引导区。
 * 每个 tab 就是一个控制台（可来自不同数据源，各绑定自己的 .sql 文件）；纯展示组件，编排在 Main/ConsoleState。
 */
@Composable
fun WindowScope.SqlWorkspace(
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
    targetLabel: String = I18n.t(Str.EditorTargetLabel),
    /** 是否显示「默认（连接库）」选项（Redis DB 不需要）。 */
    targetAllowDefault: Boolean = true,
    /** 激活控制台已选执行目标库/schema（"" = 连接默认）。 */
    targetSchema: String,
    onSelectTarget: (String) -> Unit,
    editorText: String,
    editorDirty: Boolean,
    /** 编辑器正文变更：参数 (consoleId, 文本)。带 id 是为了让写入目标 = 编辑器节点所属控制台。 */
    onTextChange: (String, String) -> Unit,
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
    /** Redis 结果视图的键元数据（非 Redis 后端忽略）。 */
    redisKeyMeta: RedisKeyMeta? = null,
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
    /** 主窗口状态：自定义标题栏的最小化/最大化/还原用。 */
    mainWindowState: WindowState,
    /** 自定义标题栏关闭按钮回调（与系统窗口关闭同一条路径）。 */
    onWindowCloseRequest: () -> Unit,
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
    // formatRef 由下方控制台区块赋值（那里才能访问编辑器的权威状态）。
    var findReplaceOpen by remember { mutableStateOf(false) }
    val formatRef = remember { mutableStateOf<(() -> Unit)?>(null) }
    // 每个控制台一个 TextFieldState（含原生撤销栈 UndoState），跨重组/切控制台存活。
    // 删控制台后这里会留一个实例（文本量小，TODO 后续接到删除回调清理）。
    val editorStates = remember { mutableMapOf<String, TextFieldState>() }
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
            mainWindowState = mainWindowState,
            onWindowCloseRequest = onWindowCloseRequest,
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
                    t(Str.EditorEmptyNoSource),
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
        // 编辑器状态：每个控制台一个 TextFieldState，存在 SqlWorkspace 的 map 里（跨重组/切控制台存活）。
        // TextFieldState 自带撤销栈（UndoState），因此切控制台后 undo/redo 仍按控制台各自保留。
        val state = editorStates.getOrPut(consoleId) {
            TextFieldState(
                editorText,
                TextRange(
                    savedCaret.first.coerceIn(0, editorText.length),
                    savedCaret.second.coerceIn(0, editorText.length),
                ),
            )
        }
        // 文本/选区变化 → ConsoleState（脏标记 + 防抖落库 + 光标记忆）。drop(1) 跳过 collect 时的初值。
        LaunchedEffect(consoleId) {
            snapshotFlow { state.text.toString() }
                .drop(1)
                .collect { onTextChange(consoleId, it) }
        }
        LaunchedEffect(consoleId) {
            snapshotFlow { state.selection }
                .drop(1)
                .collect { sel -> onCaretChange(consoleId, sel.start, sel.end) }
        }
        // 预览 / 历史 SQL 插入：在光标/选区处插入（有选区则替换之），不覆盖整段草稿；
        // 光标落到插入内容之后。文本/选区变化由上面的 snapshotFlow 统一转发。
        LaunchedEffect(insertRequest) {
            val snippet = insertRequest ?: return@LaunchedEffect
            onInsertRequestConsumed()
            val cur = state.text.toString()
            val (newText, caret) = insertSnippetAtCaret(
                cur = cur,
                selStart = state.selection.start,
                selEnd = state.selection.end,
                snippet = snippet,
            )
            state.edit {
                replace(0, length, newText)
                selection = TextRange(caret)
            }
        }
        // N2：格式化 = 有选区只格式化选区，否则整段；只调空白/关键字大小写，语义不变。
        val doFormat: () -> Unit = {
            state.edit {
                val cur = toString()
                val fsel = selection
                val newText: String
                val caret: Int
                if (!fsel.collapsed) {
                    val from = minOf(fsel.start, fsel.end).coerceIn(0, cur.length)
                    val to = maxOf(fsel.start, fsel.end).coerceIn(0, cur.length)
                    val formatted = formatSql(cur.substring(from, to)).trimEnd('\n')
                    newText = cur.substring(0, from) + formatted + cur.substring(to)
                    caret = from
                } else {
                    newText = formatSql(cur).trimEnd('\n')
                    caret = fsel.start.coerceIn(0, newText.length)
                }
                replace(0, length, newText)
                selection = TextRange(caret)
            }
        }
        SideEffect { formatRef.value = doFormat }
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onSizeChanged { paneH = it.height },
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(top = 4.dp, bottom = 4.dp)) {
                    // 编辑器吃满剩余高，结果区按 resultFrac 分配；中间只隔一根 5dp 可拖细线，不留空隙。
                    // 执行动作/状态/多语句 tabs 全部收在结果区顶部一条 28dp 工具条里（无结果时不显示）。
                    // 每个控制台一个独立的编辑器节点（key(consoleId)）：旧节点输入会话随切换销毁，
                    // 避免 BasicTextField 在 value 被外部替换后“回弹”旧文本造成串台（历史事故：A1 覆盖 B4）。
                    // 撤销历史不在节点里、而在上面 per-console 的 TextFieldState 里，所以 key 重建不丢 undo。
                    key(consoleId) {
                        EditorPane(
                            state = state,
                            consoleId = consoleId,
                            dirty = editorDirty,
                            onCtrlEnter = { selectedSqlOf(state.text.toString(), state.selection)?.let(onRun) },
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
                    }
                    if (resultsVisible) {
                        // 拖动分隔条：像素差 / 可用高 → 比例增量；窗口缩放不改变已设比例。
                        ResultSplitter(
                            paneHeightPx = paneH,
                            onDragDeltaPx = { delta ->
                                resultFrac = (resultFrac + delta).coerceIn(MIN_RESULT_FRAC, MAX_RESULT_FRAC)
                            },
                        )
                        if (profile?.dbType?.protocol == Protocol.REDIS) {
                            RedisResultView(
                                run = run,
                                redisMeta = redisKeyMeta,
                                onSelectOutcome = onSelectOutcome,
                                onRefreshResult = onRefreshResult,
                                onCancelRun = onCancelRun,
                                onExport = onExport,
                                onCopyText = onCopyText,
                                modifier = Modifier.weight(resultFrac).fillMaxWidth(),
                            )
                        } else {
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
            title = t(Str.HistoryTitle),
            content = row.sqlText,
            onDismiss = { historyView = null },
            onCopy = onCopyText,
            highlightSql = true,
            subtitle = historyEntryMeta(row),
            copyToast = t(Str.HistoryCopied),
            extraAction = if (activeConsole != null) {
                t(Str.HistoryInsertCurrent) to {
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
internal fun canTranspose(run: ConsoleRunUi): Boolean {
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
private fun selectedSqlOf(text: String, sel: TextRange): String? {
    if (sel.collapsed) return null
    // 反向选择（从下往上）时 start>end，必须取 min/max
    val from = minOf(sel.start, sel.end)
    val to = maxOf(sel.start, sel.end)
    val sub = text.substring(from, to)
    return sub.trim().takeIf { it.isNotEmpty() }
}
