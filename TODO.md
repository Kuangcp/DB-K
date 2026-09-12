# TODO（未完成 / 待办事项清单）

> 零散即时事项。核心分阶段发展计划见 `Roadmap.md`；设计依据见 `doc/DESIGN.md`。
> 完成一项即删除对应条目（不再保留"已完成"占位）。但是 ** 加粗标记的分类标题 不删除。

**标题栏区域**


**编辑区域**


**数据源支持**

- SQL Server / Oracle：外部驱动机制已实现（`<dataDir>/drivers` 放 jar + 独立 classloader + 方言 + 驱动未加载提示）→ **待放真实驱动 jar 人工验收**（建连 / 展开树 / 查询）；驱动需与 JDK 25 兼容
- （可选）SQL Server 暂按 schema 建模（跨 database 需改档案的 database）；如需数据库级目录再扩展
- （远期）SSH 隧道连内网库

**树区域**

- 已做：按类型细分分组 + 组内计数（P3：表/物化视图/视图/触发器/序列/函数与过程/聚合/操作符/类型/操作符类/操作符族，PG 生效；非 PG 库仅产出自己支持的类型）
- 组折叠已做（对象行只在组展开时渲染），但对象清单仍随 schema 展开一次性拉取（`loadObjects` 全量），大库（routines 上千）首屏慢 → 按组懒加载（schema 只拉组+计数，组展开才拉对象），详见 `Roadmap.md` P6
- 集群级目录（Database Objects：casts/extensions/languages；Server Objects：roles/tablespaces 等）未做（需跨 schema 查询），详见 `Roadmap.md` P6

**连接与安全**

- 已做：密码本机加密（AES-GCM，密钥 `<dataDir>/secret.key` 600，存量明文自动迁移，`enc:v1:` 前缀标记）→ 待人工验收
- 已做：连接档案导出/导入（JSON，树工具栏「⋮」入口：导出不含密码 / 导出含明文密码 / 导入；导入为幂等合并、id 冲突自动重映射不覆盖、单事务）
- （可选）加密导出（用户口令派生密钥）与同名冲突的交互式「跳过 / 导入为新档案」选择；本轮未做

**历史与日志**

- 应用内"打开 logs 目录"入口（现在只能手动 find）

**清理与技术债**

**工程与发布**

- 打包验证：nativeDistributions（Deb）+ jlink modules（java.sql 等）配置已就绪但从未产出安装包 → 首次构建 Deb + 干净环境安装自检
- 单测：kotlin.test + JUnit5（`gradle test`）已覆盖 jdbc 纯逻辑/嵌入式库、tree 行派生、db 迁移/仓库/vault/meta_cache/consoleFiles/ProfileTransfer、SqlEditing/CsvExport、app/state 协程状态（ConsoleState 防抖/激活/删除清理 + run 端到端 H2）。期间修复 ConsoleState.createConsole 冷缓存重复添加、onConnectionDeleted 漏清激活控制台两个缺陷
