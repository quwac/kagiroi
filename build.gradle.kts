import org.jetbrains.kotlin.gradle.targets.js.npm.NpmExtension
import org.jetbrains.kotlin.gradle.targets.wasm.npm.WasmNpmExtension

buildscript {
    dependencies {
        classpath("com.google.guava:guava:33.7.2-jre")
        classpath("org.bouncycastle:bcpkix-jdk18on:1.86")
        classpath("org.bouncycastle:bcprov-jdk18on:1.86")
        classpath("io.opentelemetry:opentelemetry-api:1.67.0")
        classpath("org.bitbucket.b_c:jose4j:0.9.7")
        classpath("org.jdom:jdom2:2.0.6.1")
        classpath("org.apache.commons:commons-lang3:3.21.0")
    }
}

NpmExtension[rootProject].apply {
    override("@azure/msal-node", "7.1.0")
    override("diff", "8.0.3")
    override("serialize-javascript", "7.1.4")
    override("undici", "7.30.1")
}

gradle.projectsEvaluated {
    WasmNpmExtension[rootProject].apply {
        override("@azure/msal-node", "7.1.0")
        override("diff", "8.0.3")
    }
}

plugins {
    kotlin("jvm") version "2.4.21" apply false
    kotlin("multiplatform") version "2.4.21" apply false
    kotlin("plugin.serialization") version "2.4.21" apply false
    id("com.android.kotlin.multiplatform.library") version "9.5.0" apply false
    id("dev.detekt") version "2.0.0-alpha.6"
}

allprojects {
    group = "dev.quwac.kagiroi"
    version = "0.0.1-SNAPSHOT"
}

dependencies {
    detektPlugins("dev.detekt:detekt-rules-ktlint-wrapper:2.0.0-alpha.6")
}

detekt {
    toolVersion = "2.0.0-alpha.6"
    source.setFrom(
        files("build.gradle.kts", "settings.gradle.kts"),
        subprojects.filter { it.path != ":compile-tests" }.map {
            files(it.layout.projectDirectory.dir("src"), it.buildFile)
        },
    )
    config.setFrom(layout.projectDirectory.file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    basePath.set(rootDir)
}

tasks.named<dev.detekt.gradle.Detekt>("detekt") {
    include("**/*.kt", "**/*.kts")
}
