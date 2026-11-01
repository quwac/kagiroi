@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    kotlin("multiplatform")
}

group = "dev.quwac.kagiroi"

kotlin {
    js {
        nodejs {
            testTask {
                useMocha {
                    timeout = "10s"
                }
            }
        }
    }
    wasmJs {
        nodejs()
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":kagiroi-sqlite"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
        commonTest {
            kotlin.srcDir(rootProject.file("test-support/sql-database-contract/src/commonTest/kotlin"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            implementation(project(":kagiroi-sqlite-quwac-bignumber"))
            implementation(project(":kagiroi-sqlite-kotlinx-datetime"))
        }
        jsMain.dependencies {
            implementation(npm("@sqlite.org/sqlite-wasm", "3.53.4-build2"))
        }
        wasmJsMain.dependencies {
            implementation(npm("@sqlite.org/sqlite-wasm", "3.53.4-build2"))
        }
    }
}
