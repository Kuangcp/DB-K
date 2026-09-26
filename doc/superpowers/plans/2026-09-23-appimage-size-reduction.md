# AppImage 体积瘦身 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `db-k-1.0.3-x86_64.AppImage` 从 116MB 降到 ~100MB 以下，**不改任何功能**（纯打包层：去符号 + sqlite 按平台裁剪 + 更高压缩）。

**Architecture:** 全部改动落在 `build.gradle.kts` 的自定义 `makeAppImage` 任务里——它已经把 `createDistributable` 的 app-image `cp -a` 进 `AppDir` 再调 appimagetool。我们在 `cp -a` 之后、appimagetool 之前，对 **AppDir 副本**做两步后处理（对 `.so` 跑 `strip --strip-unneeded`；把 sqlite-jdbc jar 里非当前平台的原生库删掉），并给 appimagetool 加 `--comp xz`。只动 AppDir 副本，原 app-image（免安装目录）与 Deb 不受影响。

**Tech Stack:** Gradle Kotlin DSL（`build.gradle.kts`）、`strip`（binutils）、`zip/unzip`、appimagetool。JDK 25 JBR / Gradle 9.4.1（无 wrapper）。

**Spec:** 无独立 spec；本计划源自 size 调研（见 `doc/release` 发布前的调研结论：app-image 解包 211MB，runtime 103MB + lib/app 106MB）。

## Global Constraints

- 工具链固定：`JAVA_HOME=/home/zk/.sdkman/candidates/java/25.0.3-jbr`，Gradle `/home/zk/.sdkman/candidates/gradle/9.4.1/bin`（不引入 wrapper）。
- `allWarningsAsErrors`：改动 `build.gradle.kts` 不涉及 Kotlin 编译告警，但不要引入未使用变量。
- **不改功能**：POI（Excel 导出）、全部内置 JDBC 驱动、JBR 模块、fonts 一律保留；只动 AppImage 的字节形态（去符号/裁剪/压缩）。
- glibc ≥ 2.28 下限不变（strip/裁剪/压缩都不改变 ELF 的 GLIBC 依赖）。
- 只影响 `makeAppImage` 产物；`createDistributable`（免安装目录）与 `packageDeb` 不在本批范围内。
- **禁止 GUI 自动化验证**（AGENTS）：运行产物用「无窗口」检查（`java -version`、`ldd -r`、单文件源码跑 SQLite 驱动），完整 GUI 由用户人工跑一次 AppImage。

## Review Focus

按本批改动、测试覆盖不到、最可能坑用户的点（每行在对应 Task 有落点）：

1. **stripped `.so` 仍能加载**：`libjvm.so` / `libskiko-linux-x64.so` 去符号后不能被 `dlopen` 失败。
   归 Task 1（`java -version` + `ldd -r`）。
2. **裁剪后的 sqlite-jdbc 仍能加载本地库**：`org/sqlite/native/Linux/x86_64/libsqlitejdbc.so` 必须保留且可被驱动提取。
   归 Task 2（`unzip -l` + 单文件源码加载驱动）。
3. **xz 压缩产物仍可运行**：`--comp xz` 后 AppImage 正常启动（人工）。
   归 Task 3。
4. **体积确实下降**：每步记录 AppImage 大小，最终 < 100MB。
   归 Task 1-3 的测量步骤。
5. **不误删 sqlite 平台库**：`trimSqliteJdbc` 只删 `org/sqlite/native/**` 下非目标前缀，类文件一个不动。
   归 Task 2 的 `unzip -l` 断言。

---

### Task 1: 原生库去符号（`strip --strip-unneeded`）

**Files:**
- Modify: `build.gradle.kts`

**Interfaces:**
- Produces: `fun stripNativeLibs(root: File)`（顶层函数，`makeAppImage` 调用）。

- [ ] **Step 1: 加 `stripNativeLibs` 助手**

在 `build.gradle.kts` 的 `execChecked` 函数之后加入：

```kotlin
/** 对 [root] 下所有 .so 去未使用符号（保留 .dynsym 动态符号，不影响 dlopen/加载）。 */
fun stripNativeLibs(root: File) {
    val strip = File("/usr/bin/strip")
    if (!strip.exists()) {
        logger.warn("strip 不可用，跳过原生库瘦身")
        return
    }
    root.walkTopDown()
        .filter { it.isFile && it.extension == "so" }
        .forEach { f -> execChecked(strip.absolutePath, "--strip-unneeded", f.absolutePath) }
}
```

