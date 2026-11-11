@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.quwac.kagiroi.mysql.hyperdrive

import kotlin.js.JsAny

internal actual fun connectionFields(connectionString: String): List<String> {
    val config = connectionStringConfig(connectionString)
    return listOf("host", "user", "password", "database", "port", "disableEval", "rowsAsArray", "uri", "ssl-mode")
        .map { property(config, it) }
}

@Suppress("UnusedParameter")
private fun property(config: JsAny, key: String): String = js("String(config[key])")
