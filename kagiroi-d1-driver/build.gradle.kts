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
            kotlin.srcDir(rootProject.file("test-support/sql-database-contract/src/commonTest/kotlin"))
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            }
        }
        jsTest.dependencies {
            implementation(npm("miniflare", "5.20261012.0-beta"))
        }
    }
}
