@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("FunctionNaming", "PropertyName", "TooGenericExceptionCaught", "TooManyFunctions", "UnusedParameter")

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
import kotlin.js.JsAny
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

@JsModule("oracledb")
private external object OracleModuleImport : JsAny

@JsModule("node:buffer")
private external object NodeBufferModule : JsAny {
    val Buffer: NodeBufferConstructor
}

private external interface OracleModule : JsAny {
    fun createPool(options: JsAny): Promise<NativeOraclePool>
}

private val oracleModule: OracleModule
    get() = oracleDefaultExport(OracleModuleImport).unsafeCast()

private external interface NodeBufferConstructor : JsAny {
    fun from(value: Uint8Array): JsAny
}

internal external interface NativeOraclePool : JsAny {
    fun getConnection(): Promise<NativeOracleConnection>
    fun close(): Promise<JsAny?>
}

internal external interface NativeOracleConnection : JsAny {
    fun execute(sql: String, binds: JsAny, options: JsAny): Promise<JsAny>
    fun commit(): Promise<JsAny?>
    fun close(options: JsAny): Promise<JsAny?>
}

internal actual fun createNodeOraclePool(settings: NodeOracleSettings): NodeOraclePool = WasmNodeOraclePool(
    oracleModule.createPool(
        oraclePoolConfig(
            connectString = settings.connectString,
            user = settings.user,
            password = settings.password,
            minConnections = settings.pool.minConnections,
            maxConnections = settings.pool.maxConnections,
            increment = settings.pool.increment,
            acquisitionTimeoutMilliseconds = settings.pool.acquisitionTimeoutMilliseconds,
            idleTimeoutSeconds = settings.pool.idleTimeoutSeconds,
        ),
    ),
)

private class WasmNodeOraclePool(private val pool: Promise<NativeOraclePool>) : NodeOraclePool {
    override suspend fun acquire(): NodeOracleSession = translate(OracleFailureKind.CONNECTION) {
        val connection = pool.awaitCompletion().getConnection().awaitConnection()
        try {
            SESSION_SETTINGS.forEach { statement ->
                connection.execute(statement, emptyOracleObject(), emptyOracleObject()).awaitCompletion()
            }
        } catch (error: Throwable) {
            connection.close(oracleCloseOptions(false)).awaitCompletion()
            throw error
        }
        WasmNodeOracleSession(connection)
    }

    override suspend fun close() {
        translate(OracleFailureKind.CONNECTION) { pool.awaitCompletion().close().awaitCompletion() }
    }
}

private class WasmNodeOracleSession(private val connection: NativeOracleConnection) : NodeOracleSession {
    override suspend fun execute(statement: OracleStatement): OracleCommandResult {
        val result = translate(OracleFailureKind.EXECUTION) {
            connection.execute(
                statement.sql,
                oracleBindings(statement),
                emptyOracleObject(),
            ).awaitCompletion()
        }
        return translate(OracleFailureKind.RESULT_CONVERSION) {
            OracleCommandResult(
                affectedRows = oracleRowsAffected(result)?.toLong() ?: 0,
                returningValues = decodeReturningValues(result, statement.returning),
            )
        }
    }

    override suspend fun query(statement: OracleStatement): OracleRows {
        val result = translate(OracleFailureKind.EXECUTION) {
            connection.execute(
                statement.sql,
                oracleBindings(statement),
                oracleQueryOptions(oracleModule),
            ).awaitCompletion()
        }
        return translate(OracleFailureKind.RESULT_CONVERSION) { decodeRows(result) }
    }

    override suspend fun commit() {
        translate(OracleFailureKind.EXECUTION) { connection.commit().awaitCompletion() }
    }

    override suspend fun release(discard: Boolean) {
        translate(OracleFailureKind.CONNECTION) {
            connection.close(oracleCloseOptions(discard)).awaitCompletion()
        }
    }
}

private data class OracleBoundValue(val value: JsAny?, val kind: String)

