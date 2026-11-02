@file:Suppress("TooGenericExceptionCaught", "TooManyFunctions", "UnsafeCastFromDynamic", "UnusedParameter")

package dev.quwac.kagiroi.pglite

import dev.quwac.kagiroi.postgres.PostgresBinding
import dev.quwac.kagiroi.postgres.PostgresResultValue
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Promise

@JsModule("@electric-sql/pglite")
@JsNonModule
private external object PGliteModule {
    val PGlite: dynamic
}

internal actual suspend fun createPGliteEndpoint(settings: PGliteSettings): PGliteEndpoint = JsPGliteEndpoint(
    translate(PGliteFailureKind.INITIALIZATION) {
        createDatabase(PGliteModule.PGlite, settings.dataDirectory, settings.relaxedDurability).awaitCompletion()
    },
)

private class JsPGliteEndpoint(private val database: dynamic) : PGliteEndpoint {
    override suspend fun execute(statement: PGliteStatement): PGliteCommandResult {
        val result = translate(PGliteFailureKind.EXECUTION) {
            queryDatabase(
                database,
                statement.sql,
                statement.parameters.map(::bindingValue).toTypedArray(),
                queryOptions(),
            ).awaitCompletion()
        }
        return translate(PGliteFailureKind.RESULT_CONVERSION) {
            PGliteCommandResult((result.affectedRows as? Number)?.toLong() ?: 0)
        }
    }

    override suspend fun query(statement: PGliteStatement): PGliteRows {
        val result = translate(PGliteFailureKind.EXECUTION) {
            queryDatabase(
                database,
                statement.sql,
                statement.parameters.map(::bindingValue).toTypedArray(),
                queryOptions(),
            ).awaitCompletion()
        }
        return translate(PGliteFailureKind.RESULT_CONVERSION) {
            val fields = (result.fields as Array<dynamic>).toList()
            val names = fields.map { it.name as String }
            val typeObjectIds = fields.map { (it.dataTypeID as Number).toInt() }
            val rows = (result.rows as Array<Array<dynamic>>).map { row ->
                require(row.size == typeObjectIds.size) { "PGlite result field count does not match row values" }
                row.mapIndexed { index, value -> PostgresResultValue(typeObjectIds[index], resultText(value)) }
            }
            PGliteRows(names, rows)
        }
    }

    override suspend fun close() {
        translate(PGliteFailureKind.INITIALIZATION) { closeDatabase(database).awaitCompletion() }
    }
}

private fun createDatabase(factory: dynamic, dataDirectory: String, relaxedDurability: Boolean): Promise<dynamic> =
    factory.create(dataDirectory, options(relaxedDurability))

@Suppress("UnusedParameter")
private fun queryDatabase(
    database: dynamic,
    sql: String,
    parameters: Array<dynamic>,
    options: dynamic,
): Promise<dynamic> = js("database.query(sql, parameters, options)")

@Suppress("UnusedParameter")
private fun closeDatabase(database: dynamic): Promise<dynamic> = js("database.close()")

private fun options(relaxedDurability: Boolean): dynamic = js("({ relaxedDurability })")

private fun queryOptions(): dynamic = js(
    """
    ({
      rowMode: 'array',
      parsers: Object.fromEntries([
        16, 17, 20, 21, 23, 25, 700, 701, 1043, 1082, 1083, 1114, 1184, 1700, 2950,
      ].map((oid) => [oid, (value) => value])),
    })
    """,
)

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

private fun resultText(value: dynamic): String? = when {
    value == null -> null
    jsTypeOf(value) == "string" -> value as String
    else -> throw IllegalArgumentException("PGlite text parser returned ${jsTypeOf(value)}")
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
    val native = asDynamic()
    val sqlState = native.code as? String
    val detail = native.message as? String ?: message ?: toString()
    val attributes = ERROR_ATTRIBUTES.mapNotNull { name -> (native[name] as? String)?.let { name to it } }.toMap()
    return PGliteDriverException(kind, sqlState, attributes, detail, this)
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
