import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.jetbrains.kotlin.gradle.targets.js.npm.NpmExtension
import org.jetbrains.kotlin.gradle.targets.wasm.npm.WasmNpmExtension

buildscript {
    dependencies {
        classpath("com.google.guava:guava:33.8.0-jre")
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
    override("glob", "14.0.2")
    override("minimatch", "11.0.1")
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
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
    id("dev.detekt") version "2.0.0-alpha.6"
}

val publishedProjects = setOf(
    ":kagiroi-bom",
    ":kagiroi-core",
    ":kagiroi-d1-driver",
    ":kagiroi-kotlinx-datetime",
    ":kagiroi-mariadb",
    ":kagiroi-mysql",
    ":kagiroi-mysql-hyperdrive-driver",
    ":kagiroi-mysql-kotlinx-datetime",
    ":kagiroi-mysql-node-driver",
    ":kagiroi-mysql-quwac-bignumber",
    ":kagiroi-node-oracle-driver",
    ":kagiroi-oracle",
    ":kagiroi-oracle-kotlinx-datetime",
    ":kagiroi-oracle-quwac-bignumber",
    ":kagiroi-pglite-driver",
    ":kagiroi-postgres",
    ":kagiroi-postgres-hyperdrive-driver",
    ":kagiroi-postgres-kotlinx-datetime",
    ":kagiroi-postgres-node-driver",
    ":kagiroi-postgres-quwac-bignumber",
    ":kagiroi-quwac-bignumber",
    ":kagiroi-schema",
    ":kagiroi-sql",
    ":kagiroi-sqlite",
    ":kagiroi-sqlite-file-driver",
    ":kagiroi-sqlite-inmemory-driver",
    ":kagiroi-sqlite-kotlinx-datetime",
    ":kagiroi-sqlite-quwac-bignumber",
    ":kagiroi-sqlserver",
    ":kagiroi-sqlserver-kotlinx-datetime",
    ":kagiroi-sqlserver-node-driver",
    ":kagiroi-sqlserver-quwac-bignumber",
)

val connectionTargetProjects = setOf(
    ":kagiroi-d1-driver",
    ":kagiroi-mysql-hyperdrive-driver",
    ":kagiroi-mysql-node-driver",
    ":kagiroi-node-oracle-driver",
    ":kagiroi-pglite-driver",
    ":kagiroi-postgres-hyperdrive-driver",
    ":kagiroi-postgres-node-driver",
    ":kagiroi-sqlite-file-driver",
    ":kagiroi-sqlite-inmemory-driver",
    ":kagiroi-sqlserver-node-driver",
    ":integration-tests:mysql-hyperdrive",
    ":integration-tests:postgres-hyperdrive",
)

val libraryTests = tasks.register("libraryTests") {
    group = "verification"
    description = "Runs library unit tests without connection-target tests"
}

subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        if (path !in connectionTargetProjects) {
            libraryTests.configure {
                dependsOn(tasks.named("jsNodeTest"))
                dependsOn(tasks.named("wasmJsNodeTest"))
            }
        }
    }
}

allprojects {
    group = "dev.quwac.kagiroi"
    version = providers.gradleProperty("version").getOrElse("0.1.0-SNAPSHOT")
}

subprojects {
    if (path in publishedProjects) {
        apply(plugin = "com.vanniktech.maven.publish")

        extensions.configure<MavenPublishBaseExtension> {
            publishToMavenCentral()
            if (providers.gradleProperty("signingInMemoryKey").isPresent) {
                signAllPublications()
            }

            coordinates(project.group.toString(), project.name, project.version.toString())
            pom {
                name.set("Kagiroi ${project.name}")
                description.set("Type-safe Kotlin Multiplatform SQL toolkit for Kotlin/JS and Kotlin/Wasm.")
                inceptionYear.set("2026")
                url.set("https://github.com/quwac/kagiroi")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("quwac")
                        name.set("quwabara")
                        url.set("https://github.com/quwac")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/quwac/kagiroi.git")
                    developerConnection.set("scm:git:ssh://git@github.com/quwac/kagiroi.git")
                    url.set("https://github.com/quwac/kagiroi")
                }
            }
        }
    }
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
