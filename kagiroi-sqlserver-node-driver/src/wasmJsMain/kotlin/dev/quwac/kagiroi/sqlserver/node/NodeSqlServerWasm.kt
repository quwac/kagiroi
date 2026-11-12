@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress(
    "FunctionNaming",
    "PropertyName",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
    "VariableNaming",
)

package dev.quwac.kagiroi.sqlserver.node

import dev.quwac.kagiroi.sqlserver.SqlServerBinding
import dev.quwac.kagiroi.sqlserver.SqlServerResultValue
import dev.quwac.kagiroi.sqlserver.SqlServerScalar
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsException
import kotlin.js.JsNumber
import kotlin.js.Promise
import kotlin.js.asJsException
import kotlin.js.toJsBoolean
import kotlin.js.toJsNumber
import kotlin.js.toJsString
import kotlin.js.unsafeCast

@JsModule("mssql")
private external object SqlServerModuleImport : JsAny

private external interface SqlServerModule : JsAny {
    val ConnectionPool: JsAny
    val ISOLATION_LEVEL: JsAny
    val NVarChar: JsAny
    val Bit: JsAny
    val TinyInt: JsAny
    val SmallInt: JsAny
    val Int: JsAny
    val BigInt: JsAny
    val Real: JsAny
    val Float: JsAny
    val VarBinary: JsAny
    val UniqueIdentifier: JsAny
}

private val sqlServerModule: SqlServerModule
    get() = defaultExport(SqlServerModuleImport).unsafeCast()

internal external interface NativePool : JsAny {
    fun connect(): Promise<JsAny?>
    fun request(): NativeRequest
    fun transaction(): NativeTransaction
    fun close(): Promise<JsAny?>
}

internal external interface NativeTransaction : JsAny {
    fun request(): NativeRequest
    fun begin(isolation: JsAny? = definedExternally): Promise<JsAny?>
    fun commit(): Promise<JsAny?>
    fun rollback(): Promise<JsAny?>
}

internal external interface NativeRequest : JsAny {
    fun input(name: String, type: JsAny, value: JsAny?): NativeRequest
    fun query(sql: String): Promise<NativeResult>
}

internal external interface NativeResult : JsAny {
    val recordset: JsArray<JsArray<JsAny?>>?
    val columns: JsArray<JsArray<NativeColumn>>?
    val rowsAffected: JsArray<JsNumber>?
}

internal external interface NativeColumn : JsAny {
    val name: String
    val type: NativeColumnType
}

internal external interface NativeColumnType : JsAny {
    val declaration: String
}

internal actual fun createNodeSqlServerPool(settings: NodeSqlServerSettings): NodeSqlServerPool =
    WasmNodeSqlServerPool(createPool(sqlServerModule.ConnectionPool, poolConfig(settings)))

internal actual fun createNodeSqlServerPool(connectionString: String): NodeSqlServerPool =
    WasmNodeSqlServerPool(createConnectionStringPool(sqlServerModule.ConnectionPool, connectionString))

private class WasmNodeSqlServerPool(private val pool: NativePool) : NodeSqlServerPool {
    private var connection: Promise<JsAny?>? = null

    override val supportsDedicatedSession: Boolean = false

    override suspend fun execute(statement: SqlServerStatement): SqlServerCommandResult =
        SqlServerCommandResult(affectedRows(requestResult(request(), statement)))

    override suspend fun query(statement: SqlServerStatement): SqlServerRows = rows(requestResult(request(), statement))

    override suspend fun begin(isolation: NodeSqlServerIsolation?): NodeSqlServerTransaction {
        connected()
        val transaction = pool.transaction()
        translate(SqlServerFailureKind.CONNECTION) {
            transaction.begin(isolationLevel(sqlServerModule.ISOLATION_LEVEL, isolation?.name)).awaitCompletion()
        }
        return WasmNodeSqlServerTransaction(transaction)
    }

    override suspend fun <T> dedicatedSession(block: suspend (NodeSqlServerSession) -> T): T =
        error("Dedicated SQL Server sessions are currently available on Kotlin/JS only")

    override suspend fun close() {
        translate(SqlServerFailureKind.CONNECTION) { pool.close().awaitCompletion() }
    }

