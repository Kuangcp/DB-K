package app.dialog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.DialogWindowScope
import androidx.compose.ui.window.rememberDialogState
import app.state.ConnectionEditorRequest
import app.i18n.ProvideI18n
import app.i18n.t
import app.state.SessionFactory
import app.state.friendlySqlError
import app.ui.DbIcons
import app.ui.LocalThemeColors
import app.ui.ProvideUiScale
import app.ui.ThemeSpec
import app.ui.scaledSize
import db.DbCategory
import db.ConnectionProfile
import db.DbType
import db.FolderRow
import i18n.I18n
import i18n.Lang
import i18n.Str
import jdbc.ExternalDrivers
import tree.TypeBadge
import java.awt.FileDialog
import java.awt.Point
import java.awt.Rectangle
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tree.flattenFolderTree

/** 字段统一紧凑高度 + 圆角 + 字号。 */
private val FieldHeight = 40.dp
private val FieldCorner = RoundedCornerShape(6.dp)
private val FieldTextStyle = TextStyle(fontSize = 13.sp)

/** 左侧标签栏宽度：单行与两列行共用，保证表单里所有输入框左边框对齐。 */
private val LabelGutter = 76.dp

@Composable
private fun fieldOutline(focused: Boolean) =
    if (focused) MaterialTheme.colors.primary
    else MaterialTheme.colors.onSurface.copy(alpha = 0.45f)

/**
 * 连接档案编辑弹窗：名称 / 类型 / 文件夹 / 主机 / 端口 / 库 / 账号 / 附加参数，
 * 底部实时预览 JDBC URL + 「测试连接」。主机与端口、用户名与密码两两并排成两列，
 * 其余一行一个。SQLite 类型时隐藏服务端相关字段，database 视为文件路径。
 */