- [ ] **Step 2: 在 `makeAppImage` 里 `cp -a` 之后调用**

找到：

```kotlin
        execChecked("cp", "-a", "$appImage/.", "${appDir.resolve("usr")}/")
```

在其后加：

```kotlin
        // 瘦身 1/2：去 .so 未使用符号（只作用于 AppDir 副本，不改原 app-image）
        stripNativeLibs(appDir.resolve("usr/lib"))
```

- [ ] **Step 3: 跑一次，记录体积**

```bash
export JAVA_HOME=/home/zk/.sdkman/candidates/java/25.0.3-jbr
export PATH="$JAVA_HOME/bin:/home/zk/.sdkman/candidates/gradle/9.4.1/bin:$PATH"
gradle makeAppImage 2>&1 | tail -5
ls -lh build/compose/binaries/main/appimage/db-k-*.AppImage
```

Expected：任务成功；AppImage 变小几 MB（去符号在 squashfs 内二次压缩后收益有限，先记录基线即可）。

- [ ] **Step 4: 验证 stripped `.so` 仍可用（无窗口）**

```bash
APPDIR=build/appimage/db-k.AppDir           # 已 strip 的副本
# 1) 去符号后的 JVM 能起
"$APPDIR/usr/lib/runtime/bin/java" -version
# 2) 关键 .so 无未解析重定位（-r 检查 relocation）
ldd -r "$APPDIR/usr/lib/runtime/lib/server/libjvm.so" 2>&1 | grep -i "undefined\|not found" || echo "libjvm OK"
ldd -r "$APPDIR/usr/lib/app/libskiko-linux-x64.so" 2>&1 | grep -i "undefined\|not found" || echo "skiko OK"
```

Expected：`java -version` 正常打印版本；两个 `ldd -r` 无 `undefined symbol`。

- [ ] **Step 5: 提交**

```bash
git add build.gradle.kts
git commit -m "build(appimage): 打包前去 .so 未使用符号"
```

---

### Task 2: sqlite-jdbc 只保留当前平台原生库

**Files:**
- Modify: `build.gradle.kts`

**Interfaces:**
- Consumes: `execChecked`（Task 1 同文件）。
- Produces: `fun findSqliteJdbc(appDir: File): File?`、`fun trimSqliteJdbc(jar: File, keep: List<String>)`。

- [ ] **Step 1: 加助手函数**

```kotlin
/** 在 app-image 的 lib/app 下找 sqlite-jdbc jar。 */
fun findSqliteJdbc(appDir: File): File? =
    appDir.resolve("usr/lib/app").listFiles()?.firstOrNull { it.name.startsWith("sqlite-jdbc-") }

/**
 * 重写 [jar]，只保留 [keep] 前缀的原生库（其它平台/架构的 org/sqlite/native/** 删除），
 * 类文件原样保留。xerial 的驱动在运行时把 native 提取到临时目录加载，因此压缩方式可重排。
 */
fun trimSqliteJdbc(jar: File, keep: List<String>) {
    val tmp = File(jar.parentFile, jar.name + ".tmp")
    java.util.zip.ZipFile(jar).use { zin ->
        java.util.zip.ZipOutputStream(tmp.outputStream()).use { zout ->
            zin.entries().asSequence().forEach { e ->
                val drop = e.name.startsWith("org/sqlite/native/") &&
                    keep.none { e.name.startsWith(it) }
                if (drop) return@forEach
                zout.putNextEntry(java.util.zip.ZipEntry(e.name))
                zin.getInputStream(e).copyTo(zout)
                zout.closeEntry()
            }
        }
    }
    check(jar.delete() && tmp.renameTo(jar)) { "替换 sqlite-jdbc jar 失败" }
}
```

- [ ] **Step 2: 在 `makeAppImage` 里调用（Task 1 的 strip 之后）**

```kotlin
        // 瘦身 2/2：sqlite-jdbc 只留 Linux/x86_64 原生库（x86_64 AppImage 用）
        findSqliteJdbc(appDir)?.let {
            trimSqliteJdbc(it, listOf("org/sqlite/native/Linux/x86_64/"))
        }
```

