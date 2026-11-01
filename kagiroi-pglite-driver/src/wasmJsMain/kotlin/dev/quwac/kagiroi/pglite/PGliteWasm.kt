@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("FunctionNaming", "PropertyName", "TooGenericExceptionCaught")

package dev.quwac.kagiroi.pglite

import dev.quwac.kagiroi.postgres.PostgresBinding
import dev.quwac.kagiroi.postgres.PostgresResultValue
import kotlinx.coroutines.CancellationException
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

@JsModule("@electric-sql/pglite")
private external object PGliteModule : JsAny {
    val PGlite: JsAny
}

internal external interface NativePGlite : JsAny {
    fun query(sql: String, parameters: JsArray<JsAny?>, options: JsAny): Promise<NativePGliteResult>

    fun close(): Promise<JsAny?>
}

internal external interface NativePGliteResult : JsAny {
    val affectedRows: JsAny?
    val fields: JsArray<NativePGliteField>
    val rows: JsArray<JsArray<JsAny?>>
}

internal external interface NativePGliteField : JsAny {
    val name: String
    val dataTypeID: Int
}

internal actual suspend fun createPGliteEndpoint(settings: PGliteSettings): PGliteEndpoint = WasmPGliteEndpoint(
    translate(PGliteFailureKind.INITIALIZATION) {
        createDatabase(PGliteModule.PGlite, settings.dataDirectory, settings.relaxedDurability).awaitCompletion()
    },
)

private class WasmPGliteEndpoint(private val database: NativePGlite) : PGliteEndpoint {
    override suspend fun execute(statement: PGliteStatement): PGliteCommandResult {
        val result = translate(PGliteFailureKind.EXECUTION) {
            database.query(
                statement.sql,
                statement.parameters.map(::bindingValue).toJsArray(),
                queryOptions(),
            ).awaitCompletion()
        }
        return translate(PGliteFailureKind.RESULT_CONVERSION) {
            PGliteCommandResult(result.affectedRows.numberOrNull()?.toLong() ?: 0)
        }
    }

    override suspend fun query(statement: PGliteStatement): PGliteRows {
        val result = translate(PGliteFailureKind.EXECUTION) {
            database.query(
                statement.sql,
                statement.parameters.map(::bindingValue).toJsArray(),
                queryOptions(),
            ).awaitCompletion()
        }
        return translate(PGliteFailureKind.RESULT_CONVERSION) {
            val fields = result.fields.toList().filterNotNull()
            val typeObjectIds = fields.map(NativePGliteField::dataTypeID)
            val rows = result.rows.toList().filterNotNull().map { row ->
                val values = row.toList()
                require(values.size == typeObjectIds.size) { "PGlite result field count does not match row values" }
                values.mapIndexed { index, value -> PostgresResultValue(typeObjectIds[index], resultText(value)) }
            }
            PGliteRows(fields.map(NativePGliteField::name), rows)
        }
    }

    override suspend fun close() {
        translate(PGliteFailureKind.INITIALIZATION) { database.close().awaitCompletion() }
    }
}

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

private fun resultText(value: JsAny?): String? = when {
    value == null -> null
    jsType(value) == "string" -> value.unsafeCast<JsString>().toString()
    else -> throw IllegalArgumentException("PGlite text parser returned ${jsType(value)}")
}

private suspend inline fun <T> translate(kind: PGliteFailureKind, crossinline block: suspend () -> T): T = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: PGliteDriverException) {
    throw error
} catch (error: Throwable) {
    throw error.asPGliteDriverException(kind)
}

private fun Throwable.asPGliteDriverException(kind: PGliteFailureKind): PGliteDriverException {
    val thrown = (this as? JsException)?.thrownValue
    val sqlState = thrown?.let { stringProperty(it, "code") }
    val detail = thrown?.let { stringProperty(it, "message") } ?: message ?: toString()
    val attributes = ERROR_ATTRIBUTES.mapNotNull { name ->
        thrown?.let {
            stringProperty(
                it,
                name,
            )
        }?.let { name to it }
    }.toMap()
    return PGliteDriverException(kind, sqlState, attributes, detail, this)
}

private fun JsAny?.numberOrNull(): Double? =
    takeIf { it != null && jsType(it) == "number" }?.unsafeCast<JsNumber>()?.toDouble()

private suspend fun <T : JsAny?> Promise<T>.awaitCompletion(): T = suspendCoroutine { continuation ->
    then { value ->
        continuation.resume(value)
        value
    }.catch { error ->
        continuation.resumeWithException(error.asJsException())
        null
    }
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
