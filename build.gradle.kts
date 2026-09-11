import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

group = "dev.dbk"
version = "1.0.0"
val appVersion = project.version.toString()

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.components:components-resources:${property("compose.version")}")
    implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
    // 应用自身元数据存储（连接档案/文件夹/SQL 历史），与目标库无关
    implementation("org.xerial:sqlite-jdbc:3.47.2.0")
    // 目标库 JDBC 驱动（轻量客户端直接放 classpath；新增库只需在下方加一行 + 实现方言）
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.mysql:mysql-connector-j:9.1.0")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.1")
    implementation("com.h2database:h2:2.3.232")
    // ClickHouse（jdbc:clickhouse://host:8123/db，HTTP 协议，默认端口 8123）
    implementation("com.clickhouse:clickhouse-jdbc:0.7.2")
    // SQL 编辑器语法高亮（api-x 同源：NeoUtils Highlight Compose）
    implementation("com.neoutils.highlight:highlight-compose:2.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.tinylog:tinylog-api:2.7.0")
    // impl 含 writers 抽象类，SessionLogWriter 编译需在 compileClasspath
    implementation("org.tinylog:tinylog-impl:2.7.0")

    // 单元测试：kotlin.test（JUnit 5 平台） + JUnit Jupiter（含 engine，@TempDir 等）
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // 协程虚拟时间测试（app/state 防抖/执行确定性推进）
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

// JDBC 方言/元数据探测自检：gradle smokeJdbc （无需启动 UI）
tasks.register<JavaExec>("smokeJdbc") {
    group = "verification"
    description = "Verify JDBC dialects against embedded/temp databases"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("jdbc.JdbcSmokeKt")
}

// 生成版本信息（含 git commit hash），设置窗口左下角展示（与 api-x 同源）
val generatedVersionDir = layout.buildDirectory.dir("generated/version/kotlin")

val generateVersion by tasks.registering {
    val outputDir = generatedVersionDir.get()
    outputs.cacheIf { true }
    doLast {
        outputDir.asFile.mkdirs()
        val hash = try {
            val proc = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                .directory(project.rootDir)
                .start()
            proc.inputStream.bufferedReader().readText().trim()
        } catch (_: Exception) {
            "unknown"
        }

        val file = outputDir.file("app/build/Version.kt").asFile
        file.parentFile.mkdirs()
        val content = """
            |package app.build
            |
            |object Version {
            |    const val COMMIT = "$hash"
            |    const val NAME = "${project.version}"
            |}
        """.trimMargin()
        if (!file.exists() || file.readText() != content) {
            file.writeText(content)
        }
    }
}

kotlin.sourceSets.main {
    kotlin.srcDir(generatedVersionDir)
}

tasks.matching { it.name == "compileKotlin" }.configureEach {
    dependsOn(generateVersion)
}

compose.desktop {
    application {
        // WM_CLASS 由主类名决定（app.core.DbkMainKt → app-core-DbkMainKt），
        // 刻意不与兄弟项目共用 app.core.MainKt，避免 X11 面板把两个应用归并成一组。
        mainClass = "app.core.DbkMainKt"

        nativeDistributions {
            // Deb 在 Linux 构建；Msi 只能在 Windows 构建（jpackage 不支持交叉打包）
            targetFormats(TargetFormat.Deb, TargetFormat.Msi)
            packageName = "db-k"
            packageVersion = appVersion
            // JDBC 驱动需要这些模块（jlink 默认运行时未包含）
            modules("java.sql", "java.naming", "java.management")
            // 应用图标：母版 icon/db-k.svg（1024，纯几何无字体）
            linux {
                iconFile.set(project.file("icon/db-k-512.png"))
            }
            windows {
                // 永久固定：Windows 安装器据此识别「同一应用的升级」，改动会导致升级失败
                upgradeUuid = "ab0abd13-bf35-4b79-8898-fbb9cfdc0f82"
                msiPackageVersion = appVersion
                menu = true
                menuGroup = "DB-K"
                shortcut = true
                dirChooser = true
                perUserInstall = false
                iconFile.set(project.file("icon/db-k.ico"))
            }
        }
    }
}

