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
                    timeout = "90s"
                }
            }
        }
    }
    wasmJs {
        nodejs()
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":kagiroi-oracle"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
        commonTest {
            kotlin.srcDir(rootProject.file("test-support/sql-database-contract/src/commonTest/kotlin"))
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
                implementation(project(":kagiroi-oracle-quwac-bignumber"))
                implementation(project(":kagiroi-oracle-kotlinx-datetime"))
            }
        }
        jsMain.dependencies {
            implementation(npm("oracledb", "6.10.0"))
        }
        wasmJsMain.dependencies {
            implementation(npm("oracledb", "6.10.0"))
        }
    }
}
