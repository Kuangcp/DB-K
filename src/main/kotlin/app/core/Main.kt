@file:kotlin.jvm.JvmName("DbkMainKt")
// 主类固定为 app.core.DbkMainKt（不是默认的 MainKt）：AWT 窗口的 WM_CLASS = 主类名（点转短横），
// 与 api-x 等兄弟项目同名 MainKt 会导致 X11 面板把两个应用当同一个程序归并成一组。

package app.core

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.core.export.ExportFormat
import app.core.export.ExportOptions
import app.core.export.ResultExport
import app.core.export.defaultQuoteIdent
import app.dialog.CommitPreviewDialog
import app.dialog.ConfirmDialog
import app.dialog.ConnectionEditorDialog
import app.dialog.ConsoleNameDialog
import app.dialog.DdlDialog
import app.dialog.ExportDialog
import app.dialog.FolderNameDialog
import app.dialog.ImportConflictDialog
import app.dialog.PassphraseDialog
import app.dialog.SettingsDialog
import app.dialog.SettingsSnapshot
import app.settings.EditorPrefs
import app.settings.KeymapPrefs
import app.settings.ShortcutCommand
import app.settings.ThemePrefs
import app.settings.TreeExpandPrefs
import app.settings.WindowPrefs
import app.state.CommitPreviewRequest
import app.state.ConfirmRequest
import app.state.ConnectionEditorRequest
import app.state.ConnectionsState
import app.state.ConsoleRenameRequest
import app.state.ConsoleState
import app.state.ConsoleRunUi
import app.state.DialogState
import app.state.ExportRequest
import app.state.FolderDialogRequest
import app.state.ImportConflictRequest
import app.state.PassphraseRequest
import app.state.TableDdlRequest
import app.state.TreeState
import app.state.ToastState
import app.ui.CompletionTable
import app.ui.CompletionDismissSignal
import app.ui.LocalCompletionDismiss
import app.ui.LocalKeymap
import app.ui.ResultEdits
import app.ui.SqlWorkspace
import app.ui.appMaterialColors
import app.ui.matchAnyAwt
import app.ui.sqlHasOrderBy
import app.ui.tableAtCaret
import db.AppPaths
import db.ConnectionProfile
import db.ConnectionsRepository
import db.ConsoleRecord
import db.ProfileTransfer
import engine.EditorLanguage
import engine.Protocol
import engine.model.ObjectKind
import engine.model.QueryResult
import engine.model.SchemaMeta
import engine.model.displayNoun
import engine.model.isPreviewable
import jdbc.ExternalDrivers
import jdbc.QueryExecutor
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import org.tinylog.Logger
import tree.ConnUiStatus
import tree.DbTreeSidebar
import tree.TreeRowInfo
import tree.TreeRowKind
import tree.buildTreeRows
import java.awt.Cursor
import java.awt.FileDialog
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

fun main() = application {
    // 首条日志触发 tinylog 初始化 → SessionLogWriter 立即创建本次会话日志文件（logs/yyyy-MM/yyyy-MM-dd_N.log）
    Logger.info("db-k session start; dataDir={}", AppPaths.dataDirectory())
    // 未捕获异常（AWT-EventQueue / 后台线程 / 协程）默认只打到 stderr、不进会话日志；统一挂到
    // tinylog。JDK 的 EDT 在 processException 里调线程的 UncaughtExceptionHandler，最终落到这里。
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        Logger.error(throwable, "uncaught exception on thread {}", thread.name)
    }
    // glibc 原生内存治理：压低 mmap/trim 阈值，避免大块分配的峰值变成常驻 RSS
    NativeMemory.configure()
    // 外部 JDBC 驱动（SQL Server / Oracle）：<dataDir>/drivers 下的 jar 以独立 classloader 加载。
    // 需重启才生效（新增 jar 后重启应用）。
    ExternalDrivers.ensureLoaded()
    AppRoot(onExit = ::exitApplication)
}

