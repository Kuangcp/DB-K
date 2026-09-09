package app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import db.ConnectionProfile
import jdbc.LiveConnection
import jdbc.model.SchemaMeta
import jdbc.model.SchemaObjects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tinylog.Logger
import tree.ConnUiStatus
import tree.ConnectionRuntimeView
import java.sql.SQLException

/** 把异常压成一行可读短消息（供状态栏/弹层展示）。 */
internal fun friendlySqlError(t: Throwable?): String {
    val cause = t ?: return "未知错误"
    val root = generateSequence(cause) { it.cause }.last()
    return when (root) {
        is SQLException -> {
            val detail = root.message ?: "数据库错误"
            val short = detail.lineSequence().first().take(180)
            if (root.errorCode != 0) "错误码 ${root.errorCode}: $short" else short
        }
        else -> (root.message ?: root.javaClass.simpleName).take(180)
    }
}
/**
 * 连接运行时状态：profileId -> ConnRuntime。
 * 每条连接一个 LiveConnection（单线程串行 JDBC），所有探测/执行经 Dispatchers.IO。
 * UI 可观察状态全部是 compose snapshot（status/schemas/objects…），
 * 方法为 suspend，由 UI 协程调用（内部切 IO 后再写回 snapshot 状态）。
 */
class ConnectionsState : ConnectionRuntimeView {

    private class ConnRuntime(val profile: ConnectionProfile) {
        val live = LiveConnection(profile)
        // 仅 ConnectionsState（外层）修改；对外只经 ConnectionRuntimeView 只读暴露
        var status by mutableStateOf(ConnUiStatus.DISCONNECTED)
        var statusMessage by mutableStateOf<String?>(null)
        var schemas by mutableStateOf<List<SchemaMeta>?>(null)
        var schemasLoading by mutableStateOf(false)
        val objects = mutableStateMapOf<String, SchemaObjects>()
        val objectsLoading = mutableStateMapOf<String, Boolean>()

        fun reset() {
            live.close()
            status = ConnUiStatus.DISCONNECTED
            statusMessage = null
            schemas = null
            schemasLoading = false
            objects.clear()
            objectsLoading.clear()
        }
    }

    private val runtimes = mutableMapOf<String, ConnRuntime>()

    private fun runtime(profile: ConnectionProfile): ConnRuntime =
        runtimes.getOrPut(profile.id) { ConnRuntime(profile) }

    // ---------- ConnectionRuntimeView（UI 读） ----------

    override fun statusOf(profileId: String): ConnUiStatus =
        runtimes[profileId]?.status ?: ConnUiStatus.DISCONNECTED

    override fun statusMessageOf(profileId: String): String? = runtimes[profileId]?.statusMessage

    override fun schemasOf(profileId: String): List<SchemaMeta>? = runtimes[profileId]?.schemas

    override fun schemasLoadingOf(profileId: String): Boolean =
        runtimes[profileId]?.schemasLoading == true

    override fun objectsOf(profileId: String, schemaKey: String): SchemaObjects? =
        runtimes[profileId]?.objects?.get(schemaKey)

    override fun objectsLoadingOf(profileId: String, schemaKey: String): Boolean =
        runtimes[profileId]?.objectsLoading?.get(schemaKey) == true

    // ---------- 动作（suspend；UI 协程调用） ----------

    /**
     * 展开连接：未连接则连接；已连接则确保库列表已加载（幂等，可并发调）。
     * 成功后 status=CONNECTED 且 schemas 就绪；失败 status=ERROR + message。
     */
    suspend fun ensureConnectionReady(profile: ConnectionProfile) {
        val rt = runtime(profile)
        if (rt.status == ConnUiStatus.CONNECTING) return
        if (rt.status != ConnUiStatus.CONNECTED) {
            rt.status = ConnUiStatus.CONNECTING
            rt.statusMessage = null
            rt.schemasLoading = false
            val opened = withContext(Dispatchers.IO) { runCatching { rt.live.open() } }
            opened.exceptionOrNull()?.let { Logger.error(it, "connect failed {}", profile.name) }
            if (opened.isFailure) {
                rt.status = ConnUiStatus.ERROR
                rt.statusMessage = friendlyMessage(opened.exceptionOrNull())
                return
            }
            rt.status = ConnUiStatus.CONNECTED
        }
        if (rt.schemas == null && !rt.schemasLoading) {
            loadSchemas(rt)
        }
    }

    private suspend fun loadSchemas(rt: ConnRuntime) {
        rt.schemasLoading = true
        rt.statusMessage = null
        val result = withContext(Dispatchers.IO) {
            runCatching { rt.live.loadSchemas() }
        }
        rt.schemasLoading = false
        result.onSuccess { rt.schemas = it }
            .onFailure {
                Logger.error(it, "load schemas failed {}", rt.profile.name)
                rt.status = ConnUiStatus.ERROR
                rt.statusMessage = friendlyMessage(it)
            }
    }

    /**
     * 展开 schema：确保其对象已加载（幂等）。
     * schema 展开错误直接置 statusMessage（连接级显示），对象失败不打断其它 schema。
     */
    suspend fun ensureSchemaObjects(profile: ConnectionProfile, schema: SchemaMeta) {
        val rt = runtime(profile)
        val key = schema.key
        if (rt.objects.containsKey(key) || rt.objectsLoading[key] == true) return
        rt.objectsLoading[key] = true
        val result = withContext(Dispatchers.IO) {
            runCatching { rt.live.loadObjects(schema) }
        }
        rt.objectsLoading[key] = false
        result.onSuccess { rt.objects[key] = it }
            .onFailure {
                Logger.error(it, "load objects failed {} {} {}", rt.profile.name, schema.displayName)
                rt.statusMessage = friendlyMessage(it)
            }
    }

    /** 刷新库列表：清对象缓存后重载 schemas（连接保持）。 */
    suspend fun refreshSchemas(profile: ConnectionProfile) {
        val rt = runtime(profile)
        if (rt.status != ConnUiStatus.CONNECTED) return
        rt.schemas = null
        rt.objects.clear()
        rt.objectsLoading.clear()
        loadSchemas(rt)
    }

    /** 主动断开。 */
    fun disconnect(profile: ConnectionProfile) {
        runtime(profile).reset()
    }

    /** 档案被编辑（URL 可能变）：断开并丢弃运行缓存。 */
    fun invalidate(profileId: String) {
        runtimes.remove(profileId)?.live?.close()
    }

    /** 供查询执行引擎取连接句柄（连接必须在 CONNECTED 才非空）。 */
    fun liveConnection(profileId: String): LiveConnection? = runtimes[profileId]?.live

    /** 档案被删除。 */
    fun forget(profileId: String) {
        invalidate(profileId)
    }

    /** 应用退出清理。 */
    fun disposeAll() {
        runtimes.values.forEach { it.live.close() }
        runtimes.clear()
    }

    /** 树收起时是否可释放：M2 缓存策略 —— 不释放，连接保持到断开/退出。 */
    companion object {
        private fun friendlyMessage(t: Throwable?): String = friendlySqlError(t)
    }
}
