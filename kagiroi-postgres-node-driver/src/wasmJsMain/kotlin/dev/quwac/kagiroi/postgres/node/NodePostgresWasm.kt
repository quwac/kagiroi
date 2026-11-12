@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("FunctionNaming", "PropertyName", "TooGenericExceptionCaught")

package dev.quwac.kagiroi.postgres.node

import dev.quwac.kagiroi.postgres.PostgresBinding
import dev.quwac.kagiroi.postgres.PostgresResultValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsException
import kotlin.js.JsNumber
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.asJsException
import kotlin.js.toJsArray
import kotlin.js.toJsBoolean
import kotlin.js.toJsNumber
import kotlin.js.toJsString
import kotlin.js.unsafeCast

@JsModule("pg")
private external object PostgresModule : JsAny {
    val Pool: JsAny
}

internal external interface NativePostgresPool : JsAny {
    fun connect(): Promise<NativePostgresClient>
    fun end(): Promise<JsAny?>
}

internal external interface NativePostgresClient : JsAny {
    fun query(config: JsAny): Promise<NativePostgresResult>
    fun release(destroy: Boolean)
}

internal external interface NativePostgresResult : JsAny {
    val rowCount: JsAny?
    val fields: JsArray<NativePostgresField>
    val rows: JsArray<JsArray<JsAny?>>
}

internal external interface NativePostgresField : JsAny {
    val name: String
    val dataTypeID: Int
}

internal actual fun createNodePostgresPool(settings: NodePostgresSettings): NodePostgresPool =
    WasmNodePostgresPool(createPool(PostgresModule.Pool, poolConfig(settings)))

private class WasmNodePostgresPool(private val pool: NativePostgresPool) : NodePostgresPool {
    override suspend fun acquire(): NodePostgresSession = translate(PostgresFailureKind.CONNECTION) {
        WasmNodePostgresSession(pool.connect().awaitClient())
    }

    override suspend fun close() {
        translate(PostgresFailureKind.CONNECTION) { pool.end().awaitCompletion() }
    }
}

private class WasmNodePostgresSession(private val client: NativePostgresClient) : NodePostgresSession {
    override suspend fun execute(statement: PostgresStatement): PostgresCommandResult {
        val result = translate(PostgresFailureKind.EXECUTION) {
            client.query(queryConfig(statement)).awaitCompletion()
        }
        return translate(PostgresFailureKind.RESULT_CONVERSION) {
            PostgresCommandResult(result.rowCount.numberOrNull()?.toLong() ?: 0)
        }
    }

    override suspend fun query(statement: PostgresStatement): PostgresRows {
        val result = translate(PostgresFailureKind.EXECUTION) {
            client.query(queryConfig(statement)).awaitCompletion()
        }
        return translate(PostgresFailureKind.RESULT_CONVERSION) {
            val nativeFields = result.fields.toList().filterNotNull()
            val typeObjectIds = nativeFields.map(NativePostgresField::dataTypeID)
            val rows = result.rows.toList().filterNotNull().map { row ->
                val values = row.toList()
                require(values.size == typeObjectIds.size) {
                    "PostgreSQL result field count does not match row values"
                }
                values.mapIndexed { index, value ->
                    if (value != null && jsType(value) != "string") {
                        throw IllegalArgumentException("PostgreSQL text result parser returned ${jsType(value)}")
                    }
                    PostgresResultValue(typeObjectIds[index], value?.unsafeCast<JsString>()?.toString())
                }
            }
            PostgresRows(nativeFields.map(NativePostgresField::name), rows)
        }
    }

    override fun release(discard: Boolean) {
        client.release(discard)
    }
}

private fun poolConfig(settings: NodePostgresSettings): JsAny = poolConfig(
    connectionString = settings.connectionUrl,
    host = settings.host,
    port = settings.port,
    database = settings.database,
    user = settings.user,
    password = settings.password,
    minConnections = settings.pool.minConnections,
    maxConnections = settings.pool.maxConnections,
    connectionTimeoutMilliseconds = settings.pool.connectionTimeoutMilliseconds,
    idleTimeoutMilliseconds = settings.pool.idleTimeoutMilliseconds,
    tlsMode = settings.tls?.mode?.name,
    certificateAuthority = settings.tls?.certificateAuthority,
    clientCertificate = settings.tls?.clientCertificate,
    clientPrivateKey = settings.tls?.clientPrivateKey,
)

private fun queryConfig(statement: PostgresStatement): JsAny = queryConfig(
    statement.sql,
    statement.parameters.map(::bindingValue).toJsArray(),
)

private fun bindingValue(value: PostgresBinding): JsAny? = when (value) {
    PostgresBinding.Null -> null
    is PostgresBinding.Text -> value.value.toJsString()
    is PostgresBinding.BooleanValue -> value.value.toJsBoolean()
    is PostgresBinding.Integer -> value.decimal.toJsString()
    is PostgresBinding.Real -> value.value.toJsNumber()
    is PostgresBinding.Bytes -> value.value.toUint8Array()
    is PostgresBinding.UuidValue -> value.value.toString().toJsString()
}

private external class Uint8Array(length: Int) : JsAny {
    operator fun set(index: Int, value: Int)
}

private fun ByteArray.toUint8Array(): Uint8Array = Uint8Array(size).also { output ->
    forEachIndexed { index, value -> output[index] = value.toInt() and BYTE_MASK }
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
    val thrown = (this as? JsException)?.thrownValue
    val sqlState = thrown?.let { stringProperty(it, "code") }
    val detail = thrown?.let { stringProperty(it, "message") } ?: message ?: toString()
    val attributes = ERROR_ATTRIBUTES.mapNotNull { name ->
        thrown?.let { stringProperty(it, name) }?.let { name to it }
    }.toMap()
    return PostgresDriverException(kind, sqlState, attributes, detail, this)
}

private fun JsAny?.numberOrNull(): Double? =
    takeIf { it != null && jsType(it) == "number" }?.unsafeCast<JsNumber>()?.toDouble()

// Attach rejection handlers to the source Promise so coroutine failures cannot trigger a second resume.
private suspend fun Promise<NativePostgresClient>.awaitClient(): NativePostgresClient =
    suspendCancellableCoroutine { continuation ->
        then(onFulfilled = { client ->
            if (continuation.isActive) {
                continuation.resume(client)
            } else {
                client.release(false)
            }
            client
        }, onRejected = { error ->
            if (continuation.isActive) continuation.resumeWithException(error.asJsException())
            null
        })
    }

internal suspend fun <T : JsAny?> Promise<T>.awaitCompletion(): T = suspendCoroutine { continuation ->
    then(onFulfilled = { value ->
        continuation.resume(value)
        value
    }, onRejected = { error ->
        continuation.resumeWithException(error.asJsException())
        null
    })
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