@Composable
fun ConnectionEditorDialog(
    request: ConnectionEditorRequest,
    folders: List<FolderRow>,
    theme: ThemeSpec,
    language: Lang,
    onDismiss: () -> Unit,
    onSubmit: (ConnectionProfile) -> Unit,
) {
    val isEdit = request is ConnectionEditorRequest.Edit
    val initial = (request as? ConnectionEditorRequest.Edit)?.profile
    val createFolderId = (request as? ConnectionEditorRequest.Create)?.folderId

    var name by remember(request) { mutableStateOf(initial?.name ?: "") }
    var dbType by remember(request) { mutableStateOf(initial?.dbType ?: DbType.POSTGRES) }
    var folderId by remember(request) { mutableStateOf(initial?.folderId ?: createFolderId) }
    var host by remember(request) { mutableStateOf(initial?.host ?: "localhost") }
    var portText by remember(request) { mutableStateOf((initial?.port ?: dbType.defaultPort).toString()) }
    var database by remember(request) { mutableStateOf(initial?.database ?: "") }
    var user by remember(request) { mutableStateOf(initial?.user ?: "") }
    var password by remember(request) { mutableStateOf(initial?.password ?: "") }
    var extra by remember(request) { mutableStateOf(initial?.extraParams ?: "") }
    var keySeparator by remember(request) { mutableStateOf(initial?.keySeparator ?: ":") }

    val isEmbedded = dbType == DbType.SQLITE || dbType == DbType.H2LOCAL
    val port = portText.toIntOrNull()

    fun build(): ConnectionProfile = ConnectionProfile(
        id = initial?.id ?: "",
        name = name.trim(),
        folderId = folderId,
        dbType = dbType,
        host = if (isEmbedded) "" else host.trim(),
        port = if (isEmbedded || port == null) dbType.defaultPort else port,
        database = database.trim(),
        user = if (isEmbedded) null else user.trim().ifEmpty { null },
        password = if (isEmbedded) null else password.takeIf { it.isNotEmpty() },
        extraParams = if (isEmbedded) "" else extra.trim(),
        keySeparator = if (dbType == DbType.REDIS) keySeparator else ":",
    )

    val isRedis = dbType == DbType.REDIS
    val isEs = dbType == DbType.ELASTICSEARCH
    // 数据库名非必填：服务端类数据库可不指定库连接（SQLite 的“数据库”是文件路径，仍必填）
    val canSubmit = name.isNotBlank() && (!isEmbedded || database.isNotBlank())

    // ---------- 测试连接 ----------
    val scope = rememberCoroutineScope()
    var testing by remember(request) { mutableStateOf(false) }
    var testPassed by remember(request) { mutableStateOf<Boolean?>(null) }
    var testMessage by remember(request) { mutableStateOf("") }
    val canTest = when {
        isEmbedded -> database.isNotBlank()
        else -> host.isNotBlank()
    }

    fun runTestConnection() {
        if (testing) return
        testing = true
        testPassed = null
        testMessage = ""
        scope.launch {
            val profile = build()
            val failure = withContext(Dispatchers.IO) {
                runCatching {
                    // 建连成功即代表可达+认证通过；立即释放（协议无关：走 SessionFactory）
                    SessionFactory.create(profile).use { it.open() }
                    null
                }.exceptionOrNull()?.let { friendlySqlError(it) }
            }
            testing = false
            if (failure == null) {
                testPassed = true
                testMessage = I18n.t(Str.ConnTestOk)
            } else {
                testPassed = false
                testMessage = failure
            }
        }
    }

    val density = LocalDensity.current
    val maxFormHeight = remember(density) {
        val usablePx = runCatching {
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .maximumWindowBounds.height
        }.getOrDefault(900)
        (with(density) { usablePx.toDp() } - 180.dp).coerceAtLeast(280.dp)
    }
    val bodyHeight = maxFormHeight.coerceAtMost(480.dp)
    val title = t(if (isEdit) Str.TreeMenuEditConnection else Str.TreePillNewConnection)
    // 自绘窗口：DialogWindow(undecorated + transparent) + 显式尺寸；自绘标题栏（可拖动）、圆角 + 边框、
    // 右/下/右下角缩放把手；避开 AlertDialog 的 pack-to-content 撑高问题。
    val dialogState = rememberDialogState(size = scaledSize(768.dp, bodyHeight + 136.dp))
    DialogWindow(
        onCloseRequest = onDismiss,
        state = dialogState,
        title = title,
        undecorated = true,
        transparent = true,
        resizable = false,
        onPreviewKeyEvent = { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                onDismiss()
                true
            } else {
                false
            }
        },
    ) {
        ProvideUiScale {
            ProvideI18n(language) {
                MaterialTheme(colors = theme.colors.toMaterialColors()) {
                    CompositionLocalProvider(
                        LocalContentColor provides MaterialTheme.colors.onSurface,
                        LocalThemeColors provides theme.colors,
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colors.background,
                                border = BorderStroke(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.35f)),
                            ) {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    WindowTitleBar(title = title, onClose = onDismiss)
                                    Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.18f))
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(20.dp),
                                    ) {
                                        DialogBody(
                                            maxHeight = bodyHeight,
                                            dbType = dbType,
                                            onSelectType = { next ->
                                                // 切换类型时若端口还是旧默认值则跟随新默认
                                                if (port == null || port == dbType.defaultPort) {
                                                    portText = next.defaultPort.toString()
                                                }
                                                dbType = next
                                            },
                                        ) {
                                            FormRow(t(Str.CommonName)) {
                    CompactField(value = name, onValueChange = { name = it })
                }
                val ungroupedLabel = t(Str.ConnUngrouped)
                FormRow(t(Str.TreePillFolder)) {
                    val flatFolders = remember(folders) { flattenFolderTree(folders) }
                    val options = remember(folders, ungroupedLabel) {
                        listOf(ungroupedLabel) + flatFolders.map { (f, depth) ->
                            buildString {
                                repeat(depth) { append("    ") }
                                append(f.name)
                            }
                        }
                    }
                    DropdownField(
                        label = folderId?.let { fid -> folders.firstOrNull { it.id == fid }?.name } ?: ungroupedLabel,
                        options = options,
                        onSelect = { idx ->
                            folderId = if (idx == 0) null else flatFolders[idx - 1].first.id
                        },
                    )
                }
                if (!isEmbedded) {
                    PairRow(
                        labelA = t(Str.FormHost),
                        fieldA = {
                            CompactField(
                                value = host, onValueChange = { host = it },
                                placeholder = "localhost",
                            )
                        },
                        labelB = t(Str.FormPort),
                        fieldB = {
                            CompactField(
                                value = portText,
                                onValueChange = { portText = it.filter { c -> c.isDigit() }.take(6) },
                                placeholder = dbType.defaultPort.toString(),
                            )
                        },
                    )
                }
                FormRow(if (isEmbedded) t(Str.FormFilePath) else if (isRedis) t(Str.FormDbIndex) else if (isEs) t(Str.FormDefaultIndex) else t(Str.FormDatabase)) {
                    CompactField(
                        value = database,
                        onValueChange = { database = it },
                        placeholder = when {
                            dbType == DbType.H2LOCAL -> t(Str.ConnH2LocalPathPlaceholder)
                            isEmbedded -> "/path/to/demo.db"
                            isRedis -> "0"
                            isEs -> t(Str.ConnEsIndexPlaceholder)
                            else -> dbType.label.lowercase()
                        },
                        modifier = if (isEmbedded) Modifier.weight(1f) else Modifier,
                    )
                    // 本地库（SQLite / H2 本地）：原生文件选择器定/选文件路径（新建时可输入新文件名）
                    if (isEmbedded) {
                        Spacer(Modifier.width(6.dp))
                        IconButton(
                            onClick = { pickDatabaseFile(dbType, database)?.let { database = it } },
                            modifier = Modifier.size(FieldHeight),
                        ) {
                            Icon(
                                DbIcons.Folder,
                                contentDescription = t(Str.ConnPickFile),
                                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                if (isRedis) {
                    FormRow(t(Str.FormKeySeparator)) {
                        CompactField(
                            value = keySeparator,
                            onValueChange = { keySeparator = it },
                            placeholder = ":",
                        )
                    }
                    Text(
                        t(Str.ConnKeySeparatorHint),
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = LabelGutter, top = 2.dp),
                    )
                }
                if (!isEmbedded) {
                    PairRow(
                        labelA = t(Str.FormUser),
                        fieldA = {
                            CompactField(
                                value = user, onValueChange = { user = it },
                                placeholder = if (isEs) t(Str.ConnEsUserPlaceholder) else "root",
                            )
                        },
                        labelB = if (isEs) t(Str.FormPasswordOrApiKey) else t(Str.FormPassword),
                        fieldB = {
                            CompactField(
                                value = password, onValueChange = { password = it },
                                placeholder = "••••••",
                                isPassword = true,
                            )
                        },
                    )
                    if (isEs) {
                        Text(
                            t(Str.ConnEsAuthHint),
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(start = LabelGutter, top = 2.dp),
                        )
                    }
                    FormRow(t(Str.FormExtraParams)) {
                        CompactField(
                            value = extra,
                            onValueChange = { extra = it },
                            placeholder = if (isEs) "scheme=https&path=/es" else "sslMode=require&connectTimeout=5",
                        )
                    }
                }
                if (!isEmbedded || dbType == DbType.H2LOCAL) {
                    Text(
                        when {
                            isRedis -> t(Str.ConnUrlPreviewRedis, build().urlPreview())
                            isEs -> t(Str.ConnUrlPreviewEs, build().urlPreview())
                            else -> "JDBC URL：${build().urlPreview()}"
                        },
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                // 测试连接（底部）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = if (isEmbedded) 2.dp else 0.dp),
                ) {
                    OutlinedButton(
                        onClick = ::runTestConnection,
                        enabled = canTest && !testing,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp),
                    ) {
                        if (testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp).padding(end = 6.dp),
                                strokeWidth = 1.5.dp,
                            )
                        }
                        Text(t(Str.ConnTestButton), fontSize = 12.5.sp)
                    }
                    val statusColor = when {
                        testPassed == true -> Color(0xFF43A047)
                        testPassed == false -> Color(0xFFE53935)
                        else -> MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                    }
                    val statusText = when {
                        testing -> t(Str.ConnTesting)
                        testMessage.isNotEmpty() -> testMessage
                        else -> t(Str.ConnTestHint)
                    }
                    Text(
                        statusText,
                        fontSize = 12.sp,
                        color = statusColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 12.dp).weight(1f),
                    )
                }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text(t(Str.CommonCancel)) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(enabled = canSubmit, onClick = { onSubmit(build()) }) { Text(t(Str.CommonSave)) }
                }
            }
            }
        }
        ResizeHandle(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp),
            resizeWidth = true,
            resizeHeight = false,
        )
        ResizeHandle(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(6.dp),
            resizeWidth = false,
            resizeHeight = true,
        )
        ResizeHandle(
            modifier = Modifier.align(Alignment.BottomEnd).size(20.dp),
            resizeWidth = true,
            resizeHeight = true,
            grip = true,
        )
    }
}
}
}
}
}
}

