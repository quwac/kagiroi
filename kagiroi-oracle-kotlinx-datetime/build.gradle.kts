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
            api(project(":kagiroi-oracle"))
            api(project(":kagiroi-kotlinx-datetime"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
