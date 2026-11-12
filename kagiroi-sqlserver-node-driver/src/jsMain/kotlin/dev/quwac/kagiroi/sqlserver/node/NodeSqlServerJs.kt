@file:Suppress("TooGenericExceptionCaught", "TooManyFunctions", "UnsafeCastFromDynamic")

package dev.quwac.kagiroi.sqlserver.node

import dev.quwac.kagiroi.sqlserver.SqlServerBinding
import dev.quwac.kagiroi.sqlserver.SqlServerResultValue
import dev.quwac.kagiroi.sqlserver.SqlServerScalar
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Promise

@JsModule("mssql")
@JsNonModule
private external object SqlServerModule {
    val ConnectionPool: dynamic
    val ISOLATION_LEVEL: dynamic
    val NVarChar: dynamic
    val Bit: dynamic
    val TinyInt: dynamic
    val SmallInt: dynamic
    val Int: dynamic
    val BigInt: dynamic
    val Real: dynamic
    val Float: dynamic
    val VarBinary: dynamic
    val UniqueIdentifier: dynamic
}

@JsModule("tedious")
@JsNonModule
private external object TediousModule {
    val Request: dynamic
    val TYPES: dynamic
}

internal actual fun createNodeSqlServerPool(settings: NodeSqlServerSettings): NodeSqlServerPool =
    JsNodeSqlServerPool(newPool(SqlServerModule.ConnectionPool, poolConfig(settings)))

internal actual fun createNodeSqlServerPool(connectionString: String): NodeSqlServerPool =
    JsNodeSqlServerPool(newPool(SqlServerModule.ConnectionPool, connectionString))

private class JsNodeSqlServerPool(private val pool: dynamic) : NodeSqlServerPool {
    private var connection: Promise<dynamic>? = null

    override val supportsDedicatedSession: Boolean = true

    override suspend fun execute(statement: SqlServerStatement): SqlServerCommandResult {
        val result = requestResult(request(), statement)
        return SqlServerCommandResult(affectedRows(result))
    }

    override suspend fun query(statement: SqlServerStatement): SqlServerRows = rows(requestResult(request(), statement))

    override suspend fun begin(isolation: NodeSqlServerIsolation?): NodeSqlServerTransaction {
        val connectedPool = connected()
        val transaction = connectedPool.transaction()
        translate(SqlServerFailureKind.CONNECTION) {
            begin(transaction, isolationLevel(isolation)).awaitCompletion()
        }
        return JsNodeSqlServerTransaction(transaction)
    }

    override suspend fun <T> dedicatedSession(block: suspend (NodeSqlServerSession) -> T): T {
        val connectedPool = connected()
        val leased = translate(SqlServerFailureKind.CONNECTION) { acquire(connectedPool).awaitCompletion() }
        try {
            return block(JsDedicatedSqlServerSession(leased))
        } finally {
            translate(SqlServerFailureKind.CONNECTION) { release(connectedPool, leased) }
        }
    }

    override suspend fun close() {
        translate(SqlServerFailureKind.CONNECTION) { close(pool).awaitCompletion() }
    }

    private suspend fun request(): dynamic {
        val connectedPool = connected()
        return connectedPool.request()
    }

    private suspend fun connected(): dynamic {
        val pending = connection ?: connect(pool).also { connection = it }
        return translate(SqlServerFailureKind.CONNECTION) { pending.awaitCompletion() }
    }
}

private class JsDedicatedSqlServerSession(private val connection: dynamic) : NodeSqlServerSession {
    override suspend fun execute(statement: SqlServerStatement): SqlServerCommandResult {
        val result = dedicatedRequestResult(connection, statement)
        return SqlServerCommandResult((result.affectedRows as Number).toLong())
    }

    override suspend fun query(statement: SqlServerStatement): SqlServerRows = dedicatedRows(
        dedicatedRequestResult(connection, statement),
    )
}

private class JsNodeSqlServerTransaction(private val transaction: dynamic) : NodeSqlServerTransaction {
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

    override suspend fun execute(statement: SqlServerStatement): SqlServerCommandResult {
        val result = requestResult(transaction.request(), statement)
        return SqlServerCommandResult(affectedRows(result))
    }

    override suspend fun query(statement: SqlServerStatement): SqlServerRows =
        rows(requestResult(transaction.request(), statement))

    override suspend fun commit() {
        translate(SqlServerFailureKind.CONNECTION) { commit(transaction).awaitCompletion() }
    }

    override suspend fun rollback() {
        translate(SqlServerFailureKind.CONNECTION) { rollback(transaction).awaitCompletion() }
    }
}

