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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.dialog.ConfirmDialog
import app.dialog.ConnectionEditorDialog
import app.dialog.ConsoleNameDialog
import app.dialog.FolderNameDialog
import app.settings.ThemePrefs
import app.settings.TreeExpandPrefs
import app.settings.WindowPrefs
import app.state.ConfirmRequest
import app.state.ConnectionEditorRequest
import app.state.ConnectionsState
import app.state.ConsoleRenameRequest
import app.state.ConsoleState
import app.state.ConsoleRunUi
import app.state.DialogState
import app.state.FolderDialogRequest
import app.state.TreeState
import app.state.ToastState
import app.ui.SqlWorkspace
import app.ui.appMaterialColors
import db.AppPaths
import db.ConnectionProfile
import db.ConnectionsRepository
import db.ConsoleRecord
import jdbc.DialectRegistry
import jdbc.model.isPreviewable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    val consoleState = remember { ConsoleState(repository, connectionsState, scope) }

    DisposableEffect(Unit) {
        onDispose {
            consoleState.flushAllSync()
            connectionsState.disposeAll()
            repository.close()
        }
    }

    Window(
        title = "db-k",
        state = windowState,
        onCloseRequest = {
            consoleState.flushAllSync()
            val size = windowState.size
            val pos = windowState.position
            WindowPrefs.save(
                WindowPrefs.Geometry(
                    x = if (pos.isSpecified) (pos.x.value * density).roundToInt() else null,
                    y = if (pos.isSpecified) (pos.y.value * density).roundToInt() else null,
                    width = (size.width.value * density).roundToInt(),
                    height = (size.height.value * density).roundToInt(),
                ),
            )
            onExit()
        },
    ) {
        AppBody(
            repository = repository,
            treeState = treeState,
            connectionsState = connectionsState,
            consoleState = consoleState,
            dialogState = dialogState,
            toastState = toastState,
        )
    }
}

