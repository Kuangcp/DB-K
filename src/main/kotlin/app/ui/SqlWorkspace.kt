package app.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
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
import tree.ConnUiStatus
import tree.TypeBadge

/** 每个单元格最窄 64dp / 最宽 320dp（字符数 → dp 估算，12sp monospace 约 0.6em/字符）。 */
private fun estWidth(chars: Int): Int = (chars * 7 + 20).coerceIn(64, 320)

/**
 * 右侧 SQL 工作台（M4：多控制台）。
 * 结构：顶部「数据源切换 + 控制台标签」→ 编辑器（当前控制台缓冲区）→ 执行条 → 结果区。
 * 纯展示组件；全部编排/持久化在 Main/ConsoleState。
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
    onCreateConsole: () -> Unit,
    onRenameConsole: (ConsoleRecord) -> Unit,
    onDeleteConsole: (ConsoleRecord) -> Unit,
    editorText: String,
    editorDirty: Boolean,
    onTextChange: (String) -> Unit,
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
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
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
        if (profile == null) {
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
        ConnectionNavBar(profile, status, statusMessage, profiles, onSelectProfile, onDisconnect)
        ConsoleTabBar(
            consoles = consoles,
            activeConsole = activeConsole,
            onSelectConsole = onSelectConsole,
            onCreateConsole = onCreateConsole,
            onRenameConsole = onRenameConsole,
            onDeleteConsole = onDeleteConsole,
        )
        Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.08f))
        // 编辑器状态：文本由外部权威（切换控制台/预览/清空），选区是本地瞬态
        var tfv by remember { mutableStateOf(TextFieldValue(editorText)) }
        LaunchedEffect(editorText) {
            if (tfv.text != editorText) tfv = TextFieldValue(editorText, TextRange(editorText.length))
        }
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EditorPane(
                        value = tfv,
                        dirty = editorDirty,
                        onValueChange = { v -> tfv = v; onTextChange(v.text) },
                        onCtrlEnter = { selectedSqlOf(tfv)?.let(onRun) },
                        completionIdentifiers = completionIdentifiers,
                        modifier = Modifier.weight(0.44f).fillMaxWidth(),
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
                    ResultPane(
                        result = run.result,
                        error = run.error,
                        transposed = transposed,
                        onCopyText = onCopyText,
                        modifier = Modifier.weight(0.56f).fillMaxWidth(),
                    )
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

/** 绑定数据源导航行：点击连接名可切换到其它数据源；含连接状态与断开。 */
@Composable
private fun ConnectionNavBar(
    profile: ConnectionProfile,
    status: ConnUiStatus,
    statusMessage: String?,
    profiles: List<ConnectionProfile>,
    onSelectProfile: (String) -> Unit,
    onDisconnect: () -> Unit,
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
        Text(
            "SQL 文件自动保存",
            fontSize = 10.5.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.35f),
        )
        if (status == ConnUiStatus.CONNECTED) {
            TextButton(onClick = onDisconnect) { Text("断开", fontSize = 12.sp) }
        }
    }
}