- [ ] **Step 3: 跑一次并验证 jar 结构**

```bash
export JAVA_HOME=/home/zk/.sdkman/candidates/java/25.0.3-jbr
export PATH="$JAVA_HOME/bin:/home/zk/.sdkman/candidates/gradle/9.4.1/bin:$PATH"
gradle makeAppImage 2>&1 | tail -3
J=$(ls build/appimage/db-k.AppDir/usr/lib/app/sqlite-jdbc-*.jar)
unzip -l "$J" | grep "org/sqlite/native/" 
ls -lh "$J"
```

Expected：`org/sqlite/native/` 下只剩 `Linux/x86_64/libsqlitejdbc.so`；jar 从 ~14MB 降到 ~2MB。

- [ ] **Step 4: 验证裁剪后的 jar 能加载 SQLite（无窗口，单文件源码 + 打包内 JVM）**

```bash
cat > /tmp/CheckSqlite.java <<'EOF'
import java.sql.*;
public class CheckSqlite {
    public static void main(String[] a) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            System.out.println("OK: sqlite " + c.getMetaData().getDatabaseProductVersion());
        }
    }
}
EOF
J=$(ls build/appimage/db-k.AppDir/usr/lib/app/sqlite-jdbc-*.jar)
build/appimage/db-k.AppDir/usr/lib/runtime/bin/java -cp "$J" /tmp/CheckSqlite.java
```

Expected：打印 `OK: sqlite …`（说明类 + Linux/x86_64 本地库都能加载）。

- [ ] **Step 5: 提交**

```bash
git add build.gradle.kts
git commit -m "build(appimage): sqlite-jdbc 只保留 Linux/x86_64 原生库"
```

---

### Task 3: AppImage 用 xz 压缩（可配置）

**Files:**
- Modify: `build.gradle.kts`

**Interfaces:**
- Produces: 读 `-Pappimage.comp=<gzip|xz|zstd|lz4|lzo>`（默认 `xz`）。

- [ ] **Step 1: 确认 appimagetool 支持 `--comp`**

```bash
TOOL=tools/appimagetool-x86_64.AppImage   # 若用 -Pappimagetool=<path> 指定，则替换为此路径
"$TOOL" --help 2>&1 | grep -iE "comp|xz|zstd"
```

Expected：帮助里有 `--comp`（或 `-comp`）。若无，改用 `--mksquashfs-opt` 传 `-comp xz`（见 Step 2 注释）。

- [ ] **Step 2: 读属性并传参**

在 `makeAppImage` 任务体加（靠近 `val tool = resolveAppimagetool()`）：

```kotlin
        val comp = (project.findProperty("appimage.comp") as String?)?.takeIf { it.isNotBlank() } ?: "xz"
```

把 `execChecked` 改为：

```kotlin
        execChecked(
            tool.absolutePath, "--comp", comp, appDir.absolutePath, out.absolutePath,
            extraEnv = mapOf("ARCH" to "x86_64", "APPIMAGE_EXTRACT_AND_RUN" to "1"),
        )
```

（若 Step 1 发现无 `--comp`，改为 `tool.absolutePath, "--mksquashfs-opt=-comp", "--mksquashfs-opt=$comp", appDir.absolutePath, out.absolutePath`。）

- [ ] **Step 3: 构建并量体积**

```bash
export JAVA_HOME=/home/zk/.sdkman/candidates/java/25.0.3-jbr
export PATH="$JAVA_HOME/bin:/home/zk/.sdkman/candidates/gradle/9.4.1/bin:$PATH"
gradle makeAppImage 2>&1 | tail -3
ls -lh build/compose/binaries/main/appimage/db-k-*.AppImage
```

Expected：任务成功；AppImage < 100MB（目标）。若 xz 太慢，可 `gradle makeAppImage -Pappimage.comp=zstd` 对比。

- [ ] **Step 4: 人工运行一次 AppImage**

```bash
chmod +x build/compose/binaries/main/appimage/db-k-*.AppImage
./build/compose/binaries/main/appimage/db-k-*.AppImage
```

Expected：正常启动、打开 SQLite/其它数据源、编辑执行无异常（交给用户执行；xz 只影响解压启动速度）。

- [ ] **Step 5: 提交**