private fun oracleBindings(statement: OracleStatement): JsAny {
    val values = statement.parameters.map(::boundValue)
    return oracleBindings(
        values.map(OracleBoundValue::value).toJsArray(),
        values.map { it.kind.toJsString() }.toJsArray(),
        statement.returning.map(OracleReturningBinding::name).map(String::toJsString).toJsArray(),
        statement.returning.map(OracleReturningBinding::kind).map { it.name.toJsString() }.toJsArray(),
        statement.returning.map(OracleReturningBinding::maxSize).map { it?.toJsNumber() }.toJsArray(),
        oracleModule,
    )
}

private fun boundValue(value: OracleBinding): OracleBoundValue = when (value) {
    OracleBinding.Null -> OracleBoundValue(null, "NULL")
    is OracleBinding.Text -> OracleBoundValue(value.value.toJsString(), value.type.textKind())
    is OracleBinding.Decimal -> OracleBoundValue(value.value.toJsString(), "NUMBER_TEXT")
    is OracleBinding.BooleanValue -> OracleBoundValue(value.value.toJsBoolean(), "BOOLEAN")
    is OracleBinding.Floating -> OracleBoundValue(value.value.toJsNumber(), "FLOAT")
    is OracleBinding.Bytes -> bytesBoundValue(value)
    is OracleBinding.Temporal -> OracleBoundValue(temporalValue(value).toJsString(), "TEXT")
}

private fun bytesBoundValue(value: OracleBinding.Bytes): OracleBoundValue = OracleBoundValue(
    NodeBufferModule.Buffer.from(value.value.toUint8Array()),
    value.type.bytesKind(),
)

private fun OracleType?.textKind(): String = when (this) {
    OracleType.CLOB -> "CLOB"
    OracleType.NCLOB -> "NCLOB"
    else -> "TEXT"
}

private fun OracleType.bytesKind(): String = if (this == OracleType.BLOB) "BLOB" else "RAW"

private fun temporalValue(binding: OracleBinding.Temporal): String = when (binding.type) {
    OracleType.TIMESTAMP_WITH_TIME_ZONE,
    OracleType.TIMESTAMP_WITH_LOCAL_TIME_ZONE,
    -> binding.iso8601.removeSuffix("Z") + if (binding.iso8601.endsWith("Z")) "+00:00" else ""

    else -> binding.iso8601
}

private fun decodeRows(result: JsAny): OracleRows {
    val metadata = oracleMetadata(result).toList().filterNotNull()
    val names = metadata.map(::oracleColumnName)
    val numberColumns = metadata.map { oracleIsNumberColumn(it, oracleModule) }
    val rows = oracleResultRows(result).toList().filterNotNull().map { row ->
        val values = row.toList()
        require(values.size == names.size) { "Oracle result field count does not match row values" }
        values.mapIndexed { index, value -> scalar(value, numberColumns[index]) }
    }
    return OracleRows(names, rows)
}

private fun decodeReturningValues(
    result: JsAny,
    bindings: List<OracleReturningBinding>,
): List<List<OracleResultValue>> = bindings.map { binding ->
    oracleReturningValues(result, binding.name).toList().map { value -> returningValue(value, binding) }
}

private fun returningValue(value: JsAny?, binding: OracleReturningBinding): OracleResultValue = OracleResultValue(
    when (binding.kind) {
        OracleReturningKind.TEXT -> returningText(value)
        OracleReturningKind.DECIMAL -> returningDecimal(value)
        OracleReturningKind.BOOLEAN -> returningBoolean(value)
        OracleReturningKind.BYTES -> returningBytes(value)
    },
)

private fun returningText(value: JsAny?): OracleScalar? = when {
    value == null -> null
    oracleJsType(value) == "string" -> OracleScalar.Text(value.unsafeCast<JsString>().toString())
    else -> throw IllegalArgumentException("Oracle DML RETURNING INTO text bind is not a string")
}

private fun returningDecimal(value: JsAny?): OracleScalar? = when {
    value == null -> null
    oracleJsType(value) == "string" -> OracleScalar.Decimal(value.unsafeCast<JsString>().toString())
    else -> throw IllegalArgumentException("Oracle DML RETURNING INTO number bind is not decimal text")
}

