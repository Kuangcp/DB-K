pluginManagement {
    repositories {
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        google()
        gradlePluginPortal()
        mavenCentral()
    }

    val kotlinVersion = providers.gradleProperty("kotlin.version").get()
    val composeVersion = providers.gradleProperty("compose.version").get()

    plugins {
        kotlin("jvm").version(kotlinVersion)
        kotlin("plugin.serialization").version(kotlinVersion)
        id("org.jetbrains.kotlin.plugin.compose").version(kotlinVersion)
        id("org.jetbrains.compose").version(composeVersion)
    }
}

rootProject.name = "db-k"