private suspend fun requestResult(request: dynamic, statement: SqlServerStatement): dynamic =
    translate(SqlServerFailureKind.EXECUTION) {
        statement.parameters.forEachIndexed { index, binding ->
            request.input(parameterName(index), bindingType(binding), bindingValue(binding))
        }
        query(request, statement.sql).awaitCompletion()
    }

private suspend fun dedicatedRequestResult(connection: dynamic, statement: SqlServerStatement): dynamic =
    translate(SqlServerFailureKind.EXECUTION) {
        dedicatedRequest(
            TediousModule.Request,
            connection,
            statement.sql,
            statement.parameters.map(::dedicatedBinding).toTypedArray(),
        ).awaitCompletion()
    }

private fun dedicatedRows(result: dynamic): SqlServerRows = translateResult {
    val columns = result.columns as Array<dynamic>
    val rows = (result.rows as Array<Array<dynamic>>).map { row ->
        row.mapIndexed { index, value -> resultValue(value, isExactTediousNumberColumn(columns[index])) }
    }
    SqlServerRows(columns.map { it.colName as String }.toList(), rows)
}

private fun dedicatedBinding(binding: SqlServerBinding): dynamic {
    val output = js("({})")
    output.type = tediousBindingType(binding)
    output.value = bindingValue(binding)
    return output
}

private fun tediousBindingType(binding: SqlServerBinding): dynamic = when (binding) {
    SqlServerBinding.Null, is SqlServerBinding.Text -> TediousModule.TYPES.NVarChar
    is SqlServerBinding.BooleanValue -> TediousModule.TYPES.Bit
    is SqlServerBinding.TinyInteger -> TediousModule.TYPES.TinyInt
    is SqlServerBinding.SmallInteger -> TediousModule.TYPES.SmallInt
    is SqlServerBinding.Integer -> TediousModule.TYPES.Int
    is SqlServerBinding.BigInteger -> TediousModule.TYPES.BigInt
    is SqlServerBinding.Real -> TediousModule.TYPES.Real
    is SqlServerBinding.FloatValue -> TediousModule.TYPES.Float
    is SqlServerBinding.Bytes -> TediousModule.TYPES.VarBinary
    is SqlServerBinding.UuidValue -> TediousModule.TYPES.UniqueIdentifier
}

private fun rows(result: dynamic): SqlServerRows = translateResult {
    val recordSet = result.recordset
    val columns = if (recordSet == null) emptyArray() else result.columns[0] as Array<dynamic>
    val rows = if (recordSet ==
        null
    ) {
        emptyList<List<SqlServerResultValue>>()
    } else {
        (recordSet as Array<Array<dynamic>>).map { record ->
            record.mapIndexed { index, value -> resultValue(value, isExactNumberColumn(columns[index])) }
        }
    }
    val names = if (recordSet == null || recordSet.size == 0) emptyList<String>() else columnNames(result).toList()
    SqlServerRows(names, rows)
}

private fun affectedRows(result: dynamic): Long = translateResult {
    val values = result.rowsAffected as? Array<dynamic> ?: emptyArray()
    values.sumOf { (it as? Number)?.toLong() ?: 0L }
}

private fun resultValue(value: dynamic, exactNumber: Boolean): SqlServerResultValue = when (valueKind(value)) {
    "null" -> SqlServerResultValue(null)

    "text", "date" -> SqlServerResultValue(SqlServerScalar.Text(stringValue(value)))

    "boolean" -> SqlServerResultValue(SqlServerScalar.BooleanValue(value as Boolean))

    "integer", "real" -> if (exactNumber) {
        throw IllegalArgumentException(
            "mssql decoded an exact SQL Server value as JavaScript Number; select CONVERT(NVARCHAR, value) in raw SQL",
        )
    } else if (valueKind(value) == "integer") {
        SqlServerResultValue(SqlServerScalar.Integer(stringValue(value)))
    } else {
        SqlServerResultValue(SqlServerScalar.Real((value as Number).toDouble()))
    }

    "bytes" -> SqlServerResultValue(SqlServerScalar.Bytes(bytesValue(value)))

    else -> throw IllegalArgumentException("mssql returned unsupported JavaScript value: ${valueDescription(value)}")
}

