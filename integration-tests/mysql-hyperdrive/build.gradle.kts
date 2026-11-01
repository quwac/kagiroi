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
                    "kagiroi-mysql-hyperdrive-driver/src/contractTest/kotlin",
                ),
            )
            kotlin.srcDir(rootProject.file("test-support/sql-database-contract/src/commonTest/kotlin"))
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":kagiroi-mysql-hyperdrive-driver"))
                implementation(project(":kagiroi-mysql-quwac-bignumber"))
                implementation(project(":kagiroi-mysql-kotlinx-datetime"))
                implementation(project(":kagiroi-mariadb"))
            }
        }
    }
}
