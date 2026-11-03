@file:Suppress(
    "Indentation",
    "MagicNumber",
    "ThrowsCount",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
)

package dev.quwac.kagiroi.oracle.node

import dev.quwac.kagiroi.core.CapabilityKey
import dev.quwac.kagiroi.core.DatabaseCapability
import dev.quwac.kagiroi.oracle.OracleDialect
import dev.quwac.kagiroi.oracle.OracleExtension
import dev.quwac.kagiroi.oracle.OracleStandardTypes
import dev.quwac.kagiroi.oracle.OracleType
import dev.quwac.kagiroi.oracle.OracleTypeRegistry
import dev.quwac.kagiroi.oracle.OracleValueCodec
import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.sql.ConnectionFailure
import dev.quwac.kagiroi.sql.ConstraintKind
import dev.quwac.kagiroi.sql.ConstraintViolation
import dev.quwac.kagiroi.sql.DatabaseClosed
import dev.quwac.kagiroi.sql.DatabaseError
import dev.quwac.kagiroi.sql.DatabaseErrorMetadata
import dev.quwac.kagiroi.sql.DeadlockFailure
import dev.quwac.kagiroi.sql.DedicatedSqlSession
import dev.quwac.kagiroi.sql.DedicatedSqlSessionCapability
import dev.quwac.kagiroi.sql.ExecuteResult
import dev.quwac.kagiroi.sql.ExecutionFailure
import dev.quwac.kagiroi.sql.InvalidBinding
import dev.quwac.kagiroi.sql.LockTimeoutFailure
import dev.quwac.kagiroi.sql.NestedTransactionUnsupported
import dev.quwac.kagiroi.sql.RawSqlRow
import dev.quwac.kagiroi.sql.RenderedSql
import dev.quwac.kagiroi.sql.ResultConversionFailure
import dev.quwac.kagiroi.sql.ReturningIntoCapability
import dev.quwac.kagiroi.sql.SavepointCapability
import dev.quwac.kagiroi.sql.SavepointController
import dev.quwac.kagiroi.sql.SavepointScope
import dev.quwac.kagiroi.sql.SavepointSupport
import dev.quwac.kagiroi.sql.SerializationFailure
import dev.quwac.kagiroi.sql.SqlCapabilities
import dev.quwac.kagiroi.sql.SqlCapability
import dev.quwac.kagiroi.sql.SqlDatabase
import dev.quwac.kagiroi.sql.SqlDialect
import dev.quwac.kagiroi.sql.SqlDmlReturningInto
import dev.quwac.kagiroi.sql.SqlDmlReturningIntoExecution
import dev.quwac.kagiroi.sql.SqlDmlReturningIntoResult
import dev.quwac.kagiroi.sql.SqlParameter
import dev.quwac.kagiroi.sql.SqlRow
import dev.quwac.kagiroi.sql.SqlStreamingCapability
import dev.quwac.kagiroi.sql.SqlStreamingMode
import dev.quwac.kagiroi.sql.SqlTransaction
import dev.quwac.kagiroi.sql.SqlTypeCodec
import dev.quwac.kagiroi.sql.SqlTypeCodecRegistry
import dev.quwac.kagiroi.sql.TransactionCapability
import dev.quwac.kagiroi.sql.TransactionFailure
import dev.quwac.kagiroi.sql.TransactionIsolation
import dev.quwac.kagiroi.sql.TransactionIsolationCapability
import dev.quwac.kagiroi.sql.rawSqlRow
import dev.quwac.kagiroi.sql.sqlRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Pool settings implemented privately by node-oracledb Thin pooling. */
class NodeOraclePoolConfiguration internal constructor() {
    var minConnections: Int = 0
    var maxConnections: Int = DEFAULT_MAX_CONNECTIONS
    var increment: Int = 1
    var acquisitionTimeout: Duration = Duration.ZERO
    var idleTimeout: Duration = DEFAULT_IDLE_TIMEOUT

