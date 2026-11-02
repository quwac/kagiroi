@file:Suppress(
    "MaximumLineLength",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
    "UnsafeCastFromDynamic",
    "UnusedParameter",
)

package dev.quwac.kagiroi.oracle.node

import dev.quwac.kagiroi.oracle.OracleBinding
import dev.quwac.kagiroi.oracle.OracleResultValue
import dev.quwac.kagiroi.oracle.OracleScalar
import dev.quwac.kagiroi.oracle.OracleType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Promise

@JsModule("oracledb")
@JsNonModule
private external object OracleModule {
    fun createPool(options: dynamic): Promise<NativeOraclePool>
    val OUT_FORMAT_ARRAY: dynamic
    val DB_TYPE_NUMBER: dynamic
    val DB_TYPE_BOOLEAN: dynamic
    val DB_TYPE_RAW: dynamic
    val DB_TYPE_BLOB: dynamic
    val DB_TYPE_CLOB: dynamic
    val DB_TYPE_NCLOB: dynamic
    val DB_TYPE_DATE: dynamic
    val DB_TYPE_TIMESTAMP: dynamic
    val DB_TYPE_TIMESTAMP_TZ: dynamic
    val DB_TYPE_TIMESTAMP_LTZ: dynamic
    val BIND_OUT: dynamic
    val STRING: dynamic
    val BUFFER: dynamic
}

@JsModule("node:buffer")
@JsNonModule
private external object NodeBufferModule {
    val Buffer: NodeBufferConstructor
}

private external interface NodeBufferConstructor {
    fun from(value: Uint8Array): dynamic
}

private external interface NativeOraclePool {
    fun getConnection(): Promise<NativeOracleConnection>
    fun close(): Promise<Unit>
}

private external interface NativeOracleConnection {
    fun execute(sql: String, binds: dynamic = definedExternally, options: dynamic = definedExternally): Promise<dynamic>
    fun commit(): Promise<Unit>
    fun close(options: dynamic = definedExternally): Promise<Unit>
}

internal actual fun createNodeOraclePool(settings: NodeOracleSettings): NodeOraclePool =
    JsNodeOraclePool(OracleModule.createPool(poolConfig(settings)))

private class JsNodeOraclePool(private val pool: Promise<NativeOraclePool>) : NodeOraclePool {
    override suspend fun acquire(): NodeOracleSession = translate(OracleFailureKind.CONNECTION) {
        val connection = pool.awaitCompletion().getConnection().awaitConnection()
        try {
            SESSION_SETTINGS.forEach { statement ->
                connection.execute(statement, js("({})"), js("({})")).awaitCompletion()
            }
        } catch (error: Throwable) {
            connection.close().awaitCompletion()
            throw error
        }
        JsNodeOracleSession(connection)
    }

    override suspend fun close() {
        translate(OracleFailureKind.CONNECTION) { pool.awaitCompletion().close().awaitCompletion() }
    }
}

private class JsNodeOracleSession(private val connection: NativeOracleConnection) : NodeOracleSession {
    override suspend fun execute(statement: OracleStatement): OracleCommandResult {
        val result = translate(OracleFailureKind.EXECUTION) {
            connection.execute(statement.sql, bindings(statement), commandOptions()).awaitCompletion()
        }
        return translate(OracleFailureKind.RESULT_CONVERSION) {
            OracleCommandResult(
                affectedRows = (result.rowsAffected as? Number)?.toLong() ?: 0,
                returningValues = decodeReturningValues(result, statement.returning),
            )
        }
    }

    override suspend fun query(statement: OracleStatement): OracleRows {
        val result = translate(OracleFailureKind.EXECUTION) {
            connection.execute(statement.sql, bindings(statement), queryOptions()).awaitCompletion()
        }
        return translate(OracleFailureKind.RESULT_CONVERSION) { decodeRows(result) }
    }

    override suspend fun commit() {
        translate(OracleFailureKind.EXECUTION) { connection.commit().awaitCompletion() }
    }

    override suspend fun release(discard: Boolean) {
        translate(OracleFailureKind.CONNECTION) {
            connection.close(closeOptions(discard)).awaitCompletion()
        }
    }
}

private fun poolConfig(settings: NodeOracleSettings): dynamic {
    val config = js("({})")
    config.connectString = settings.connectString
    config.user = settings.user
    config.password = settings.password
    config.poolMin = settings.pool.minConnections
    config.poolMax = settings.pool.maxConnections
    config.poolIncrement = settings.pool.increment
    config.queueTimeout = settings.pool.acquisitionTimeoutMilliseconds
    config.poolTimeout = settings.pool.idleTimeoutSeconds
    return config
}

private fun bindings(statement: OracleStatement): dynamic {
    val output = js("({})")
    statement.parameters.forEachIndexed { index, value -> output["p${index + 1}"] = bindingValue(value) }
    statement.returning.forEach { binding -> output[binding.name] = returningBindingValue(binding) }
    return output
}