    private suspend fun request(): NativeRequest {
        connected()
        return pool.request()
    }

    private suspend fun connected() {
        val pending = connection ?: pool.connect().also { connection = it }
        translate(SqlServerFailureKind.CONNECTION) { pending.awaitCompletion() }
    }
}

private class WasmNodeSqlServerTransaction(private val transaction: NativeTransaction) : NodeSqlServerTransaction {
    private var protected = false
    override fun protectRlsConnection() {
        if (!protected) {
            protectContextConnection(transaction)
            protected = true
        }
    }

    override fun releaseRlsConnectionProtection() {
        releaseContextConnectionProtection(transaction)
        protected = false
    }

    override suspend fun discard() {
        discardContextConnection(transaction)
    }

    override suspend fun execute(statement: SqlServerStatement): SqlServerCommandResult =
        SqlServerCommandResult(affectedRows(requestResult(transaction.request(), statement)))

    override suspend fun query(statement: SqlServerStatement): SqlServerRows =
        rows(requestResult(transaction.request(), statement))

    override suspend fun commit() {
        translate(SqlServerFailureKind.CONNECTION) { transaction.commit().awaitCompletion() }
    }

    override suspend fun rollback() {
        translate(SqlServerFailureKind.CONNECTION) { transaction.rollback().awaitCompletion() }
    }
}

private suspend fun requestResult(request: NativeRequest, statement: SqlServerStatement): NativeResult =
    translate(SqlServerFailureKind.EXECUTION) {
        statement.parameters.forEachIndexed { index, binding ->
            request.input(parameterName(index), bindingType(binding), bindingValue(binding))
        }
        request.query(statement.sql).awaitCompletion()
    }

private fun rows(result: NativeResult): SqlServerRows = translateResult {
    val records = result.recordset?.toList().orEmpty()
    val columns = result.columns?.toList()?.firstOrNull()?.toList().orEmpty()
    val rows = records.map { record ->
        record.toList().mapIndexed { index, value -> resultValue(value, isExactNumberColumn(columns[index])) }
    }
    val names = columns.map { it.name }
    SqlServerRows(names, rows)
}

private fun affectedRows(result: NativeResult): Long =
    result.rowsAffected?.toList()?.filterNotNull()?.sumOf { it.toDouble().toLong() } ?: 0L

private fun resultValue(value: JsAny?, exactNumber: Boolean): SqlServerResultValue {
    if (value == null) return SqlServerResultValue(null)
    val kind = valueKind(value)
    if (exactNumber && (kind == "integer" || kind == "real")) {
        throw IllegalArgumentException(
            "mssql decoded an exact SQL Server value as JavaScript Number; select CONVERT(NVARCHAR, value) in raw SQL",
        )
    }
    return when (kind) {
        "text", "date" -> SqlServerResultValue(SqlServerScalar.Text(stringValue(value)))
        "boolean" -> SqlServerResultValue(SqlServerScalar.BooleanValue(booleanValue(value)))
        "integer" -> SqlServerResultValue(SqlServerScalar.Integer(stringValue(value)))
        "real" -> SqlServerResultValue(SqlServerScalar.Real(numberValue(value)))
        "bytes" -> SqlServerResultValue(SqlServerScalar.Bytes(bytesValue(value)))
        else -> throw IllegalArgumentException("mssql returned unsupported JavaScript value")
    }
}

private fun bindingType(binding: SqlServerBinding): JsAny = when (binding) {
    SqlServerBinding.Null, is SqlServerBinding.Text -> sqlServerModule.NVarChar
    is SqlServerBinding.BooleanValue -> sqlServerModule.Bit
    is SqlServerBinding.TinyInteger -> sqlServerModule.TinyInt
    is SqlServerBinding.SmallInteger -> sqlServerModule.SmallInt
    is SqlServerBinding.Integer -> sqlServerModule.Int
    is SqlServerBinding.BigInteger -> sqlServerModule.BigInt
    is SqlServerBinding.Real -> sqlServerModule.Real
    is SqlServerBinding.FloatValue -> sqlServerModule.Float
    is SqlServerBinding.Bytes -> varBinaryType(sqlServerModule.VarBinary, binding.value.size.coerceAtLeast(1))
    is SqlServerBinding.UuidValue -> sqlServerModule.UniqueIdentifier
}

