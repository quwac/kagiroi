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
            api("dev.quwac:bignumber:1.0.0")
            api(project(":kagiroi-schema"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":kagiroi-sql"))
        }
    }
}
