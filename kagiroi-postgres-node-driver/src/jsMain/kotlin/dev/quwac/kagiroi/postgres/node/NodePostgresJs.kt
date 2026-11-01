@file:Suppress("TooGenericExceptionCaught", "TooManyFunctions", "UnsafeCastFromDynamic")

package dev.quwac.kagiroi.postgres.node

import dev.quwac.kagiroi.postgres.PostgresBinding
import dev.quwac.kagiroi.postgres.PostgresResultValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Promise

@JsModule("pg")
@JsNonModule
private external object PostgresModule {
    val Pool: dynamic
}

private external interface NativePostgresPool {
    fun connect(): Promise<NativePostgresClient>
    fun end(): Promise<Unit>
}

private external interface NativePostgresClient {
    fun query(config: dynamic): Promise<dynamic>
    fun release(destroy: Boolean)
}

internal actual fun createNodePostgresPool(settings: NodePostgresSettings): NodePostgresPool =
    JsNodePostgresPool(createPool(PostgresModule.Pool, poolConfig(settings)))

private class JsNodePostgresPool(private val pool: NativePostgresPool) : NodePostgresPool {
    override suspend fun acquire(): NodePostgresSession = translate(PostgresFailureKind.CONNECTION) {
        JsNodePostgresSession(pool.connect().awaitClient())
    }

    override suspend fun close() {
        translate(PostgresFailureKind.CONNECTION) { pool.end().awaitCompletion() }
    }
}

private class JsNodePostgresSession(private val client: NativePostgresClient) : NodePostgresSession {
    override suspend fun execute(statement: PostgresStatement): PostgresCommandResult {
        val result = translate(PostgresFailureKind.EXECUTION) {
            client.query(queryConfig(statement)).awaitCompletion()
        }
        return translate(PostgresFailureKind.RESULT_CONVERSION) {
            PostgresCommandResult((result.rowCount as? Number)?.toLong() ?: 0)
        }
    }

    override suspend fun query(statement: PostgresStatement): PostgresRows {
        val result = translate(PostgresFailureKind.EXECUTION) {
            client.query(queryConfig(statement)).awaitCompletion()
        }
        return translate(PostgresFailureKind.RESULT_CONVERSION) {
            val fields = (result.fields as Array<dynamic>).toList()
            val names = fields.map { it.name as String }
            val typeObjectIds = fields.map { (it.dataTypeID as Number).toInt() }
            val rows = (result.rows as Array<Array<dynamic>>).map { row ->
                require(row.size == typeObjectIds.size) { "PostgreSQL result field count does not match row values" }
                row.mapIndexed { index, value ->
                    if (value != null && jsTypeOf(value) != "string") {
                        throw IllegalArgumentException("PostgreSQL text result parser returned ${jsTypeOf(value)}")
                    }
                    PostgresResultValue(typeObjectIds[index], value as? String)
                }
            }
            PostgresRows(names, rows)
        }
    }

    override fun release(discard: Boolean) {
        client.release(discard)
    }
}

private fun poolConfig(settings: NodePostgresSettings): dynamic {
    val config = js("({})")
    settings.connectionUrl?.let { config.connectionString = it }
    settings.host?.let { config.host = it }
    settings.port?.let { config.port = it }
    settings.database?.let { config.database = it }
    settings.user?.let { config.user = it }
    settings.password?.let { config.password = it }
    config.min = settings.pool.minConnections
    config.max = settings.pool.maxConnections
    config.connectionTimeoutMillis = settings.pool.connectionTimeoutMilliseconds
    config.idleTimeoutMillis = settings.pool.idleTimeoutMilliseconds
    settings.tls?.let { config.ssl = tlsConfig(it) }
    return config
}

private fun tlsConfig(settings: NodePostgresTlsSettings): dynamic = when (settings.mode) {
    NodePostgresTlsMode.DISABLED -> false

    NodePostgresTlsMode.REQUIRE -> tlsObject(false, null, settings.clientCertificate, settings.clientPrivateKey)

    NodePostgresTlsMode.VERIFY_FULL -> tlsObject(
        true,
        settings.certificateAuthority,
        settings.clientCertificate,
        settings.clientPrivateKey,
    )
}

