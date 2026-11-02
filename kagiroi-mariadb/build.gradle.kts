@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    kotlin("multiplatform")
}

group = "dev.quwac.kagiroi"

kotlin {
    js {
        nodejs()
    }
    wasmJs {
        nodejs()
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":kagiroi-sql"))
            implementation(project(":kagiroi-mysql"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":kagiroi-mysql-kotlinx-datetime"))
        }
    }
}