/** 自绘标题栏：整条可拖动窗口（拖动标题栏 = 移动窗口），右侧关闭按钮。 */
@Composable
private fun DialogWindowScope.WindowTitleBar(title: String, onClose: () -> Unit) {
    var startLoc by remember { mutableStateOf(Point(0, 0)) }
    var startPtr by remember { mutableStateOf(Offset.Zero) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        startLoc = window.location
                        startPtr = offset
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val d = change.position - startPtr
                        window.location = Point(startLoc.x + d.x.roundToInt(), startLoc.y + d.y.roundToInt())
                    },
                )
            },
    ) {
        Text(
            title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 14.dp).weight(1f),
        )
        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = t(Str.CommonCancel),
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 窗口边缘/角落缩放把手：拖动按 [resizeWidth]/[resizeHeight] 改变窗口尺寸（无边框窗口需自绘）。 */
@Composable
private fun DialogWindowScope.ResizeHandle(
    modifier: Modifier,
    resizeWidth: Boolean,
    resizeHeight: Boolean,
    grip: Boolean = false,
) {
    var startBounds by remember { mutableStateOf(Rectangle(0, 0, 0, 0)) }
    var startPtr by remember { mutableStateOf(Offset.Zero) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { offset ->
                    startBounds = window.bounds
                    startPtr = offset
                },
                onDrag = { change, _ ->
                    change.consume()
                    val d = change.position - startPtr
                    val w = if (resizeWidth) (startBounds.width + d.x.roundToInt()).coerceAtLeast(560) else startBounds.width
                    val h = if (resizeHeight) (startBounds.height + d.y.roundToInt()).coerceAtLeast(360) else startBounds.height
                    window.setBounds(startBounds.x, startBounds.y, w, h)
                },
            )
        },
    ) {
        if (grip) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) { i ->
                    Box(
                        Modifier
                            .width((13 - i * 4).dp)
                            .height(2.dp)
                            .background(MaterialTheme.colors.onSurface.copy(alpha = 0.35f)),
                    )
                }
            }
        }
    }
}

