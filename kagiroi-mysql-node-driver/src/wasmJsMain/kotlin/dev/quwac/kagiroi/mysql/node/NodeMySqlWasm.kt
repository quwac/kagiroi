@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("FunctionNaming", "PropertyName", "TooGenericExceptionCaught")

package dev.quwac.kagiroi.mysql.node

import dev.quwac.kagiroi.mysql.MySqlBinding
import dev.quwac.kagiroi.mysql.MySqlResultValue
import dev.quwac.kagiroi.mysql.MySqlScalar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
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

@JsModule("mysql2/promise")
private external object MySqlModule : JsAny {
    @Suppress("UnusedParameter")
    fun createPool(config: JsAny): NativeMySqlPool
}

@JsModule("node:buffer")
private external object NodeBufferModule : JsAny {
    val Buffer: NodeBufferConstructor
}

private external interface NodeBufferConstructor : JsAny {
    fun from(value: Uint8Array): JsAny
}

internal external interface NativeMySqlPool : JsAny {
    fun getConnection(): Promise<NativeMySqlConnection>
    fun end(): Promise<JsAny?>
}

internal external interface NativeMySqlConnection : JsAny {
    fun query(sql: String, values: JsArray<JsAny?>): Promise<JsArray<JsAny?>>
    fun execute(sql: String, values: JsArray<JsAny?>): Promise<JsArray<JsAny?>>
    fun release()
    fun destroy()
}

internal external interface NativeMySqlField : JsAny {
    val name: String
    val columnType: Int
}

internal actual fun createNodeMySqlPool(settings: NodeMySqlSettings): NodeMySqlPool =
    WasmNodeMySqlPool(MySqlModule.createPool(poolConfig(settings)))

private class WasmNodeMySqlPool(private val pool: NativeMySqlPool) : NodeMySqlPool {
    override suspend fun acquire(timeoutMilliseconds: Int): NodeMySqlSession = translate(MySqlFailureKind.CONNECTION) {
        WasmNodeMySqlSession(connectionWithTimeout(pool, timeoutMilliseconds).awaitConnection())
    }

    override suspend fun close() {
        translate(MySqlFailureKind.CONNECTION) { pool.end().awaitCompletion() }
    }
}

private class WasmNodeMySqlSession(private val connection: NativeMySqlConnection) : NodeMySqlSession {
    override suspend fun execute(statement: MySqlStatement): MySqlCommandResult {
        val result = run(statement)
        return translate(MySqlFailureKind.RESULT_CONVERSION) {
            MySqlCommandResult(result.first?.let { safeLongProperty(it, "affectedRows") } ?: 0)
        }
    }

    override suspend fun query(statement: MySqlStatement): MySqlRows {
        val result = run(statement)
        return translate(MySqlFailureKind.RESULT_CONVERSION) {
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
    }

    override fun release(discard: Boolean) {
        if (discard) connection.destroy() else connection.release()
    }

    private suspend fun run(statement: MySqlStatement): Pair<JsAny?, JsAny?> = translate(MySqlFailureKind.EXECUTION) {
        val parameters = statement.parameters.map(::bindingValue).toJsArray()
        val result = if (statement.parameters.isEmpty()) {
            connection.query(statement.sql, parameters).awaitCompletion()
        } else {
            connection.execute(statement.sql, parameters).awaitCompletion()
        }.toList()
        result.getOrNull(0) to result.getOrNull(1)
    }
}

private fun poolConfig(settings: NodeMySqlSettings): JsAny = poolConfig(
    connectionUrl = settings.connectionUrl,
    host = settings.host,
    port = settings.port,
    database = settings.database,
    user = settings.user,
    password = settings.password,
    maxConnections = settings.pool.maxConnections,
    maxIdleConnections = settings.pool.maxIdleConnections,
    connectionTimeoutMilliseconds = settings.pool.connectionTimeoutMilliseconds,
    idleTimeoutMilliseconds = settings.pool.idleTimeoutMilliseconds,
    tlsMode = settings.tls?.mode?.name,
    certificateAuthority = settings.tls?.certificateAuthority,
    clientCertificate = settings.tls?.clientCertificate,
    clientPrivateKey = settings.tls?.clientPrivateKey,
)

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

    jsType(this) == "number" -> {
        require(isFiniteNumber(this)) { "mysql2 returned a non-finite numeric field value" }
        MySqlScalar.Number(numberString(this))
    }

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

@Suppress("CyclomaticComplexMethod")
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
        thrown?.let { stringProperty(it, "sqlMessage") }?.let { put("sqlMessage", it) }
        thrown?.let { booleanProperty(it, "fatal") }?.let { put("fatal", it.toString()) }
        thrown?.let { numberProperty(it, "timeoutMilliseconds") }?.let {
            put("timeoutMilliseconds", it.toInt().toString())
        }
    }
    return MySqlDriverException(kind, sqlState, vendorCode, errorNumber, attributes, detail, this)
}

private suspend fun Promise<NativeMySqlConnection>.awaitConnection(): NativeMySqlConnection =
    suspendCancellableCoroutine { continuation ->
        then { connection ->
            if (continuation.isActive) {
                continuation.resume(connection)
            } else {
                connection.release()
            }
            connection
        }.catch { error ->
            if (continuation.isActive) continuation.resumeWithException(error.asJsException())
            null
        }
    }

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
