package engine

/**
 * 数据源协议：决定连接实现、树语义与编辑器语言。
 * 目前仅 [JDBC] 落地；[REDIS] / [ELASTICSEARCH] 为多协议后端预留（见 Roadmap §7）。
 */
enum class Protocol { JDBC, REDIS, ELASTICSEARCH }

/** 编辑器语言：驱动高亮 / 补全 / 语句切分策略（SQL / Redis 命令 / JSON DSL）。 */
enum class EditorLanguage { SQL, REDIS_COMMAND, JSON }