```bash
git add build.gradle.kts
git commit -m "build(appimage): 默认 xz 压缩（-Pappimage.comp 可覆盖）"
```

---

### Task 4（可选，本批最后做）：JNA 换 JDK FFM，删 jna 依赖

**Files:**
- Modify: `src/main/kotlin/app/core/NativeMemory.kt`
- Modify: `build.gradle.kts`（删 `jna`、`jna-platform`）
- Test: `src/test/kotlin/app/core/NativeMemoryTest.kt`

**Interfaces:**
- Consumes: 无（独立）。
- Produces: `NativeMemory` 行为不变（`configure()/trim()/rssKb()`）。

- [ ] **Step 1: 写测试（Linux 上冒烟：不抛异常、RSS 合法）**

```kotlin
package app.core

import kotlin.test.Test
import kotlin.test.assertTrue

class NativeMemoryTest {
    @Test
    fun `configure and trim do not throw and rss is sane`() {
        NativeMemory.configure()
        NativeMemory.trim("test")
        val rss = NativeMemory.rssKb()
        assertTrue(rss == -1L || rss >= 0L)
    }
}
```

- [ ] **Step 2: 跑测试确认当前通过（JNA 版基线）**

```bash
export JAVA_HOME=/home/zk/.sdkman/candidates/java/25.0.3-jbr
export PATH="$JAVA_HOME/bin:/home/zk/.sdkman/candidates/gradle/9.4.1/bin:$PATH"
gradle test --tests "app.core.NativeMemoryTest"
```

Expected：PASS（Linux 上 `configure/trim` 走 glibc 分支）。

- [ ] **Step 3: 用 FFM 重写 `NativeMemory` 的 libc 绑定**

`NativeMemory.kt` 里删掉 `com.sun.jna.*` import 与 `LibC` 接口，改为：

```kotlin
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

private val linker = Linker.nativeLinker()
private val lookup = Linker.defaultLookup()

private val mallocTrim: MethodHandle? = runCatching {
    linker.downcallHandle(
        lookup.find("malloc_trim").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
    )
}.getOrNull()

private val mallopt: MethodHandle? = runCatching {
    linker.downcallHandle(
        lookup.find("mallopt").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
    )
}.getOrNull()
```

`configure()` 里把 `c.mallopt(...)` 换成 `mallopt?.invoke(param, value) as? Int`；`trim()` 里 `libc?.malloc_trim(0) == 1` 换成 `mallocTrim?.invoke(0) as? Int == 1`。`isLinux` 判断保留。

- [ ] **Step 4: 跑测试确认仍通过**

```bash
gradle test --tests "app.core.NativeMemoryTest"
gradle test
```

Expected：全绿。

- [ ] **Step 5: 删 JNA 依赖并编译**

`build.gradle.kts` 删除：

```kotlin
implementation("net.java.dev.jna:jna:5.13.0")
implementation("net.java.dev.jna:jna-platform:5.13.0")   // 若仅 NativeMemory 用
```

```bash
gradle compileKotlin
gradle smokeJdbc
```

Expected：编译无警告错误；smoke 通过。

- [ ] **Step 6: 提交**

```bash
git add src/main/kotlin/app/core/NativeMemory.kt src/test/kotlin/app/core/NativeMemoryTest.kt build.gradle.kts
git commit -m "build: NativeMemory 用 JDK FFM 替代 JNA，移除 jna 依赖"
```

---

## Self-Review

**1. 覆盖**
- 去符号：Task 1；sqlite 裁剪：Task 2；xz 压缩：Task 3；JNA→FFM：Task 4（可选）。四项对应调研方案 ①②③⑤。
- 不在本批：POI/驱动外部化（⑥⑦，产品取舍）、删 JBR fonts（⑧，风险中）、Deb/免安装目录（后续）。

**2. Placeholder scan**：无 TBD；代码步骤均完整可粘贴。

**3. Type consistency**：`stripNativeLibs`/`findSqliteJdbc`/`trimSqliteJdbc` 在定义与调用处命名一致；`--comp` 与 `-Pappimage.comp` 一致。

**4. Review Focus 落点**：5 项分别落在 Task 1 Step 4、Task 2 Step 3/4、Task 3 Step 4、各测量步骤、Task 2 Step 3。