/** 弹窗主体：左侧按大类分组的类型导航 + 右侧配置表单（各自独立滚动）。 */
@Composable
private fun DialogBody(
    maxHeight: Dp,
    dbType: DbType,
    onSelectType: (DbType) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 固定高度（上限取屏幕可用高），左右两栏各自滚动；点窗外不关，只能取消/保存
    val height = maxHeight.coerceAtMost(480.dp)
    Row(modifier = Modifier.width(720.dp).height(height)) {
        TypeNavColumn(current = dbType, onSelect = onSelectType)
        Divider(
            modifier = Modifier.width(1.dp).fillMaxHeight(),
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.12f),
        )
        val scrollState = rememberScrollState()
        Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(end = 6.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(start = 18.dp, end = if (scrollState.maxValue > 0) 12.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                TypeHeader(dbType)
                content()
            }
            if (scrollState.maxValue > 0) {
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(scrollState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    style = viewerScrollbarStyle(),
                )
            }
        }
    }
}

/** 右侧表单顶部的「类型头」：徽章 + 名称（类型由左侧导航选择）；外部驱动未就绪时给提示。 */
@Composable
private fun TypeHeader(dbType: DbType) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TypeBadge(dbType)
        Spacer(Modifier.width(8.dp))
        Text(
            typeLabel(dbType),
            style = MaterialTheme.typography.subtitle2,
            color = MaterialTheme.colors.onSurface,
        )
    }
    if (dbType.externalDriver) {
        val ready = ExternalDrivers.isAvailable(dbType.driverClass)
        Text(
            if (ready) {
                t(Str.ConnExternalDriverLoaded, dbType.driverClass)
            } else {
                t(Str.ConnExternalDriverMissing, dbType.label, ExternalDrivers.driversDir())
            },
            style = MaterialTheme.typography.caption,
            color = if (ready) MaterialTheme.colors.onSurface.copy(alpha = 0.55f) else Color(0xFFFFB300),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** 左侧类型导航：按大类分段，每类下是各数据源（选中项高亮）。 */
@Composable
private fun TypeNavColumn(current: DbType, onSelect: (DbType) -> Unit) {
    Column(
        modifier = Modifier
            .width(176.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colors.surface)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        DbCategory.entries.forEach { category ->
            Text(
                categoryLabel(category),
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 4.dp),
            )
            DbType.entries.filter { it.category == category }.forEach { type ->
                TypeNavRow(type = type, selected = type == current) { onSelect(type) }
            }
        }
    }
}

@Composable
private fun TypeNavRow(type: DbType, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colors.primary.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        TypeBadge(type)
        Spacer(Modifier.width(8.dp))
        Text(
            typeLabel(type),
            fontSize = 12.5.sp,
            color = if (selected) MaterialTheme.colors.onSurface
            else MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 类型展示名：H2 双形态区分服务端/本地文件，其余用品牌名（不翻译）。 */
private fun typeLabel(type: DbType): String = when (type) {
    DbType.H2 -> I18n.t(Str.ConnTypeH2)
    DbType.H2LOCAL -> I18n.t(Str.ConnTypeH2Local)
    else -> type.label
}

private fun categoryLabel(category: DbCategory): String = when (category) {
    DbCategory.LOCAL -> I18n.t(Str.ConnCategoryLocal)
    DbCategory.RELATIONAL -> I18n.t(Str.ConnCategoryRelational)
    DbCategory.NON_RELATIONAL -> I18n.t(Str.ConnCategoryNonRelational)
}

/** 单行：左标签 + 全宽内容。 */
@Composable
private fun FormRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.75f),
            modifier = Modifier.width(LabelGutter),
        )
        Row(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/** 两列并排行：小标签置于字段上方，整体从标签栏后开始。
 *  左列字段与单行字段左边框对齐；两列等宽，右缘不必对齐表单右边界。 */
@Composable
private fun PairRow(
    labelA: String,
    fieldA: @Composable () -> Unit,
    labelB: String,
    fieldB: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = LabelGutter),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CellWithHeader(labelA, Modifier.weight(1f)) { fieldA() }
        CellWithHeader(labelB, Modifier.weight(1f)) { fieldB() }
    }
}

