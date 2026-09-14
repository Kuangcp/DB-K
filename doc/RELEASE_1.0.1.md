# db-k v1.0.1 发布公告

> **Release 标题建议**：`db-k v1.0.1`
>
> 本次从 `v1.0.0` 起累计 24 个提交、100 个文件（+11091 / −555）。
> 重点：**可编辑结果集**、**Redis 后端**、**协议无关引擎契约**、**外部 JDBC 驱动**、
> **连接档案加密导出/导入**，以及结果网格、SQL 编辑器、快捷键的一系列增强。

从本地 SQL 客户端，向「多协议数据工具」迈出一步：1.0.1 引入可写回结果网格与
Redis 支持，并把后端抽象为协议无关契约，为后续 Elasticsearch 等新数据源铺好路。

---

## ✨ 新特性

### 可编辑结果集（P0–P3）
- 结果单元格**行内编辑**（`Ctrl+双击` 或右键「编辑单元格」）。
- 提交前弹窗**预览逐行参数化 UPDATE**，确认后**单事务写回**；按主键定位，
  （无主键时回落到唯一索引）影响行数 ≠ 1 则整体回滚。
- 支持**撤销单格 / 撤销全部**；刷新、重跑、切换结果/控制台、断开、退出前都有未提交守卫。
- 长值（>60 字符）、多行文本、NULL 自动改用**对话框编辑**，NULL 与空串严格区分。

### 新数据源：Redis（N5）
- 内置 Jedis（非 JDBC），对象树按 key 类型分组。
- 命名空间（DB）以**过滤条**呈现：DB 下拉 + 类型下拉 + key 模式搜索。
- 键列表走 `SCAN MATCH` **游标分页** + pipeline 批量取 `TYPE`/`TTL`。
- 命令台可执行任意原生命令；`FLUSHALL` 等**危险命令二次确认**。

### 协议无关引擎契约（N4，架构）
- 新增 `engine` 叶层：`DataSourceSession` / `BackendCapabilities` / `Protocol` + `engine/model`。
- 后端选择集中到 `app/state/SessionFactory`；JDBC 成为首个实现，Redis 为第二个。
- JDBC 专属能力（主键写回等）另立 `jdbc.EditableSession`，不污染通用层。

### 外部 JDBC 驱动（P4/P5）
- 新增数据源：**SQL Server**、**Oracle**——把驱动 jar 放入 `<dataDir>/drivers/`
  （`-Ddbk.driversDir` 可覆盖），独立 classloader 加载，重启生效（license 限制不内置）。
- 「新建连接 → 类型」处可见驱动加载状态。

### 连接档案加密导出 / 导入
- 支持导出为 AES-256-GCM **口令加密**的 `.dbk` 档案，导入时输入口令解密。
- 默认不含密码，可选导出明文密码并二次确认。
- 导入**幂等合并**，id 冲突自动重映射；同名冲突时交互选择「导入为新档案 / 跳过」。

### 结果网格增强（N1）
- **客户端排序 / 筛选**视图：只改行顺序与可见行，不重跑 SQL，且保留回原始行的映射。
- **「取更多」**：结果被截断时按方言注入分页，把新行**追加**到当前结果（不重跑原查询），
  并提示顺序保证。

### SQL 编辑器增强（N2）
- **格式化 SQL**：`Ctrl+Alt+L`，有选区只格式化选区，否则整段。
- **查找替换**：`Ctrl+F` / `Ctrl+H`，支持 `Aa` 区分大小写、`.*` 正则，`Enter`/`Shift+Enter` 上下跳转。

### 快捷键可配置
- 快捷键统一登记到一处注册表；**扩展业务功能**可自定义（执行 / 格式化 / 查看定义 /
  显隐结果区 / 转置 / 刷新），**基础编辑键**（保存 / 复制粘贴 / 查找替换 / 补全 /
  方向导航 / 取消执行）固定不可改。
- 设置窗口新增**「快捷键」分区**：点组合键胶囊录制新键、单条重置、全部恢复默认、
  同作用域冲突红字提示；保存写 `<dataDir>/keymap.properties`，启动读取并即时生效。

