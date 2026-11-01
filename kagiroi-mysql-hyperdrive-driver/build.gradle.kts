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
            api(project(":kagiroi-mysql"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
        commonTest {
            kotlin.srcDir("src/contractTest/kotlin")
            kotlin.srcDir(rootProject.file("test-support/sql-database-contract/src/commonTest/kotlin"))
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
                implementation(project(":kagiroi-mysql-quwac-bignumber"))
                implementation(project(":kagiroi-mysql-kotlinx-datetime"))
                implementation(project(":kagiroi-mariadb"))
            }
        }
        jsMain.dependencies {
            implementation(npm("mysql2", "3.23.1"))
        }
        wasmJsMain.dependencies {
            implementation(npm("mysql2", "3.23.1"))
        }
    }
}
