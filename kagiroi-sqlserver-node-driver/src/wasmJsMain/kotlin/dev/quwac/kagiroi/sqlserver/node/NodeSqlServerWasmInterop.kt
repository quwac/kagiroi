@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("TooManyFunctions", "UnusedParameter")

package dev.quwac.kagiroi.sqlserver.node

import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsNumber
import kotlin.js.JsString

internal fun defaultExport(module: JsAny): JsAny = js("module.default ?? module")

internal fun poolConfig(settings: NodeSqlServerSettings): JsAny = poolConfig(
    settings.host,
    settings.port,
    settings.database,
    settings.user,
    settings.password,
    settings.pool.minConnections,
    settings.pool.maxConnections,
    settings.pool.connectionTimeoutMilliseconds,
    settings.pool.idleTimeoutMilliseconds,
    settings.requestTimeoutMilliseconds,
    settings.tls.mode.name,
    settings.tls.certificateAuthority,
    settings.tls.hostNameInCertificate,
)

internal fun poolConfig(
    host: String,
    port: Int,
    database: String,
    user: String,
    password: String?,
    min: Int,
    max: Int,
    connectionTimeout: Int,
    idleTimeout: Int,
    requestTimeout: Int,
    tlsMode: String,
    certificateAuthority: String?,
    hostNameInCertificate: String?,
): JsAny = js(
    """
    ({
      server: host, port, database, user,
      password: password == null ? undefined : password,
      connectionTimeout, requestTimeout,
      arrayRowMode: true,
      pool: { min, max, idleTimeoutMillis: idleTimeout },
      options: {
        encrypt: tlsMode !== 'DISABLED',
        trustServerCertificate: tlsMode === 'REQUIRE',
        cryptoCredentialsDetails: certificateAuthority == null ? undefined : { ca: certificateAuthority },
        serverName: hostNameInCertificate == null ? undefined : hostNameInCertificate,
      },
    })
    """,
)

internal fun createPool(constructor: JsAny, config: JsAny): NativePool = js("new constructor(config)")

internal fun isolationLevel(levels: JsAny, isolation: String?): JsAny? = js(
    "isolation == null ? null : levels[isolation]",
)

internal fun varBinaryType(factory: JsAny, length: Int): JsAny = js("factory(length)")

internal fun recordValues(record: JsAny): JsArray<JsAny> = js("Object.values(record)")

internal fun recordNames(record: JsAny): JsArray<JsString> = js("Object.keys(record)")

internal fun valueKind(value: JsAny): String = js(
    """
    typeof value === 'string' ? 'text'
      : typeof value === 'boolean' ? 'boolean'
      : typeof value === 'bigint' ? 'integer'
      : typeof value === 'number' ? (Number.isInteger(value) ? 'integer' : 'real')
      : value instanceof Date ? 'date'
      : ArrayBuffer.isView(value) ? 'bytes'
      : 'unsupported'
    """,
)

internal fun stringValue(value: JsAny): String = js("value instanceof Date ? value.toISOString() : String(value)")

internal fun booleanValue(value: JsAny): Boolean = js("Boolean(value)")

internal fun numberValue(value: JsAny): Double = js("Number(value)")

internal fun byteValues(value: JsAny): JsArray<JsNumber> = js("Array.from(value)")

internal fun bytesValue(value: JsAny): ByteArray =
    byteValues(value).toList().filterNotNull().map { it.toDouble().toInt().toByte() }.toByteArray()

internal fun originalError(value: JsAny): JsAny? = js("value.originalError == null ? null : value.originalError")

internal fun stringProperty(value: JsAny, name: String): String? =
    js("typeof value[name] === 'string' ? value[name] : null")

internal fun numberProperty(value: JsAny, name: String): Int? =
    js("typeof value[name] === 'number' ? value[name] : null")
