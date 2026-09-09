# db-k

轻量级 JDBC 数据库客户端（Compose Desktop），架构对齐 [api-x](https://github.com/kuangcp/api-x) 的四层最佳实践。

技术栈：JDK 25 JBR + Gradle 9.4.1 + Kotlin 2.4.0 + Compose 1.12.0（不依赖 wrapper，见下方工具链）。

## 工具链

```bash
source ~/.sdkman/bin/sdkman-init.sh   # 或按你 shell 的方式加载 sdkman
source env-init                        # sdk use java 25.0.3-jbr / sdk use gradle 9.4.1
gradle run                             # 调试运行
gradle createDistributable             # 构建可分发目录
```

## 数据

本地元数据（连接档案 / 文件夹 / SQL 历史）位于平台数据目录下的 `app.db`：

- Linux: `~/.local/share/db-k/app.db`
- 开发调试可用 `debugHome` 重定向：在主数据目录放 `app-settings.properties`，内容 `debugHome=/path/to/sandbox`。

## 目录结构

```
src/main/kotlin/
├── app/     # Compose UI + 状态（状态即 ViewModel：TreeState/DialogState/…）
│   ├── core/    # Main.kt 窗口与组装
│   ├── state/   # 各状态类（含业务动作）
│   ├── dialog/  # 连接/文件夹编辑弹窗
│   ├── ui/      # 主题、右侧面板、自绘矢量图标
│   └── settings/# 树展开等持久化
├── db/      # 应用自身元数据：SQLite 迁移 + Repository
├── tree/    # 左侧树：扁平化行模型 + 侧栏组件
└── jdbc/    # （M2 起）目标库方言/连接/元数据探测
```

分层纪律：`db/` `tree/` 不依赖 compose，`app/` 单向依赖它们。

## 里程碑

- [x] M0 脚手架：空窗口 + 主题
- [x] M1 元数据+树：文件夹/连接档案 CRUD、左侧树、展开持久化、右侧数据源面板
- [ ] M2 JDBC 运行时：方言抽象 + 连接 + 懒加载 库→表/视图/触发器 树
- [ ] M3 编辑执行：SQL 编辑器 + 结果网格 + 历史
- [ ] M4 体验打磨 / M5 打包
