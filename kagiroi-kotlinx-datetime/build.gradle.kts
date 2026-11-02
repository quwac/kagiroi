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
            api(project(":kagiroi-schema"))
            api("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