### 其它 UI / 交互
- 单元格弹窗支持 **JSON 高亮 + 可折叠树视图**。
- 对象树**按组懒加载**：PostgreSQL 下 schema 展开只取组计数 + 核心类型，重类型组展开时才拉正文。
- 对象树双击预览改为在该数据源**最后控制台的光标处追加**，不覆盖草稿、不自动执行。
- 设置窗口新增**「诊断」分区**：展示会话日志 / 数据目录，一键用系统文件管理器打开
  （无桌面环境回落复制路径 + 行内提示）。

---

## 🐛 修复
- 修复 Redis 预览误写执行目标（`console.target`）：预览不再改动 flatNamespace 连接的 DB 过滤器。

## ⚠️ 行为变更 / 注意
- 对象树双击对象：改为**追加到光标处且不自动执行**（旧行为会覆盖/新建草稿并自动执行）。
- 快捷键「保存 / 复制粘贴 / 查找替换」等基础编辑键**固定**，不在快捷键设置中暴露。

---

## 📦 安装与升级

- **AppImage**（单文件）：`db-k-1.0.1-x86_64.AppImage`，需 glibc ≥ 2.28（Debian 10+ / Ubuntu 20.04+ / RHEL 8+）。
- **Deb**：`gradle packageDeb` → `db-k_1.0.1_*.deb`。
- **MSI**（仅 Windows 构建）：`gradle packageMsi` → `db-k-1.0.1.msi`，升级 UUID 固定，可直接覆盖升级。
- **免安装目录**：`gradle createDistributable`。

升级无需迁移操作：本地数据（连接 / 控制台 / 历史）自动兼容；新增配置为独立 `*.properties`。

> 外部驱动（SQL Server / Oracle）需自行下载 jar 放入数据目录 `drivers/` 后**重启**应用。

---

## 📊 变更统计
- 提交：**24**（`v1.0.0` → `v1.0.1`）
- 文件：**100**（+11091 / −555）
- 数据源：新增 **Redis**（内置），**SQL Server / Oracle**（外部驱动）

## 📝 完整变更
```
146dbf2 feat(settings): 快捷键统一注册表 + 设置窗口快捷键分区（业务功能可配）
672c0b4 feat(editor): SQL 格式化与查找替换（N2，折叠待做）
cd7eb52 feat(result): N1「取更多」按方言分页追加 + 顺序提示
81487fd feat(result): N1 结果网格客户端排序/筛选视图（不重跑 SQL）
36217ec feat(profiles): 数据源档案 AES 口令加密导出/导入 + 同名冲突交互选择
8d2885b docs(preview): 记录 Redis flatNamespace 守卫决策
e11067a fix(tree): Redis 预览不写 console.target（恢复 flatNamespace 守卫）
6b756d6 feat(tree): 双击预览改为在该数据源最后控制台光标处追加，不自动执行
e69e6aa refactor(ui): 编辑器插入请求提升到 Main，预览/历史共用
5f33cd6 Merge branch 'main' of gitee.com:gin9/db-k
47243fd feat(redis): 命名空间即过滤器（DB/类型/key 搜索 + 游标分页）
87e6c21 docs(preview): 对象树双击预览改为目标控制台光标处追加（设计）
b4ae182 feat(n5): Redis 后端（Jedis）+ 对象组数据驱动 + 危险命令确认
b011504 refactor(n4): 抽出协议无关引擎契约，JDBC 成为首个实现
133899d docs(roadmap): 落地已定方向 + 新增多协议后端（Redis/ES）设计
7766cbb docs(roadmap): 对标 Navicat/DataGrip 重排路线，压缩已完成阶段
d5bb8c1 feat(p9): 设置窗口诊断分区——打开日志/数据目录入口
d0e2500 feat(p6,p7): 对象树按组懒加载 + 提交前 UPDATE 预览
b1f64b8 feat(p4,p5): 外部驱动加载（SQL Server/Oracle）+ 连接档案导出/导入
8c0769e feat(result-edit): Phase 3 长值/多行单元格对话框编辑
32e1f73 feat(result-edit): Phase 2 撤销全部、退出守卫与唯一索引回落
fd9bb4e feat(result-edit): Phase 1 结果单元格行内编辑、提交与刷新
86ca4ac feat(result-edit): Phase 0 地基——可编辑结果元数据/判定/写回（无 UI）
7e8e3fd feat(cell-viewer): 单元格弹窗支持 JSON 高亮与可折叠树视图
```

**Full Changelog**: https://gitee.com/gin9/db-k/compare/v1.0.0...v1.0.1
