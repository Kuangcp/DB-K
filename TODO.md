# TODO（未完成 / 待办事项清单）

> 零散即时事项。核心分阶段发展计划见 `Roadmap.md`；设计依据见 `doc/DESIGN.md`。
> 完成一项即删除对应条目（不再保留"已完成"占位）。

**编辑区域**

- 列名级自动补全：现有关键字 + 表/视图/物化视图名补全已实现（P2/P3）；列名候选需先在元数据层缓存列（数据模型落地后补）
- 允许多个tab 一个tab就是一个控制台(也就是将现在的数据源主导改为控制台主导，因为日常gong) 控制台内 右上角展示当前数据源，然后可以切换 数据库 或者pg里的 schema category 


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
- 单测起步：jdbc/tree 层纯逻辑（urlPreview、quoteIdent、isSystemSchemaName、树行派生）加 kotlin.test；现在只有 smokeJdbc 一个程序化冒烟入口
