@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    kotlin("multiplatform")
}

group = "dev.quwac.kagiroi"

kotlin {
    js { nodejs() }
    wasmJs { nodejs() }
    sourceSets {
        commonMain.dependencies {
            api(project(":kagiroi-sqlserver"))
            api(project(":kagiroi-quwac-bignumber"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