    internal fun snapshot(): NodeOraclePoolSettings {
        require(minConnections >= 0) { "minConnections must not be negative" }
        require(maxConnections > 0) { "maxConnections must be positive" }
        require(minConnections <= maxConnections) { "minConnections must not exceed maxConnections" }
        require(increment > 0) { "increment must be positive" }
        return NodeOraclePoolSettings(
            minConnections = minConnections,
            maxConnections = maxConnections,
            increment = increment,
            acquisitionTimeoutMilliseconds = acquisitionTimeout.milliseconds("acquisitionTimeout"),
            idleTimeoutSeconds = idleTimeout.seconds("idleTimeout"),
        )
    }
}

/** Direct Oracle settings without exposing node-oracledb Pool or Connection objects. */
class NodeOracleDatabaseConfiguration internal constructor() {
    var connectString: String? = null
    var user: String? = null
    var password: String? = null

    private var poolConfiguration = NodeOraclePoolConfiguration()
    private val extensions = mutableListOf<OracleExtension>()

    fun pool(configure: NodeOraclePoolConfiguration.() -> Unit) {
        poolConfiguration = NodeOraclePoolConfiguration().apply(configure)
    }

    fun install(extension: OracleExtension) {
        extensions += extension
    }

    internal fun snapshot(): NodeOracleSettings {
        require(!connectString.isNullOrBlank()) { "connectString is required" }
        require(!user.isNullOrBlank()) { "user is required" }
        require(password != null) { "password is required" }
        val installed = extensions.toList()
        return NodeOracleSettings(
            connectString = requireNotNull(connectString),
            user = requireNotNull(user),
            password = requireNotNull(password),
            pool = poolConfiguration.snapshot(),
            extensions = installed,
            typeCodecs = OracleStandardTypes.typeCodecs + installed.flatMap(OracleExtension::typeCodecs),
            dialect = OracleDialect.configured(installed),
        )
    }
}

internal data class NodeOraclePoolSettings(
    val minConnections: Int,
    val maxConnections: Int,
    val increment: Int,
    val acquisitionTimeoutMilliseconds: Int,
    val idleTimeoutSeconds: Int,
)

internal data class NodeOracleSettings(
    val connectString: String,
    val user: String,
    val password: String,
    val pool: NodeOraclePoolSettings,
    val extensions: List<OracleExtension>,
    val typeCodecs: List<SqlTypeCodec<*, *>>,
    val dialect: SqlDialect,
)

/** Creates a pooled Node.js Oracle database. node-oracledb stays in its default Thin mode. */
fun NodeOracleDatabase(configure: NodeOracleDatabaseConfiguration.() -> Unit): NodeOracleDatabase {
    val settings = NodeOracleDatabaseConfiguration().apply(configure).snapshot()
    return NodeOracleDatabase.create(
        pool = createNodeOraclePool(settings),
        codecs = settings.typeCodecs,
        typeRegistry = OracleTypeRegistry(settings.extensions),
        dialect = settings.dialect,
    )
}