/** 控制台标签条：当前数据源下所有控制台 + 「新建」。 */
@Composable
private fun ConsoleTabBar(
    consoles: List<ConsoleRecord>,
    activeConsole: ConsoleRecord?,
    onSelectConsole: (ConsoleRecord) -> Unit,
    onCreateConsole: () -> Unit,
    onRenameConsole: (ConsoleRecord) -> Unit,
    onDeleteConsole: (ConsoleRecord) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(36.dp).padding(start = 12.dp, end = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
        ) {
            consoles.forEach { c ->
                ConsoleChip(
                    console = c,
                    active = c.id == activeConsole?.id,
                    onSelect = { onSelectConsole(c) },
                    onRename = { onRenameConsole(c) },
                    onDelete = { onDeleteConsole(c) },
                )
                Spacer(Modifier.width(5.dp))
            }
        }
        Spacer(Modifier.width(6.dp))
        IconButton(
            onClick = onCreateConsole,
            modifier = Modifier.size(24.dp),
        ) {
            Icon(
                Icons.Filled.Add, "新建控制台",
                tint = MaterialTheme.colors.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun ConsoleChip(
    console: ConsoleRecord,
    active: Boolean,
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
                .padding(horizontal = 9.dp, vertical = 4.dp),
        ) {
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

/**
 * SQL 编辑器（语法高亮 + 自动补全）。
 *
 * 结构：外层自绘边框/底；内部 BasicTextField 消费带 span 高亮的 TextFieldValue（NeoUtils
 * rememberHighlight + rememberTextFieldValue 实时着色），滚动用外层 verticalScroll。
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

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colors.surface)
                .border(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                .onSizeChanged { boxW = it.width; boxH = it.height },
        ) {
            BasicTextField(
                value = highlightedValue.copy(composition = value.composition),
                onValueChange = ::commitEdit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
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
            // 编辑状态提示（● = 有未落盘改动，自动保存中）
            Text(
                if (dirty) "● 未保存" else "已保存到 .sql 文件",
                fontSize = 10.sp,
                color = if (dirty) MaterialTheme.colors.primary.copy(alpha = 0.75f)
                else MaterialTheme.colors.onSurface.copy(alpha = 0.3f),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 6.dp),
            )
        }
        // 补全弹层：位置 = caret 的真实排版位置（TextMeasurer 按内宽换行测出，避免覆盖正在输入的行）
        if (popupOpen) {
            val w = word ?: return@Box
            val padXPx = with(density) { 10.dp.toPx() }
            val padYPx = with(density) { 8.dp.toPx() }
            val gapPx = with(density) { 6.dp.toPx() }
            val innerMaxW = (boxW - padXPx * 2).toInt().coerceAtLeast(40)
            // 与 BasicTextField 同 style + 同内宽排版，取 caret 处字符框作为锚点
            val layout = runCatching {
                textMeasurer.measure(
                    AnnotatedString(value.text),
                    style = editorStyle,
                    constraints = Constraints(maxWidth = innerMaxW),
                )
            }.getOrNull()
            val caretRect = layout?.getCursorRect(sel.start.coerceIn(0, value.text.length))
            val padX = 10.dp
            val padY = 8.dp
            val charW = 7.8.dp
            val lineH = 20.dp
            val gap = 6.dp
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
                            (padXPx + caretRect.left).toInt().coerceIn(0, (boxW - popW.toInt()).coerceAtLeast(0))
                        } else {
                            val before = value.text.substring(0, w.start)
                            val lineNo = before.count { it == '\n' }
                            val colNo = w.start - (before.lastIndexOf('\n') + 1)
                            (padX.toPx() + colNo * charW.toPx()).toInt().coerceIn(0, (boxW - popW.toInt()).coerceAtLeast(0))
                        }
                        val caretTop = if (caretRect != null) padYPx + caretRect.top - scroll.value else padY.toPx() - scroll.value
                        val caretBottom = if (caretRect != null) caretTop + caretRect.height else caretTop + lineH.toPx()
                        // 下移一格再放：弹窗顶部低于 caret 行下一行的行底，确保不压住当前输入行与紧随其后的行
                        val rowH = if (caretRect != null) caretRect.height else lineH.toPx()
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
 * 交互：单击单元格复制值（NULL → 空串）；右键单元格可「复制单元格值 / 复制本行 → INSERT」。
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
    val hScroll = rememberLazyListState()
    // 每行可生成的 INSERT（仅原布局；复杂查询/无法定表时 null）
    val tableName = extractTableName(result.sql)
    val insertSqls: List<String?> = if (transposed) view.rows.map { null }
    else result.rows.map { row -> rowToInsertSql(result.sql, result.columns.map { it.name }, row) }
    Column(modifier = modifier.fillMaxSize()) {
        LazyRow(state = hScroll, modifier = Modifier.background(MaterialTheme.colors.onSurface.copy(alpha = 0.06f))) {
            item {
                Row {
                    RowHeaderCell("", 44)
                    cols.forEachIndexed { c, col ->
                        RowHeaderCell(col.name, widths[c])
                    }
                }
            }
        }
        Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.1f))
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(view.rows) { index, row ->
                val insertSql = insertSqls.getOrNull(index)
                LazyRow(state = hScroll, modifier = Modifier.fillMaxWidth()) {
                    item {
                        Row(modifier = Modifier.background(
                            if (index % 2 == 1) MaterialTheme.colors.onSurface.copy(alpha = 0.025f)
                            else Color.Transparent,
                        )) {
                            DataCell("${index + 1}", 44, mono = false, muted = true)
                            row.forEachIndexed { c, v ->
                                val colName = view.columns[c].name
                                DataCell(
                                    value = v,
                                    width = widths[c],
                                    onCopy = {
                                        copyCellValue(onCopyText, v, colName)
                                    },
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
                    }
                }
                Divider(
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.05f),
                    modifier = Modifier.padding(start = 44.dp),
                )
            }
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
    onCopy: (() -> Unit)? = null,
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
    if (onCopy == null && menuItems.isEmpty()) {
        Box(modifier = base, contentAlignment = Alignment.CenterStart) { content() }
    } else {
        ContextMenuArea(items = { menuItems }) {
            Box(
                modifier = base.clickable(onClick = onCopy ?: {}),
                contentAlignment = Alignment.CenterStart,
            ) {
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