private fun returningBindingValue(binding: OracleReturningBinding): dynamic {
    val output = js("({})")
    output.dir = OracleModule.BIND_OUT
    output.type = when (binding.kind) {
        OracleReturningKind.TEXT,
        OracleReturningKind.DECIMAL,
        -> OracleModule.STRING

        OracleReturningKind.BOOLEAN -> OracleModule.DB_TYPE_BOOLEAN

        OracleReturningKind.BYTES -> OracleModule.BUFFER
    }
    binding.maxSize?.let { output.maxSize = it }
    return output
}

private fun bindingValue(value: OracleBinding): dynamic = when (value) {
    OracleBinding.Null -> null

    is OracleBinding.Text -> typedBinding(value.value, textType(value.type))

    // Bind decimal text without a JS Number so values above 53 bits and exact decimal scales survive.
    // The session fixes NLS_NUMERIC_CHARACTERS to make Oracle's server-side conversion deterministic.
    is OracleBinding.Decimal -> value.value

    is OracleBinding.BooleanValue -> typedBinding(value.value, OracleModule.DB_TYPE_BOOLEAN)

    is OracleBinding.Floating -> value.value

    is OracleBinding.Bytes -> typedBinding(
        NodeBufferModule.Buffer.from(value.value.toUint8Array()),
        bytesType(value.type),
    )

    is OracleBinding.Temporal -> temporalValue(value)
}

private fun textType(type: OracleType?): dynamic = when (type) {
    OracleType.CLOB -> OracleModule.DB_TYPE_CLOB
    OracleType.NCLOB -> OracleModule.DB_TYPE_NCLOB
    else -> null
}

private fun bytesType(type: OracleType): dynamic = when (type) {
    OracleType.BLOB -> OracleModule.DB_TYPE_BLOB
    else -> OracleModule.DB_TYPE_RAW
}

private fun temporalValue(binding: OracleBinding.Temporal): String = when (binding.type) {
    OracleType.TIMESTAMP_WITH_TIME_ZONE,
    OracleType.TIMESTAMP_WITH_LOCAL_TIME_ZONE,
    -> binding.iso8601.removeSuffix("Z") + if (binding.iso8601.endsWith("Z")) "+00:00" else ""

    else -> binding.iso8601
}

private fun typedBinding(value: dynamic, type: dynamic): dynamic {
    if (type == null) return value
    val binding = js("({})")
    binding.`val` = value
    binding.type = type
    return binding
}

private fun commandOptions(): dynamic = js("({})")

private fun queryOptions(): dynamic = queryOptionsFor(OracleModule)

@Suppress("UnusedParameter")
private fun queryOptionsFor(module: dynamic): dynamic = js(
    "({ outFormat: module.OUT_FORMAT_ARRAY, fetchTypeHandler: function(metadata) { " +
        "if (metadata.dbType === module.DB_TYPE_NUMBER) return { type: module.STRING }; " +
        "if (metadata.dbType === module.DB_TYPE_RAW || metadata.dbType === module.DB_TYPE_BLOB) return { type: module.BUFFER }; " +
        "if (metadata.dbType === module.DB_TYPE_CLOB || metadata.dbType === module.DB_TYPE_NCLOB) return { type: module.STRING }; " +
        "} })",
)

private fun decodeRows(result: dynamic): OracleRows {
    val fields = (result.metaData as? Array<dynamic>)?.toList().orEmpty()
    val names = fields.map { it.name as? String ?: error("Oracle result metadata has no column name") }
    val numberColumns = fields.map { it.dbType === OracleModule.DB_TYPE_NUMBER }
    val rows = (result.rows as? Array<Array<dynamic>>)?.map { row ->
        require(row.size == names.size) { "Oracle result field count does not match row values" }
        row.mapIndexed { index, value -> scalar(value, numberColumns[index]) }
    }.orEmpty()
    return OracleRows(names, rows)
}

private fun decodeReturningValues(
    result: dynamic,
    bindings: List<OracleReturningBinding>,
): List<List<OracleResultValue>> = bindings.map { binding ->
    val values = result.outBinds[binding.name] as? Array<dynamic>
        ?: throw IllegalArgumentException("Oracle DML RETURNING INTO did not return bind: ${binding.name}")
    values.map { value -> returningValue(value, binding) }
}

private fun returningValue(value: dynamic, binding: OracleReturningBinding): OracleResultValue = OracleResultValue(
    when (binding.kind) {
        OracleReturningKind.TEXT -> returningText(value)
        OracleReturningKind.DECIMAL -> returningDecimal(value)
        OracleReturningKind.BOOLEAN -> returningBoolean(value)
        OracleReturningKind.BYTES -> returningBytes(value)
    },
)

private fun returningText(value: dynamic): OracleScalar? = when {
    value == null -> null
    jsTypeOf(value) == "string" -> OracleScalar.Text(value as String)
    else -> throw IllegalArgumentException("Oracle DML RETURNING INTO text bind is not a string")
}

