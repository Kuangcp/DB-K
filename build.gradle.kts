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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.tinylog:tinylog-api:2.7.0")
    runtimeOnly("org.tinylog:tinylog-impl:2.7.0")
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
        mainClass = "app.core.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb)
            packageName = "db-k"
            packageVersion = "0.1.0"
            // JDBC 驱动需要这些模块（jlink 默认运行时未包含）
            modules("java.sql", "java.naming", "java.management")
        }
    }
}