private fun bindingValue(binding: SqlServerBinding): JsAny? = when (binding) {
    SqlServerBinding.Null -> null
    is SqlServerBinding.Text -> binding.value.toJsString()
    is SqlServerBinding.BooleanValue -> binding.value.toJsBoolean()
    is SqlServerBinding.TinyInteger -> binding.decimal.toInt().toJsNumber()
    is SqlServerBinding.SmallInteger -> binding.decimal.toInt().toJsNumber()
    is SqlServerBinding.Integer -> binding.decimal.toInt().toJsNumber()
    is SqlServerBinding.BigInteger -> binding.decimal.toJsString()
    is SqlServerBinding.Real -> binding.value.toJsNumber()
    is SqlServerBinding.FloatValue -> binding.value.toJsNumber()
    is SqlServerBinding.Bytes -> bufferFrom(binding.value.toUint8Array())
    is SqlServerBinding.UuidValue -> binding.value.toString().toJsString()
}

private external class Uint8Array(length: Int) : JsAny {
    operator fun set(index: Int, value: Int)
}

private fun ByteArray.toUint8Array(): Uint8Array = Uint8Array(size).also { output ->
    forEachIndexed { index, byte -> output[index] = byte.toInt() and BYTE_MASK }
}

private fun isExactNumberColumn(column: NativeColumn): Boolean =
    column.type.declaration.lowercase() in setOf("decimal", "numeric", "money", "smallmoney")

@Suppress("UnusedParameter")
private fun bufferFrom(value: Uint8Array): JsAny = js("Buffer.from(value)")

private suspend inline fun <T> translate(kind: SqlServerFailureKind, crossinline block: suspend () -> T): T = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: SqlServerDriverException) {
    throw error
} catch (error: Throwable) {
    throw error.asSqlServerDriverException(kind)
}

private fun <T> translateResult(block: () -> T): T = try {
    block()
} catch (error: Throwable) {
    throw error.asSqlServerDriverException(SqlServerFailureKind.RESULT_CONVERSION)
}

private fun Throwable.asSqlServerDriverException(kind: SqlServerFailureKind): SqlServerDriverException {
    val thrown = (this as? JsException)?.thrownValue
    val original = thrown?.let(::originalError) ?: thrown
    val number = original?.let { numberProperty(it, "number") } ?: thrown?.let { numberProperty(it, "number") }
    val code = thrown?.let { stringProperty(it, "code") } ?: original?.let { stringProperty(it, "code") }
    val message = thrown?.let { stringProperty(it, "message") } ?: message ?: toString()
    val attributes = ERROR_ATTRIBUTES.mapNotNull { name ->
        original?.let {
            stringProperty(
                it,
                name,
            )
        }?.let { name to it }
    }.toMap()
    return SqlServerDriverException(kind, number, code, attributes, message, this)
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

private fun parameterName(index: Int): String = "kb" + (index + 1)

private const val BYTE_MASK = 0xff
private val ERROR_ATTRIBUTES = listOf("number", "state", "class", "serverName", "procName", "lineNumber")

@JsFun(
    """(transaction) => {
    const connection = transaction._acquiredConnection;
    if (!connection) throw new Error('RLS context requires an active physical transaction');
    transaction.__kagiroiRlsConnection = connection;
    transaction.__kagiroiRlsAbort = () => { if (!transaction._rollbackRequested) connection.close(); };
      connection.prependOnceListener('rollbackTransaction', transaction.__kagiroiRlsAbort);
}""",
)
private external fun protectContextConnection(transaction: NativeTransaction)

@JsFun("(transaction) => (transaction.__kagiroiRlsConnection || transaction._acquiredConnection).close()")
private external fun discardContextConnection(transaction: NativeTransaction)

@JsFun(
    "(transaction) => transaction.__kagiroiRlsConnection.removeListener('rollbackTransaction', transaction.__kagiroiRlsAbort)",
)
private external fun releaseContextConnectionProtection(transaction: NativeTransaction)
