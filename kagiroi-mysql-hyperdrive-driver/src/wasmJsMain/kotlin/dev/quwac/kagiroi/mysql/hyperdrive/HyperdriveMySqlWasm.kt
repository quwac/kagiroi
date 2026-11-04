@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("FunctionNaming", "TooGenericExceptionCaught")

package dev.quwac.kagiroi.mysql.hyperdrive

import dev.quwac.kagiroi.mysql.MySqlBinding
import dev.quwac.kagiroi.mysql.MySqlResultValue
import dev.quwac.kagiroi.mysql.MySqlScalar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsException
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.asJsException
import kotlin.js.toJsArray
import kotlin.js.toJsBoolean
import kotlin.js.toJsNumber
import kotlin.js.toJsString
import kotlin.js.unsafeCast

/** The Cloudflare Workers Hyperdrive binding passed through an environment object. */
external interface CloudflareHyperdriveBinding : JsAny {
    /** Value associated with host. */
    val host: String

    /** Value associated with user. */
    val user: String

    /** Value associated with password. */
    val password: String

    /** Value associated with database. */
    val database: String

    /** Value associated with port. */
    val port: Int
}

/** Provides the hyperdrive my sql database result. */
fun HyperdriveMySqlDatabase(
    binding: CloudflareHyperdriveBinding,
    configure: HyperdriveMySqlDatabaseConfiguration.() -> Unit = {},
): HyperdriveMySqlDatabase = HyperdriveMySqlDatabase.create(WasmMySqlEndpoint(binding), configure)

@JsModule("mysql2/promise")
private external object MySqlModule : JsAny {
    @Suppress("UnusedParameter")
    fun createConnection(config: JsAny): Promise<NativeMySqlConnection>
}

@JsModule("node:buffer")
private external object NodeBufferModule : JsAny {
    val Buffer: NodeBufferConstructor
}

private external interface NodeBufferConstructor : JsAny {
    fun from(value: Uint8Array): JsAny
}

internal external interface NativeMySqlConnection : JsAny {
    fun query(sql: String, values: JsArray<JsAny?>): Promise<JsArray<JsAny?>>
    fun end(): Promise<JsAny?>
}

internal external interface NativeMySqlField : JsAny {
    val name: String
    val columnType: Int
}

private class WasmMySqlEndpoint(binding: CloudflareHyperdriveBinding) : MySqlEndpoint {
    private val connected = MySqlModule.createConnection(connectionConfig(binding))

    override suspend fun execute(statement: MySqlStatement): MySqlCommandResult =
        translate(MySqlFailureKind.EXECUTION) {
            val result = run(statement)
            MySqlCommandResult(numberProperty(requireNotNull(result.first), "affectedRows")?.toLong() ?: 0)
        }

    override suspend fun query(statement: MySqlStatement): MySqlRows = translate(MySqlFailureKind.RESULT_CONVERSION) {
        val result = run(statement)
        val fields = requireNotNull(result.second)
            .unsafeCast<JsArray<NativeMySqlField>>()
            .toList()
            .filterNotNull()
        val protocolTypes = fields.map(NativeMySqlField::columnType)
        val rows = requireNotNull(result.first)
            .unsafeCast<JsArray<JsArray<JsAny?>>>()
            .toList()
            .filterNotNull()
            .map { row ->
                val nativeValues = row.toList()
                require(nativeValues.size == protocolTypes.size) {
                    "MySQL result field count does not match row values"
                }
                nativeValues.mapIndexed { index, value ->
                    MySqlResultValue(protocolTypes[index], value.toMySqlScalar())
                }
            }
        MySqlRows(fields.map(NativeMySqlField::name), rows)
    }

    override suspend fun close() {
        translate(MySqlFailureKind.CONNECTION) { connected.await().end().await() }
    }

    private suspend fun run(statement: MySqlStatement): Pair<JsAny?, JsAny?> {
        val client = ensureConnected()
        val parameters = statement.parameters.map(::bindingValue).toJsArray()
        val result = client.query(statement.sql, parameters).await().toList()
        return result.getOrNull(0) to result.getOrNull(1)
    }

    private suspend fun ensureConnected(): NativeMySqlConnection = try {
        connected.await()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        throw error.asMySqlDriverException(MySqlFailureKind.CONNECTION)
    }
}

private fun bindingValue(value: MySqlBinding): JsAny? = when (value) {
    MySqlBinding.Null -> null
    is MySqlBinding.Text -> value.value.toJsString()
    is MySqlBinding.BooleanValue -> value.value.toJsBoolean()
    is MySqlBinding.Integer -> value.decimal.toJsString()
    is MySqlBinding.Real -> value.value.toJsNumber()
    is MySqlBinding.Bytes -> NodeBufferModule.Buffer.from(value.value.toUint8Array())
}

private fun JsAny?.toMySqlScalar(): MySqlScalar? = when {
    this == null -> null
    isUint8Array(this) -> MySqlScalar.Bytes(toByteArray(this))
    jsType(this) == "string" -> MySqlScalar.Text(unsafeCast<JsString>().toString())
    jsType(this) == "number" -> MySqlScalar.Number(numberString(this))
    jsType(this) == "boolean" -> MySqlScalar.BooleanValue(booleanValue(this))
    else -> throw IllegalArgumentException("mysql2 returned unsupported ${jsType(this)} field value")
}

private external class Uint8Array(length: Int) : JsAny {
    val length: Int
    operator fun get(index: Int): Int
    operator fun set(index: Int, value: Int)
}

private fun ByteArray.toUint8Array(): Uint8Array = Uint8Array(size).also { output ->
    forEachIndexed { index, value -> output[index] = value.toInt() and BYTE_MASK }
}

private fun toByteArray(value: JsAny): ByteArray {
    val bytes = value.unsafeCast<Uint8Array>()
    return ByteArray(bytes.length) { index -> (bytes[index] and BYTE_MASK).toByte() }
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
    val thrown = (this as? JsException)?.thrownValue
    val vendorCode = thrown?.let { stringProperty(it, "code") }
    val sqlState = thrown?.let { stringProperty(it, "sqlState") }
    val errorNumber = thrown?.let { numberProperty(it, "errno") }?.toInt()
    val detail = thrown?.let { stringProperty(it, "sqlMessage") ?: stringProperty(it, "message") }
        ?: message
        ?: toString()
    val attributes = buildMap {
        errorNumber?.let { put("errno", it.toString()) }
        thrown?.let { stringProperty(it, "sql") }?.let { put("sql", it) }
    }
    return MySqlDriverException(kind, sqlState, vendorCode, errorNumber, attributes, detail, this)
}

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