@Composable
private fun AppRoot(onExit: () -> Unit) {
    // 窗口几何：上次退出时保存则恢复位置与尺寸
    val geo = remember { WindowPrefs.load() }
    val density = LocalDensity.current.density
    val windowState = rememberWindowState(
        width = Dp((geo?.width ?: 1180) / density),
        height = Dp((geo?.height ?: 760) / density),
        position = if (geo?.x != null && geo.y != null) {
            WindowPosition(Dp(geo.x / density), Dp(geo.y / density))
        } else {
            WindowPosition(Alignment.Center)
        },
    )

    val repository = remember { ConnectionsRepository(AppPaths.appDatabasePath()) }
    val expandLoaded = remember { TreeExpandPrefs.load() }
    val treeState = remember { TreeState(repository, expandLoaded) }
    val connectionsState = remember { ConnectionsState() }
    val dialogState = remember { DialogState() }
    val toastState = remember { ToastState() }
    val scope = rememberCoroutineScope()
    val consoleState = remember {
        ConsoleState(repository, connectionsState, scope) { command ->
            // 危险命令（Redis FLUSHALL 等）：弹确认框，用户选择后继续/取消。
            suspendCancellableCoroutine { cont ->
                dialogState.confirm = ConfirmRequest.DangerConfirm(command) { ok ->
                    dialogState.confirm = null
                    if (cont.isActive) cont.resume(ok)
                }
                cont.invokeOnCancellation { dialogState.confirm = null }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            consoleState.flushAllSync()
            connectionsState.disposeAll()
            repository.close()
        }
    }

    // 窗口几何：只在浮动状态落盘（最大化/全屏时尺寸是桌面给的，不是用户的恢复尺寸）；
    // 位置不是绝对坐标（如首次启动居中）时仍保存尺寸，但不保存 x/y。
    fun persistWindowGeometry() {
        if (windowState.placement != WindowPlacement.Floating) return
        if (!windowState.size.isSpecified) return
        val pos = windowState.position as? WindowPosition.Absolute
        WindowPrefs.save(
            WindowPrefs.Geometry(
                x = pos?.x?.value?.times(density)?.roundToInt(),
                y = pos?.y?.value?.times(density)?.roundToInt(),
                width = (windowState.size.width.value * density).roundToInt(),
                height = (windowState.size.height.value * density).roundToInt(),
            ),
        )
    }

    // 退出流程（先落盘控制台草稿 + 保存窗口几何）；有未提交结果修改时先确认。
    val performExit: () -> Unit = {
        consoleState.flushAllSync()
        persistWindowGeometry()
        onExit()
    }

    // 自定义标题栏的关闭按钮与系统窗口关闭走同一条路径（含未提交修改确认）。
    val requestClose: () -> Unit = {
        val pending = consoleState.totalEditCount()
        if (pending > 0) {
            dialogState.confirm = ConfirmRequest.DiscardResultEdits(pending, "退出应用") { performExit() }
        } else {
            performExit()
        }
    }

    Window(
        title = "DB-K",
        state = windowState,
        undecorated = true,
        onCloseRequest = requestClose,
    ) {
        // 运行时窗口图标（X11 任务栏/装饰、Windows 任务栏）：从 classpath 读 png 设到 AWT Frame。
        // 不用 compose painterResource(String)——已废弃且工程 -Werror。
        LaunchedEffect(Unit) {
            db.AppPaths::class.java.getResourceAsStream("/icon/db-k.png")?.use { s ->
                val img = javax.imageio.ImageIO.read(s) ?: return@LaunchedEffect
                window.iconImages = listOf(img)
            }
        }
        AppBody(
            repository = repository,
            treeState = treeState,
            connectionsState = connectionsState,
            consoleState = consoleState,
            dialogState = dialogState,
            toastState = toastState,
            mainWindowState = windowState,
            onWindowCloseRequest = requestClose,
        )
    }
}

@Composable
private fun WindowScope.AppBody(
    repository: ConnectionsRepository,
    treeState: TreeState,
    connectionsState: ConnectionsState,
    consoleState: ConsoleState,
    dialogState: DialogState,
    toastState: ToastState,
    mainWindowState: WindowState,
    onWindowCloseRequest: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var isDark by remember { mutableStateOf(ThemePrefs.load() ?: false) }
    // 编辑器外观（字体/字号）：设置窗口保存后即写盘并即时生效
    var editorSettings by remember { mutableStateOf(EditorPrefs.load()) }
    // 快捷键（扩展业务功能可配置；基础编辑键固定）：设置窗口保存后写盘并即时生效
    var keymap by remember { mutableStateOf(KeymapPrefs.load()) }
    var treeWidthDp by remember { mutableStateOf(280f) }
    // 结果区显隐由窗口根层接管（Alt+D），任意焦点位置都能命中（编辑器/树搜索框都不会漏字）
    var resultsVisible by remember { mutableStateOf(true) }

    // 待插入编辑器的文本（预览 / 历史 SQL）：在激活控制台光标/选区处追加，由 SqlWorkspace 消费后清空
    var pendingInsert by remember { mutableStateOf<String?>(null) }

    /** 提交结果单元格修改（真正的写库动作；UI 侧先在 P7 预览弹窗确认）。 */
    fun commitEditsNow(c: ConsoleRecord, p: ConnectionProfile) {
        scope.launch {
            consoleState.commitEdits(c, p)
                .onSuccess { n -> toastState.show(if (n > 0) "已提交 $n 处修改" else "没有修改") }
                .onFailure { t -> toastState.show("提交失败：${t.message?.take(120)}") }
        }
    }

    /**
     * N8 结果导出：内存结果直接写（≤1000 行）；用户选「全量流式」时重跑 SQL，
     * 按方言游标逐行导出（不受行数上限、内存常量级）。文件写出在 `Dispatchers.IO`。
     */
    fun startResultExport(
        c: ConsoleRecord,
        p: ConnectionProfile,
        result: QueryResult,
        file: File,
        format: ExportFormat,
        options: ExportOptions,
        fullStream: Boolean,
    ) {
        if (!fullStream) {
            val quote = connectionsState.jdbcConnection(p.id)?.let { l -> l::quoteIdent } ?: ::defaultQuoteIdent
            runCatching { ResultExport.writeCached(file, format, result, options, quote) }
                .onSuccess { n -> toastState.show("已导出 $n 行 → ${file.name}") }
                .onFailure { t ->
                    Logger.error(t, "result export failed")
                    toastState.show("导出失败：${t.message?.take(80)}")
                }
            return
        }
        scope.launch {
            // 重跑需要活连接：先确保就绪（已连接时为幂等空操作）
            connectionsState.ensureConnectionReady(p)
            val live = connectionsState.jdbcConnection(p.id)
            if (live == null) {
                toastState.show("全量导出需要可用的 JDBC 连接，请重连后再试")
                return@launch
            }
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val contextSql = consoleState.sessionContextSqlFor(c, p)
                    ResultExport.writeStreamed(file, format, live, result.sql, contextSql, options, live::quoteIdent)
                }
            }
            outcome.onSuccess { n -> toastState.show("已导出 $n 行 → ${file.name}") }
                .onFailure { t ->
                    Logger.error(t, "stream export failed")
                    toastState.show("全量导出失败：${t.message?.take(80)}")
                }
        }
    }

    // 运行时状态在 composition 中读取（snapshot 依赖 → 状态变化自动重排）
    // Redis key pattern 防抖搜索：输入停 300ms 后才重扫（类型切换立即生效）
    var keySearchRequest by remember { mutableStateOf<Pair<ConnectionProfile, String>?>(null) }
    LaunchedEffect(keySearchRequest) {
        val (p, pattern) = keySearchRequest ?: return@LaunchedEffect
        delay(300)
        connectionsState.setObjectFilter(p, pattern, connectionsState.objectSearchOf(p.id).type)
    }

    val rows = buildTreeRows(
        treeState.folders,
        treeState.connections,
        treeState.expandedFolderIds,
        treeState.expandedConnectionIds,
        treeState.expandedSchemaKeys,
        treeState.expandedGroupKeys,
        connectionsState,
    )

    fun toggleRow(row: TreeRowInfo) {
        when (row.kind) {
            TreeRowKind.FOLDER -> treeState.toggleFolder(row.folderId!!)
            TreeRowKind.CONNECTION -> {
                val p = row.profile ?: return
                if (row.expanded) {
                    treeState.collapseConnection(p.id)
                } else {
                    treeState.expandConnection(p.id)
                    scope.launch { connectionsState.ensureConnectionReady(p) }
                }
            }
            TreeRowKind.SCHEMA -> {
                val p = row.profile ?: return
                val schema = row.schema ?: return
                if (row.expanded) {
                    treeState.collapseSchema(row.key)
                } else {
                    treeState.expandSchema(row.key)
                    scope.launch { connectionsState.ensureSchemaObjects(p, schema) }
                }
            }
            // 对象组（表/视图/序列…）支持展开/折叠：只展开单个类型组；
            // 懒加载方言在展开未加载组时触发该组正文拉取（P6）。
            TreeRowKind.OBJECT_GROUP -> {
                if (row.expanded) {
                    treeState.collapseGroup(row.key)
                } else {
                    treeState.expandGroup(row.key)
                    val p = row.profile
                    val s = row.schema
                    val g = row.groupKind
                    if (p != null && s != null && g != null) {
                        scope.launch { connectionsState.ensureGroupObjects(p, s, g) }
                    }
                }
            }
            else -> {}
        }
    }

    /** 树里选中某行：同步切换工作台数据源（保持“点哪用哪”）。 */
    fun selectRow(rowKey: String?) {
        treeState.select(rowKey)
        val profileId = rowProfileId(rowKey) ?: return
        val active = consoleState.activeConsole()
        if (active?.connectionId != profileId) {
            consoleState.activateForProfile(profileId)
        }
    }

    fun disconnectProfile(p: ConnectionProfile) {
        connectionsState.disconnect(p)
        treeState.collapseConnection(p.id)
    }

    // ---------- 控制台联动动作 ----------

    val profiles = treeState.connections
    val activeConsole = consoleState.activeConsole()
    val activeProfile = activeConsole?.let { a -> profiles.firstOrNull { it.id == a.connectionId } }

    // 控制台主导：启动即回到最近改动的控制台（跨数据源，不必先点树）；无控制台时保持引导态
    LaunchedEffect(Unit) {
        if (consoleState.activeConsoleId == null && profiles.isNotEmpty()) {
            consoleState.activateMostRecent(profiles.map { it.id })
        }
    }

    // 编辑器补全候选：当前数据源全部 schema 的表/视图/物化视图名 + 所属 schema。
    // 元数据（库列表 + 对象）已由 ConnectionsState 在连接建立时整体预取并读缓存（见 ensureConnectionReady），
    // 数据来自数据源目录元信息；树是否展开不影响候选完整性。
    val completionTables: List<CompletionTable> = activeProfile?.let { p ->
        // ES：对象是索引/别名（不是表/视图）；补全与 DSL 的 index 候选同源
        val es = p.dbType.protocol == Protocol.ELASTICSEARCH
        connectionsState.schemasOf(p.id).orEmpty()
            .flatMap { s ->
                connectionsState.objectsOf(p.id, s.key)
                    ?.let { o ->
                        if (es) (o.forKind(ObjectKind.INDEX) + o.forKind(ObjectKind.ALIAS)).map { it.name }
                        else o.tables + o.views + o.materializedViews
                    }.orEmpty()
                    .map { name -> CompletionTable(name, s) }
            }
    }.orEmpty()
    val completionIdentifiers: List<String> = completionTables.map { it.name }.distinct().sorted()
    // 函数/过程/聚合名（PG 等能探测到的数据源；其余为空集），补全时排在表名之前
    val completionFunctions: List<String> = activeProfile?.let { p ->
        connectionsState.schemasOf(p.id).orEmpty()
            .flatMap { s ->
                connectionsState.objectsOf(p.id, s.key)?.let { o ->
                    (o.forKind(ObjectKind.ROUTINE) + o.forKind(ObjectKind.AGGREGATE)).map { it.name }
                }.orEmpty()
            }
            .distinct()
            .sorted()
    }.orEmpty()

    fun runActiveConsole(sql: String?) {
        val c = consoleState.activeConsole() ?: return
        val p = profiles.firstOrNull { it.id == c.connectionId } ?: return
        val target = sql?.trim().orEmpty()
        if (target.isEmpty()) {
            toastState.show("请先选中要执行的 SQL（Ctrl+A 全选）")
            return
        }
        if (consoleState.runStateOf(c.id).executing) {
            toastState.show("已有查询在执行中（可点「取消」或按 Esc）")
            return
        }
        val pending = consoleState.editCount(c.id)
        if (pending > 0) {
            dialogState.confirm = ConfirmRequest.DiscardResultEdits(pending, "重新执行") {
                scope.launch { consoleState.run(c, p, target); NativeMemory.trim("after query") }
            }
            return
        }
        scope.launch { consoleState.run(c, p, target); NativeMemory.trim("after query") }
    }

    fun suggestConsoleName(profile: ConnectionProfile): String {
        val n = consoleState.profileConsoles(profile.id).size + 1
        return "控制台 $n"
    }

    /**
     * 双击对象 → 预览（SQL 后端 = SELECT 前 100 行；Redis = 按 key 类型的查看命令）：
     * 追加到该数据源最后打开控制台的光标/选区处，不新建、不覆盖草稿、不自动执行。
     */
    fun previewObject(row: TreeRowInfo) {
        val p = row.profile ?: return
        val obj = row.dbObject ?: return
        val session = connectionsState.sessionOf(p.id)
        if (session == null || !session.capabilities.objectPreview) {
            toastState.show("当前连接不支持对象预览")
            return
        }
        if (!obj.kind.isPreviewable()) {
            toastState.show("该对象类型不支持预览")
            return
        }
        val sql = session.previewQuery(row.schema, obj)
        // 复用该数据源已有控制台（优先最近激活；全关闭则重开最近改动；都没有则新建 控制台 1）——
        // 不因双击而重复新建控制台。
        val target = consoleState.activateForProfile(p.id) ?: return
        // 会话型目标（多 schema）：预览对象的命名空间随之切换；
        // Redis DB 是连接级过滤器（flatNamespaceOf），执行目标与 console.target 解耦，不在此写
        if (!connectionsState.flatNamespaceOf(p.id) &&
            session.capabilities.sessionContext && row.schema != null
        ) {
            consoleState.setTarget(target.id, row.schema.displayName)
        }
        // 交给编辑器在目标控制台光标/选区处插入，不自动执行
        pendingInsert = sql
    }

    // 编辑器补全元数据已由 ConnectionsState 在连接建立时统一预取（缓存命中/查库回写），
    // 不再依赖此处 keyed effect —— 旧的实现会在 CONNECTED 但库列表尚未加载完的窗口期提前
    // return，库列表就绪后又没有重触发，导致双击连接后补全一直为空。

    // Ctrl+Q 查看定义：优先取「编辑器光标所在表名」（最近 4s 内动过编辑器），其次取左侧树选中项。
    // 编辑器与树都没指向可看定义的表时提示。用 rememberUpdatedState 把最新 Lambda 交给 AWT 派发器。
    val lastEditorActivity = remember { java.util.concurrent.atomic.AtomicLong(0L) }

    fun ddlNoun(profileId: String, schema: SchemaMeta?, name: String): String {
        val s = schema ?: return "对象"
        val objs = connectionsState.objectsOf(profileId, s.key) ?: return "对象"
        for (kind in listOf(ObjectKind.TABLE, ObjectKind.VIEW, ObjectKind.MATERIALIZED_VIEW, ObjectKind.INDEX, ObjectKind.ALIAS)) {
            if (objs.forKind(kind).any { it.name.equals(name, ignoreCase = true) }) return kind.displayNoun
        }
        return "对象"
    }

    val resolveDdlTarget: () -> TableDdlRequest? = {
        val treeTarget = rows.firstOrNull { it.key == treeState.selectedRowKey }?.let { row ->
            val p = row.profile
            val obj = row.dbObject
            if (row.kind == TreeRowKind.DB_OBJECT && p != null && obj != null &&
                (p.dbType.protocol == Protocol.JDBC || p.dbType.protocol == Protocol.ELASTICSEARCH) &&
                obj.kind.isPreviewable()
            ) {
                TableDdlRequest(p, row.schema, obj.name, obj.kind.displayNoun)
            } else {
                null
            }
        }
        val editorTarget = run {
            val c = consoleState.activeConsole() ?: return@run null
            val p = profiles.firstOrNull { it.id == c.connectionId } ?: return@run null
            val text = consoleState.textOf(c.id)
            if (text.isBlank()) return@run null
            val (selStart, _) = consoleState.caretOf(c.id)
            val sch = connectionsState.schemasOf(p.id).orEmpty()
            val at = tableAtCaret(
                text = text,
                caret = selStart,
                knownTables = completionTables,
                schemas = sch,
                defaultSchema = sch.firstOrNull { it.displayName == c.target.orEmpty() },
            ) ?: return@run null
            TableDdlRequest(p, at.schema, at.name, ddlNoun(p.id, at.schema, at.name))
        }
        val recentEditor = System.currentTimeMillis() - lastEditorActivity.get() < 4000
        when {
            recentEditor && editorTarget != null -> editorTarget
            treeTarget != null -> treeTarget
            else -> editorTarget
        }
    }
    val resolveDdlTargetState = rememberUpdatedState(resolveDdlTarget)
    // AWT 派发器一次性安装（DisposableEffect(Unit)），用 rememberUpdatedState 读最新键表，
    // 否则用户改键后派发器仍按旧键匹配。
    val keymapState = rememberUpdatedState(keymap)

    // 在指定数据源新建控制台（标签条「+」与树右键「打开控制台 → 新建控制台」共用）。
    val createConsoleFor: (ConnectionProfile) -> Unit = { p ->
        val name = suggestConsoleName(p)
        consoleState.createConsole(p.id, name)
        toastState.show("已新建控制台「$name」（绑定 ${p.name}）")
    }

    // ---------- P5 连接档案导出 / 导入（AES 口令加密；不自动连接，导入后刷新树） ----------

    /** 真正入库 + toast（[skipConnectionIds] 为同名冲突中选择跳过的导入连接 id）。 */
    fun applyImport(bundle: ProfileTransfer.ProfileBundle, skipConnectionIds: Set<String>) {
        runCatching { treeState.importProfiles(bundle, skipConnectionIds) }
            .onSuccess { summary ->
                val notes = buildList {
                    if (bundle.skipped > 0) add("跳过 ${bundle.skipped} 个未知类型连接")
                    if (summary.connectionsSkipped > 0) add("跳过 ${summary.connectionsSkipped} 个同名数据源")
                }
                val skipNote = if (notes.isEmpty()) "" else "（${notes.joinToString("，")}）"
                toastState.show(
                    "已导入 ${summary.foldersAdded} 个文件夹 / ${summary.connectionsAdded} 个数据源$skipNote",
                )
            }
            .onFailure {
                Logger.error(it, "import profiles failed")
                toastState.show("导入失败：${it.message?.take(140)}")
            }
    }

    /** 解析成功后的导入流程：同名冲突则先弹选择框，否则直接导入。 */
    fun startImport(bundle: ProfileTransfer.ProfileBundle) {
        val conflicts = ProfileTransfer.findNameConflicts(bundle.connections, treeState.connections.map { it.name })
        if (conflicts.isEmpty()) {
            applyImport(bundle, emptySet())
        } else {
            dialogState.importConflicts = ImportConflictRequest(conflicts) { choices ->
                applyImport(bundle, choices.filterValues { !it }.keys)
                dialogState.importConflicts = null
            }
        }
    }

    fun exportProfiles(includePasswords: Boolean) {
        // Linux 桌面支持无主窗口的 AWT 文件对话框（owner=null），与 CSV 导出同源
        val ownerFrame: java.awt.Frame? = null
        val fd = FileDialog(
            ownerFrame,
            if (includePasswords) "导出数据源（含密码）" else "导出数据源（不含密码）",
            FileDialog.SAVE,
        )
        fd.file = "db-k-connections.dbk"
        fd.isVisible = true
        val name = fd.file ?: return
        val file = File(fd.directory, name)
        val passwordNote = if (includePasswords) {
            "\n\n注意：本次导出包含明文密码，任何能解密该文件的人都能直接读取，请仅在可信环境使用。"
        } else {
            "\n\n本次导出不含密码，导入后需重新填写。"
        }
        dialogState.passphrase = PassphraseRequest(
            title = "设置加密口令",
            message = "文件将用 AES-256-GCM 加密，请设置口令（导入时需要输入相同口令）。$passwordNote",
            confirmLabel = "加密导出",
            requireConfirmation = true,
        ) { passphrase ->
            runCatching {
                ProfileTransfer.write(file, treeState.folders, treeState.connections, includePasswords, passphrase)
            }.fold(
                onSuccess = {
                    toastState.show(
                        "已导出 ${treeState.folders.size} 个文件夹 / ${treeState.connections.size} 个数据源 → ${file.name}",
                    )
                    null
                },
                onFailure = {
                    Logger.error(it, "export profiles failed")
                    "导出失败：${it.message?.take(120)}"
                },
            )
        }
    }

    fun importProfiles() {
        val ownerFrame: java.awt.Frame? = null
        val fd = FileDialog(ownerFrame, "导入数据源", FileDialog.LOAD)
        fd.file = "*.dbk"
        fd.isVisible = true
        val name = fd.file ?: return
        val file = File(fd.directory, name)
        dialogState.passphrase = PassphraseRequest(
            title = "输入解密口令",
            message = "该文件由 db-k 加密导出，请输入导出时设置的口令。",
            confirmLabel = "解密导入",
            requireConfirmation = false,
        ) { passphrase ->
            val result = runCatching { ProfileTransfer.read(file, passphrase) }
            val error = result.exceptionOrNull()
            if (error != null) {
                Logger.error(error, "import profiles read failed")
                "解密失败：${error.message?.take(100) ?: "口令错误或文件已损坏"}"
            } else {
                startImport(result.getOrThrow())
                null
            }
        }
    }

    val completionDismiss = remember { CompletionDismissSignal() }
    MaterialTheme(colors = appMaterialColors(isDark)) {
        // M2 MaterialTheme 不设置 LocalContentColor（默认黑）——所有裸 Text 默认色在
        // 深色主题下会不可见。统一兜底为 onSurface；组件内显式色仍优先。
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colors.onSurface,
            LocalKeymap provides keymap,
            LocalCompletionDismiss provides completionDismiss,
        ) {
            // 窗口级业务键（显示/隐藏结果区、查看定义 DDL）用 AWT 级 KeyEventDispatcher 拦截：
            // Linux/X11 实测，修饰键+字母除 KEY_PRESSED 外还会派发一次字符事件
            // （Alt+字母：key=Unknown、utf16CodePoint=98；Ctrl+字母：控制字符），该事件绕过
            // KeyDown 的消费直接进入编辑器的文本输入会话（编辑器失焦时平台会话仍绑定它，
            // 故“焦点在哪都会漏 b”/漏控制字符）。AWT 派发器在事件进入 Compose 之前把整颗按键
            // （KEY_PRESSED + KEY_TYPED）吃掉，两条通道都收不到；命中哪两条命令由用户可配置键表决定。
            DisposableEffect(Unit) {
                val dispatcher = java.awt.KeyEventDispatcher { e ->
                    when (keymapState.value.matchAnyAwt(e)) {
                        ShortcutCommand.TOGGLE_RESULTS -> {
                            // 只在首次按下时切换，忽略自动重复（KEY_RELEASED/KEY_TYPED 只吞不切）
                            if (e.id == java.awt.event.KeyEvent.KEY_PRESSED) {
                                resultsVisible = !resultsVisible
                            }
                            true
                        }
                        ShortcutCommand.VIEW_DDL -> {
                            // 只在首次按下时取值（控制字符 KEY_TYPED 只吞不重复触发）
                            if (e.id == java.awt.event.KeyEvent.KEY_PRESSED) {
                                val target = resolveDdlTargetState.value()
                                if (target != null) {
                                    dialogState.tableDdl = target
                                } else {
                                    val keys = keymapState.value.chordsOf(ShortcutCommand.VIEW_DDL)
                                        .joinToString(" / ") { it.format() }
                                    toastState.show("请把编辑器光标放到表名上，或在左侧选中表/视图，再按 $keys")
                                }
                            }
                            true
                        }
                        else -> false
                    }
                }
                val kfm = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                kfm.addKeyEventDispatcher(dispatcher)
                onDispose { kfm.removeKeyEventDispatcher(dispatcher) }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colors.background)
                    .pointerInput(Unit) {
                        // 补全弹层是编辑器内 overlay，收不到别处（树/结果区/工具栏）的点击。
                        // Final pass 旁路观察（只读不消费）：弹层已在 Initial pass 标记命中，
                        // 没标记的按下就是“点弹层外” → 收起弹层。
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Final)
                                if (e.type == PointerEventType.Press) completionDismiss.onPress()
                            }
                        }
                    },
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    DbTreeSidebar(
                        modifier = Modifier.width(treeWidthDp.dp).fillMaxHeight(),
                        rows = rows,
                        selectedKey = treeState.selectedRowKey,
                        onSelectRow = ::selectRow,
                        onToggleExpand = ::toggleRow,
                        onDisconnectConnection = ::disconnectProfile,
                        onRefreshMetadata = { p ->
                            scope.launch {
                                val ok = connectionsState.refreshMetadata(p)
                                toastState.show(
                                    if (ok) "已刷新「${p.name}」元数据缓存"
                                    else "刷新「${p.name}」失败：${connectionsState.statusMessageOf(p.id)?.take(80) ?: "未知错误"}",
                                )
                            }
                        },
                        onCopyName = { row -> row.dbObject?.name?.let { writeClipboardText(it) } },
                        onCopyQuery = { row ->
                            val p = row.profile
                            val obj = row.dbObject
                            if (p != null && obj != null) {
                                connectionsState.sessionOf(p.id)?.previewQuery(row.schema, obj)
                                    ?.let { writeClipboardText(it) }
                            }
                        },
                        onAddFolder = { dialogState.folderDialog = FolderDialogRequest.Create(null) },
                        onAddConnectionAt = { folderId -> dialogState.connectionEditor = ConnectionEditorRequest.Create(folderId) },
                        onRenameFolder = { f -> dialogState.folderDialog = FolderDialogRequest.Rename(f) },
                        onDeleteFolder = { f ->
                            dialogState.confirm = ConfirmRequest.DeleteFolder(f.id, f.name, treeState.countConnectionsInFolder(f.id))
                        },
                        onEditConnection = { p -> dialogState.connectionEditor = ConnectionEditorRequest.Edit(p) },
                        onDeleteConnection = { p -> dialogState.confirm = ConfirmRequest.DeleteConnection(p.id, p.name) },
                        onRefresh = { treeState.refresh() },
                        onOpenConsoleForProfile = { p -> consoleState.activateForProfile(p.id) },
                        consolesForProfile = { pid -> consoleState.profileConsoles(pid) },
                        activeConsoleId = activeConsole?.id,
                        onOpenConsoleRecord = { c -> consoleState.reopenConsole(c.id) },
                        onCreateConsoleForProfile = createConsoleFor,
                        onPreviewObject = ::previewObject,
                        onViewObjectDef = { row ->
                            val p = row.profile
                            val obj = row.dbObject
                            if (p != null && obj != null) {
                                dialogState.tableDdl = TableDdlRequest(p, row.schema, obj.name, obj.kind.displayNoun)
                            }
                        },
                        onExportProfiles = { exportProfiles(includePasswords = false) },
                        onExportProfilesWithPasswords = { exportProfiles(includePasswords = true) },
                        onImportProfiles = { importProfiles() },
                        onSelectDb = { p, db ->
                            val ns = connectionsState.schemasOf(p.id).orEmpty()
                                .firstOrNull { it.displayName.equals(db, ignoreCase = true) }
                            if (ns != null) scope.launch { connectionsState.setActiveDb(p, ns) }
                        },
                        onSelectKeyType = { p, type ->
                            scope.launch {
                                connectionsState.setObjectFilter(p, connectionsState.objectSearchOf(p.id).pattern, type)
                            }
                        },
                        onKeyPatternChange = { p, pattern -> keySearchRequest = p to pattern },
                        onLoadMoreObjects = { p -> scope.launch { connectionsState.loadMoreObjects(p) } },
                    )
                    TreeSplitter { delta -> treeWidthDp = (treeWidthDp + delta).coerceIn(180f, 680f) }
                    val runState = activeConsole?.let { consoleState.runStateOf(it.id) } ?: ConsoleRunUi()
                    val activeRun = runState // 仅供下方 lambda 引用
                    // 结果落地/切 Tab 后重算可编辑计划（异步拉列元数据；幂等）
                    LaunchedEffect(activeConsole?.id, activeRun.activeIndex, activeRun.executing) {
                        val c = activeConsole
                        val p = activeProfile
                        if (c != null && p != null && !activeRun.executing && activeRun.result != null) {
                            consoleState.ensureEditPlan(c, p)
                        }
                    }
                    SqlWorkspace(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        profile = activeProfile,
                        status = connectionsState.statusOf(activeProfile?.id.orEmpty()),
                        statusMessage = connectionsState.statusMessageOf(activeProfile?.id.orEmpty()),
                        profiles = profiles,
                        onSelectProfile = { pid -> consoleState.activateForProfile(pid) },
                        consoles = consoleState.allOpenConsoles(profiles.map { it.id }),
                        activeConsole = activeConsole,
                        onSelectConsole = { c -> consoleState.activate(c) },
                        onCloseConsole = { c -> consoleState.closeConsole(c.id) },
                        onCreateConsoleAt = { pid ->
                            profiles.firstOrNull { it.id == pid }?.let(createConsoleFor)
                        },
                        dirtyConsoleIds = consoleState.dirtyConsoleIds,
                        pendingEditCounts = consoleState.editBuffers.mapValues { it.value.count },
                        schemas = activeProfile?.let { connectionsState.schemasOf(it.id) },
                        supportsTargetSwitch =
                            activeProfile?.let { connectionsState.sessionOf(it.id)?.capabilities?.sessionContext } == true,
                        targetLabel = if (activeProfile?.let { connectionsState.flatNamespaceOf(it.id) } == true) "DB" else "目标",
                        targetAllowDefault = activeProfile?.let { !connectionsState.flatNamespaceOf(it.id) } ?: true,
                        targetSchema = activeProfile?.let { p ->
                            if (connectionsState.flatNamespaceOf(p.id)) {
                                connectionsState.activeNamespaceOf(p.id)?.displayName.orEmpty()
                            } else {
                                consoleState.activeConsole()?.target.orEmpty()
                            }
                        }.orEmpty(),
                        onSelectTarget = { t ->
                            val p = activeProfile
                            if (p != null && connectionsState.flatNamespaceOf(p.id)) {
                                val ns = connectionsState.schemasOf(p.id).orEmpty()
                                    .firstOrNull { it.displayName.equals(t, ignoreCase = true) }
                                if (ns != null) {
                                    scope.launch { connectionsState.setActiveDb(p, ns) }
                                    toastState.show("已切换到 DB：${ns.displayName}")
                                }
                            } else {
                                val c = consoleState.activeConsole()
                                if (c != null) {
                                    consoleState.setTarget(c.id, t)
                                    toastState.show(
                                        if (t.isBlank()) "已恢复默认执行目标（连接库）" else "执行目标已设为：$t",
                                    )
                                }
                            }
                        },
                        onRenameConsole = { c -> dialogState.consoleRename = ConsoleRenameRequest(c.id, c.name) },
                        onDeleteConsole = { c ->
                            val srcName = profiles.firstOrNull { it.id == c.connectionId }?.name ?: ""
                            dialogState.confirm = ConfirmRequest.DeleteConsole(c.id, c.name, srcName)
                        },
                        editorText = activeConsole?.let { consoleState.textOf(it.id) }.orEmpty(),
                        editorDirty = activeConsole?.let { consoleState.isDirty(it.id) } == true,
                        onTextChange = { id, t ->
                            consoleState.setText(id, t)
                            lastEditorActivity.set(System.currentTimeMillis())
                        },
                        insertRequest = pendingInsert,
                        onInsertRequestConsumed = { pendingInsert = null },
                        onRequestInsert = { pendingInsert = it },
                        caretOf = consoleState::caretOf,
                        onCaretChange = { id, s, e ->
                            consoleState.setCaret(id, s, e)
                            lastEditorActivity.set(System.currentTimeMillis())
                        },
                        onSaveNow = { consoleState.activeConsole()?.let { consoleState.saveNow(it.id) } },
                        run = activeRun,
                        onRun = ::runActiveConsole,
                        onSelectOutcome = { i ->
                            val c = activeConsole
                            if (c != null) {
                                val pending = consoleState.editCount(c.id)
                                val current = consoleState.runStateOf(c.id).activeIndex
                                if (pending > 0 && i != current) {
                                    dialogState.confirm = ConfirmRequest.DiscardResultEdits(pending, "切换结果 Tab") {
                                        consoleState.selectRunOutcome(c.id, i)
                                    }
                                } else {
                                    consoleState.selectRunOutcome(c.id, i)
                                }
                            }
                        },
                        onCommitEdits = {
                            val c = activeConsole
                            val p = activeProfile
                            if (c != null && p != null) {
                                // P7：先看将要执行的 UPDATE，确认后才真正提交
                                val preview = consoleState.previewCommit(c, p)
                                if (preview.isEmpty()) {
                                    commitEditsNow(c, p)
                                } else {
                                    dialogState.commitPreview = CommitPreviewRequest(preview) {
                                        commitEditsNow(c, p)
                                    }
                                }
                            }
                        },
                        onClearAllEdits = {
                            activeConsole?.let { consoleState.clearEdits(it.id) }
                        },
                        onRefreshResult = {
                            val c = activeConsole
                            val p = activeProfile
                            if (c != null && p != null) {
                                val pending = consoleState.editCount(c.id)
                                val doRefresh: () -> Unit = {
                                    scope.launch {
                                        consoleState.refreshOutcome(c, p, consoleState.runStateOf(c.id).activeIndex)
                                    }
                                }
                                if (pending > 0) {
                                    dialogState.confirm = ConfirmRequest.DiscardResultEdits(pending, "刷新结果", doRefresh)
                                } else {
                                    doRefresh()
                                }
                            }
                        },
                        canFetchMore = activeConsole?.let { consoleState.canFetchMore(it.id) } == true,
                        onFetchMore = {
                            val c = activeConsole
                            val p = activeProfile
                            if (c != null && p != null) {
                                val stmt = consoleState.runStateOf(c.id).active?.sql
                                if (stmt != null && !sqlHasOrderBy(stmt)) {
                                    toastState.show("原查询无 ORDER BY，取更多顺序不保证")
                                }
                                scope.launch {
                                    consoleState.fetchMore(c, p)
                                        .onSuccess { n ->
                                            toastState.show(if (n > 0) "已追加 $n 行" else "没有更多数据了")
                                        }
                                        .onFailure { toastState.show("取更多失败：${it.message?.take(120)}") }
                                }
                            }
                        },
                        resultEdits = activeConsole?.let { consoleState.editsOf(it.id) } ?: ResultEdits.EMPTY,
                        editPlan = activeConsole?.let { consoleState.editPlanOf(it.id) },
                        resultBusy = activeConsole?.let { consoleState.resultBusyOf(it.id) } == true,
                        onCellEdit = { key, value ->
                            activeConsole?.let { consoleState.setCellEdit(it.id, key, value) }
                        },
                        onClearCellEdit = { key ->
                            activeConsole?.let { consoleState.clearCellEdit(it.id, key) }
                        },
                        onInsertRow = {
                            activeConsole?.let { consoleState.addPendingInsert(it.id) }
                        },
                        onRemoveInsertRow = { id ->
                            activeConsole?.let { consoleState.removePendingInsert(it.id, id) }
                        },
                        onInsertCellEdit = { id, col, value ->
                            activeConsole?.let { consoleState.setPendingInsertCell(it.id, id, col, value) }
                        },
                        onClearInsertCell = { id, col ->
                            activeConsole?.let { consoleState.clearPendingInsertCell(it.id, id, col) }
                        },
                        onToggleRowDelete = { row ->
                            activeConsole?.let { c ->
                                val deletes = consoleState.editsOf(c.id).deletes
                                when {
                                    row in deletes -> consoleState.restoreRow(c.id, row)
                                    deletes.isNotEmpty() -> dialogState.confirm = ConfirmRequest.DeleteRows(deletes.size + 1) {
                                        consoleState.markRowDeleted(c.id, row)
                                    }
                                    else -> consoleState.markRowDeleted(c.id, row)
                                }
                            }
                        },
                        onExport = {
                            val c = activeConsole
                            val p = activeProfile
                            val result = c?.let { consoleState.runStateOf(it.id).result }
                            if (c != null && p != null && result != null) {
                                // SQL INSERT 默认表名取结果列的真实基表（单表查询时可用）
                                val defaultTable = result.columns.firstNotNullOfOrNull { it.table } ?: "exported_data"
                                // 结果里若有被截断的单元格，缓存导出会写出截断标记 → 强制全量流式
                                val cellsTruncated = result.rows.any { r -> r.any { QueryExecutor.isTruncatedCell(it) } }
                                dialogState.export = ExportRequest(
                                    rowCount = result.rowCount,
                                    truncated = result.truncated,
                                    defaultTableName = defaultTable,
                                    cellsTruncated = cellsTruncated,
                                    onSubmit = { format, options, fullStream ->
                                        dialogState.export = null
                                        // Linux 桌面支持无主窗口的 AWT 文件对话框（owner=null）
                                        val fd = FileDialog(null as java.awt.Frame?, "导出 ${format.label}", FileDialog.SAVE)
                                        fd.file = "${p.name}.${format.extension}"
                                        fd.isVisible = true
                                        val name = fd.file
                                        if (name != null) {
                                            val file = File(fd.directory, name)
                                            startResultExport(c, p, result, file, format, options, fullStream)
                                        }
                                    },
                                )
                            }
                        },
                        onCancelRun = {
                            val c = consoleState.activeConsole()
                            if (c != null) {
                                val hit = consoleState.cancelRun(c.id)
                                toastState.show(
                                    if (hit) "已请求取消当前查询"
                                    else "取消未生效（语句未开始或驱动不支持取消）",
                                )
                            }
                        },
                        history = activeProfile?.let { consoleState.historyOf(it.id) }.orEmpty(),
                        onRefreshHistory = { activeProfile?.let { consoleState.refreshHistory(it.id) } },
                        onClearHistory = {
                            activeProfile?.let { consoleState.clearHistory(it.id) }
                            toastState.show("已清空执行历史")
                        },
                        completionIdentifiers = completionIdentifiers,
                        completionTables = completionTables,
                        completionFunctions = completionFunctions,
                        completionEnabled = activeProfile?.let {
                            connectionsState.sessionOf(it.id)?.capabilities?.sqlCompletion
                        } ?: true,
                        editorLanguage = activeProfile?.let {
                            connectionsState.sessionOf(it.id)?.capabilities?.editorLanguage
                        } ?: EditorLanguage.SQL,
                        columnCatalog = connectionsState.columns,
                        onCopyText = { text, label ->
                            writeClipboardText(text)
                            toastState.show(label)
                        },
                        onDisconnect = { activeProfile?.let(::disconnectProfile) },
                        resultsVisible = resultsVisible,
                        onToggleResults = { resultsVisible = !resultsVisible },
                        isDark = isDark,
                        onToggleTheme = {
                            isDark = !isDark
                            ThemePrefs.save(isDark)
                        },
                        editorSettings = editorSettings,
                        onOpenSettings = { dialogState.showSettings = true },
                        mainWindowState = mainWindowState,
                        onWindowCloseRequest = onWindowCloseRequest,
                    )
                }

                DialogHost(
                    dialogState = dialogState,
                    treeState = treeState,
                    connectionsState = connectionsState,
                    consoleState = consoleState,
                    profiles = profiles,
                    onCopyText = { text, label ->
                        writeClipboardText(text)
                        toastState.show(label)
                    },
                )
                ToastHost(toastState)
                SettingsDialog(
                    visible = dialogState.showSettings,
                    isDark = isDark,
                    initial = SettingsSnapshot(editorSettings, keymap),
                    onDismiss = { dialogState.showSettings = false },
                    onSave = { snapshot ->
                        editorSettings = snapshot.editor
                        keymap = snapshot.keymap
                        EditorPrefs.save(snapshot.editor)
                        KeymapPrefs.save(snapshot.keymap)
                        dialogState.showSettings = false
                    },
                )
            }
        }
    }
}

