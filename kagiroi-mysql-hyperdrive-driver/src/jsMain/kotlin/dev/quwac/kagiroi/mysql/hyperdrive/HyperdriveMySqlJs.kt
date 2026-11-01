@file:Suppress("TooGenericExceptionCaught", "UnsafeCastFromDynamic")

package dev.quwac.kagiroi.mysql.hyperdrive

import dev.quwac.kagiroi.mysql.MySqlBinding
import dev.quwac.kagiroi.mysql.MySqlResultValue
import dev.quwac.kagiroi.mysql.MySqlScalar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.Promise

/** The Cloudflare Workers Hyperdrive binding passed through an environment object. */
external interface CloudflareHyperdriveBinding {
    val host: String
    val user: String
    val password: String
    val database: String
    val port: Int
}

fun HyperdriveMySqlDatabase(
    binding: CloudflareHyperdriveBinding,
    configure: HyperdriveMySqlDatabaseConfiguration.() -> Unit = {},
): HyperdriveMySqlDatabase = HyperdriveMySqlDatabase.create(JsMySqlEndpoint(binding), configure)

@JsModule("mysql2/promise")
@JsNonModule
private external object MySqlModule {
    @Suppress("UnusedParameter")
    fun createConnection(config: dynamic): Promise<NativeMySqlConnection>
}

@JsModule("node:buffer")
@JsNonModule
private external object NodeBufferModule {
    val Buffer: NodeBufferConstructor
}

private external interface NodeBufferConstructor {
    fun from(value: Uint8Array): dynamic
}

private external interface NativeMySqlConnection {
    fun query(sql: String, values: Array<dynamic>): Promise<Array<dynamic>>
    fun end(): Promise<Unit>
}

private data class JsMySqlResult(val first: dynamic, val second: dynamic)

private class JsMySqlEndpoint(binding: CloudflareHyperdriveBinding) : MySqlEndpoint {
    private val connected = MySqlModule.createConnection(connectionConfig(binding))

    override suspend fun execute(statement: MySqlStatement): MySqlCommandResult =
        translate(MySqlFailureKind.EXECUTION) {
            val result = run(statement)
            MySqlCommandResult((result.first.affectedRows as? Number)?.toLong() ?: 0)
        }

    override suspend fun query(statement: MySqlStatement): MySqlRows = translate(MySqlFailureKind.RESULT_CONVERSION) {
        val result = run(statement)
        val fields = (result.second as Array<dynamic>).toList()
        val names = fields.map { it.name as String }
        val protocolTypes = fields.map { (it.columnType as Number).toInt() }
        val rows = (result.first as Array<Array<dynamic>>).map { row ->
            require(row.size == protocolTypes.size) { "MySQL result field count does not match row values" }
            row.mapIndexed { index, value -> MySqlResultValue(protocolTypes[index], toMySqlScalar(value)) }
        }
        MySqlRows(names, rows)
    }

    override suspend fun close() {
        translate(MySqlFailureKind.CONNECTION) { connected.await().end().await() }
    }

    private suspend fun run(statement: MySqlStatement): JsMySqlResult {
        val client = ensureConnected()
        val parameters = statement.parameters.map(::bindingValue).toTypedArray()
        val result = client.query(statement.sql, parameters).await()
        return JsMySqlResult(result[0], result[1])
    }

    private suspend fun ensureConnected(): NativeMySqlConnection = try {
        connected.await()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        throw error.asMySqlDriverException(MySqlFailureKind.CONNECTION)
    }
}

private fun bindingValue(value: MySqlBinding): dynamic = when (value) {
    MySqlBinding.Null -> null
    is MySqlBinding.Text -> value.value
    is MySqlBinding.BooleanValue -> value.value
    is MySqlBinding.Integer -> value.decimal
    is MySqlBinding.Real -> value.value
    is MySqlBinding.Bytes -> NodeBufferModule.Buffer.from(value.value.toUint8Array())
}

private fun toMySqlScalar(value: dynamic): MySqlScalar? = when {
    value == null -> null
    isUint8Array(value) -> MySqlScalar.Bytes(toByteArray(value))
    jsTypeOf(value) == "string" -> MySqlScalar.Text(value as String)
    jsTypeOf(value) == "number" -> MySqlScalar.Number(value.toString())
    jsTypeOf(value) == "boolean" -> MySqlScalar.BooleanValue(value as Boolean)
    else -> throw IllegalArgumentException("mysql2 returned unsupported ${jsTypeOf(value)} field value")
}

private external class Uint8Array(length: Int) {
    val length: Int
}

private fun ByteArray.toUint8Array(): Uint8Array {
    val output = Uint8Array(size)
    forEachIndexed { index, value -> setByte(output, index, value.toInt() and BYTE_MASK) }
    return output
}

private fun toByteArray(value: dynamic): ByteArray = ByteArray(value.length as Int) { index ->
    ((value[index] as Number).toInt() and BYTE_MASK).toByte()
}

private suspend inline fun <T> translate(kind: MySqlFailureKind, crossinline block: suspend () -> T): T = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: MySqlDriverException) {
    throw error
} catch (error: Throwable) {
    throw error.asMySqlDriverException(kind)
}

private fun Throwable.asMySqlDriverException(kind: MySqlFailureKind): MySqlDriverException {
    val native = asDynamic()
    val vendorCode = native.code as? String
    val sqlState = native.sqlState as? String
    val errorNumber = (native.errno as? Number)?.toInt()
    val detail = native.sqlMessage as? String ?: native.message as? String ?: message ?: toString()
    val attributes = buildMap {
        errorNumber?.let { put("errno", it.toString()) }
        (native.sql as? String)?.let { put("sql", it) }
    }
    return MySqlDriverException(kind, sqlState, vendorCode, errorNumber, attributes, detail, this)
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
private fun connectionConfig(binding: CloudflareHyperdriveBinding): dynamic = js(
    "({ host: binding.host, user: binding.user, password: binding.password, database: binding.database, " +
        "port: binding.port, disableEval: true, rowsAsArray: true, supportBigNumbers: true, " +
        "bigNumberStrings: true, jsonStrings: true, dateStrings: true, timezone: 'Z', " +
        "typeCast: (field, next) => field.type === 'GEOMETRY' ? field.buffer() : next() })",
)

@Suppress("UnusedParameter")
private fun isUint8Array(value: dynamic): Boolean = js("value instanceof Uint8Array")

@Suppress("UnusedParameter")
private fun setByte(output: Uint8Array, index: Int, value: Int) {
    js("output[index] = value")
}

private const val BYTE_MASK = 0xff
