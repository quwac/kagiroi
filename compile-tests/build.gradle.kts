@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    kotlin("multiplatform")
}

val fixture = providers.gradleProperty("compileFixture").get()
require(fixture.matches(Regex("[A-Za-z]+")))
require(file("fixtures/$fixture").isDirectory) { "Unknown compile fixture: $fixture" }

kotlin {
    wasmJs {
        nodejs()
        compilerOptions {
            freeCompilerArgs.add("-Xrender-internal-diagnostic-names")
        }
    }
    sourceSets {
        commonMain {
            kotlin.srcDirs("contracts", "fixtures/$fixture")
            dependencies {
                implementation(project(":kagiroi-postgres-hyperdrive-driver"))
                implementation(project(":kagiroi-mysql-hyperdrive-driver"))
                implementation(project(":kagiroi-d1-driver"))
                implementation(project(":kagiroi-quwac-bignumber"))
                implementation(project(":kagiroi-sql"))
                implementation(project(":kagiroi-kotlinx-datetime"))
                implementation(project(":kagiroi-mariadb"))
                implementation(project(":kagiroi-mysql"))
                implementation(project(":kagiroi-oracle"))
                implementation(project(":kagiroi-sqlserver"))
                implementation(project(":kagiroi-postgres"))
                implementation(project(":kagiroi-sqlite"))
            }
        }
    }
}
