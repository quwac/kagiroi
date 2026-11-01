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
                    timeout = "30s"
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
            kotlin.srcDir("src/contractTest/kotlin")
            kotlin.srcDir(rootProject.file("test-support/sql-database-contract/src/commonTest/kotlin"))
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
                implementation(project(":kagiroi-sqlite-quwac-bignumber"))
                implementation(project(":kagiroi-sqlite-kotlinx-datetime"))
            }
        }
        jsMain.dependencies {
            implementation(npm("better-sqlite3", "13.0.3"))
        }
        wasmJsMain.dependencies {
            implementation(npm("better-sqlite3", "13.0.3"))
        }
    }
}
