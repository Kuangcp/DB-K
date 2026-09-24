package app.ui

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollbarAdapter
import kotlinx.coroutines.flow.drop
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.event.KeyEvent as AwtKeyEvent
import app.state.ColumnCatalog
import app.state.RunStatus
import app.state.StatementProgress
import app.state.aggregateLineMarkers
import app.settings.EditorSettings
import app.settings.ShortcutCommand
import app.i18n.t
import db.ConnectionProfile
import engine.EditorLanguage
import engine.model.SchemaMeta
import i18n.Str

/**
 * 返回**稳定**的 [OutputTransformation]（同一 composition 内只创建一次）。
 *
 * Compose 的 `BasicTextField` 用 `remember(state, codepointTransformation, outputTransformation)`
 * 建 `TransformedTextFieldState`，再 `remember(transformedState)` 建 `TextFieldSelectionState`；
 * 只要 `outputTransformation` 换新对象，selection state 就重建、`TextFieldDecoratorModifier` 会
 * `resetPointerInputHandler()`，把正在进行的拖选/双击选词打断（历史 bug：刚编辑完双击选词没反应，
 * 再双击一次才选中）。而 `transformOutput` 在 `TransformedTextFieldState` 的 derivedStateOf 内执行，
 * 其中的 snapshot state 读取会被追踪，所以动态值经 [rememberUpdatedState] 读取即可随文本/光标
 * 自动重算视觉文本，且不会重置选择状态。
 *
 * （无法用单测守卫：需真实指针手势 + Compose UI 测试环境；本工程沿用「UI 行为人工验收」。
 * 修改此函数时务必保持返回值不随入参变化而重建。）
 */
@Composable
internal fun rememberHighlightTransformation(
    spans: List<AnnotatedString.Range<SpanStyle>>,
    currentLineRange: Pair<Int, Int>?,
    lineBgColor: Color,
    templateStop: Pair<Int, Int>? = null,
    templateStopBg: Color = Color.Transparent,
): OutputTransformation {
    val latestSpans = rememberUpdatedState(spans)
    val latestLineRange = rememberUpdatedState(currentLineRange)
    val latestLineBg = rememberUpdatedState(lineBgColor)
    val latestTemplateStop = rememberUpdatedState(templateStop)
    val latestTemplateBg = rememberUpdatedState(templateStopBg)
    return remember {
        OutputTransformation {
            // 重要：buffer 可能是比 composition 期算好的 spans 更“短”的文本（undo/快速输入时，
            // outputTransformation 会在重组前就被触发）。addStyle 的范围必须落在当前 buffer 长度内，
            // 否则 TextFieldBuffer.requireValidStyleRange 抛 Expected TextRange(...) 直接崩。
            // 逐条夹取，越界部分丢弃；下一次重算会用新 spans 重新着色。
            for (s in latestSpans.value) {
                val start = s.start.coerceIn(0, length)
                val end = s.end.coerceIn(0, length)
                if (start < end) addStyle(s.item, start, end)
            }
            latestLineRange.value?.let { (a, b) ->
                val start = a.coerceIn(0, length)
                val end = b.coerceIn(0, length)
                if (start < end) addStyle(SpanStyle(background = latestLineBg.value), start, end)
            }
            latestTemplateStop.value?.let { (a, b) ->
                val start = a.coerceIn(0, length)
                val end = b.coerceIn(0, length)
                if (start < end) addStyle(SpanStyle(background = latestTemplateBg.value), start, end)
            }
        }
    }
}

/**
 * SQL 编辑器（语法高亮 + 自动补全）。
 *
 * 结构：外层自绘边框/底；左侧行号槽（Canvas 绘制、只画可视行、随滚动重绘）；内部
 * BasicTextField 消费带 span 高亮的 TextFieldValue（`SQL/JSON：SyntaxHighlight.kt 手写扫描器`
 * 实时着色），自身 verticalScroll 滚动。行号/补全弹层共用同一份
 * TextMeasurer 排版结果，保证与编辑区逐行对齐（含自动换行）。
 * 文本/选区权威仍在上层 EditorArea 持有的 tfv（受控），高亮是纯派生渲染。
 *
 * 自动补全：caret 位于标识符词内（且不在字符串/注释中）时按前缀匹配 [completionIdentifiers]
 * + SQL 关键字；Enter/Tab 上屏、↑/↓ 选择、Esc 关闭，也可鼠标点击。弹层颜色取自主题
 * （surface 底 + onSurface 文字 + primary 选中条），浅/深色均随主题。
 */
