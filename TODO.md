# TODO（未完成 / 待办事项清单）

> 零散即时事项。核心分阶段发展计划见 `Roadmap.md`；设计依据见 `doc/DESIGN.md`。
> 完成一项即删除对应条目（不再保留"已完成"占位）。

**编辑区域**

- 自动补全：关键字 + 当前连接的表/列名补全（候选来自连接元数据缓存），Enter/Tab 上屏、Esc 关闭；补全弹层样式跟随主题
- 结果 Ctrl+T 行列转制（方便看宽行数据）
- 编辑器增强（可选）：行号、当前行高亮

**执行与结果区**

- 单元格复制：点击/右键复制单元格值（NULL 显示灰色 "(NULL)"，复制为空串要有明确规则）
- 复制行 → INSERT 语句（右键菜单）

**数据源支持**

- SQL Server / Oracle：需要外部驱动加载机制（Oracle 驱动有 license 不能进内置 classpath）→ `<dataDir>/drivers` 目录放 jar，启动时用独立 classloader 注册进 DriverManager，再加方言（Generic 兜底先跑通，再按需覆写）
- （远期）SSH 隧道连内网库

**树区域**

- DataGrip 式多类型分组：现在每个 schema 下只有 表/视图/触发器 三组（ObjectGroupKind），要扩成按类型细分目录 + 组内计数，PG 示例结构：

```
* 📁 gptdb
* 📁 public
   * 📂 tables 186
      * 📂 materialized views 4
      * 📂 views 5
      * 📂 routines 1330
      * 📂 aggregates 33
      * 📂 operators 107
      * 📂 sequences 81
      * 📂 object types 21
      * 📂 operator classes 37
      * 📂 operator families 37
   * 📁 Database Objects
   * 📂 access methods 9
      * 📂 casts 262
      * 📂 extensions 3
      * 📂 languages 4
   * 📁 virtual views 1
   * 📁 Server Objects
   * 📂 roles 56
      * 📂 tablespaces 2
```

**连接与安全**

- app.db 里密码明文存储（ConnectionsRepository 直存）→ 本机级加密（AES-GCM，密钥文件放 dataDir 且 chmod 600），含存量行迁移；明文只在打开编辑框时解密回显
- 连接档案导出/导入（JSON 一份，便于换机迁移；密码字段按导出选项决定带不带）

**历史与日志**

- 应用内"打开 logs 目录"入口（现在只能手动 find）

**清理与技术债**

- `app/ui/RightPane.kt` 全工程无引用（M1 首页列表遗留，现被树+SqlWorkspace 取代）→ 确认后整体删除
- TODO 里"支持 CK 数据库"已完成，勿再重复排期（含反代链路的 compress=0 兼容处理）

**工程与发布**

- 打包验证：nativeDistributions（Deb）+ jlink modules（java.sql 等）配置已就绪但从未产出安装包 → 首次构建 Deb + 干净环境安装自检
- 单测起步：jdbc/tree 层纯逻辑（urlPreview、quoteIdent、isSystemSchemaName、树行派生）加 kotlin.test；现在只有 smokeJdbc 一个程序化冒烟入口