/** 行 key → profileId（c:/s:/p: 前缀）。 */
private fun rowProfileId(rowKey: String?): String? {
    val key = rowKey ?: return null
    val prefix = key.substringBefore(':')
    if (prefix !in setOf("c", "s", "p")) return null
    return key.split(':').getOrNull(1)
}

/** 连接编辑 / 控制台 / 文件夹 / 删除确认等弹窗编排。 */
@Composable
private fun DialogHost(
    dialogState: DialogState,
    treeState: TreeState,
    connectionsState: ConnectionsState,
    consoleState: ConsoleState,
    profiles: List<ConnectionProfile>,
    onCopyText: (String, String) -> Unit = { _, _ -> },
) {
    dialogState.tableDdl?.let { request ->
        DdlDialog(
            request = request,
            loadDdl = connectionsState::fetchDdl,
            onDismiss = { dialogState.tableDdl = null },
            onCopy = onCopyText,
        )
    }
    dialogState.commitPreview?.let { request ->
        CommitPreviewDialog(
            request = request,
            onDismiss = { dialogState.commitPreview = null },
        )
    }
    dialogState.folderDialog?.let { request ->
        FolderNameDialog(
            request = request,
            onDismiss = { dialogState.folderDialog = null },
            onConfirm = { name ->
                when (request) {
                    is FolderDialogRequest.Create -> treeState.addFolder(name)
                    is FolderDialogRequest.Rename -> treeState.renameFolder(request.folder.id, name)
                }
                dialogState.folderDialog = null
            },
        )
    }
    dialogState.consoleRename?.let { request ->
        ConsoleNameDialog(
            initial = request.currentName,
            isCreate = false,
            onDismiss = { dialogState.consoleRename = null },
            onConfirm = { name ->
                consoleState.renameConsole(request.consoleId, name)
                dialogState.consoleRename = null
            },
        )
    }
    dialogState.connectionEditor?.let { request ->
        ConnectionEditorDialog(
            request = request,
            folders = treeState.folders,
            onDismiss = { dialogState.connectionEditor = null },
            onSubmit = { profile ->
                when (request) {
                    is ConnectionEditorRequest.Create -> treeState.createConnection(profile)
                    is ConnectionEditorRequest.Edit -> {
                        val id = request.profile.id
                        // URL 可能已变：断开并丢弃运行时缓存（下次展开自动重连）
                        connectionsState.invalidate(id)
                        treeState.updateConnection(profile.copy(id = id))
                    }
                }
                dialogState.connectionEditor = null
            },
        )
    }
    dialogState.passphrase?.let { request ->
        PassphraseDialog(
            request = request,
            onDismiss = { dialogState.passphrase = null },
        )
    }
    dialogState.importConflicts?.let { request ->
        ImportConflictDialog(
            request = request,
            onDismiss = { dialogState.importConflicts = null },
        )
    }
    dialogState.export?.let { request ->
        ExportDialog(
            request = request,
            onDismiss = { dialogState.export = null },
            onConfirm = { format, options, fullStream -> request.onSubmit(format, options, fullStream) },
        )
    }
    dialogState.confirm?.let { request ->
        ConfirmDialog(
            request = request,
            onDismiss = {
                (request as? ConfirmRequest.DangerConfirm)?.onDecision(false)
                dialogState.confirm = null
            },
            onConfirm = {
                when (request) {
                    is ConfirmRequest.DeleteFolder -> treeState.deleteFolder(request.id)
                    is ConfirmRequest.DeleteConnection -> {
                        treeState.deleteConnection(request.id)
                        connectionsState.forget(request.id)
                        consoleState.onConnectionDeleted(request.id)
                    }
                    is ConfirmRequest.DeleteConsole -> consoleState.deleteConsole(request.id)
                    is ConfirmRequest.DiscardResultEdits -> request.onDiscard()
                    is ConfirmRequest.DeleteRows -> request.onConfirm()
                    is ConfirmRequest.DangerConfirm -> request.onDecision(true)
                }
                dialogState.confirm = null
            },
        )
    }
}

/** 底部 Toast 浮层（token 变化自动重新计时后消失）。 */
@Composable
private fun ToastHost(toastState: ToastState) {
    val msg = toastState.message ?: return
    LaunchedEffect(msg.token) {
        delay(2600)
        toastState.dismiss()
    }
    Box(
        modifier = Modifier.fillMaxSize().padding(bottom = 22.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .background(Color(0xE6333438), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                msg.text,
                fontSize = 12.5.sp,
                color = Color.White,
            )
        }
    }
}

/** 左右面板间的垂直分割条（拖拽改宽度）。 */
@Composable
private fun TreeSplitter(onResize: (Float) -> Unit) {
    Box(
        modifier = Modifier
            .width(5.dp)
            .fillMaxHeight()
            .background(Color.Transparent)
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    onResize(dragAmount)
                }
            },
    ) {
        Box(
            modifier = Modifier.width(1.dp).fillMaxHeight()
                .background(MaterialTheme.colors.onSurface.copy(alpha = 0.08f)),
        )
    }
}

fun writeClipboardText(text: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}