@Composable
private fun AppBody(
    repository: ConnectionsRepository,
    treeState: TreeState,
    connectionsState: ConnectionsState,
    consoleState: ConsoleState,
    dialogState: DialogState,
    toastState: ToastState,
) {
    val scope = rememberCoroutineScope()
    var isDark by remember { mutableStateOf(ThemePrefs.load() ?: false) }
    var treeWidthDp by remember { mutableStateOf(280f) }

    // 运行时状态在 composition 中读取（snapshot 依赖 → 状态变化自动重排）
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
            // 对象组（表/视图/序列…）支持展开/折叠：只展开单个类型组
            TreeRowKind.OBJECT_GROUP -> {
                if (row.expanded) treeState.collapseGroup(row.key)
                else treeState.expandGroup(row.key)
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

    // 编辑器补全候选：当前数据源已加载 schema 中的表/视图名（树展开 + 下方懒预取触发）
    val completionIdentifiers: List<String> = activeProfile?.let { p ->
        connectionsState.schemasOf(p.id).orEmpty()
            .flatMap { s ->
                connectionsState.objectsOf(p.id, s.key)
                    ?.let { o -> o.tables + o.views + o.materializedViews }.orEmpty()
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
        scope.launch { consoleState.run(c, p, target) }
    }

    fun suggestConsoleName(profile: ConnectionProfile): String {
        val n = consoleState.profileConsoles(profile.id).size + 1
        return "控制台 $n"
    }

    /** 双击表/视图/物化视图 → 预览前 100 行（DialectRegistry.previewSelect）：空控制台直接复用，否则新建命名控制台。 */
    fun previewObject(row: TreeRowInfo) {
        val p = row.profile ?: return
        val obj = row.dbObject ?: return
        if (!obj.kind.isPreviewable()) {
            toastState.show("该对象类型不支持 SQL 预览")
            return
        }
        val sql = DialectRegistry.forProfile(p).previewSelect(row.schema, obj.name)
        val active = consoleState.activeConsole()
        val reuse = active != null && active.connectionId == p.id && consoleState.textOf(active.id).isBlank()
        val target: ConsoleRecord = when {
            active != null && reuse -> {
                consoleState.setText(active.id, sql)
                active
            }
            else -> {
                val name = suggestConsoleName(p)
                val created = consoleState.createConsole(p.id, name)
                consoleState.setText(created.id, sql)
                toastState.show("已新建控制台「$name」，可右键标签重命名")
                created
            }
        }
        scope.launch { consoleState.run(target, p) }
    }

    // 编辑器补全元数据预取：数据源已连接时懒加载首个（默认）schema 的对象，不改变树的展开态
    LaunchedEffect(activeProfile?.id, connectionsState.statusOf(activeProfile?.id.orEmpty())) {
        val p = activeProfile ?: return@LaunchedEffect
        if (connectionsState.statusOf(p.id) != ConnUiStatus.CONNECTED) return@LaunchedEffect
        val schemas = connectionsState.schemasOf(p.id) ?: return@LaunchedEffect
        schemas.firstOrNull()?.let { connectionsState.ensureSchemaObjects(p, it) }
    }

    MaterialTheme(colors = appMaterialColors(isDark)) {
        // M2 MaterialTheme 不设置 LocalContentColor（默认黑）——所有裸 Text 默认色在
        // 深色主题下会不可见。统一兜底为 onSurface；组件内显式色仍优先。
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colors.onSurface) {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
                Row(modifier = Modifier.fillMaxSize()) {
                    DbTreeSidebar(
                        modifier = Modifier.width(treeWidthDp.dp).fillMaxHeight(),
                        rows = rows,
                        selectedKey = treeState.selectedRowKey,
                        onSelectRow = ::selectRow,
                        onToggleExpand = ::toggleRow,
                        onDisconnectConnection = ::disconnectProfile,
                        onRefreshSchemas = { p -> scope.launch { connectionsState.refreshSchemas(p) } },
                        onCopyName = { row -> row.dbObject?.name?.let { writeClipboardText(it) } },
                        onCopyQuery = { row ->
                            val p = row.profile
                            val obj = row.dbObject
                            if (p != null && obj != null) {
                                writeClipboardText(DialectRegistry.forProfile(p).previewSelect(row.schema, obj.name))
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
                        onPreviewObject = ::previewObject,
                    )
                    TreeSplitter { delta -> treeWidthDp = (treeWidthDp + delta).coerceIn(180f, 680f) }
                    val runState = activeConsole?.let { consoleState.runStateOf(it.id) } ?: ConsoleRunUi()
                    val activeRun = runState // 仅供下方 lambda 引用
                    SqlWorkspace(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        profile = activeProfile,
                        status = connectionsState.statusOf(activeProfile?.id.orEmpty()),
                        statusMessage = connectionsState.statusMessageOf(activeProfile?.id.orEmpty()),
                        profiles = profiles,
                        onSelectProfile = { pid -> consoleState.activateForProfile(pid) },
                        consoles = activeProfile?.let { consoleState.profileConsoles(it.id) }.orEmpty(),
                        activeConsole = activeConsole,
                        onSelectConsole = { c -> consoleState.activate(c) },
                        onCreateConsole = {
                            val p = activeProfile
                            if (p != null) {
                                val name = suggestConsoleName(p)
                                val c = consoleState.createConsole(p.id, name)
                                toastState.show("已新建控制台「$name」（绑定 ${p.name} 数据源）")
                            }
                        },
                        onRenameConsole = { c -> dialogState.consoleRename = ConsoleRenameRequest(c.id, c.name) },
                        onDeleteConsole = { c ->
                            dialogState.confirm = ConfirmRequest.DeleteConsole(
                                c.id, c.name, activeProfile?.name ?: "",
                            )
                        },
                        editorText = activeConsole?.let { consoleState.textOf(it.id) }.orEmpty(),
                        editorDirty = activeConsole?.let { consoleState.isDirty(it.id) } == true,
                        onTextChange = { t -> activeConsole?.let { consoleState.setText(it.id, t) } },
                        run = activeRun,
                        onRun = ::runActiveConsole,                        onClear = { activeConsole?.let { consoleState.clearEditor(it.id) } },
                        onExportCsv = {
                            val result = activeConsole?.let { consoleState.runStateOf(it.id).result }
                            if (result != null) {
                                // Linux 桌面支持无主窗口的 AWT 文件对话框（owner=null）
                                val ownerFrame: java.awt.Frame? = null
                                val fd = FileDialog(ownerFrame, "导出 CSV", FileDialog.SAVE)
                                fd.file = "${activeProfile?.name ?: "query"}.csv"
                                fd.isVisible = true
                                val name = fd.file
                                if (name != null) {
                                    val file = File(fd.directory, name)
                                    runCatching { CsvExport.write(file, result) }
                                        .onSuccess {
                                            toastState.show("已导出 ${result.rowCount} 行 → ${file.name}")
                                        }
                                        .onFailure {
                                            Logger.error(it, "csv export failed")
                                            toastState.show("导出失败：${it.message?.take(80)}")
                                        }
                                }
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
                        onExportAllCsv = {
                            val c = consoleState.activeConsole()
                            val p = activeProfile
                            val runState = c?.let { consoleState.runStateOf(it.id) }
                            val result = runState?.result
                            if (c == null || p == null || result == null || !result.truncated) {
                                return@SqlWorkspace
                            }
                            val ownerFrame: java.awt.Frame? = null
                            val fd = FileDialog(ownerFrame, "导出全量 CSV（重新执行，不受 1000 行限制）", FileDialog.SAVE)
                            fd.file = "${p.name}.csv"
                            fd.isVisible = true
                            val name = fd.file
                            if (name != null) {
                                val file = File(fd.directory, name)
                                scope.launch {
                                    val outcome = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val live = connectionsState.liveConnection(p.id)
                                                ?: error("连接已断开，请重连后再导出")
                                            live.onConnection { conn -> CsvExport.exportAll(file, conn, result.sql) }
                                        }
                                    }
                                    outcome.onSuccess { n ->
                                        toastState.show("全量导出 ${n} 行 → ${file.name}")
                                    }.onFailure { t ->
                                        Logger.error(t, "full csv export failed")
                                        toastState.show("导出失败：${t.message?.take(80)}")
                                    }
                                }
                            }
                        },
                        history = activeProfile?.let { consoleState.historyOf(it.id) }.orEmpty(),
                        onRefreshHistory = { activeProfile?.let { consoleState.refreshHistory(it.id) } },
                        onClearHistory = {
                            activeProfile?.let { consoleState.clearHistory(it.id) }
                            toastState.show("已清空执行历史")
                        },
                        onFillHistory = { sql ->
                            val c = consoleState.activeConsole()
                            if (c != null) {
                                consoleState.setText(c.id, sql)
                                toastState.show("已回填历史 SQL 到当前控制台")
                            }
                        },
                        completionIdentifiers = completionIdentifiers,
                        onCopyText = { text, label ->
                            writeClipboardText(text)
                            toastState.show(label)
                        },
                        onDisconnect = { activeProfile?.let(::disconnectProfile) },
                        isDark = isDark,
                        onToggleTheme = {
                            isDark = !isDark
                            ThemePrefs.save(isDark)
                        },
                    )
                }

                DialogHost(
                    dialogState = dialogState,
                    treeState = treeState,
                    connectionsState = connectionsState,
                    consoleState = consoleState,
                    profiles = profiles,
                )
                ToastHost(toastState)
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
) {
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
    dialogState.confirm?.let { request ->
        ConfirmDialog(
            request = request,
            onDismiss = { dialogState.confirm = null },
            onConfirm = {
                when (request) {
                    is ConfirmRequest.DeleteFolder -> treeState.deleteFolder(request.id)
                    is ConfirmRequest.DeleteConnection -> {
                        treeState.deleteConnection(request.id)
                        connectionsState.forget(request.id)
                        consoleState.onConnectionDeleted(request.id)
                    }
                    is ConfirmRequest.DeleteConsole -> consoleState.deleteConsole(request.id)
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
