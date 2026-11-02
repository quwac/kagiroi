@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.quwac.kagiroi.mysql.node

import kotlin.js.JsString

internal actual fun mariaDbTestUrl(): String? = mariaDbTestUrlValue()?.toString()

@Suppress("UnusedParameter")
private fun mariaDbTestUrlValue(): JsString? = js(
    "typeof process !== 'undefined' && typeof process.env.KAGIROI_MARIADB_TEST_URL === 'string' ? process.env.KAGIROI_MARIADB_TEST_URL : null",
)
