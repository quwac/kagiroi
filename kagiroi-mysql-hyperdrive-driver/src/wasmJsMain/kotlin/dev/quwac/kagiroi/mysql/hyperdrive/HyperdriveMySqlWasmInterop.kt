@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("UnusedParameter")

package dev.quwac.kagiroi.mysql.hyperdrive

import kotlin.js.JsAny

internal fun connectionConfig(binding: CloudflareHyperdriveBinding): JsAny = js(
    "({ host: binding.host, user: binding.user, password: binding.password, database: binding.database, " +
        "port: binding.port, disableEval: true, rowsAsArray: true, supportBigNumbers: true, " +
        "bigNumberStrings: true, jsonStrings: true, dateStrings: true, timezone: 'Z', " +
        "typeCast: (field, next) => field.type === 'GEOMETRY' ? field.buffer() : next() })",
)

internal fun jsType(value: JsAny): String = js("typeof value")

internal fun stringProperty(value: JsAny, name: String): String? =
    js("typeof value[name] === 'string' ? value[name] : null")

internal fun numberProperty(value: JsAny, name: String): Double? =
    js("typeof value[name] === 'number' ? value[name] : null")

internal fun isUint8Array(value: JsAny): Boolean = js("value instanceof Uint8Array")

internal fun numberString(value: JsAny): String = js("String(value)")

internal fun booleanValue(value: JsAny): Boolean = js("value === true")