/** 一列：列标题在字段上方。 */
@Composable
private fun CellWithHeader(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier = modifier) {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(5.dp))
        Box(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

/** 紧凑单行输入框：自绘边框、水平小内边距（10dp），13sp 文本垂直居中，不裁切。 */
@Composable
private fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
    isPassword: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().height(FieldHeight),
        singleLine = true,
        textStyle = FieldTextStyle.copy(color = MaterialTheme.colors.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colors.primary),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        interactionSource = interaction,
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(FieldCorner)
                    .border(1.dp, fieldOutline(focused), FieldCorner)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty() && placeholder != null) {
                    Text(
                        placeholder,
                        style = FieldTextStyle.copy(color = MaterialTheme.colors.onSurface.copy(alpha = 0.42f)),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                inner()
            }
        },
    )
}

/** 只读下拉框：与 CompactField 同高、同边框风格的展示框 + DropdownMenu。 */
@Composable
private fun DropdownField(
    label: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth().height(FieldHeight)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .clip(FieldCorner)
                .border(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.45f), FieldCorner)
                .clickable { expanded = true }
                .padding(start = 10.dp, end = 2.dp),
        ) {
            Text(
                label,
                style = FieldTextStyle.copy(color = MaterialTheme.colors.onSurface),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = t(Str.ConnSelect),
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(onClick = { onSelect(index); expanded = false }) {
                    Text(option, fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * 原生文件选择（AWT `FileDialog`）：用 SAVE 模式，既能挑已有库文件，也能进入目录 + 输入新文件名
 * 来**新建**本地库（SELECT/LOAD 模式无法选择不存在的文件）。返回绝对路径；取消返回 null。
 *
 * H2 本地库磁盘后缀为 `.mv.db`（旧版 `.h2.db`），若用户点到这类文件，剥掉后缀作为基名，
 * 避免 URL 拼成 `xxx.mv.db` 后再生成 `xxx.mv.db.mv.db`。
 */
private fun pickDatabaseFile(dbType: DbType, current: String): String? {
    val suggested = current.trim().takeIf { it.isNotEmpty() }
        ?.let { runCatching { File(it).absoluteFile }.getOrNull() }
    val fd = FileDialog(null as java.awt.Frame?, I18n.t(Str.ConnPickFile), FileDialog.SAVE)
    if (suggested != null && suggested.parent != null) {
        fd.directory = suggested.parent
        fd.file = suggested.name
    } else {
        fd.file = if (dbType == DbType.H2LOCAL) "demo-h2" else "demo.db"
    }
    fd.isVisible = true
    val dir = fd.directory ?: return null
    val name = fd.file ?: return null
    var path = File(dir, name).absolutePath
    if (dbType == DbType.H2LOCAL) {
        for (suffix in listOf(".mv.db", ".h2.db")) {
            if (path.endsWith(suffix, ignoreCase = true)) {
                path = path.dropLast(suffix.length)
                break
            }
        }
    }
    return path
}