/** Kagiroi Oracle database backed by a private node-oracledb Thin pool. */
class NodeOracleDatabase private constructor(
    private val pool: NodeOraclePool,
    private val codecs: SqlTypeCodecRegistry,
    private val typeRegistry: OracleTypeRegistry,
    override val dialect: SqlDialect,
) : SqlDatabase,
    SqlDmlReturningIntoExecution {
    private val values = OracleValueCodec(codecs, typeRegistry)
    private val state = Mutex()
    private var closed = false
    private var pendingAcquisitions = 0
    private var activeSessions = 0
    private var drainWaiter: CompletableDeferred<Unit>? = null
    private var closeCompletion: CompletableDeferred<Unit>? = null
    private val isolationCapability = object : TransactionIsolationCapability {
        override val supported: Set<TransactionIsolation> = setOf(
            TransactionIsolation.READ_COMMITTED,
            TransactionIsolation.SERIALIZABLE,
        )

        override suspend fun <T> transaction(
            isolation: TransactionIsolation,
            block: suspend SqlTransaction.() -> T,
        ): T = runTransaction(isolation, block)
    }
    private val dedicatedSessionCapability = object : DedicatedSqlSessionCapability {
        override suspend fun <T> use(block: suspend DedicatedSqlSession.() -> T): T = withSession { execution ->
            block(LeasedSession(execution))
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <C : DatabaseCapability> capability(key: CapabilityKey<C>): C? = when (key) {
        SqlCapabilities.Sql -> SqlCapability as C
        SqlCapabilities.Transactions -> TransactionCapability as C
        SqlCapabilities.Savepoints -> SavepointCapability as C
        SqlCapabilities.ReturningInto -> ReturningIntoCapability as C
        SqlCapabilities.TransactionIsolation -> isolationCapability as C
        SqlCapabilities.Streaming -> SqlStreamingCapability(SqlStreamingMode.BUFFERED) as C
        SqlCapabilities.DedicatedSession -> dedicatedSessionCapability as C
        else -> null
    }

    override suspend fun execute(statement: RenderedSql): ExecuteResult = withSession { execution ->
        val result = execution.execute(statement)
        execution.commit()
        result
    }

    override suspend fun executeDmlReturningInto(
        statement: RenderedSql,
        returning: SqlDmlReturningInto,
    ): SqlDmlReturningIntoResult = withSession { execution ->
        val result = execution.executeDmlReturningInto(statement, returning)
        execution.commit()
        result
    }

    override fun query(statement: RenderedSql, columns: List<SqlExpression<*>>): Flow<SqlRow> = flow {
        val rows = withSession { it.queryRows(statement, columns) }
        rows.forEach { row ->
            currentCoroutineContext().ensureActive()
            emit(row)
        }
    }

    override suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult = withSession { execution ->
        val result = execution.executeRaw(sql, parameters)
        execution.commit()
        result
    }

    override fun queryRaw(sql: String, parameters: List<Any?>): Flow<RawSqlRow> = flow {
        val rows = withSession { it.queryRawRows(sql, parameters) }
        rows.forEach { row ->
            currentCoroutineContext().ensureActive()
            emit(row)
        }
    }

    override suspend fun <T> transaction(block: suspend SqlTransaction.() -> T): T = runTransaction(null, block)

    @Suppress("CyclomaticComplexMethod")
    private suspend fun <T> runTransaction(
        isolation: TransactionIsolation?,
        block: suspend SqlTransaction.() -> T,
    ): T {
        val inherited = currentCoroutineContext()[ActiveNodeOracleTransactions]
        if (inherited?.contains(this) == true) throw NestedTransactionUnsupported()
        val session = acquireSession()
        val execution = OracleExecution(session, values, typeRegistry)
        val scope = TransactionScope(execution)
        var discard = false
        var transactionStarted = false
        try {
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) { initializeTransaction(execution, isolation) }
            transactionStarted = true
            currentCoroutineContext().ensureActive()
            val result = try {
                withContext((inherited ?: ActiveNodeOracleTransactions()).including(this)) { scope.block() }
            } catch (error: Throwable) {
                discard = withContext(NonCancellable) { rollbackAfter(execution, error) } || discard
                transactionStarted = false
                throw error
            }
            var committed = false
            try {
                scope.requireUsable()
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) { execution.control("COMMIT") }
                committed = true
                transactionStarted = false
                currentCoroutineContext().ensureActive()
            } catch (error: Throwable) {
                if (!committed) {
                    discard = withContext(NonCancellable) { rollbackAfter(execution, error) } || discard
                    transactionStarted = false
                }
                throw when (error) {
                    is SerializationFailure, is DeadlockFailure -> error
                    is DatabaseError -> TransactionFailure("Failed to commit Oracle transaction", error)
                    else -> error
                }
            }
            transactionStarted = false
            return result
        } catch (error: Throwable) {
            if (transactionStarted) {
                discard = withContext(NonCancellable) { rollbackAfter(execution, error) } || discard
                transactionStarted = false
            }
            discard = discard || error.hasConnectionFailure()
            throw error
        } finally {
            scope.active = false
            releaseSession(session, discard)
        }
    }

    override suspend fun close() {
        ensureOutsideTransactionScope()
        val request = state.withLock {
            closeCompletion?.let { return@withLock CloseRequest(it, false) }
            val result = CompletableDeferred<Unit>()
            closeCompletion = result
            closed = true
            if (activeSessions == 0 && pendingAcquisitions == 0) {
                result.complete(Unit)
            } else {
                drainWaiter = result
            }
            CloseRequest(result, true)
        }
        if (!request.owner) {
            request.completion.await()
            return
        }
        request.completion.await()
        try {
            pool.close()
        } catch (error: OracleDriverException) {
            throw error.toDatabaseError("Failed to close Oracle database")
        }
    }

    private suspend fun initializeTransaction(execution: OracleExecution, isolation: TransactionIsolation?) {
        when (isolation) {
            null, TransactionIsolation.READ_COMMITTED -> Unit
            TransactionIsolation.SERIALIZABLE -> execution.control("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE")
            TransactionIsolation.REPEATABLE_READ -> error("Oracle does not support REPEATABLE READ")
        }
    }

    /** Returns whether the session must be discarded after an unsuccessful rollback. */
    @Suppress("TooGenericExceptionCaught") // A rollback failure is deliberately retained on the original failure.
    private suspend fun rollbackAfter(execution: OracleExecution, original: Throwable): Boolean = try {
        execution.control("ROLLBACK")
        false
    } catch (rollback: Throwable) {
        original.addSuppressed(TransactionFailure("Failed to roll back Oracle transaction", rollback))
        true
    }

    private suspend fun <T> withSession(block: suspend (OracleExecution) -> T): T {
        ensureOutsideTransactionScope()
        val session = acquireSession()
        var discard = false
        return try {
            block(OracleExecution(session, values, typeRegistry))
        } catch (error: Throwable) {
            discard = error.hasConnectionFailure()
            throw error
        } finally {
            releaseSession(session, discard)
        }
    }

    private suspend fun acquireSession(): NodeOracleSession {
        state.withLock {
            ensureOpen()
            pendingAcquisitions++
        }
        return try {
            val session = try {
                pool.acquire()
            } catch (error: OracleDriverException) {
                throw error.toDatabaseError("Failed to acquire Oracle pool connection")
            }
            state.withLock {
                pendingAcquisitions--
                activeSessions++
                signalDrainedIfNeeded()
            }
            session
        } catch (error: Throwable) {
            state.withLock {
                pendingAcquisitions--
                signalDrainedIfNeeded()
            }
            throw error
        }
    }

    private suspend fun releaseSession(session: NodeOracleSession, discard: Boolean) {
        try {
            session.release(discard)
        } finally {
            state.withLock {
                activeSessions--
                signalDrainedIfNeeded()
            }
        }
    }

    private fun signalDrainedIfNeeded() {
        if (closed && activeSessions == 0 && pendingAcquisitions == 0) drainWaiter?.complete(Unit)
    }

    private suspend fun ensureOutsideTransactionScope() {
        if (currentCoroutineContext()[ActiveNodeOracleTransactions]?.contains(this) == true) {
            throw ExecutionFailure("Use the transaction scope for database operations inside a transaction")
        }
    }

    private fun ensureOpen() {
        if (closed) throw DatabaseClosed()
    }

    private inner class TransactionScope(private val execution: OracleExecution) :
        SqlTransaction,
        SqlDmlReturningIntoExecution {
        override val dialect: SqlDialect get() = this@NodeOracleDatabase.dialect
        var active = true
        private val savepoints = SavepointSupport(
            SavepointController(
                create = { name -> execution.control("SAVEPOINT $name") },
                rollbackTo = { name -> execution.control("ROLLBACK TO SAVEPOINT $name") },
            ),
        )

        override suspend fun <T> savepoint(block: suspend SavepointScope.() -> T): T = inScope {
            savepoints.savepoint(this@TransactionScope, block)
        }

        override suspend fun execute(statement: RenderedSql): ExecuteResult = inScope { execution.execute(statement) }

        override fun query(statement: RenderedSql, columns: List<SqlExpression<*>>): Flow<SqlRow> = flow {
            requireActive()
            execution.queryRows(statement, columns).forEach { row ->
                currentCoroutineContext().ensureActive()
                emit(row)
            }
        }

        override suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult = inScope {
            execution.executeRaw(sql, parameters)
        }

        override fun queryRaw(sql: String, parameters: List<Any?>): Flow<RawSqlRow> = flow {
            requireActive()
            execution.queryRawRows(sql, parameters).forEach { row ->
                currentCoroutineContext().ensureActive()
                emit(row)
            }
        }

        override suspend fun executeDmlReturningInto(
            statement: RenderedSql,
            returning: SqlDmlReturningInto,
        ): SqlDmlReturningIntoResult = inScope { execution.executeDmlReturningInto(statement, returning) }

        fun requireUsable() = savepoints.requireUsable()

        private suspend fun <T> inScope(block: suspend () -> T): T {
            requireActive()
            requireUsable()
            return block()
        }

        private fun requireActive() {
            if (!active) throw ExecutionFailure("Transaction scope is no longer active")
        }
    }

    private inner class LeasedSession(private val execution: OracleExecution) : DedicatedSqlSession {
        override val dialect: SqlDialect = this@NodeOracleDatabase.dialect

        override suspend fun execute(statement: RenderedSql): ExecuteResult = execution.execute(statement)

        override fun query(statement: RenderedSql, columns: List<SqlExpression<*>>): Flow<SqlRow> = flow {
            for (row in execution.queryRows(statement, columns)) emit(row)
        }

        override suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult =
            execution.executeRaw(sql, parameters)

        override fun queryRaw(sql: String, parameters: List<Any?>): Flow<RawSqlRow> = flow {
            for (row in execution.queryRawRows(sql, parameters)) emit(row)
        }
    }

    internal companion object {
        fun create(
            pool: NodeOraclePool,
            codecs: List<SqlTypeCodec<*, *>> = OracleStandardTypes.typeCodecs,
            typeRegistry: OracleTypeRegistry = OracleTypeRegistry(),
            dialect: SqlDialect = OracleDialect,
        ): NodeOracleDatabase = NodeOracleDatabase(pool, SqlTypeCodecRegistry(codecs), typeRegistry, dialect)
    }
}

