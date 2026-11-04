@file:Suppress("TooGenericExceptionCaught", "UnsafeCastFromDynamic")

package dev.quwac.kagiroi.postgres.hyperdrive

import dev.quwac.kagiroi.postgres.PostgresBinding
import dev.quwac.kagiroi.postgres.PostgresResultValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.Promise

/** The Cloudflare Workers Hyperdrive binding passed through an environment object. */
external interface CloudflareHyperdriveBinding {
    /** Value associated with connection string. */
    val connectionString: String
}

/** Provides the hyperdrive postgres database result. */
fun HyperdrivePostgresDatabase(
    binding: CloudflareHyperdriveBinding,
    configure: HyperdrivePostgresDatabaseConfiguration.() -> Unit = {},
): HyperdrivePostgresDatabase = HyperdrivePostgresDatabase.create(
    JsPostgresEndpoint(binding.connectionString),
    configure,
)

@JsModule("pg")
@JsNonModule
private external object PostgresModule {
    val Client: dynamic
}

private external interface NativePostgresClient {
    fun connect(): Promise<Unit>
    fun query(config: dynamic): Promise<dynamic>
    fun end(): Promise<Unit>
}

private class JsPostgresEndpoint(connectionString: String) : PostgresEndpoint {
    private val client = createClient(PostgresModule.Client, clientConfig(connectionString))
    private val connected = client.connect()

    override suspend fun execute(statement: PostgresStatement): PostgresCommandResult =
        translate(PostgresFailureKind.EXECUTION) {
            ensureConnected()
            val result = client.query(queryConfig(statement)).await()
            PostgresCommandResult((result.rowCount as? Number)?.toLong() ?: 0)
        }

    override suspend fun query(statement: PostgresStatement): PostgresRows =
        translate(PostgresFailureKind.RESULT_CONVERSION) {
            ensureConnected()
            val result = client.query(queryConfig(statement)).await()
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

private fun queryConfig(statement: PostgresStatement): dynamic {
    val config = js("({})")
    config.text = statement.sql
    config.values = statement.parameters.map(::bindingValue).toTypedArray()
    config.rowMode = "array"
    config.types = textResultTypes()
    statement.preparedName?.let { config.name = it }
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
    val attributes = listOf("severity", "detail", "schema", "table", "column", "constraint", "routine")
        .mapNotNull { name -> (native[name] as? String)?.let { name to it } }
        .toMap()
    return PostgresDriverException(kind, sqlState, attributes, detail, this)
}

private suspend fun <T> Promise<T>.await(): T = suspendCancellableCoroutine { continuation ->
    then { value ->
        if (continuation.isActive) continuation.resume(value)
        value
    }.catch { error ->
        if (continuation.isActive) continuation.resumeWithException(error)
        null
    }
}

@Suppress("UnusedParameter")
private fun clientConfig(connectionString: String): dynamic = js("({ connectionString })")

private fun textResultTypes(): dynamic = js("({ getTypeParser: () => (value => value) })")

@Suppress("UnusedParameter")
private fun createClient(constructor: dynamic, config: dynamic): NativePostgresClient = js("new constructor(config)")

@Suppress("UnusedParameter")
private fun setByte(output: Uint8Array, index: Int, value: Int) {
    js("output[index] = value")
}

private const val BYTE_MASK = 0xff
