@file:Suppress("TooGenericExceptionCaught", "TooManyFunctions", "UnsafeCastFromDynamic")

package dev.quwac.kagiroi.mysql.node

import dev.quwac.kagiroi.mysql.MySqlBinding
import dev.quwac.kagiroi.mysql.MySqlResultValue
import dev.quwac.kagiroi.mysql.MySqlScalar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Promise

@JsModule("mysql2/promise")
@JsNonModule
private external object MySqlModule {
    @Suppress("UnusedParameter")
    fun createPool(config: dynamic): NativeMySqlPool
}

@JsModule("node:buffer")
@JsNonModule
private external object NodeBufferModule {
    val Buffer: NodeBufferConstructor
}

private external interface NodeBufferConstructor {
    fun from(value: Uint8Array): dynamic
}

private external interface NativeMySqlPool {
    fun getConnection(): Promise<NativeMySqlConnection>
    fun end(): Promise<Unit>
}

private external interface NativeMySqlConnection {
    fun query(sql: String, values: Array<dynamic>): Promise<Array<dynamic>>
    fun execute(sql: String, values: Array<dynamic>): Promise<Array<dynamic>>
    fun release()
    fun destroy()
}

internal actual fun createNodeMySqlPool(settings: NodeMySqlSettings): NodeMySqlPool =
    JsNodeMySqlPool(MySqlModule.createPool(poolConfig(settings)))

private class JsNodeMySqlPool(private val pool: NativeMySqlPool) : NodeMySqlPool {
    override suspend fun acquire(timeoutMilliseconds: Int): NodeMySqlSession = translate(MySqlFailureKind.CONNECTION) {
        JsNodeMySqlSession(connectionWithTimeout(pool, timeoutMilliseconds).awaitConnection())
    }

    override suspend fun close() {
        translate(MySqlFailureKind.CONNECTION) { pool.end().awaitCompletion() }
    }
}

private data class JsMySqlResult(val first: dynamic, val second: dynamic)

private class JsNodeMySqlSession(private val connection: NativeMySqlConnection) : NodeMySqlSession {
    override suspend fun execute(statement: MySqlStatement): MySqlCommandResult {
        val result = run(statement)
        return translate(MySqlFailureKind.RESULT_CONVERSION) {
            MySqlCommandResult(safeLongProperty(result.first, "affectedRows") ?: 0)
        }
    }

    override suspend fun query(statement: MySqlStatement): MySqlRows {
        val result = run(statement)
        return translate(MySqlFailureKind.RESULT_CONVERSION) {
            val fields = (result.second as Array<dynamic>).toList()
            val names = fields.map { it.name as String }
            val protocolTypes = fields.map { (it.columnType as Number).toInt() }
            val rows = (result.first as Array<Array<dynamic>>).map { row ->
                require(row.size == protocolTypes.size) { "MySQL result field count does not match row values" }
                row.mapIndexed { index, value -> MySqlResultValue(protocolTypes[index], toMySqlScalar(value)) }
            }
            MySqlRows(names, rows)
        }
    }

    override fun release(discard: Boolean) {
        if (discard) connection.destroy() else connection.release()
    }

    private suspend fun run(statement: MySqlStatement): JsMySqlResult = translate(MySqlFailureKind.EXECUTION) {
        val parameters = statement.parameters.map(::bindingValue).toTypedArray()
        val result = if (parameters.isEmpty()) {
            connection.query(statement.sql, parameters).awaitCompletion()
        } else {
            connection.execute(statement.sql, parameters).awaitCompletion()
        }
        JsMySqlResult(result[0], result[1])
    }
}

private fun poolConfig(settings: NodeMySqlSettings): dynamic {
    val config = js("({})")
    settings.connectionUrl?.let { config.uri = it }
    settings.host?.let { config.host = it }
    settings.port?.let { config.port = it }
    settings.database?.let { config.database = it }
    settings.user?.let { config.user = it }
    settings.password?.let { config.password = it }
    config.waitForConnections = true
    config.connectionLimit = settings.pool.maxConnections
    config.maxIdle = settings.pool.maxIdleConnections
    config.connectTimeout = settings.pool.connectionTimeoutMilliseconds
    config.idleTimeout = settings.pool.idleTimeoutMilliseconds
    config.queueLimit = 0
    config.disableEval = true
    config.rowsAsArray = true
    config.supportBigNumbers = true
    config.bigNumberStrings = true
    config.decimalNumbers = false
    config.jsonStrings = true
    config.dateStrings = true
    config.timezone = "Z"
    // mysql2 otherwise materializes GEOMETRY fields as JavaScript objects. Kagiroi
    // transports the server's SRID-prefixed WKB bytes consistently on JS and wasmJs.
    config.typeCast = js("(field, next) => field.type === 'GEOMETRY' ? field.buffer() : next()")
    settings.tls?.let { config.ssl = tlsConfig(it) }
    return config
}