private fun bindingType(binding: SqlServerBinding): dynamic = when (binding) {
    SqlServerBinding.Null, is SqlServerBinding.Text -> SqlServerModule.NVarChar
    is SqlServerBinding.BooleanValue -> SqlServerModule.Bit
    is SqlServerBinding.TinyInteger -> SqlServerModule.TinyInt
    is SqlServerBinding.SmallInteger -> SqlServerModule.SmallInt
    is SqlServerBinding.Integer -> SqlServerModule.Int
    is SqlServerBinding.BigInteger -> SqlServerModule.BigInt
    is SqlServerBinding.Real -> SqlServerModule.Real
    is SqlServerBinding.FloatValue -> SqlServerModule.Float
    is SqlServerBinding.Bytes -> varBinaryType(SqlServerModule.VarBinary, binding.value.size.coerceAtLeast(1))
    is SqlServerBinding.UuidValue -> SqlServerModule.UniqueIdentifier
}

private fun bindingValue(binding: SqlServerBinding): dynamic = when (binding) {
    SqlServerBinding.Null -> null
    is SqlServerBinding.Text -> binding.value
    is SqlServerBinding.BooleanValue -> binding.value
    is SqlServerBinding.TinyInteger -> binding.decimal.toInt()
    is SqlServerBinding.SmallInteger -> binding.decimal.toInt()
    is SqlServerBinding.Integer -> binding.decimal.toInt()
    is SqlServerBinding.BigInteger -> binding.decimal
    is SqlServerBinding.Real -> binding.value
    is SqlServerBinding.FloatValue -> binding.value
    is SqlServerBinding.Bytes -> bufferFrom(binding.value.toUint8Array())
    is SqlServerBinding.UuidValue -> binding.value.toString()
}

private fun poolConfig(settings: NodeSqlServerSettings): dynamic {
    val config = js("({})")
    config.server = settings.host
    config.port = settings.port
    config.database = settings.database
    config.user = settings.user
    settings.password?.let { config.password = it }
    config.connectionTimeout = settings.pool.connectionTimeoutMilliseconds
    config.requestTimeout = settings.requestTimeoutMilliseconds
    config.arrayRowMode = true
    config.pool = js("({})")
    config.pool.min = settings.pool.minConnections
    config.pool.max = settings.pool.maxConnections
    config.pool.idleTimeoutMillis = settings.pool.idleTimeoutMilliseconds
    config.options = js("({})")
    config.options.encrypt = settings.tls.mode != NodeSqlServerTlsMode.DISABLED
    config.options.trustServerCertificate = settings.tls.mode == NodeSqlServerTlsMode.REQUIRE
    settings.tls.certificateAuthority?.let { authority ->
        val details = js("({})")
        details.ca = authority
        config.options.cryptoCredentialsDetails = details
    }
    settings.tls.hostNameInCertificate?.let { config.options.serverName = it }
    return config
}

private fun isolationLevel(isolation: NodeSqlServerIsolation?): dynamic = when (isolation) {
    null -> null
    NodeSqlServerIsolation.READ_COMMITTED -> SqlServerModule.ISOLATION_LEVEL.READ_COMMITTED
    NodeSqlServerIsolation.REPEATABLE_READ -> SqlServerModule.ISOLATION_LEVEL.REPEATABLE_READ
    NodeSqlServerIsolation.SERIALIZABLE -> SqlServerModule.ISOLATION_LEVEL.SERIALIZABLE
}

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
    val native = asDynamic()
    val original = native.originalError ?: native
    val number = (original.number as? Number)?.toInt() ?: (native.number as? Number)?.toInt()
    val code = (native.code as? String) ?: (original.code as? String)
    val message = (native.message as? String) ?: (original.message as? String) ?: this.message ?: toString()
    val attributes = ERROR_ATTRIBUTES.mapNotNull { name ->
        (original[name] as? String)?.let { name to it }
    }.toMap()
    return SqlServerDriverException(kind, number, code, attributes, message, this)
}

private external class Uint8Array(length: Int)

private fun ByteArray.toUint8Array(): Uint8Array {
    val output = Uint8Array(size)
    forEachIndexed { index, byte -> setByte(output, index, byte.toInt() and BYTE_MASK) }
    return output
}

@Suppress("UnusedParameter")
private fun bufferFrom(value: Uint8Array): dynamic = js("Buffer.from(value)")

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
private fun newPool(constructor: dynamic, config: dynamic): dynamic = js("new constructor(config)")

@Suppress("UnusedParameter")
private fun connect(pool: dynamic): Promise<dynamic> = js("pool.connect()")

@Suppress("UnusedParameter")
private fun close(pool: dynamic): Promise<dynamic> = js("pool.close()")

@Suppress("UnusedParameter")
private fun commit(transaction: dynamic): Promise<dynamic> = js("transaction.commit()")

@Suppress("UnusedParameter")
private fun rollback(transaction: dynamic): Promise<dynamic> = js("transaction.rollback()")

