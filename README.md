# db-k

<img src="icon/db-k-256.png" width="96" alt="db-k">

轻量级跨库 JDBC 数据库客户端（Kotlin + Compose Desktop），架构对齐 [api-x](https://github.com/kuangcp/api-x) 的分层最佳实践。

单窗口完成「连接 → 编辑 → 执行 → 看结果」主闭环；连接档案、控制台、SQL 历史全部落在本机 SQLite，不依赖任何服务端。

技术栈：JDK 25（JBR）+ Gradle 9.4.1 + Kotlin 2.4.0 + Compose Desktop 1.12.0（**项目不引入 wrapper**，统一用系统 Gradle）。

## 特性

- **对象树**：文件夹 → 连接（状态点 / 懒加载）→ schema → 对象按类型分组计数。PostgreSQL 细分 11 类对象（表 / 物化视图 / 视图 / 触发器 / 序列 / 函数与过程 / 聚合 / 操作符 / 类型 / 操作符类 / 操作符族），其余库只产出自己支持的类型；PostgreSQL 下 schema 展开只取组计数 + 核心类型，重类型组展开时才拉正文。展开状态持久化；双击连接即连、双击表 / 视图预览。
- **SQL 编辑**：语法高亮、行号、当前行高亮、关键字 / 表视图 / 列名补全，`Ctrl+Enter` 执行选中（无选中不执行）、`Ctrl+Q` 查看光标处表定义 DDL、`Ctrl+S` 保存、多语句执行 + 多 Tab 结果。
- **结果网格**：纵横滚动 + 列宽拖动 / 单列自适应；单击高亮并 `Ctrl+C`，右键复制单元格 / 「复制本行 → INSERT」；`Ctrl+T` 行列转置；超 1000 行截断提示 + 全量流式 CSV 导出；长查询可取消（`Esc`）。
- **可编辑结果**：`Ctrl+双击` 单元格就地编辑 / 右键「编辑」；提交前弹窗预览逐行参数化 UPDATE，确认后单事务写回（按主键定位，影响行数 ≠ 1 则整体回滚）。
- **多控制台**：一个数据源可开多个控制台，跨源标签；标签右键「关闭」（隐藏可重开）；每个控制台独立执行目标（库 / schema）与**光标记忆**（切换、重启都恢复到所在行）。
- **SQL 历史**：执行记录落库 + 历史面板，双击弹窗查看完整 SQL（可复制），或一键插入到当前光标处。
- **连接安全与迁移**：连接密码 AES-256-GCM 本机加密（密钥 `secret.key`，权限 600），存量明文自动迁移；连接档案 JSON 导出/导入（默认不含密码，可选含明文密码并二次确认；导入幂等合并、id 冲突自动重映射）。
- **外观与配置**：浅 / 深色主题切换并持久化；设置窗口可调编辑器字体族与字号（实时预览），并可在「快捷键」分区自定义扩展业务功能键（执行 / 格式化 / 查看定义 / 显隐结果 / 转置 / 刷新，基础编辑键固定），以及显示日志 / 数据目录路径、一键用文件管理器打开（无桌面环境回落复制路径 + 提示）。
- **其它**：窗口几何持久化、会话日志按月归档、连接目录元数据磁盘缓存。

## 支持的数据源

| 数据源 | 连接方式 | 备注 |
|---|---|---|
| PostgreSQL | 内置驱动 | 对象类型细分最全 |
| MySQL | 内置驱动 | |
| MariaDB | 内置驱动 | |
| SQLite | 内置驱动 | |
| H2 | 内置驱动 | |
| ClickHouse | 内置驱动（HTTP，默认端口 8123） | 默认关压缩，兼容反向代理链路 |
| SQL Server | **外部驱动**（`drivers/`） | `mssql-jdbc` jar 放入数据目录 `drivers/` |
| Oracle | **外部驱动**（`drivers/`） | `ojdbc` jar 放入数据目录 `drivers/`（license 限制，不内置） |
| Redis | 内置 Jedis（非 JDBC） | 命名空间 = DB 以**过滤条**呈现（DB 下拉 + 类型下拉 + key 模式搜索）；键 = `SCAN MATCH` 游标分页 + pipeline `TYPE`/`TTL`；命令台可跑任意原生命令 |
| Elasticsearch | 内置 `HttpClient`（非 JDBC） | 命名空间 = 集群；对象 = 索引 / 别名（`_cat`）；`_mapping` 展开字段；控制台输入 JSON DSL → `_search`，`from/size` 支持「取更多」 |

> **外部驱动**：把驱动 jar（及它自带的依赖 jar）放进数据目录下的 `drivers/` 目录
> （Linux `~/.local/share/db-k/drivers/`，可用 `-Ddbk.driversDir=<dir>` 覆盖），**重启应用**后生效。
> 加载状态可在「新建连接 → 类型」处看到提示。

> **Elasticsearch 连接**：填 host/port（默认 9200），`默认索引` 可留空（留空时 DSL 需自带 `index`）；
> 用户名/密码 = Basic 认证，**用户名留空、密码填 API Key** = `Authorization: ApiKey`；
> 附加参数支持 `scheme=https`、`path=/es`（反向代理前缀）。
> 受限账号无 `indices:monitor/settings/get` 时 `_cat/indices` 会 403，会依次回落 `_alias`/`_mapping`/`_search` 聚合/默认索引；
> 仍失败则填「默认索引」后用 DSL 查询，或请管理员授予权限。
> **写 DSL**：双击树里的索引会插入 `match_all` 骨架；编辑器内 JSON 补全（Ctrl+Space）会按上下文补
> 顶层键 / 查询类型 / `bool` 子句 / 字段名（取目标索引 `_mapping`）/ `sort`·`aggs` 参数，Enter/Tab 上屏。

## 快速开始

```bash
source ~/.sdkman/bin/sdkman-init.sh   # 或按你 shell 的方式加载 sdkman
source env-init                        # sdk use java 25.0.3-jbr / sdk use gradle 9.4.1

gradle run                 # 调试运行
gradle test                # 单元测试（jdbc / tree / db / app.state 纯逻辑 + 嵌入式库集成）
gradle smokeJdbc           # JDBC 方言 / QueryExecutor 冒烟自检（无需 UI）
gradle smokeRedis          # Redis 后端自检（需可连的 Redis，否则 SKIP）
gradle smokeEs             # Elasticsearch 后端自检（需可连的 ES，否则 SKIP；会建/删临时索引）
```

## 打包

```bash
gradle createDistributable   # 免安装目录 build/compose/binaries/main/app/db-k
gradle packageDeb            # Linux Deb
gradle makeAppImage          # Linux 单文件 .AppImage（需 appimagetool，见下）
gradle packageMsi            # Windows MSI（仅在 Windows + WiX 上构建）
```

- AppImage：下载 [appimagetool](https://github.com/AppImage/appimagetool/releases) 放到 `tools/`，或 `-Pappimagetool=<path>` / 环境变量 `APPIMAGETOOL`。产物 `build/compose/binaries/main/appimage/db-k-<ver>-x86_64.AppImage`。
- MSI 只能在 Windows 上构建（jpackage 不支持交叉打包），需 JDK 25 + Gradle 9.4.1 + WiX Toolset 3.x。
- 平台构建矩阵、前置条件与 glibc 兼容下限见 **[doc/PACKAGING.md](doc/PACKAGING.md)**。

## 数据与存储

本地数据默认位于平台数据目录：

- Linux：`~/.local/share/db-k/`
- 开发调试可用 `debugHome` 重定向：在主数据目录放 `app-settings.properties`，内容 `debugHome=/path/to/sandbox`。

持久化分层：

| 内容 | 位置 |
|---|---|
| 连接 / 文件夹 / 控制台元数据 / SQL 历史 / 元数据缓存 | `app.db`（SQLite，含版本迁移与 FK 级联） |
| 控制台正文 | `consoles/<id>.sql`（独立文件，防抖 3s 自动写回） |
| 主题 / 窗口几何 / 树展开 / 编辑器字体字号 | `*.properties` |
| 外部 JDBC 驱动（SQL Server / Oracle 等） | `drivers/*.jar`（可 `-Ddbk.driversDir` 覆盖） |
| 会话日志 | `logs/<yyyy-MM>/<yyyy-MM-dd>_<N>.log`（每次启动一份） |

## 目录结构

```
src/main/kotlin/
├── app/          # Compose UI + 状态（状态即 ViewModel）
│   ├── core/     # Main.kt 窗口组装、CsvExport、会话日志 writer
│   ├── state/    # ConnectionsState / ConsoleState / TreeState / DialogState / ToastState / ColumnCatalog
│   ├── dialog/   # 连接编辑、设置、单字段输入、查看器（SQL / 单元格）
│   ├── ui/       # 主题、工作区、对象树侧栏、历史面板、SQL 高亮/补全、自绘图标
│   └── settings/ # 主题 / 窗口 / 树展开 / 编辑器 等 properties 持久化
├── db/           # 应用自身元数据：AppDatabase 迁移 + Repository + 密码加密 + 控制台文件
├── tree/         # 左侧树：扁平化行模型 + 侧栏组件
└── jdbc/         # 目标库方言 / 连接 / 元数据探测 / 查询执行（阻塞 API）
```

分层纪律：`jdbc/` `db/` `tree/` 不依赖 compose / coroutines；`app/` 单向依赖它们。

## 里程碑

- [x] M0 脚手架：空窗口 + 主题
- [x] M1 元数据 + 树：连接 / 文件夹档案 CRUD、对象树、展开持久化
- [x] M2 JDBC 运行时：方言抽象 + 连接 + 懒加载 库 → 对象树（6 种数据源）
- [x] M3 编辑执行：SQL 编辑器 + 结果网格 + 取消 + 历史
- [x] M4 体验打磨：补全 / 预览 / CSV / 主题 / 设置 / 多控制台
- [x] M5 工程收敛与发布：单元测试体系 + Deb / AppImage / MSI 打包配置（v1.0.0）
- [x] P4 数据源扩展机制：`<dataDir>/drivers` 外部驱动独立 classloader（SQL Server / Oracle）
- [x] P5 连接档案导出 / 导入：JSON（默认不含密码）+ 幂等合并导入
- [x] P6 树按组懒加载：schema 只取计数 + 核心类型，重类型组展开时才拉（PostgreSQL）
- [x] P7 可编辑结果后续：提交前 UPDATE 预览确认
- [x] P9 诊断入口：设置窗口展示并打开日志 / 数据目录
- [ ] P8 发布验证（Deb / AppImage 干净环境，MSI）——已降为最低优先

下一轮路线（对标 Navicat / DataGrip，详见 [Roadmap.md](Roadmap.md) §6）：
- [x] N4 后端抽象重构：`engine.DataSourceSession` 契约 + JDBC 为首个实现（`EditableSession` 隔离写回）
- [x] N1 结果排序/筛选/取更多
- [x] N2 编辑器格式化/查找替换（代码折叠待做）
- [x] N3 增行/删行
- [x] N5 Redis 后端：Jedis 会话实现 + 数据驱动对象组（`ObjectKind.KEY`）+ 命名空间过滤器（DB/类型/key 模式搜索，`SCAN` 游标分页）+ 危险命令确认
- [x] N6 Elasticsearch 后端：`HttpClient` + JSON DSL（集群命名空间 / 索引·别名 / `_mapping` / JSON 高亮 / `from·size` 取更多）
- [ ] N7 DDL 编辑执行 → N8 导出 JSON/SQL INSERT/Excel →
  N9 树导航 → N10 SSL/驱动管理 → N11 EXPLAIN/会话锁 → N12 发布验证（最低）。

## 文档

- **[AGENTS.md](AGENTS.md)**：工具链、分层、主题硬性规则、持久化约定、验证习惯
- **[doc/DESIGN.md](doc/DESIGN.md)**：数据模型、方言抽象、线程模型、打包风险
- **[Roadmap.md](Roadmap.md)**：分阶段发展计划与完成状态
- **[doc/PACKAGING.md](doc/PACKAGING.md)**：Deb / AppImage / MSI 打包
- **[TODO.md](TODO.md)**：零散待办

## 许可

[Apache License 2.0](LICENSE)