private fun returningBoolean(value: JsAny?): OracleScalar? = when {
    value == null -> null
    oracleJsType(value) == "boolean" -> OracleScalar.BooleanValue(oracleBooleanValue(value))
    else -> throw IllegalArgumentException("Oracle DML RETURNING INTO boolean bind is not a boolean")
}

private fun returningBytes(value: JsAny?): OracleScalar? = when {
    value == null -> null
    oracleIsBytes(value) -> OracleScalar.Bytes(value.toOracleByteArray())
    else -> throw IllegalArgumentException("Oracle DML RETURNING INTO binary bind is not a Buffer")
}

private fun scalar(value: JsAny?, numberColumn: Boolean): OracleResultValue = OracleResultValue(
    when {
        value == null -> null

        numberColumn -> require(oracleJsType(value) == "string") {
            "node-oracledb returned Oracle NUMBER as ${oracleJsType(value)}, not lossless decimal text"
        }.let { OracleScalar.Decimal(value.unsafeCast<JsString>().toString()) }

        oracleJsType(value) == "string" -> OracleScalar.Text(value.unsafeCast<JsString>().toString())

        oracleJsType(value) == "boolean" -> OracleScalar.BooleanValue(oracleBooleanValue(value))

        oracleIsBytes(value) -> OracleScalar.Bytes(value.toOracleByteArray())

        // NUMBER columns are converted to text by the query fetch type handler. A remaining
        // JavaScript Number is BINARY_FLOAT or BINARY_DOUBLE and can retain its IEEE value.
        oracleJsType(value) == "number" -> OracleScalar.Floating(value.unsafeCast<JsNumber>().toDouble())

        else -> throw IllegalArgumentException("node-oracledb returned unsupported ${oracleJsType(value)} result value")
    },
)

private external class Uint8Array(length: Int) : JsAny {
    val length: Int
    operator fun set(index: Int, value: Int)
    operator fun get(index: Int): Int
}

private fun ByteArray.toUint8Array(): Uint8Array = Uint8Array(size).also { output ->
    forEachIndexed { index, value -> output[index] = value.toInt() and BYTE_MASK }
}

private fun JsAny.toOracleByteArray(): ByteArray {
    val bytes = unsafeCast<Uint8Array>()
    return ByteArray(bytes.length) { index -> (bytes[index] and BYTE_MASK).toByte() }
}

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
    val thrown = (this as? JsException)?.thrownValue
    val errorNumber = thrown?.let { oracleNumberProperty(it, "errorNum") }?.toInt()
    val sqlState = thrown?.let { oracleStringProperty(it, "code") }
    val detail = thrown?.let { oracleStringProperty(it, "message") } ?: message ?: toString()
    val attributes = buildMap {
        errorNumber?.let { put("errorNum", it.toString()) }
        thrown?.let { oracleNumberProperty(it, "offset") }?.let { put("offset", it.toInt().toString()) }
        thrown?.let { oracleBooleanProperty(it, "isRecoverable") }?.let { put("isRecoverable", it.toString()) }
    }
    val classified = if (errorNumber == INVALID_CREDENTIALS) OracleFailureKind.AUTHENTICATION else kind
    return OracleDriverException(classified, errorNumber, sqlState, attributes, detail, this)
}

private suspend fun Promise<NativeOracleConnection>.awaitConnection(): NativeOracleConnection =
    suspendCancellableCoroutine { continuation ->
        then { connection ->
            if (continuation.isActive) continuation.resume(connection) else connection.close(oracleCloseOptions(false))
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
private const val INVALID_CREDENTIALS = 1017
private val SESSION_SETTINGS = listOf(
    "ALTER SESSION SET NLS_NUMERIC_CHARACTERS = '.,'",
    "ALTER SESSION SET NLS_DATE_FORMAT = 'YYYY-MM-DD'",
    "ALTER SESSION SET NLS_TIMESTAMP_FORMAT = 'YYYY-MM-DD\"T\"HH24:MI:SS.FF9'",
    "ALTER SESSION SET NLS_TIMESTAMP_TZ_FORMAT = 'YYYY-MM-DD\"T\"HH24:MI:SS.FF9TZH:TZM'",
)
