plugins {
    id("org.jetbrains.intellij.platform") version "2.2.0"
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
}

repositories {
    maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaUltimate("2024.3.3")
        bundledPlugin("com.intellij.java")
        plugin("com.intellij.mcpServer", "1.0.30")
    }
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}

intellijPlatform {
    pluginConfiguration {
        id = "com.codenavigatormcp"
        name = "Code Navigator MCP"
        version = providers.gradleProperty("pluginVersion").get()
        description = """
            Exposes Java code navigation tools (go to definition, find references,
            call hierarchy, method body retrieval) via MCP protocol for AI coding assistants.
        """.trimIndent()
        vendor {
            name = "Developer"
        }
        ideaVersion {
            sinceBuild = "243"
            untilBuild = "253.*"
        }
    }
}

kotlin {
    jvmToolchain(21)
}