private fun returningDecimal(value: dynamic): OracleScalar? = when {
    value == null -> null
    jsTypeOf(value) == "string" -> OracleScalar.Decimal(value as String)
    else -> throw IllegalArgumentException("Oracle DML RETURNING INTO number bind is not decimal text")
}

private fun returningBoolean(value: dynamic): OracleScalar? = when {
    value == null -> null
    jsTypeOf(value) == "boolean" -> OracleScalar.BooleanValue(value as Boolean)
    else -> throw IllegalArgumentException("Oracle DML RETURNING INTO boolean bind is not a boolean")
}

private fun returningBytes(value: dynamic): OracleScalar? = when {
    value == null -> null
    isByteArray(value) -> OracleScalar.Bytes(toByteArray(value))
    else -> throw IllegalArgumentException("Oracle DML RETURNING INTO binary bind is not a Buffer")
}

private fun scalar(value: dynamic, numberColumn: Boolean): OracleResultValue = OracleResultValue(
    when {
        value == null -> null

        numberColumn -> require(jsTypeOf(value) == "string") {
            "node-oracledb returned Oracle NUMBER as ${jsTypeOf(value)}, not lossless decimal text"
        }.let { OracleScalar.Decimal(value as String) }

        jsTypeOf(value) == "string" -> OracleScalar.Text(value as String)

        jsTypeOf(value) == "boolean" -> OracleScalar.BooleanValue(value as Boolean)

        isByteArray(value) -> OracleScalar.Bytes(toByteArray(value))

        // NUMBER columns are converted to text by queryOptionsFor. A remaining JavaScript
        // Number therefore represents BINARY_FLOAT or BINARY_DOUBLE, whose IEEE value is
        // already the native representation required by the logical floating column type.
        jsTypeOf(value) == "number" -> OracleScalar.Floating(value as Double)

        else -> throw IllegalArgumentException("node-oracledb returned unsupported ${jsTypeOf(value)} result value")
    },
)

private external class Uint8Array(length: Int) {
    val length: Int
}

private fun ByteArray.toUint8Array(): Uint8Array {
    val output = Uint8Array(size)
    forEachIndexed { index, value -> setByte(output, index, value.toInt() and BYTE_MASK) }
    return output
}

private fun isByteArray(value: dynamic): Boolean = js("value instanceof Uint8Array || Buffer.isBuffer(value)")

private fun toByteArray(value: dynamic): ByteArray = ByteArray(value.length as Int) { index ->
    ((value[index] as Number).toInt() and BYTE_MASK).toByte()
}

@Suppress("UnusedParameter")
private fun setByte(output: Uint8Array, index: Int, value: Int) {
    js("output[index] = value")
}

private fun closeOptions(discard: Boolean): dynamic = if (discard) js("({ drop: true })") else js("({})")

private suspend inline fun <T> translate(kind: OracleFailureKind, crossinline block: suspend () -> T): T = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: OracleDriverException) {
    throw error
} catch (error: Throwable) {
    throw error.asOracleDriverException(kind)
}

private fun Throwable.asOracleDriverException(kind: OracleFailureKind): OracleDriverException {
    val native = asDynamic()
    val errorNumber = (native.errorNum as? Number)?.toInt()
    val sqlState = native.code as? String
    val detail = native.message as? String ?: message ?: toString()
    val attributes = buildMap {
        errorNumber?.let { put("errorNum", it.toString()) }
        (native.offset as? Number)?.let { put("offset", it.toInt().toString()) }
        (native.isRecoverable as? Boolean)?.let { put("isRecoverable", it.toString()) }
        (native.cause as? String)?.let { put("cause", it) }
    }
    val classified = if (errorNumber == INVALID_CREDENTIALS) OracleFailureKind.AUTHENTICATION else kind
    return OracleDriverException(classified, errorNumber, sqlState, attributes, detail, this)
}

private suspend fun Promise<NativeOracleConnection>.awaitConnection(): NativeOracleConnection =
    suspendCancellableCoroutine { continuation ->
        then { connection ->
            if (continuation.isActive) continuation.resume(connection) else connection.close(closeOptions(false))
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

private const val BYTE_MASK = 0xff
private const val INVALID_CREDENTIALS = 1017
private val SESSION_SETTINGS = listOf(
    "ALTER SESSION SET NLS_NUMERIC_CHARACTERS = '.,'",
    "ALTER SESSION SET NLS_DATE_FORMAT = 'YYYY-MM-DD'",
    "ALTER SESSION SET NLS_TIMESTAMP_FORMAT = 'YYYY-MM-DD\"T\"HH24:MI:SS.FF9'",
    "ALTER SESSION SET NLS_TIMESTAMP_TZ_FORMAT = 'YYYY-MM-DD\"T\"HH24:MI:SS.FF9TZH:TZM'",
)
