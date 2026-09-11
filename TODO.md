# TODO（未完成 / 待办事项清单）

> 零散即时事项。核心分阶段发展计划见 `Roadmap.md`；设计依据见 `doc/DESIGN.md`。
> 完成一项即删除对应条目（不再保留"已完成"占位）。但是 ** 加粗标记的分类标题 不删除。

**标题栏区域**
- 右上角的切换目标时，下拉出现在了左上角。

**编辑区域**


**数据源支持**

- SQL Server / Oracle：需要外部驱动加载机制（Oracle 驱动有 license 不能进内置 classpath）→ `<dataDir>/drivers` 目录放 jar，启动时用独立 classloader 注册进 DriverManager，再加方言（Generic 兜底先跑通，再按需覆写）
- （远期）SSH 隧道连内网库

**树区域**

- 已做：按类型细分分组 + 组内计数（P3：表/物化视图/视图/触发器/序列/函数与过程/聚合/操作符/类型/操作符类/操作符族，PG 生效；非 PG 库仅产出自己支持的类型）
- 组内对象多时（如 routines 上千）目前随 schema 展开一次性列出，尚未做组折叠/懒加载 → DataGrip 式“展开到组一级再展开对象”
- 集群级目录（Database Objects：casts/extensions/languages；Server Objects：roles/tablespaces 等）未做（需跨 schema 查询，列为远期观察项）

**连接与安全**

- 已做：密码本机加密（AES-GCM，密钥 `<dataDir>/secret.key` 600，存量明文自动迁移，`enc:v1:` 前缀标记）→ 待人工验收
- 连接档案导出/导入（JSON 一份，便于换机迁移；密码字段按导出选项决定带不带）

**历史与日志**

- 应用内"打开 logs 目录"入口（现在只能手动 find）

**清理与技术债**

- `app/ui/RightPane.kt` 全工程无引用（M1 首页列表遗留，现被树+SqlWorkspace 取代）→ 确认后整体删除
- TODO 里"支持 CK 数据库"已完成，勿再重复排期（含反代链路的 compress=0 兼容处理）

**工程与发布**

- 打包验证：nativeDistributions（Deb）+ jlink modules（java.sql 等）配置已就绪但从未产出安装包 → 首次构建 Deb + 干净环境安装自检
- 单测：kotlin.test + JUnit5（`gradle test`）已覆盖 jdbc 纯逻辑/嵌入式库、tree 行派生、db 迁移/仓库/vault/meta_cache/consoleFiles、SqlEditing/CsvExport、app/state 协程状态（ConsoleState 防抖/激活/删除清理 + run 端到端 H2）。期间修复 ConsoleState.createConsole 冷缓存重复添加、onConnectionDeleted 漏清激活控制台两个缺陷
