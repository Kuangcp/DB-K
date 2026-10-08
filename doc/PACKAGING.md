# 打包与分发（Deb / AppImage / MSI）

> 工具链见 `AGENTS.md`：JDK `25.0.3-jbr`、Gradle `9.4.1`（项目**不引入 wrapper**，各平台用系统 gradle）。
> `build.gradle.kts` 里 `version` 是 Deb / AppImage / MSI / 设置窗口版本号（`app/build/Version.kt`）的统一来源：
> 本地默认写死（如 `1.0.4`），CI 发布时由环境变量 `DBK_VERSION`（= tag 去掉 `v` 前缀）覆盖，保证与 tag 一致。

## 目标格式与构建机

| 目标 | 构建机 | 命令 | 依赖 |
|---|---|---|---|
| app-image（免安装目录，当前自用方式） | 任意 | `gradle createDistributable` | 无 |
| Deb | Linux | `gradle packageDeb` | jpackage；部分发行版需 `dpkg`/`fakeroot` |
| **AppImage**（单文件） | Linux | `gradle makeAppImage` | `appimagetool`（+ FUSE2 或 `APPIMAGE_EXTRACT_AND_RUN=1`） |
| **EXE** | **仅 Windows** | `gradle packageExe` | WiX Toolset 3.x |

**交叉打包不支持**：jpackage 不能在 Linux 上产出 EXE，反之亦然。所以 EXE 必须在 Windows 上构建。

---

## 自动发布（GitHub Actions）

`.github/workflows/release.yml`：推送 `v*` 标签即自动在 `ubuntu-latest`（Deb + AppImage）与
`windows-latest`（MSI）上打包，产物挂到该 tag 的 GitHub Release。

```bash
git tag v1.0.4
git push hub v1.0.4        # hub = github.com:Kuangcp/DB-K.git
```

- 版本号由 `DBK_VERSION` 注入（见文首），**tag 必须是 `vX.Y.Z`**：jpackage 的 `--app-version`
  不接受 `v1.0.4-rc1` 这类带后缀的形式。
- Release 正文优先取 `doc/release/RELEASE_<ver>.md`（存在时），否则由 GitHub 按 commit 自动生成。
- Windows 侧 WiX Toolset 3.x 由 workflow 探测/安装并挂进 `PATH`（jpackage 调 `candle.exe`）。
- 手动 `workflow_dispatch` 只构建、不发版，便于先验证流水线。
- 测试 / smoke 自检**未**纳入流水线门禁；需要时取消 `release.yml` 里 `单元测试` 步骤的注释。

---

## AppImage

Compose 的 `TargetFormat.AppImage` 实际只是 jpackage 的 `app-image` 目录（等同 `createDistributable`），**不是单文件 `.AppImage`**。因此 `makeAppImage` 任务的做法是：

1. `dependsOn("createDistributable")` 拿到 app-image；
2. 用 `cp -a` 把它塞进 `build/appimage/db-k.AppDir/usr/`（保留可执行位）；
3. 生成 `AppRun` / `db-k.desktop` / `db-k.png`；
4. 调 `appimagetool` 打成 `build/compose/binaries/main/appimage/db-k-<ver>-x86_64.AppImage`。

### 准备 appimagetool（二选一）

- 下载 `appimagetool-x86_64.AppImage`（GitHub: `AppImage/appimagetool` releases, continuous）放到 `tools/`（已 gitignore）；或
- `gradle makeAppImage -Pappimagetool=/path/to/appimagetool`，或设环境变量 `APPIMAGETOOL`。

任务内部会以 `ARCH=x86_64`、`APPIMAGE_EXTRACT_AND_RUN=1` 调它，免 FUSE 也能跑。

### 运行

```sh
chmod +x db-k-1.0.1-x86_64.AppImage
./db-k-1.0.1-x86_64.AppImage
```

### glibc 兼容性（本机实测结论）

AppImage 是"自包含"但**不是静态链接**：native ELF（JRE、Skiko、launcher）仍动态链接系统 `libc.so.6`。其兼容下限由**内置的预编译二进制**决定，而不是构建机的 glibc：

| 组件 | 最高需要的 GLIBC 符号 |
|---|---|
| `bin/db-k`（jpackage launcher） | `GLIBC_2.14` |
| `libskiko-linux-x64.so`（Compose） | `GLIBC_2.17` |
| `libjvm.so`（内置 JBR 25 运行时） | **`GLIBC_2.28`** |

→ 下限是 **glibc ≥ 2.28**（Debian 10+、Ubuntu 20.04+、RHEL 8+、Fedora 29+）。这些 `.so` 由 JDK/Maven 预编译，所以在 Manjaro（新 glibc）上构建并不会额外抬高这条下限。桌面依赖（X11/fontconfig 等）所有桌面发行版都有。

---

## EXE（Windows 构建）

配置在 `build.gradle.kts` 的 `nativeDistributions.windows { … }`，字段与 `api-x` 同源：

```kotlin
targetFormats(TargetFormat.Deb, TargetFormat.Exe)
windows {
    upgradeUuid = "ab0abd13-bf35-4b79-8898-fbb9cfdc0f82" // 永久固定，改动会导致无法升级
    menu = true
    menuGroup = "DB-K"
    shortcut = true
    dirChooser = true
    perUserInstall = false
    iconFile.set(project.file("icon/db-k.ico"))
}
```

### Windows 侧前置条件

1. **JDK 25**（JBR 或 Temurin，需含 MSI bundler；本机 Linux JBR 的 `jdk.jpackage` 就不含）。
2. **Gradle 9.4.1**（项目无 wrapper，用系统 gradle）。
3. **WiX Toolset 3.x**（jpackage 调 `candle.exe`/`light.exe`），装好并加入 `PATH`。Compose 的 `windows {}` DSL 未暴露 wix 目录，走 PATH 即可。
4. `gradle packageExe` → `build/compose/binaries/main/exe/db-k-1.0.3.exe`。

### 注意

- `upgradeUuid` 一旦发布就不要再改，否则新版本不会被识别为升级。
- 未做代码签名（自签证书过不了杀软，故不配置签名）。
