import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

group = "dev.dbk"
version = "0.1.0"

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

compose.desktop {
    application {
        // WM_CLASS 由主类名决定（app.core.DbkMainKt → app-core-DbkMainKt），
        // 刻意不与兄弟项目共用 app.core.MainKt，避免 X11 面板把两个应用归并成一组。
        mainClass = "app.core.DbkMainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb)
            packageName = "db-k"
            packageVersion = "0.1.0"
            // 应用图标：母版 icon/db-k.svg（1024，纯几何无字体）；
            // Linux/Deb 用 PNG；icon/db-k.ico（16..256 多尺寸）留给将来 Windows 目标
            linux {
                iconFile.set(project.file("icon/db-k-512.png"))
            }
            // JDBC 驱动需要这些模块（jlink 默认运行时未包含）
            modules("java.sql", "java.naming", "java.management")
        }
    }
}