private fun tlsConfig(settings: NodeMySqlTlsSettings): dynamic = when (settings.mode) {
    NodeMySqlTlsMode.DISABLED -> false

    NodeMySqlTlsMode.REQUIRE -> tlsObject(false, false, null, settings.clientCertificate, settings.clientPrivateKey)

    NodeMySqlTlsMode.VERIFY_FULL -> tlsObject(
        rejectUnauthorized = true,
        verifyIdentity = true,
        certificateAuthority = settings.certificateAuthority,
        clientCertificate = settings.clientCertificate,
        clientPrivateKey = settings.clientPrivateKey,
    )
}

private fun tlsObject(
    rejectUnauthorized: Boolean,
    verifyIdentity: Boolean,
    certificateAuthority: String?,
    clientCertificate: String?,
    clientPrivateKey: String?,
): dynamic {
    val config = js("({})")
    config.rejectUnauthorized = rejectUnauthorized
    config.verifyIdentity = verifyIdentity
    certificateAuthority?.let { config.ca = it }
    clientCertificate?.let { config.cert = it }
    clientPrivateKey?.let { config.key = it }
    return config
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

    jsTypeOf(value) == "number" -> {
        require(isFiniteNumber(value)) { "mysql2 returned a non-finite numeric field value" }
        MySqlScalar.Number(value.toString())
    }

    jsTypeOf(value) == "boolean" -> MySqlScalar.BooleanValue(value as Boolean)

    else -> throw IllegalArgumentException("mysql2 returned unsupported ${jsTypeOf(value)} field value")
}

private fun safeLongProperty(value: dynamic, name: String): Long? {
    if (value == null) return null
    val number = value[name] as? Number ?: return null
    require(isSafeInteger(number)) { "mysql2 returned an unsafe $name value" }
    return number.toLong()
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
        (native.sqlMessage as? String)?.let { put("sqlMessage", it) }
        (native.fatal as? Boolean)?.let { put("fatal", it.toString()) }
        (native.timeoutMilliseconds as? Number)?.let { put("timeoutMilliseconds", it.toInt().toString()) }
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

@Suppress("UnusedParameter")
private fun connectionWithTimeout(pool: NativeMySqlPool, timeoutMilliseconds: Int): Promise<NativeMySqlConnection> = js(
    """
    (() => {
      const pending = pool.getConnection();
      if (timeoutMilliseconds === 0) return pending;
      return new Promise((resolve, reject) => {
        let settled = false;
        const timer = setTimeout(() => {
          settled = true;
          const error = new Error('Timed out waiting for a MySQL pool connection');
          error.code = 'KAGIROI_POOL_ACQUISITION_TIMEOUT';
          error.timeoutMilliseconds = timeoutMilliseconds;
          reject(error);
        }, timeoutMilliseconds);
        pending.then(
          (connection) => {
            if (settled) {
              connection.release();
            } else {
              settled = true;
              clearTimeout(timer);
              resolve(connection);
            }
          },
          (error) => {
            if (!settled) {
              settled = true;
              clearTimeout(timer);
              reject(error);
            }
          },
        );
      });
    })()
    """,
)

@Suppress("UnusedParameter")
private fun isUint8Array(value: dynamic): Boolean = js("value instanceof Uint8Array")

@Suppress("UnusedParameter")
private fun isSafeInteger(value: Number): Boolean = js("Number.isSafeInteger(value)")

@Suppress("UnusedParameter")
private fun isFiniteNumber(value: dynamic): Boolean = js("Number.isFinite(value)")

@Suppress("UnusedParameter")
private fun setByte(output: Uint8Array, index: Int, value: Int) {
    js("output[index] = value")
}

private const val BYTE_MASK = 0xff
