@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("UnusedParameter")

package dev.quwac.kagiroi.pglite

import kotlin.js.JsAny
import kotlin.js.Promise

internal fun createDatabase(factory: JsAny, dataDirectory: String, relaxedDurability: Boolean): Promise<NativePGlite> =
    js(
        "factory.create(dataDirectory, { relaxedDurability })",
    )

internal fun queryOptions(): JsAny = js(
    """
    ({
      rowMode: 'array',
      parsers: Object.fromEntries([
        16, 17, 20, 21, 23, 25, 700, 701, 1043, 1082, 1083, 1114, 1184, 1700, 2950,
      ].map((oid) => [oid, (value) => value])),
    })
    """,
)

internal fun jsType(value: JsAny): String = js("typeof value")

internal fun stringProperty(value: JsAny, name: String): String? =
    js("typeof value[name] === 'string' ? value[name] : null")
