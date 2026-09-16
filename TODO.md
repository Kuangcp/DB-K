# TODO（未完成 / 待办事项清单）

> 零散即时事项。核心分阶段发展计划见 `Roadmap.md`；设计依据见 `doc/DESIGN.md`。
> 完成一项即删除对应条目（不再保留"已完成"占位）。但是 ** 加粗标记的分类标题 不删除。

**标题栏区域**


**编辑区域**


**结果编辑**
- （可选）行锁：刷新编辑结果时用 `SELECT … FOR UPDATE`（PG/MySQL/Oracle）降低并发覆盖；需评估对只读查询的副作用（当前靠提交时 `affected==1` 检测）
- 新增行 / 删除行；富类型（BLOB/图片/JSON 结构）就地编辑仍走查看器


**数据源支持**

- SQL Server / Oracle：外部驱动机制已实现（`<dataDir>/drivers` 放 jar + 独立 classloader + 方言 + 驱动未加载提示）→ **待放真实驱动 jar 人工验收**（建连 / 展开树 / 查询）；驱动需与 JDK 25 兼容
- （可选）SQL Server 暂按 schema 建模（跨 database 需改档案的 database）；如需数据库级目录再扩展
- 已做：Redis 后端（N5）：Jedis 会话实现 `engine.DataSourceSession` + 命名空间过滤器（DB 下拉 + 类型下拉 + key 模式搜索，`SCAN MATCH` 游标分页 + pipeline `TYPE`/`TTL` 标注，绕过 MetaCache；浏览状态持久化 `redis.properties`）+ 命令台（允许写命令，危险命令二次确认）
  - 自检：`gradle smokeRedis`（含建连 / 五类键 / SCAN+TYPE / 命令渲染 / 搜索+类型过滤+TTL / 600 键分页去重 / 取消重连；连不上 SKIP）；已对无密码、requirepass、ACL user 三种服务端实测通过
  - 待人工验收：Compose UI 下建 Redis 连接 / DB 与类型下拉 / pattern 搜索 / 继续扫描 / 双击预览 / 危险命令确认弹窗（深色模式）
- 未做（Redis 可选深化）：专用 value viewer（JSON 树 / TTL 编辑）、Redis 命令补全与语法高亮（`editorLanguage` 能力位已预留）、工作台表格式 key 浏览器（方案 B）
- （远期）Elasticsearch 后端（N6）
- （远期）SSH 隧道连内网库

**树区域**

- 已做：按类型细分分组 + 组内计数（P3：表/物化视图/视图/触发器/序列/函数与过程/聚合/操作符/类型/操作符类/操作符族，PG 生效；非 PG 库仅产出自己支持的类型）
- 按组懒加载已实现（P6）：schema 展开只取「计数 + 核心类型」，重类型组展开时才拉对象（PG 开启 `lazyObjectGroups`，非 PG 回落全量）→ **待含上千例程的实库人工验收**
- 集群级目录（Database Objects：casts/extensions/languages；Server Objects：roles/tablespaces 等）未做（需跨 schema 查询），详见 `Roadmap.md` P6
- （远期）表子节点：表下展开列 / 索引 / 约束（当前列信息只服务补全缓存，树不展示）

**连接与安全**


**历史与日志**


**清理与技术债**

**工程与发布**

- 打包验证（**最低优先，发布时再做**）：nativeDistributions（Deb）配置已就绪但从未产出安装包 → 首次构建 Deb + 干净环境安装自检（原 P8 / 现 N9）
- 单测：kotlin.test + JUnit5（`gradle test`）已覆盖 jdbc 纯逻辑/嵌入式库、tree 行派生、db 迁移/仓库/vault/meta_cache/consoleFiles/ProfileTransfer、SqlEditing/CsvExport、app/state 协程状态（ConsoleState 防抖/激活/删除清理 + run 端到端 H2）。期间修复 ConsoleState.createConsole 冷缓存重复添加、onConnectionDeleted 漏清激活控制台两个缺陷
