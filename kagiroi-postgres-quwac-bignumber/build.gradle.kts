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
            api(project(":kagiroi-quwac-bignumber"))
            api(project(":kagiroi-postgres"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
