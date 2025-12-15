import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm") version "2.2.0"
    id("com.gradleup.shadow") version "9.1.0"
}

val pluginVersion: String by project.ext
val apiVersion: String by project.ext

java.toolchain.languageVersion.set(JavaLanguageVersion.of(21))

repositories {
    mavenCentral()
    maven(url = "https://oss.sonatype.org/content/groups/public/")
    maven(url = "https://repo.papermc.io/repository/maven-public/")
    maven(url = "https://libraries.minecraft.net")
    maven(url = "https://jitpack.io")
    maven {
        url = uri("https://maven.pkg.github.com/tororo1066/TororoPluginAPI")
        credentials {
            username = System.getenv("GITHUB_USERNAME")
            password = System.getenv("GITHUB_TOKEN")
        }
    }

    maven(url = "https://repo1.maven.org/maven2")
}

fun ModuleDependency.excludeKotlinStdlib(): ModuleDependency {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    return this
}

dependencies {
    compileOnly(kotlin("stdlib"))
    compileOnly("io.papermc.paper:paper-api:$pluginVersion-R0.1-SNAPSHOT")
    compileOnly("tororo1066:commandapi:$apiVersion")
    compileOnly("tororo1066:base:$apiVersion")
    implementation("tororo1066:tororopluginapi:$apiVersion")
    compileOnly("com.mojang:brigadier:1.0.18")

    implementation("org.mongodb:mongodb-driver-sync:4.11.1")
    implementation("io.lettuce:lettuce-core:6.8.1.RELEASE")

    compileOnly("com.elmakers.mine.bukkit:MagicAPI:10.2")
    compileOnly(fileTree("libs"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2") {
        excludeKotlinStdlib()
    }
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.10.2") {
        excludeKotlinStdlib()
    }
    implementation("com.github.shynixn.mccoroutine:mccoroutine-bukkit-api:2.22.0") {
        excludeKotlinStdlib()
    }
    implementation("com.github.shynixn.mccoroutine:mccoroutine-bukkit-core:2.22.0") {
        excludeKotlinStdlib()
    }

    implementation("io.opentelemetry:opentelemetry-api:1.57.0") {
        excludeKotlinStdlib()
    }
    implementation("io.opentelemetry:opentelemetry-sdk:1.57.0") {
        excludeKotlinStdlib()
    }
    implementation("io.opentelemetry:opentelemetry-exporter-otlp:1.57.0") {
        excludeKotlinStdlib()
    }
    implementation("io.opentelemetry:opentelemetry-exporter-sender-jdk:1.57.0") {
        excludeKotlinStdlib()
    }
}

tasks.withType<ShadowJar> {
    relocate("com.mongodb", "tororo1066.libs.com.mongodb")
    relocate("org.bson", "tororo1066.libs.org.bson")
    relocate("io.lettuce", "tororo1066.libs.io.lettuce")
    relocate("io.netty", "tororo1066.libs.io.netty")
    relocate("org.reactivestreams", "tororo1066.libs.org.reactivestreams")
    relocate("org.jetbrains.kotlinx.coroutines", "tororo1066.libs.org.jetbrains.kotlinx.coroutines")
    archiveClassifier.set("")
}

tasks.named("build") {
    dependsOn("shadowJar")
}