@Suppress("UnusedParameter")
private fun varBinaryType(factory: dynamic, length: Int): dynamic = js("factory(length)")

@Suppress("UnusedParameter")
private fun begin(transaction: dynamic, isolation: dynamic): Promise<dynamic> =
    js("isolation == null ? transaction.begin() : transaction.begin(isolation)")

@Suppress("UnusedParameter")
private fun query(request: dynamic, sql: String): Promise<dynamic> = js("request.query(sql)")

@Suppress("UnusedParameter")
private fun acquire(pool: dynamic): Promise<dynamic> = js("pool.acquire()")

@Suppress("UnusedParameter")
private fun release(pool: dynamic, connection: dynamic) {
    js("pool.release(connection)")
}

@Suppress("UnusedParameter")
private fun dedicatedRequest(
    requestConstructor: dynamic,
    connection: dynamic,
    sql: String,
    parameters: Array<dynamic>,
): Promise<dynamic> = js(
    """
    new Promise((resolve, reject) => {
      const rows = [];
      let columns = [];
      const request = new requestConstructor(sql, (error, rowCount) => {
        if (error) {
          reject(error);
        } else {
          resolve({ affectedRows: rowCount || 0, columns, rows });
        }
      });
      request.on('columnMetadata', metadata => { columns = metadata; });
      request.on('row', row => { rows.push(row.map(column => column.value)); });
      parameters.forEach((parameter, index) => request.addParameter('kb' + (index + 1), parameter.type, parameter.value));
      if (parameters.length === 0) connection.execSqlBatch(request);
      else connection.execSql(request);
    })
    """,
)

@Suppress("UnusedParameter")
private fun columnNames(result: dynamic): Array<String> = js("result.columns[0].map(column => column.name)")

@Suppress("UnusedParameter")
private fun isExactNumberColumn(column: dynamic): Boolean = js(
    "['decimal', 'numeric', 'money', 'smallmoney'].includes(String(column.type.declaration).toLowerCase())",
)

@Suppress("UnusedParameter")
private fun isExactTediousNumberColumn(column: dynamic): Boolean = js(
    "['decimal', 'numeric', 'money', 'smallmoney'].includes(String(column.type.name).toLowerCase())",
)

@Suppress("UnusedParameter")
private fun valueKind(value: dynamic): String = js(
    """
    value == null ? 'null'
      : typeof value === 'string' ? 'text'
      : typeof value === 'boolean' ? 'boolean'
      : typeof value === 'bigint' ? 'integer'
      : typeof value === 'number' ? (Number.isInteger(value) ? 'integer' : 'real')
      : value instanceof Date ? 'date'
      : ArrayBuffer.isView(value) ? 'bytes'
      : 'unsupported'
    """,
)

@Suppress("UnusedParameter")
private fun valueDescription(value: dynamic): String = js(
    "typeof value + ' (' + (value && value.constructor ? value.constructor.name : '') + ')'",
)

@Suppress("UnusedParameter")
private fun stringValue(value: dynamic): String = js("value instanceof Date ? value.toISOString() : String(value)")

@Suppress("UnusedParameter")
private fun byteValues(value: dynamic): Array<dynamic> = js("Array.from(value)")

private fun bytesValue(value: dynamic): ByteArray =
    byteValues(value).map { (it as Number).toInt().toByte() }.toByteArray()

@Suppress("UnusedParameter")
private fun setByte(output: Uint8Array, index: Int, value: Int) {
    js("output[index] = value")
}

private fun parameterName(index: Int): String = "kb" + (index + 1)

private const val BYTE_MASK = 0xff
private val ERROR_ATTRIBUTES = listOf("number", "state", "class", "serverName", "procName", "lineNumber")

@Suppress("UnusedParameter")
private fun protectContextConnection(transaction: dynamic) {
    js(
        """
      const connection = transaction._acquiredConnection;
      if (!connection) throw new Error('RLS context requires an active physical transaction');
      transaction.__kagiroiRlsConnection = connection;
      transaction.__kagiroiRlsAbort = () => { if (!transaction._rollbackRequested) connection.close(); };
      connection.prependOnceListener('rollbackTransaction', transaction.__kagiroiRlsAbort);
    """,
    )
}

@Suppress("UnusedParameter")
private fun discardContextConnection(transaction: dynamic) {
    js("(transaction.__kagiroiRlsConnection || transaction._acquiredConnection).close()")
}

@Suppress("UnusedParameter")
private fun releaseContextConnectionProtection(transaction: dynamic) {
    js("transaction.__kagiroiRlsConnection.removeListener('rollbackTransaction', transaction.__kagiroiRlsAbort)")
}
