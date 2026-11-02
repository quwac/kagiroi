@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("UnusedParameter")

package dev.quwac.kagiroi.postgres.node

import kotlin.js.JsAny
import kotlin.js.JsArray

internal fun poolConfig(
    connectionString: String?,
    host: String?,
    port: Int?,
    database: String?,
    user: String?,
    password: String?,
    minConnections: Int,
    maxConnections: Int,
    connectionTimeoutMilliseconds: Int,
    idleTimeoutMilliseconds: Int,
    tlsMode: String?,
    certificateAuthority: String?,
    clientCertificate: String?,
    clientPrivateKey: String?,
): JsAny = js(
    """
    ({
      connectionString: connectionString == null ? undefined : connectionString,
      host: host == null ? undefined : host,
      port: port == null ? undefined : port,
      database: database == null ? undefined : database,
      user: user == null ? undefined : user,
      password: password == null ? undefined : password,
      min: minConnections,
      max: maxConnections,
      connectionTimeoutMillis: connectionTimeoutMilliseconds,
      idleTimeoutMillis: idleTimeoutMilliseconds,
      ssl: tlsMode == null
        ? undefined
        : tlsMode === 'DISABLED'
          ? false
          : {
              rejectUnauthorized: tlsMode === 'VERIFY_FULL',
              ca: certificateAuthority == null ? undefined : certificateAuthority,
              cert: clientCertificate == null ? undefined : clientCertificate,
              key: clientPrivateKey == null ? undefined : clientPrivateKey,
            },
    })
    """,
)

internal fun queryConfig(text: String, values: JsArray<JsAny?>): JsAny = js(
    "({ text, values, rowMode: 'array', types: { getTypeParser: () => (value => value) } })",
)

internal fun jsType(value: JsAny): String = js("typeof value")

internal fun stringProperty(value: JsAny, name: String): String? =
    js("typeof value[name] === 'string' ? value[name] : null")

// pg removes a failed idle client before emitting; the hidden pool still needs a listener to avoid an unhandled event.
internal fun createPool(constructor: JsAny, config: JsAny): NativePostgresPool =
    createNativePool(constructor, config).also(::installIdleErrorListener)

internal fun createNativePool(constructor: JsAny, config: JsAny): NativePostgresPool = js("new constructor(config)")

internal fun installIdleErrorListener(pool: NativePostgresPool) {
    js("void pool.on('error', function () {})")
}
