package app.settings

import db.AppPaths
import java.io.File
import java.util.Properties

/**
 * Redis 浏览 UI 状态（按连接）：当前查看的 DB / key pattern / 类型过滤。
 * 属「应用级浏览偏好」而非业务实体：删连接时跟随 profileId 失效即可，无需 DB 迁移。
 */
data class RedisUiState(
    val db: String? = null,
    val pattern: String = "*",
    val type: String? = null,
)

/** Redis 浏览状态持久化（轻量 properties 文件，按 profileId 分键）。 */
object RedisPrefs {

    private fun prefsFile(): File = AppPaths.dataDirectory().resolve("redis.properties").toFile()

    fun load(profileId: String): RedisUiState {
        val f = prefsFile()
        if (!f.exists()) return RedisUiState()
        return runCatching {
            val props = Properties()
            f.inputStream().use { props.load(it) }
            RedisUiState(
                db = props.getProperty("$profileId.db")?.takeIf { it.isNotBlank() },
                pattern = props.getProperty("$profileId.pattern") ?: "*",
                type = props.getProperty("$profileId.type")?.takeIf { it.isNotBlank() },
            )
        }.getOrDefault(RedisUiState())
    }

    fun save(profileId: String, state: RedisUiState) {
        runCatching {
            val f = prefsFile()
            val props = Properties()
            if (f.exists()) f.inputStream().use { props.load(it) }
            state.db?.let { props.setProperty("$profileId.db", it) } ?: props.remove("$profileId.db")
            props.setProperty("$profileId.pattern", state.pattern)
            state.type?.let { props.setProperty("$profileId.type", it) } ?: props.remove("$profileId.type")
            f.parentFile?.mkdirs()
            f.outputStream().use { props.store(it, "redis browsing ui state") }
        }
    }

    /** 档案删除：清掉该连接的浏览状态。 */
    fun clear(profileId: String) {
        runCatching {
            val f = prefsFile()
            if (!f.exists()) return
            val props = Properties()
            f.inputStream().use { props.load(it) }
            props.keys.map { it.toString() }.filter { it.startsWith("$profileId.") }
                .forEach { props.remove(it) }
            f.outputStream().use { props.store(it, "redis browsing ui state") }
        }
    }
}