private fun tlsObject(
    rejectUnauthorized: Boolean,
    certificateAuthority: String?,
    clientCertificate: String?,
    clientPrivateKey: String?,
): dynamic {
    val config = js("({})")
    config.rejectUnauthorized = rejectUnauthorized
    certificateAuthority?.let { config.ca = it }
    clientCertificate?.let { config.cert = it }
    clientPrivateKey?.let { config.key = it }
    return config
}

private fun queryConfig(statement: PostgresStatement): dynamic {
    val config = js("({})")
    config.text = statement.sql
    config.values = statement.parameters.map(::bindingValue).toTypedArray()
    config.rowMode = "array"
    config.types = textResultTypes()
    return config
}

private fun bindingValue(value: PostgresBinding): dynamic = when (value) {
    PostgresBinding.Null -> null
    is PostgresBinding.Text -> value.value
    is PostgresBinding.BooleanValue -> value.value
    is PostgresBinding.Integer -> value.decimal
    is PostgresBinding.Real -> value.value
    is PostgresBinding.Bytes -> value.value.toUint8Array()
    is PostgresBinding.UuidValue -> value.value.toString()
}

private external class Uint8Array(length: Int)

private fun ByteArray.toUint8Array(): Uint8Array {
    val output = Uint8Array(size)
    forEachIndexed { index, value -> setByte(output, index, value.toInt() and BYTE_MASK) }
    return output
}

private suspend inline fun <T> translate(kind: PostgresFailureKind, crossinline block: suspend () -> T): T = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: PostgresDriverException) {
    throw error
} catch (error: Throwable) {
    throw error.asPostgresDriverException(kind)
}

private fun Throwable.asPostgresDriverException(kind: PostgresFailureKind): PostgresDriverException {
    val native = asDynamic()
    val sqlState = native.code as? String
    val detail = if (sqlState == null) {
        native.stack as? String ?: native.message as? String ?: message ?: toString()
    } else {
        native.message as? String ?: message ?: toString()
    }
    val attributes = ERROR_ATTRIBUTES.mapNotNull { name ->
        (native[name] as? String)?.let { name to it }
    }.toMap()
    return PostgresDriverException(kind, sqlState, attributes, detail, this)
}

private suspend fun Promise<NativePostgresClient>.awaitClient(): NativePostgresClient =
    suspendCancellableCoroutine { continuation ->
        then { client ->
            if (continuation.isActive) {
                continuation.resume(client)
            } else {
                client.release(false)
            }
            client
        }.catch { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
            null
        }
    }

private suspend fun <T> Promise<T>.awaitCompletion(): T = suspendCoroutine { continuation ->
    then { value ->
        continuation.resume(value)
        value
    }.catch { error ->
        continuation.resumeWithException(error)
        null
    }
}

private fun textResultTypes(): dynamic = js("({ getTypeParser: () => (value => value) })")

@Suppress("UnusedParameter")
// pg removes a failed idle client before emitting; the hidden pool still needs a listener to avoid an unhandled event.
private fun createPool(constructor: dynamic, config: dynamic): NativePostgresPool =
    createNativePool(constructor, config).also(::installIdleErrorListener)

@Suppress("UnusedParameter")
private fun createNativePool(constructor: dynamic, config: dynamic): NativePostgresPool = js("new constructor(config)")

@Suppress("UnusedParameter")
private fun installIdleErrorListener(pool: NativePostgresPool) {
    js("void pool.on('error', function () {})")
}

@Suppress("UnusedParameter")
private fun setByte(output: Uint8Array, index: Int, value: Int) {
    js("output[index] = value")
}

private const val BYTE_MASK = 0xff
private val ERROR_ATTRIBUTES = listOf(
    "severity",
    "detail",
    "schema",
    "table",
    "column",
    "constraint",
    "position",
    "internalPosition",
    "internalQuery",
    "where",
    "file",
    "line",
    "routine",
)
