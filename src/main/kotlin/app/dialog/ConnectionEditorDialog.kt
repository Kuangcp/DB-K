package app.dialog

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.state.ConnectionEditorRequest
import app.state.SessionFactory
import app.state.friendlySqlError
import db.ConnectionProfile
import db.DbType
import db.FolderRow
import jdbc.ExternalDrivers
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

    val isEmbedded = dbType == DbType.SQLITE
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
    val canSubmit = name.isNotBlank() && (database.isNotBlank() || isEs)

    // ---------- 测试连接 ----------
    val scope = rememberCoroutineScope()
    var testing by remember(request) { mutableStateOf(false) }
    var testPassed by remember(request) { mutableStateOf<Boolean?>(null) }
    var testMessage by remember(request) { mutableStateOf("") }
    val canTest = when {
        isRedis -> host.isNotBlank()
        isEs -> host.isNotBlank()
        else -> database.isNotBlank() && (isEmbedded || host.isNotBlank())
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
                testMessage = "连接成功"
            } else {
                testPassed = false
                testMessage = failure
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "编辑连接" else "新建连接") },
        text = {
            // 表单默认按内容撑高（独立弹窗，高度随内容自适应），把底部「测试连接」等一次展示完；
            // 上限按屏幕可用高度推算，只有屏幕真装不下时才滚动，并显示右侧滚动条。
            val density = LocalDensity.current
            val maxFormHeight = remember(density) {
                val usablePx = runCatching {
                    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .maximumWindowBounds.height
                }.getOrDefault(900)
                (with(density) { usablePx.toDp() } - 180.dp).coerceAtLeast(280.dp)
            }
            FormBody(maxFormHeight) {
                FormRow("名称") {
                    CompactField(value = name, onValueChange = { name = it })
                }
                FormRow("类型") {
                    DropdownField(
                        label = dbType.label,
                        options = DbType.entries.map { it.label },
                        onSelect = { idx ->
                            val next = DbType.entries[idx]
                            // 切换类型时若端口还是旧默认值则跟随新默认
                            if (port == null || port == dbType.defaultPort) {
                                portText = next.defaultPort.toString()
                            }
                            dbType = next
                        },
                    )
                }
                if (dbType.externalDriver) {
                    val ready = ExternalDrivers.isAvailable(dbType.driverClass)
                    Text(
                        if (ready) {
                            "外部驱动已加载：${dbType.driverClass}"
                        } else {
                            "外部驱动未加载：请将 ${dbType.label} 驱动 jar 放入 ${ExternalDrivers.driversDir()} 后重启"
                        },
                        style = MaterialTheme.typography.caption,
                        color = if (ready) MaterialTheme.colors.onSurface.copy(alpha = 0.55f) else Color(0xFFFFB300),
                        modifier = Modifier.padding(start = LabelGutter, top = 2.dp),
                    )
                }
                FormRow("文件夹") {
                    val flatFolders = remember(folders) { flattenFolderTree(folders) }
                    val options = remember(folders) {
                        listOf("未分组") + flatFolders.map { (f, depth) ->
                            buildString {
                                repeat(depth) { append("    ") }
                                append(f.name)
                            }
                        }
                    }
                    DropdownField(
                        label = folderId?.let { fid -> folders.firstOrNull { it.id == fid }?.name } ?: "未分组",
                        options = options,
                        onSelect = { idx ->
                            folderId = if (idx == 0) null else flatFolders[idx - 1].first.id
                        },
                    )
                }
                if (!isEmbedded) {
                    PairRow(
                        labelA = "主机",
                        fieldA = {
                            CompactField(
                                value = host, onValueChange = { host = it },
                                placeholder = "localhost",
                            )
                        },
                        labelB = "端口",
                        fieldB = {
                            CompactField(
                                value = portText,
                                onValueChange = { portText = it.filter { c -> c.isDigit() }.take(6) },
                                placeholder = dbType.defaultPort.toString(),
                            )
                        },
                    )
                }
                FormRow(if (isEmbedded) "文件路径" else if (isRedis) "DB 序号" else if (isEs) "默认索引（可空）" else "数据库") {
                    CompactField(
                        value = database,
                        onValueChange = { database = it },
                        placeholder = when {
                            isEmbedded -> "/path/to/demo.db"
                            isRedis -> "0"
                            isEs -> "my-index（可空）"
                            else -> dbType.label.lowercase()
                        },
                    )
                }
                if (isRedis) {
                    FormRow("Key 分隔符") {
                        CompactField(
                            value = keySeparator,
                            onValueChange = { keySeparator = it },
                            placeholder = ":",
                        )
                    }
                    Text(
                        "按分隔符把键渲染成层级目录（如 a:b:c → a / b / c）；留空 = 平铺显示全部键",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = LabelGutter, top = 2.dp),
                    )
                }
                if (!isEmbedded) {
                    PairRow(
                        labelA = "用户名",
                        fieldA = {
                            CompactField(
                                value = user, onValueChange = { user = it },
                                placeholder = if (isEs) "elastic（可空）" else "root",
                            )
                        },
                        labelB = if (isEs) "密码 / API Key" else "密码",
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
                            "用户名为空时，密码按 API Key 认证（Authorization: ApiKey）；附加参数支持 scheme=https、path=/es",
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(start = LabelGutter, top = 2.dp),
                        )
                    }
                    FormRow("附加参数") {
                        CompactField(
                            value = extra,
                            onValueChange = { extra = it },
                            placeholder = if (isEs) "scheme=https&path=/es" else "sslMode=require&connectTimeout=5",
                        )
                    }
                }
                if (!isEmbedded && (database.isNotBlank() || isRedis || isEs)) {
                    Text(
                        when {
                            isRedis -> "连接串：${build().urlPreview()}"
                            isEs -> "连接地址：${build().urlPreview()}"
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
                        Text("测试连接", fontSize = 12.5.sp)
                    }
                    val statusColor = when {
                        testPassed == true -> Color(0xFF43A047)
                        testPassed == false -> Color(0xFFE53935)
                        else -> MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                    }
                    val statusText = when {
                        testing -> "正在连接…"
                        testMessage.isNotEmpty() -> testMessage
                        else -> "填入主机/端口/账号后点此验证"
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
        },
        confirmButton = {
            TextButton(enabled = canSubmit, onClick = { onSubmit(build()) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 连接表单本体：宽度 480dp、高度随内容自适应（独立弹窗），把底部「测试连接」等一次展示完。
 * 仅当内容超过 [maxHeight]（由屏幕可用高度推算）时才滚动，并在右侧显示可见滚动条，
 * 避免「必须滚动才能看到底部、又没有滚动条」的隐性截断。
 */
@Composable
private fun FormBody(maxHeight: Dp, content: @Composable ColumnScope.() -> Unit) {
    val scrollState = rememberScrollState()
    Box(modifier = Modifier.width(480.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(scrollState)
                .padding(end = if (scrollState.maxValue > 0) 12.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
            content = content,
        )
        if (scrollState.maxValue > 0) {
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scrollState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                style = viewerScrollbarStyle(),
            )
        }
    }
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
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().height(FieldHeight),
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
                contentDescription = "选择",
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
