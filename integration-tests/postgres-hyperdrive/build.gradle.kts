@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    kotlin("multiplatform")
}

kotlin {
    js {
        nodejs()
        useEsModules()
        binaries.executable()
    }
    wasmJs {
        nodejs()
        binaries.executable()
        generateTypeScriptDefinitions()
    }
    sourceSets {
        commonMain {
            kotlin.srcDir(
                rootProject.file(
                    "kagiroi-postgres-hyperdrive-driver/src/contractTest/kotlin",
                ),
            )
            kotlin.srcDir(rootProject.file("test-support/sql-database-contract/src/commonTest/kotlin"))
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":kagiroi-postgres-hyperdrive-driver"))
                implementation(project(":kagiroi-postgres-quwac-bignumber"))
                implementation(project(":kagiroi-postgres-kotlinx-datetime"))
            }
        }
    }
}
