@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("UnusedParameter")

package dev.quwac.kagiroi.postgres.hyperdrive

import kotlin.js.JsAny
import kotlin.js.JsArray

internal fun clientConfig(connectionString: String): JsAny = js("({ connectionString })")

internal fun queryConfig(text: String, values: JsArray<JsAny?>, name: String?): JsAny = js(
    "({ text, values, name: name || undefined, rowMode: 'array', types: { getTypeParser: () => (value => value) } })",
)

internal fun jsType(value: JsAny): String = js("typeof value")

internal fun stringProperty(value: JsAny, name: String): String? =
    js("typeof value[name] === 'string' ? value[name] : null")

internal fun createClient(constructor: JsAny, config: JsAny): NativePostgresClient = js("new constructor(config)")