@Composable
internal fun EditorPane(
    state: TextFieldState,
    /** 所属控制台 id：仅用作 LaunchedEffect 键——切换控制台时把视口滚到恢复的光标行。 */
    consoleId: String,
    dirty: Boolean,
    onCtrlEnter: () -> Unit,
    completionIdentifiers: List<String>,
    completionTables: List<CompletionTable>,
    completionFunctions: List<String>,
    /** Live Templates：SQL 语言下 Tab 展开 + 补全候选。 */
    liveTemplates: List<LiveTemplate> = emptyList(),
    completionEnabled: Boolean = true,
    /** 编辑器语言：决定语法高亮（SQL 关键字 / JSON DSL）。 */
    editorLanguage: EditorLanguage = EditorLanguage.SQL,
    columnCatalog: ColumnCatalog?,
    schemas: List<SchemaMeta>,
    defaultSchema: SchemaMeta?,
    profile: ConnectionProfile?,
    editorSettings: EditorSettings,
    /** N2：查找替换栏显隐（由 EditorArea 控制）。 */
    findOpen: Boolean = false,
    onCloseFind: () -> Unit = {},
    /** Ctrl+Tab / Ctrl+Shift+Tab：按 MRU 切换控制台（参数 ±1）。 */
    onSwitchConsole: (Int) -> Unit = {},
    /** 本批执行的逐语句进度（gutter 行状态标记；与结果区 tab 同源）。 */
    progress: List<StatementProgress> = emptyList(),
    /** 点击某行状态标记（参数 = 0-based 行号）：切换该行语句对应的结果。 */
    onLineMarkerClick: (Int) -> Unit = {},
    /** 外部持有的焦点请求器（Ctrl+Tab 切换后交回焦点）。 */
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    // 快捷键表：编辑器内的执行/补全用可配置键，其余为语义固定的编辑交互。
    val keymap = LocalKeymap.current
    val themeColors = LocalThemeColors.current
    val keywords = remember { sqlHighlightKeywordSet() }
    val content = state.text.toString()
    val sel = state.selection

    val scroll = rememberScrollState()

    // 补全活性：文本变化=用户敲字；纯选区变化=点一下/移光标 → 关闭。
    // 旧 API 在 onValueChange 里一次性拿到 text+selection 来判 typed；新 API 没有回调，
    // 用 snapshotFlow 观察 (text, selection) 复刻同一语义。suppressTextActivation 供程序化
    // 插入（accept 上屏）抑制“本次文本变化”激活，避免刚上屏又弹下一批候选。
    var editing by remember { mutableStateOf(false) }
    var forceComplete by remember { mutableStateOf(false) }
    var suppressTextActivation by remember { mutableStateOf(false) }
    // 多光标列编辑：空 = 单光标（BasicTextField 自身选区为权威）。expectedMultiText 用于区分
    // “多光标批量编辑造成的文本变化”（保留多光标）与其它来源（格式化/预览插入等）→ 自动退出。
    var multiCursors by remember { mutableStateOf<List<CursorSel>>(emptyList()) }
    var expectedMultiText by remember { mutableStateOf<String?>(null) }
    // Live Template 会话：展开后 Tab/Shift+Tab 在 $占位符$ 间跳转，Esc 结束。切控制台由 key(consoleId) 重置。
    var templateSession by remember { mutableStateOf<TemplateSession?>(null) }
    LaunchedEffect(state) {
        var lastText = state.text.toString()
        snapshotFlow { state.text.toString() to state.selection }
            .drop(1)
            .collect { (text, selection) ->
                val typed = text != lastText
                lastText = text
                if (typed) {
                    if (suppressTextActivation) suppressTextActivation = false
                    else { editing = true; forceComplete = false }
                } else {
                    editing = false
                }
                val session = templateSession
                if (session != null) {
                    if (typed) {
                        templateSession = reconcileSession(session, text)
                    } else {
                        val lo = session.anchorStart
                        val hi = session.stops.lastOrNull()?.end ?: session.endOffset
                        val s = minOf(selection.start, selection.end)
                        val e = maxOf(selection.start, selection.end)
                        if (s < lo || e > hi) templateSession = null
                    }
                }
                if (multiCursors.isNotEmpty() && text != expectedMultiText) {
                    multiCursors = emptyList()
                    expectedMultiText = null
                }
            }
    }

    // 语法高亮：手写扫描出 token 区间（无正则）——`(a|b)*` 型正则在长字符串字面量上会递归爆栈，
    // 见 `SyntaxHighlight.kt`。新 API 在 outputTransformation 里把 span 贴进 TextFieldBuffer。
    val highlightSpans = remember(content, themeColors, editorLanguage, keywords) {
        when (editorLanguage) {
            EditorLanguage.JSON -> jsonHighlightSpans(content, jsonSyntaxPalette(themeColors))
            else -> sqlHighlightSpans(content, sqlSyntaxPalette(themeColors), keywords)
        }
    }
    val lineBgColor = MaterialTheme.colors.onSurface.copy(alpha = 0.06f)
    val currentLineRange: Pair<Int, Int>? =
        if (editing && sel.collapsed && content.isNotEmpty()) {
            val s = sel.start.coerceIn(0, content.length)
            val ls = content.lastIndexOf('\n', s - 1) + 1
            val leRaw = content.indexOf('\n', s)
            val le = if (leRaw < 0) content.length else leRaw
            if (le > ls) ls to le else null
        } else null
    // outputTransformation 必须稳定（换新对象会 reset 文本域的指针手势，见 rememberHighlightTransformation）。
    val templateStopBg = MaterialTheme.colors.primary.copy(alpha = 0.25f)
    val templateStopSel = templateSession?.let { activeStop(it) }?.let { it.start to it.end }
    val outputTransformation = rememberHighlightTransformation(
        highlightSpans, currentLineRange, lineBgColor, templateStopSel, templateStopBg,
    )

    // 鼠标按在补全弹层外（左侧树 / 结果区 / 工具栏 / 其它控制台标签…）→ 收起弹层：语义就是
    // “不要这个提示了”。弹层是编辑器内 overlay，收不到别处的点击，由 Main 根布局的窗口级
    // 指针监听转发（见 CompletionDismissSignal）；编辑区内的点击/移光标由上面的 snapshotFlow 关闭。
    val completionDismiss = LocalCompletionDismiss.current
    LaunchedEffect(completionDismiss.tick) {
        if (completionDismiss.tick > 0) editing = false
    }

    // ---- N2 查找替换：纯逻辑在 SqlFindReplace.kt，这里只管状态与交互 ----
    var findText by remember(consoleId) { mutableStateOf("") }
    var replaceText by remember(consoleId) { mutableStateOf("") }
    var useRegex by remember(consoleId) { mutableStateOf(false) }
    var caseSensitive by remember(consoleId) { mutableStateOf(false) }
    val findOptions = FindOptions(regex = useRegex, caseSensitive = caseSensitive)
    val findHits = remember(content, findText, useRegex, caseSensitive) {
        findMatches(content, findText, findOptions)
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
            val s = sel
            if (!s.collapsed) {
                val from = minOf(s.start, s.end).coerceIn(0, content.length)
                val to = maxOf(s.start, s.end).coerceIn(0, content.length)
                findText = content.substring(from, to).lineSequence().firstOrNull().orEmpty().take(200)
            }
        }
    }

    fun selectHit(index: Int) {
        if (findHits.isEmpty()) return
        val idx = ((index % findHits.size) + findHits.size) % findHits.size
        activeMatch = idx
        val r = findHits[idx]
        state.edit { selection = TextRange(r.first, r.last + 1) }
        navTick++
    }

    fun replaceCurrentHit() {
        val r = findHits.getOrNull(activeMatch) ?: return
        val caret = r.first + replaceText.length
        suppressTextActivation = true
        editing = false
        state.edit {
            replace(r.first, r.last + 1, replaceText)
            selection = TextRange(caret)
        }
    }

    fun replaceAllHits() {
        val (newText, count) = replaceAll(content, findText, findOptions, replaceText)
        if (count == 0) return
        val caret = sel.start.coerceIn(0, newText.length)
        suppressTextActivation = true
        editing = false
        state.edit {
            replace(0, length, newText)
            selection = TextRange(caret)
        }
    }

    fun exitMultiCursor() {
        multiCursors = emptyList()
        expectedMultiText = null
        editing = false
    }

    fun addMultiCursor(delta: Int) {
        val text = state.text.toString()
        val baseHead = if (multiCursors.isEmpty()) state.selection.end else multiCursors.last().head
        val newOffset = addCursorUpDown(text, baseHead, delta) ?: return
        if (multiCursors.isEmpty()) {
            val s = state.selection
            multiCursors = listOf(CursorSel(s.start, s.end))
            expectedMultiText = text
        }
        if (multiCursors.any { it.head == newOffset }) return
        multiCursors = multiCursors + CursorSel(newOffset, newOffset)
        state.edit { selection = TextRange(newOffset, newOffset) }
    }

    fun shiftMultiCursors(delta: Int) {
        if (multiCursors.isEmpty()) return
        val newCursors = shiftCursors(multiCursors, delta, state.text.toString().length)
        multiCursors = newCursors
        val active = newCursors.last()
        state.edit { selection = TextRange(active.start, active.end) }
    }

    fun applyMultiEdit(op: MultiEditOp) {
        if (multiCursors.isEmpty()) return
        val res = applyMultiCursorEdit(state.text.toString(), multiCursors, op)
        suppressTextActivation = true
        editing = false
        expectedMultiText = res.text
        multiCursors = res.cursors
        state.edit {
            replace(0, length, res.text)
            val active = res.cursors.lastOrNull()
            selection = if (active != null) TextRange(active.start, active.end) else selection
        }
    }

    /**
     * 多光标下的编辑键：可打印字符 / Backspace / Delete / Enter 一次性批量应用到所有光标。
     * 返回是否消费该事件。
     *
     * **必须在 AWT 层拦截**：Compose 的 KeyDown 事件 `utf16CodePoint` 来自 AWT `getKeyChar()`，
     * 对 KEY_PRESSED 是 CHAR_UNDEFINED；真正字符只在 AWT `KEY_TYPED` 上（Compose 视为 Unknown
     * 类型被我们的 KeyDown 分支忽略）。所以字符/Enter 在 KEY_TYPED 处理，Backspace/Delete 在 KEY_PRESSED。
     */
    fun editableTypedChar(c: Char): Boolean =
        c != AwtKeyEvent.CHAR_UNDEFINED && c != '\b' && c != '\t' && c != '\n' && c >= ' ' && c != '\u007f'

    fun handleAwtEdit(e: AwtKeyEvent): Boolean {
        if (multiCursors.isEmpty()) return false
        return when (e.id) {
            AwtKeyEvent.KEY_TYPED -> {
                val ch = e.keyChar
                when {
                    ch == '\n' && !e.isControlDown && !e.isAltDown && !e.isShiftDown -> {
                        applyMultiEdit(MultiEditOp.Insert('\n'))
                        true
                    }
                    editableTypedChar(ch) && !e.isControlDown && !e.isAltDown -> {
                        applyMultiEdit(MultiEditOp.Insert(ch))
                        true
                    }
                    // Backspace / Delete 的删除已在 KEY_PRESSED 做；若平台还额外派发字符，一并吞掉。
                    ch == '\b' || ch.code == 0x7F -> true
                    else -> false
                }
            }
            AwtKeyEvent.KEY_PRESSED -> when (e.keyCode) {
                // Enter 的编辑在随后的 KEY_TYPED('\n') 做，这里只消费，避免穿透到文本域
                AwtKeyEvent.VK_ENTER -> !e.isControlDown && !e.isAltDown && !e.isShiftDown
                AwtKeyEvent.VK_BACK_SPACE -> when {
                    e.isControlDown && !e.isAltDown -> {
                        applyMultiEdit(MultiEditOp.BackspaceWord)
                        true
                    }
                    !e.isControlDown && !e.isAltDown -> {
                        applyMultiEdit(MultiEditOp.Backspace)
                        true
                    }
                    else -> false
                }
                AwtKeyEvent.VK_DELETE -> when {
                    e.isControlDown && !e.isAltDown -> {
                        applyMultiEdit(MultiEditOp.DeleteWord)
                        true
                    }
                    !e.isControlDown && !e.isAltDown -> {
                        applyMultiEdit(MultiEditOp.Delete)
                        true
                    }
                    else -> false
                }
                else -> false
            }
            else -> false
        }
    }

    // 窗口级 AWT 派发器（与 Main 同款：DisposableEffect(Unit) 常驻，用 rememberUpdatedState
    // 读最新状态）。多光标未激活时 handleAwtEdit 直接放行，不干扰普通输入。
    val onAwtEditEvent = rememberUpdatedState<(AwtKeyEvent) -> Boolean> { e -> handleAwtEdit(e) }
    DisposableEffect(Unit) {
        val dispatcher = java.awt.KeyEventDispatcher { e -> onAwtEditEvent.value(e) }
        val kfm = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
        kfm.addKeyEventDispatcher(dispatcher)
        onDispose { kfm.removeKeyEventDispatcher(dispatcher) }
    }

    fun duplicateLine() {
        if (multiCursors.isNotEmpty()) exitMultiCursor()
        val res = duplicateLineText(state.text.toString(), state.selection.start, state.selection.end) ?: return
        suppressTextActivation = true
        editing = false
        state.edit {
            replace(0, length, res.first)
            selection = TextRange(res.second)
        }
    }
    // 打开查找替换栏（焦点会移出编辑器）时退出多光标，避免 AWT 拦截到查找框的输入。
    LaunchedEffect(findOpen) {
        if (findOpen) {
            multiCursors = emptyList()
            expectedMultiText = null
            editing = false
        }
    }
    // 拖拽选区自动滚动：指针停在上/下边缘时持续滚动并同步延伸选区（多行大块选择必需）。
    // 编辑器是「BasicTextField + 外层 verticalScroll」结构，BasicTextField 不知道外层滚动，
    // 不会自己滚；所以在父 Box 上旁路观察指针（Final pass，不干涉文本域自身选区逻辑）。
    var dragActive by remember { mutableStateOf(false) }
    var dragPointer by remember { mutableStateOf<Offset?>(null) }
    var scrollAnchor by remember { mutableStateOf<Int?>(null) }
    var boxW by remember { mutableStateOf(0) }
    var boxH by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    // 稳定引用：editorStyle 会作为下面 textLayout 的 remember 键；若每次重组都新建（自定义字体走
    // SystemFont 时值可能不相等），会导致整篇文本反复重排版。
    val editorStyle = remember(editorSettings, themeColors) {
        TextStyle(
            fontFamily = editorFontFamily(editorSettings.fontFamilyName),
            fontSize = editorSettings.fontSizeSp.sp,
            lineHeight = EditorSettings.lineHeightSp(editorSettings.fontSizeSp).sp,
            color = themeColors.editorForeground,
        )
    }
    // 行号槽字号跟随编辑器字号（旧固定 11sp ≈ 13sp * 0.85）
    val gutterFontFamily = editorStyle.fontFamily
    val gutterFontSize = (editorSettings.fontSizeSp * 0.85f).sp
    val gutterLineHeight = editorStyle.lineHeight
    val textMeasurer = rememberTextMeasurer()
    // 行号槽用独立 measurer：行号是一堆小字符串，会挤爆主布局的小容量 LRU 缓存，
    // 反过来让正文每帧重新排版。分开后正文排版稳定命中缓存。
    val gutterMeasurer = rememberTextMeasurer(cacheSize = 64)
    // 补全弹层宽度测量同理用独立 measurer，避免把正文排版挤出缓存。
    val popupMeasurer = rememberTextMeasurer(cacheSize = 128)

    // ---- 补全派生状态：caret 词/限定符/星号 → 语句上下文（含 CTE/子查询）→ 候选 → 弹层 ----
    val caretActive = editing && multiCursors.isEmpty()
    val canComplete = caretActive && sel.collapsed
    // JSON 模式（Elasticsearch DSL）：走 [esDslSuggestions]，不走 SQL 词法/补全
    val jsonMode = editorLanguage == EditorLanguage.JSON
    val qualified = if (canComplete && !jsonMode) sqlQualifiedPrefix(content, sel.start) else null
    // 解析 caret 所在语句（只看括号深度 0；只取已写完的表名，避免边敲边查元数据）
    val prepared = if (canComplete) buildPreparedScope(content, sel.start) else null
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
        selectStarBeforeCaret(content, sel.start)
    } else {
        null
    }
    val sqlWord: CompletionWord? = when {
        qualified != null -> CompletionWord(qualified.wordStart, qualified.wordEnd, qualified.wordText)
        starPos != null -> CompletionWord(starPos, sel.start, "*")
        canComplete -> sqlCompletionWord(content, sel.start)
            ?: if (forceComplete && sqlCompletionAllowed(content, sel.start)) {
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
        ref.alias?.let { CompletionItem(it, if (ref.derived) t(Str.EditorCompletionSubquery) else ref.table, CompletionKind.ALIAS) }
    }
    val functionItems = if (qualified != null) emptyList() else completionFunctions.map {
        CompletionItem(it, t(Str.EditorCompletionFunction), CompletionKind.FUNCTION)
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
                detail = t(Str.EditorCompletionExpandColumns, columnItems.size),
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
            templates = if (editorLanguage == EditorLanguage.SQL) liveTemplates else emptyList(),
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
        esDslIndexName(content) ?: profile?.database?.trim()?.takeIf { it.isNotEmpty() }
    } else null
    val dslSchema = defaultSchema ?: schemas.firstOrNull()
    val dslFields = if (jsonMode && dslIndex != null && profile != null) {
        columnCatalog?.peek(profile.id, dslSchema, dslIndex)?.map { it.name }.orEmpty()
    } else emptyList()
    val dslSuggestion = if (jsonMode && canComplete) {
        esDslSuggestions(content, sel.start, dslFields, completionIdentifiers)
    } else null
    // 空前缀只在显式 Ctrl+Space 时列上下文（与 SQL 一致，避免回车被弹层截走）
    val dslWord = dslSuggestion?.word?.takeIf { forceComplete || it.text.isNotEmpty() }
    val word: CompletionWord? = if (jsonMode) dslWord else sqlWord
    val shown: List<CompletionItem> =
        if (jsonMode) (if (dslWord != null) dslSuggestion.items else emptyList()) else sqlShown
    var selIdx by remember(shown) { mutableStateOf(0) }
    var dismissed by remember(word?.start, word?.end, shown.size) { mutableStateOf(false) }
    val popupOpen = shown.isNotEmpty() && !dismissed

    /** 用模板替换 [wordStart, wordEnd) 并进入会话（选区落到第一个占位符；无占位符只落 endOffset）。 */
    fun startTemplate(tmpl: LiveTemplate, wordStart: Int, wordEnd: Int) {
        val (newText, session) = beginSession(content, wordStart until wordEnd, tmpl.body)
        suppressTextActivation = true
        editing = false
        state.edit {
            replace(0, length, newText)
            val stop = activeStop(session)
            selection = if (stop != null) TextRange(stop.start, stop.end) else TextRange(session.endOffset)
        }
        templateSession = session.takeIf { it.stops.isNotEmpty() }
    }

    /** 光标前单词精确等于某模板缩写则展开；否则 false，交回原逻辑。 */
    fun expandTemplateAtWord(): Boolean {
        if (editorLanguage != EditorLanguage.SQL) return false
        val w = sqlCompletionWord(content, sel.start) ?: return false
        val tmpl = liveTemplates.firstOrNull { it.abbreviation.equals(w.text, ignoreCase = true) } ?: return false
        startTemplate(tmpl, w.start, w.end)
        return true
    }

    fun accept(item: CompletionItem) {
        val w = word ?: return
        if (item.kind == CompletionKind.TEMPLATE) {
            liveTemplates.firstOrNull { it.abbreviation.equals(item.text, ignoreCase = true) }
                ?.let { startTemplate(it, w.start, w.end) }
            return
        }
        val insert = item.insertText ?: item.text
        // 抑制“本次文本变化”重新激活补全，接受后不自动重开（等下一次敲键或 Ctrl+Space）
        suppressTextActivation = true
        editing = false
        state.edit {
            replace(w.start, w.end, insert)
            selection = TextRange(w.start + insert.length)
        }
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
    val gutterWpx = with(density) { GUTTER_W.toPx() }
    val textPadLPx = with(density) { 2.dp.toPx() }
    val textPadRPx = with(density) { 6.dp.toPx() }
    val textTopPx = with(density) { 6.dp.toPx() }
    val lineHpx = with(density) { gutterLineHeight.toDp().toPx() }
    val textWpxInt = if (boxW > 0) (boxW - gutterWpx - textPadLPx - textPadRPx).toInt().coerceAtLeast(40) else 0
    // 关键：整篇文本排版必须缓存。`state.text` 与 `state.selection` 共用同一个 TextFieldState
    // 快照，选区变化也会触发重组；若每次都重测，长 SQL 下选中的高亮会明显滞后。
    // 键用 content（String，值相等即命中），选区变化不会失配。
    val textLayout = remember(content, editorStyle, textWpxInt) {
        if (textWpxInt > 40) {
            runCatching {
                textMeasurer.measure(
                    AnnotatedString(content),
                    style = editorStyle,
                    constraints = Constraints(maxWidth = textWpxInt),
                )
            }.getOrNull()
        } else {
            null
        }
    }
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
        val off = sel.start.coerceIn(0, content.length)
        val top = textTopPx + lay.getLineTop(lay.getLineForOffset(off))
        val viewport = (boxH - 2f * textTopPx).coerceAtLeast(lineHpx)
        val target = (top + lineHpx / 2f - viewport / 2f).coerceAtLeast(0f)
        scroll.scrollTo(target.toInt())
    }

    // 多光标新增时把活动光标行滚到可视区（Alt+Shift+↑/↓ 加完光标立刻可见）。
    LaunchedEffect(multiCursors.size) {
        if (multiCursors.isEmpty() || boxH <= 0) return@LaunchedEffect
        val lay = textLayout ?: return@LaunchedEffect
        val off = multiCursors.last().head.coerceIn(0, content.length)
        val top = textTopPx + lay.getLineTop(lay.getLineForOffset(off))
        val viewport = (boxH - 2f * textTopPx).coerceAtLeast(lineHpx)
        val target = (top + lineHpx / 2f - viewport / 2f).coerceAtLeast(0f)
        scroll.scrollTo(target.toInt())
    }

    // 拖拽选区自动滚动循环：指针在边缘区时持续滚动，并把选区焦点移到边缘所在文本位置
    // （固定端 = 开始拖拽时远离指针的那一端，锁在 scrollAnchor 里）。
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
    // 右侧滚动条区域（含 dirty 提示）：按下不算拖选，否则拖滚动条到底/回顶会误触发自动滚动选字
    val scrollbarZonePx = with(density) { (dbScrollbarStyle().thickness.value + 8f).dp.toPx() }
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
            val curSel = state.selection
            val anchor = scrollAnchor ?: (if (scrollDir < 0) curSel.max else curSel.min)
            scrollAnchor = anchor
            val ns = TextRange(minOf(anchor, focusOff), maxOf(anchor, focusOff))
            if (ns != curSel) state.edit { selection = ns }
            kotlinx.coroutines.delay(16)
        }
    }

    // 行号配色（主题派生）
    val gutterColor = themeColors.editorForeground.copy(alpha = 0.35f)
    val gutterCurColor = themeColors.editorForeground
    val gutterCurBg = themeColors.primary.copy(alpha = 0.16f)
    val numStyle = TextStyle(fontFamily = gutterFontFamily, fontSize = gutterFontSize, color = gutterColor)
    val numCurStyle = TextStyle(
        fontFamily = gutterFontFamily, fontSize = gutterFontSize, color = gutterCurColor,
        fontWeight = FontWeight.Medium,
    )
    // 行状态标记（执行结果）：一行多语句聚合为一点，点右侧小数字标识语句数
    val lineMarkers = remember(progress) { aggregateLineMarkers(progress) }
    val markerR = with(density) { 5.5.dp.toPx() }
    val markerCx = with(density) { 10.dp.toPx() }
    val markerCountStyle = TextStyle(
        fontFamily = gutterFontFamily,
        fontSize = (editorSettings.fontSizeSp * 0.6f).sp,
        color = gutterColor,
    )

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(6.dp))
                .background(themeColors.editorBackground)
                .border(1.dp, themeColors.onSurface.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                .onSizeChanged { boxW = it.width; boxH = it.height }
                .pointerInput(Unit) {
                    // 旁路观察拖拽指针（Final pass：文本域已在本 pass 前处理完选区，仅读取不消费）
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Final)
                            when (e.type) {
                                PointerEventType.Press -> if (e.buttons.isPrimaryPressed) {
                                    exitMultiCursor()
                                    val p = e.changes.firstOrNull()?.position
                                    // 滚动条上的按下不是文本拖选：避免拖动滚动条时被误判成拖选选区
                                    val onScrollbar = boxW > 0 && p != null && p.x >= boxW - scrollbarZonePx
                                    dragActive = !onScrollbar
                                    scrollAnchor = null
                                    dragPointer = if (onScrollbar) null else p
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
                                                val curSel = state.selection
                                                val ns = TextRange(
                                                    minOf(anchor, focusOff),
                                                    maxOf(anchor, focusOff),
                                                )
                                                if (ns != curSel) {
                                                    state.edit { selection = ns }
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
                // 行号槽：Canvas 只绘可视行，滚动时仅重绘（scroll.value 在 draw 内读取）；
                // 行状态点画在行号左侧，点击行 → 切到该行语句的结果 tab。
                Spacer(
                    modifier = Modifier
                        .width(GUTTER_W)
                        .fillMaxHeight()
                        .clipToBounds()
                        .pointerInput(lineMarkers, gutterTops, lineHpx) {
                            detectTapGestures { pos ->
                                val tops = gutterTops
                                val y = pos.y + scroll.value
                                for (i in tops.indices) {
                                    if (y >= tops[i] && y < tops[i] + lineHpx) {
                                        if (lineMarkers[i] != null) onLineMarkerClick(i)
                                        break
                                    }
                                }
                            }
                        }
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
                                    // 执行结果状态点（语义色，只做色点）；一行多语句时右侧标语句数
                                    lineMarkers[i]?.let { marker ->
                                        val cy = top + lineHpx / 2f
                                        drawCircle(
                                            color = lineMarkerColor(marker.status),
                                            radius = markerR,
                                            center = Offset(markerCx, cy),
                                        )
                                        if (marker.count > 1) {
                                            val cm = gutterMeasurer.measure(
                                                AnnotatedString(marker.count.toString()),
                                                style = markerCountStyle,
                                            )
                                            drawText(
                                                textLayoutResult = cm,
                                                topLeft = Offset(
                                                    markerCx + markerR + 1f,
                                                    top + ((lineHpx - cm.size.height) / 2f).coerceAtLeast(0f),
                                                ),
                                            )
                                        }
                                    }
                                    val m = gutterMeasurer.measure(
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
                    state = state,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = 2.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
                        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                        .onFocusChanged { if (!it.isFocused) exitMultiCursor() }
                        .verticalScroll(scroll)
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            if (keymap.matches(ShortcutCommand.EXECUTE, e)) {
                                // 选中 SQL 才执行；无选中什么都不做（禁止整段执行）
                                if (multiCursors.isNotEmpty()) exitMultiCursor()
                                onCtrlEnter()
                                return@onPreviewKeyEvent true
                            }
                            // 基础编辑键（固定）：Ctrl+Space 显式唤起补全（Esc 关闭后可重新呼出；空前缀也列出上下文列/表）
                            if (keymap.matches(ShortcutCommand.COMPLETE, e)) {
                                // 显式唤起：允许空前缀（列/表）；未敲字也行
                                if (multiCursors.isNotEmpty()) exitMultiCursor()
                                editing = true
                                forceComplete = true
                                dismissed = false
                                return@onPreviewKeyEvent true
                            }
                            // 业务功能：复制当前行 / 选区覆盖的整块行（可配置，默认 Ctrl+Y）
                            if (keymap.matches(ShortcutCommand.DUPLICATE_LINE, e)) {
                                duplicateLine()
                                return@onPreviewKeyEvent true
                            }
                            // Ctrl+Tab / Ctrl+Shift+Tab：按最近使用切控制台（消费，避免 Tab 走焦点遍历）
                            if (keymap.matches(ShortcutCommand.SWITCH_CONSOLE_NEXT, e)) {
                                editing = false // 顺带收起补全弹层
                                onSwitchConsole(1)
                                return@onPreviewKeyEvent true
                            }
                            if (keymap.matches(ShortcutCommand.SWITCH_CONSOLE_PREV, e)) {
                                editing = false
                                onSwitchConsole(-1)
                                return@onPreviewKeyEvent true
                            }
                            // Live Templates：会话内 Tab/Shift+Tab/Esc；无会话时「精确缩写 + Tab」展开。
                            // 精确缩写优先于补全上屏（DataGrip 语义）；其余 Tab 放行到下面的弹层分支。
                            if (editorLanguage == EditorLanguage.SQL) {
                                val session = templateSession
                                if (session != null) {
                                    when (e.key) {
                                        Key.Tab -> {
                                            if (e.isShiftPressed) {
                                                val (ns, target) = sessionPrev(session)
                                                templateSession = ns
                                                state.edit { selection = TextRange(target.first, target.second) }
                                            } else {
                                                val (ns, target) = sessionNext(session)
                                                templateSession = ns
                                                state.edit { selection = TextRange(target.first, target.second) }
                                            }
                                            return@onPreviewKeyEvent true
                                        }
                                        Key.Escape -> { templateSession = null; return@onPreviewKeyEvent true }
                                    }
                                } else if (e.key == Key.Tab && !e.isShiftPressed && !e.isCtrlPressed &&
                                    !e.isAltPressed && sel.collapsed
                                ) {
                                    if (expandTemplateAtWord()) return@onPreviewKeyEvent true
                                }
                            }
                            // 多光标列编辑：Alt+Shift+↑/↓ 加光标；Shift+←/→ 横向扩展；Esc / 普通方向键退出
                            if (e.isAltPressed && e.isShiftPressed && !e.isCtrlPressed) {
                                when (e.key) {
                                    Key.DirectionUp -> { addMultiCursor(-1); return@onPreviewKeyEvent true }
                                    Key.DirectionDown -> { addMultiCursor(1); return@onPreviewKeyEvent true }
                                    else -> {}
                                }
                            }
                            if (multiCursors.isNotEmpty()) {
                                // Shift+←/→ 与 Ctrl+Shift+←/→ 都按「所有光标同步扩展」处理。
                                // （很多用户习惯用 Ctrl+Shift 选词；若放给原生，只会移动活动光标，
                                // 导致看起来只有最后一行被选中、且替换编辑不生效。）
                                if (e.isShiftPressed && !e.isAltPressed) {
                                    when (e.key) {
                                        Key.DirectionLeft -> { shiftMultiCursors(-1); return@onPreviewKeyEvent true }
                                        Key.DirectionRight -> { shiftMultiCursors(1); return@onPreviewKeyEvent true }
                                        else -> {}
                                    }
                                }
                                if (!e.isCtrlPressed && !e.isAltPressed && !e.isShiftPressed) {
                                    when (e.key) {
                                        Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> {
                                            exitMultiCursor()
                                            return@onPreviewKeyEvent false
                                        }
                                        Key.Escape -> { exitMultiCursor(); return@onPreviewKeyEvent true }
                                        else -> {}
                                    }
                                }
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
                    textStyle = editorStyle,
                    keyboardOptions = KeyboardOptions.Default,
                    lineLimits = TextFieldLineLimits.MultiLine(1, Int.MAX_VALUE),
                    cursorBrush = SolidColor(themeColors.editorForeground),
                    outputTransformation = outputTransformation,
                    decorator = object : TextFieldDecorator {
                        @Composable
                        override fun Decoration(innerTextField: @Composable () -> Unit) {
                            Box {
                                if (content.isEmpty()) {
                                    Text(
                                        if (jsonMode) {
                                            t(Str.EditorEsPlaceholder)
                                        } else {
                                            t(Str.EditorSqlPlaceholder)
                                        },
                                        fontSize = editorStyle.fontSize,
                                        lineHeight = editorStyle.lineHeight,
                                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.35f),
                                    )
                                }
                                innerTextField()
                            }
                        }
                    },
                )
            }
            // 多光标 overlay：自绘额外光标线与矩形选区（不拦截指针）；主光标仍由 BasicTextField 原生绘制。
            if (multiCursors.isNotEmpty()) {
                val lay = textLayout
                val cursorColor = themeColors.editorForeground
                val selColor = themeColors.primary.copy(alpha = 0.22f)
                val baseX = gutterWpx + textPadLPx
                Spacer(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            val layout = lay ?: return@drawBehind
                            val scrolled = scroll.value
                            multiCursors.forEach { cursor ->
                                val head = cursor.head.coerceIn(0, content.length)
                                val lineIdx = layout.getLineForOffset(head)
                                val lineTop = layout.getLineTop(lineIdx) + textTopPx - scrolled
                                val lineBottom = layout.getLineBottom(lineIdx) + textTopPx - scrolled
                                val x = baseX + layout.getCursorRect(head).left
                                if (cursor.collapsed) {
                                    drawLine(
                                        color = cursorColor,
                                        start = Offset(x, lineTop),
                                        end = Offset(x, lineBottom),
                                        strokeWidth = 2f,
                                    )
                                } else {
                                    val start = cursor.start.coerceIn(0, content.length)
                                    val end = cursor.end.coerceIn(0, content.length)
                                    if (layout.getLineForOffset(start) == layout.getLineForOffset(end)) {
                                        val left = baseX + layout.getCursorRect(start).left
                                        val right = baseX + layout.getCursorRect(end).left
                                        drawRect(
                                            color = selColor,
                                            topLeft = Offset(left, lineTop),
                                            size = Size((right - left).coerceAtLeast(0f), lineBottom - lineTop),
                                        )
                                        drawLine(
                                            color = cursorColor,
                                            start = Offset(x, lineTop),
                                            end = Offset(x, lineBottom),
                                            strokeWidth = 2f,
                                        )
                                    } else {
                                        drawLine(
                                            color = cursorColor,
                                            start = Offset(x, lineTop),
                                            end = Offset(x, lineBottom),
                                            strokeWidth = 2f,
                                        )
                                    }
                                }
                            }
                        },
                )
            }
            // 右侧纵向滚动条（文本区 end padding 已留 6dp，不会遮字）
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scroll),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(vertical = 6.dp, horizontal = 3.dp),
                style = dbScrollbarStyle(),
            )
            // 编辑状态提示（● = 有未落盘改动；Ctrl+S 立即保存）
            Text(
                if (dirty) t(Str.EditorDirty) else t(Str.EditorSaved),
                fontSize = 10.sp,
                color = if (dirty) MaterialTheme.colors.primary.copy(alpha = 0.75f)
                else MaterialTheme.colors.onSurface.copy(alpha = 0.3f),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 6.dp),
            )
        }
        // 补全弹层：位置 = caret 的真实排版位置（TextMeasurer 按内宽换行测出，避免覆盖正在输入的行）
        if (popupOpen) {
            val w = word ?: return@Box
            val gapPx = with(density) { 6.dp.toPx() }
            val caretRect = textLayout?.getCursorRect(sel.start.coerceIn(0, content.length))
            val textLeftPx = gutterWpx + textPadLPx
            // 宽度自适应：最长候选名 + 最长详情（+ 色点 6dp / 间距 8dp / 左右内边距 20dp），
            // 夹在 [COMPLETION_W, COMPLETION_MAX_W] 且不超过编辑区内宽 - 4dp，长表名不再被省略成 table_…
            // 测量样式必须与 Text 实际用的样式同源：Text 会在 LocalTextStyle 基础上合并显式参数
            // （字号/字族被覆盖，但 letterSpacing 等继承），不带上它会低估宽度 → 刚好放不下又出现省略号。
            val baseTextStyle = LocalTextStyle.current
            val popupWidth: Dp = remember(shown, boxW, density, textMeasurer, baseTextStyle) {
                val nameStyle = baseTextStyle.merge(
                    TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                )
                val detailStyle = baseTextStyle.merge(TextStyle(fontSize = 10.sp))
                val nameW = shown.maxOfOrNull {
                    popupMeasurer.measure(AnnotatedString(it.text), nameStyle).size.width
                } ?: 0
                val detailW = shown.asSequence().mapNotNull { it.detail }
                    .maxOfOrNull { popupMeasurer.measure(AnnotatedString(it), detailStyle).size.width }
                    ?: 0
                with(density) {
                    // chrome: 左右内边距 20 + 色点 6 + 间距 8（有详情时再加 8）+ 2dp 取整/字距余量
                    val chrome = 36.dp.roundToPx() + if (detailW > 0) 8.dp.roundToPx() else 0
                    val max = (boxW - 4.dp.toPx())
                        .coerceAtLeast(COMPLETION_W.toPx())
                        .coerceAtMost(COMPLETION_MAX_W.toPx())
                    (nameW + detailW + chrome).coerceIn(COMPLETION_W.roundToPx(), max.toInt()).toDp()
                }
            }
            val popW = with(density) { popupWidth.toPx() }
            val popH = with(density) { popupH.toPx() }
            CompletionPopup(
                items = shown,
                selectedIndex = selIdx,
                onSelect = { i -> accept(shown[i]) },
                modifier = Modifier
                    .width(popupWidth)
                    .height(popupH)
                    .offset {
                        val x = if (caretRect != null) {
                            (textLeftPx + caretRect.left).toInt().coerceIn(0, (boxW - popW.toInt()).coerceAtLeast(0))
                        } else {
                            val before = content.substring(0, w.start)
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

private val GUTTER_W = 48.dp

/** gutter 行状态点的语义色（只做色块/点；文字仍走主题色）。 */
private fun lineMarkerColor(status: RunStatus): Color = when (status) {
    RunStatus.OK -> Color(0xFF43A047)
    RunStatus.FAILED -> Color(0xFFE53935)
    RunStatus.RUNNING, RunStatus.PENDING -> Color(0xFFFFB300)
    RunStatus.SKIPPED -> Color(0xFF9E9E9E)
}

// 补全弹层宽度：300dp 为下限（原固定宽度，保持观感）；实际宽度按候选内容自适应，上限 960dp
private val COMPLETION_W = 300.dp

private val COMPLETION_MAX_W = 1260.dp

private val COMPLETION_H = 176.dp

private const val MAX_COMPLETIONS = 60

/** 结果表 / 编辑器共用的纵向滚动条样式（主题派生色，深色下可见）。 */
@Composable
internal fun dbScrollbarStyle(): ScrollbarStyle = ScrollbarStyle(
    minimalHeight = 24.dp,
    thickness = 10.dp,
    shape = RoundedCornerShape(5.dp),
    hoverDurationMillis = 300,
    unhoverColor = MaterialTheme.colors.onSurface.copy(alpha = 0.20f),
    hoverColor = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
)
