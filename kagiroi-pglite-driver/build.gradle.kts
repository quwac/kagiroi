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
            api(project(":kagiroi-postgres"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
        commonTest {
            kotlin.srcDir(rootProject.file("test-support/postgres-rls-contract/src/commonTest/kotlin"))
            kotlin.srcDir("src/contractTest/kotlin")
            kotlin.srcDir(rootProject.file("test-support/sql-database-contract/src/commonTest/kotlin"))
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
                implementation(project(":kagiroi-postgres-quwac-bignumber"))
                implementation(project(":kagiroi-postgres-kotlinx-datetime"))
            }
        }
        jsMain.dependencies {
            implementation(npm("@electric-sql/pglite", "0.5.8"))
        }
        wasmJsMain.dependencies {
            implementation(npm("@electric-sql/pglite", "0.5.8"))
        }
    }
}