private class ActiveNodeOracleTransactions(private val databases: List<NodeOracleDatabase> = emptyList()) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ActiveNodeOracleTransactions>

    fun contains(database: NodeOracleDatabase): Boolean = databases.any { it === database }

    fun including(database: NodeOracleDatabase): ActiveNodeOracleTransactions =
        ActiveNodeOracleTransactions(databases + database)
}

private data class CloseRequest(val completion: CompletableDeferred<Unit>, val owner: Boolean)

private class OracleExecution(
    private val session: NodeOracleSession,
    private val values: OracleValueCodec,
    private val typeRegistry: OracleTypeRegistry,
) {
    suspend fun execute(statement: RenderedSql): ExecuteResult {
        currentCoroutineContext().ensureActive()
        val result = translate { session.execute(toStatement(statement)) }
        currentCoroutineContext().ensureActive()
        return ExecuteResult(result.affectedRows)
    }

    suspend fun queryRows(statement: RenderedSql, columns: List<SqlExpression<*>>): List<SqlRow> {
        currentCoroutineContext().ensureActive()
        val result = translate { session.query(toStatement(statement)) }
        currentCoroutineContext().ensureActive()
        if (result.rows.any { it.size != columns.size }) {
            throw ResultConversionFailure("Oracle returned a row whose value count does not match selected columns")
        }
        return result.rows.map { row -> sqlRow(columns, columns.indices.map { values.decode(columns[it], row[it]) }) }
    }

    suspend fun executeDmlReturningInto(
        statement: RenderedSql,
        returning: SqlDmlReturningInto,
    ): SqlDmlReturningIntoResult {
        currentCoroutineContext().ensureActive()
        val result = translate { session.execute(toReturningStatement(statement, returning)) }
        currentCoroutineContext().ensureActive()
        val output = result.returningValues
        if (output.size != returning.columns.size) {
            throw ResultConversionFailure("Oracle returned an unexpected number of DML RETURNING INTO bind values")
        }
        val rowCount = output.firstOrNull()?.size ?: 0
        if (output.any { values -> values.size != rowCount }) {
            throw ResultConversionFailure("Oracle returned DML RETURNING INTO bind arrays with different lengths")
        }
        val rows = List(rowCount) { rowIndex ->
            sqlRow(
                returning.columns,
                returning.columns.indices.map { columnIndex ->
                    values.decode(returning.columns[columnIndex], output[columnIndex][rowIndex])
                },
            )
        }
        return SqlDmlReturningIntoResult(result.affectedRows, rows)
    }

    suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult = execute(
        RenderedSql(sql, parameters.map { SqlParameter(it, null) }),
    )

    suspend fun queryRawRows(sql: String, parameters: List<Any?>): List<RawSqlRow> {
        val result = translate {
            session.query(
                toStatement(RenderedSql(sql, parameters.map { SqlParameter(it, null) })),
            )
        }
        return result.rows.map { row ->
            if (row.size != result.columnNames.size) {
                throw ResultConversionFailure("Oracle returned a raw row whose value count does not match columns")
            }
            rawSqlRow(result.columnNames, row.map(values::rawValue))
        }
    }

    suspend fun control(sql: String) {
        translate { session.execute(OracleStatement(sql, emptyList())) }
    }

    suspend fun commit() {
        translate { session.commit() }
    }

    private fun toStatement(statement: RenderedSql): OracleStatement = OracleStatement(
        statement.sql,
        statement.parameters.mapIndexed { index, parameter ->
            try {
                values.binding(parameter.value, parameter.expression)
            } catch (error: Throwable) {
                throw InvalidBinding(
                    "Oracle cannot bind ${parameter.column?.toString() ?: "parameter ${index + 1}"}",
                    error,
                )
            }
        },
    )

    private fun toReturningStatement(statement: RenderedSql, returning: SqlDmlReturningInto): OracleStatement =
        toStatement(statement).copy(
            returning = returning.columns.zip(returning.parameterNames).map { (column, name) ->
                OracleReturningBinding(name, returningKind(column), returningMaxSize(column))
            },
        )

    private fun returningKind(column: Column<*>): OracleReturningKind = when (typeRegistry.typeOf(column.storageType)) {
        OracleType.VARCHAR2,
        OracleType.NVARCHAR2,
        OracleType.CHAR,
        OracleType.NCHAR,
        OracleType.DATE,
        OracleType.TIMESTAMP,
        OracleType.TIMESTAMP_WITH_TIME_ZONE,
        OracleType.TIMESTAMP_WITH_LOCAL_TIME_ZONE,
        -> OracleReturningKind.TEXT

        OracleType.NUMBER_3,
        OracleType.NUMBER_5,
        OracleType.NUMBER_10,
        OracleType.NUMBER_19,
        OracleType.NUMBER_20,
        OracleType.NUMBER,
        OracleType.BINARY_FLOAT,
        OracleType.BINARY_DOUBLE,
        -> OracleReturningKind.DECIMAL

        OracleType.BOOLEAN -> OracleReturningKind.BOOLEAN

        OracleType.RAW,
        OracleType.RAW_UUID,
        -> OracleReturningKind.BYTES

        OracleType.CLOB,
        OracleType.NCLOB,
        OracleType.BLOB,
        null,
        -> throw InvalidBinding("Oracle DML RETURNING INTO cannot bind ${column.storageType.name}")
    }

    private fun returningMaxSize(column: Column<*>): Int? = when (returningKind(column)) {
        OracleReturningKind.TEXT,
        OracleReturningKind.BYTES,
        -> MAX_RETURNING_BIND_BYTES

        OracleReturningKind.DECIMAL -> MAX_RETURNING_NUMBER_BYTES

        OracleReturningKind.BOOLEAN -> null
    }

    private suspend inline fun <T> translate(crossinline block: suspend () -> T): T = try {
        block()
    } catch (error: OracleDriverException) {
        throw error.toDatabaseError("Oracle execution failed")
    }
}

