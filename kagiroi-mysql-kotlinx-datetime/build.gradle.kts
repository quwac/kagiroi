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
            api(project(":kagiroi-kotlinx-datetime"))
            api(project(":kagiroi-mysql"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jsMain.dependencies {
            implementation(npm("@js-joda/timezone", "2.22.0"))
        }
        wasmJsMain.dependencies {
            implementation(npm("@js-joda/timezone", "2.22.0"))
        }
    }
}
