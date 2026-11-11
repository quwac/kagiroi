@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("FunctionNaming", "PropertyName", "TooGenericExceptionCaught")

package dev.quwac.kagiroi.postgres.hyperdrive

import dev.quwac.kagiroi.postgres.PostgresBinding
import dev.quwac.kagiroi.postgres.PostgresResultValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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

internal actual fun createHyperdrivePostgresEndpoint(connectionString: String): PostgresEndpoint =
    WasmPostgresEndpoint(connectionString)

@JsModule("pg")
private external object PostgresModule : JsAny {
    val Client: JsAny
}

internal external interface NativePostgresClient : JsAny {
    fun connect(): Promise<JsAny?>
    fun query(config: JsAny): Promise<NativePostgresResult>
    fun end(): Promise<JsAny?>
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

private class WasmPostgresEndpoint(connectionString: String) : PostgresEndpoint {
    private val client = createClient(PostgresModule.Client, clientConfig(connectionString))
    private val connected = client.connect()

    override suspend fun execute(statement: PostgresStatement): PostgresCommandResult =
        translate(PostgresFailureKind.EXECUTION) {
            ensureConnected()
            val result = client.query(queryConfig(statement)).await()
            PostgresCommandResult(result.rowCount.numberOrNull()?.toLong() ?: 0)
        }

    override suspend fun query(statement: PostgresStatement): PostgresRows =
        translate(PostgresFailureKind.RESULT_CONVERSION) {
            ensureConnected()
            val fields = client.query(queryConfig(statement)).await().let { result ->
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
            fields
        }

    override suspend fun close() {
        translate(PostgresFailureKind.CONNECTION) {
            try {
                ensureConnected()
            } finally {
                client.end().await()
            }
        }
    }

    private suspend fun ensureConnected() {
        try {
            connected.await()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw error.asPostgresDriverException(PostgresFailureKind.CONNECTION)
        }
    }
}

private fun queryConfig(statement: PostgresStatement): JsAny = queryConfig(
    statement.sql,
    statement.parameters.map(::bindingValue).toJsArray(),
    statement.preparedName,
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
    val attributes = listOf("severity", "detail", "schema", "table", "column", "constraint", "routine")
        .mapNotNull { name -> thrown?.let { stringProperty(it, name) }?.let { name to it } }
        .toMap()
    return PostgresDriverException(kind, sqlState, attributes, detail, this)
}

private fun JsAny?.numberOrNull(): Double? =
    takeIf { it != null && jsType(it) == "number" }?.unsafeCast<JsNumber>()?.toDouble()

private suspend fun <T : JsAny?> Promise<T>.await(): T = suspendCancellableCoroutine { continuation ->
    then { value ->
        if (continuation.isActive) continuation.resume(value)
        value
    }.catch { error ->
        if (continuation.isActive) continuation.resumeWithException(error.asJsException())
        null
    }
}

private const val BYTE_MASK = 0xff