private const val MAX_RETURNING_BIND_BYTES = 32_767
private const val MAX_RETURNING_NUMBER_BYTES = 128

@Suppress("CyclomaticComplexMethod")
private fun OracleDriverException.toDatabaseError(prefix: String): DatabaseError {
    val vendorCode = errorNumber?.let { "ORA-${it.toString().padStart(5, '0')}" }
    val metadata = DatabaseErrorMetadata(
        sqlState,
        vendorCode,
        attributes + mapOfNotNull("errorNumber", errorNumber?.toString()),
    )
    val message = "$prefix: ${message ?: "unknown error"}"
    return when (errorNumber) {
        1 -> ConstraintViolation(message, this, vendorCode, ConstraintKind.UNIQUE, metadata)

        2291, 2292 -> ConstraintViolation(message, this, vendorCode, ConstraintKind.FOREIGN_KEY, metadata)

        1400 -> ConstraintViolation(message, this, vendorCode, ConstraintKind.NOT_NULL, metadata)

        2290 -> ConstraintViolation(message, this, vendorCode, ConstraintKind.CHECK, metadata)

        8177 -> SerializationFailure(message, this, metadata)

        60 -> DeadlockFailure(message, this, metadata)

        54, 1013 -> LockTimeoutFailure(message, this, metadata)

        1008, 1036, 1722, 6502 -> InvalidBinding(message, this, vendorCode, metadata)

        else -> when {
            kind == OracleFailureKind.CONNECTION || kind == OracleFailureKind.AUTHENTICATION ||
                errorNumber in connectionErrors ->
                ConnectionFailure(message, this, metadata)

            kind == OracleFailureKind.TIMEOUT -> LockTimeoutFailure(message, this, metadata)

            kind == OracleFailureKind.RESULT_CONVERSION -> ResultConversionFailure(message, this, vendorCode, metadata)

            else -> ExecutionFailure(message, this, vendorCode, metadata)
        }
    }
}

private fun Throwable.hasConnectionFailure(): Boolean =
    this is ConnectionFailure || cause?.hasConnectionFailure() == true

private fun Duration.milliseconds(name: String): Int {
    require(!isNegative() && isFinite()) { "$name must be a finite non-negative duration" }
    require(inWholeMilliseconds <= Int.MAX_VALUE) { "$name is too large" }
    return inWholeMilliseconds.toInt()
}

private fun Duration.seconds(name: String): Int {
    require(!isNegative() && isFinite()) { "$name must be a finite non-negative duration" }
    require(inWholeSeconds <= Int.MAX_VALUE) { "$name is too large" }
    return inWholeSeconds.toInt()
}

private fun <K : Any, V : Any> mapOfNotNull(key: K, value: V?): Map<K, V> = if (value == null) {
    emptyMap()
} else {
    mapOf(
        key to value,
    )
}

private const val DEFAULT_MAX_CONNECTIONS = 10
private val DEFAULT_IDLE_TIMEOUT = 60.seconds
private val connectionErrors = setOf(1017, 12154, 12514, 12516, 12520, 12528, 12537, 12541, 12545)