// ---------- AppImage（Linux 单文件） ----------
// Compose 的 TargetFormat.AppImage 只是 jpackage 的 app-image 目录（等同 createDistributable），
// 不是单文件 .AppImage。这里把 createDistributable 产物套成 AppDir，再用 appimagetool 打成单文件。
// 工具：下载 appimagetool-x86_64.AppImage 放 tools/（已 gitignore），
//      或用 -Pappimagetool=<path> / 环境变量 APPIMAGETOOL 指定。
val appimagetoolProp: String? = (project.findProperty("appimagetool")?.toString()
    ?: System.getenv("APPIMAGETOOL"))?.takeIf { it.isNotBlank() }

fun resolveAppimagetool(): File {
    appimagetoolProp?.let {
        val f = File(it)
        check(f.exists()) { "appimagetool 不存在：$it" }
        return f
    }
    project.file("tools/appimagetool-x86_64.AppImage").takeIf { it.exists() }?.let { return it }
    System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .map { File(it, "appimagetool") }
        .firstOrNull { it.canExecute() }
        ?.let { return it }
    error(
        "未找到 appimagetool。请下载 appimagetool-x86_64.AppImage 放到 tools/，" +
            "或用 -Pappimagetool=<path>（或环境变量 APPIMAGETOOL）指定。",
    )
}

/** 执行外部命令，非 0 退出即失败；返回合并后的 stdout/stderr。 */
fun execChecked(vararg cmd: String, extraEnv: Map<String, String> = emptyMap()): String {
    val proc = ProcessBuilder(*cmd).redirectErrorStream(true).apply {
        environment().putAll(extraEnv)
    }.start()
    val output = proc.inputStream.bufferedReader().readText()
    val code = proc.waitFor()
    check(code == 0) { "命令失败（exit $code）：${cmd.joinToString(" ")}\n$output" }
    return output
}

val makeAppImage by tasks.registering {
    group = "distribution"
    description = "把 createDistributable 的 app-image 打成单文件 .AppImage（仅 Linux）"
    dependsOn("createDistributable")
    doLast {
        if (!System.getProperty("os.name").lowercase().contains("linux")) {
            logger.lifecycle("makeAppImage 仅支持 Linux，当前为 ${System.getProperty("os.name")}，跳过")
            return@doLast
        }
        val appImage = layout.buildDirectory.dir("compose/binaries/main/app/db-k").get().asFile
        check(appImage.isDirectory) { "未找到 app-image：$appImage，请先运行 createDistributable" }

        val appDir = layout.buildDirectory.dir("appimage/db-k.AppDir").get().asFile
        val outDir = layout.buildDirectory.dir("compose/binaries/main/appimage").get().asFile
        appDir.deleteRecursively()
        appDir.resolve("usr").mkdirs()

        // cp -a 保留可执行位/软链接（Kotlin/Gradle 的 copy 不保证 POSIX 权限）
        execChecked("cp", "-a", "$appImage/.", "${appDir.resolve("usr")}/")
        project.file("icon/db-k-512.png").copyTo(appDir.resolve("db-k.png"), overwrite = true)
        appDir.resolve("db-k.desktop").writeText(
            """
            [Desktop Entry]
            Type=Application
            Name=DB-K
            Comment=Database client
            Exec=db-k
            Icon=db-k
            Terminal=false
            Categories=Development;Database;
            """.trimIndent() + "\n",
        )
        val appRun = appDir.resolve("AppRun")
        appRun.writeText(
            "#!/bin/sh\nHERE=\"\$(dirname \"\$(readlink -f \"\$0\")\")\"\nexec \"\$HERE/usr/bin/db-k\" \"\$@\"\n",
        )
        appRun.setExecutable(true)

        outDir.mkdirs()
        val out = outDir.resolve("db-k-$appVersion-x86_64.AppImage")
        val tool = resolveAppimagetool()
        logger.lifecycle("appimagetool: ${tool.absolutePath}")
        execChecked(
            tool.absolutePath, appDir.absolutePath, out.absolutePath,
            extraEnv = mapOf("ARCH" to "x86_64", "APPIMAGE_EXTRACT_AND_RUN" to "1"),
        )
        check(out.exists()) { "appimagetool 未生成输出：$out" }
        logger.lifecycle("AppImage: ${out.absolutePath}")
    }
}
