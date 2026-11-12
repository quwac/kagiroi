package dev.quwac.kagiroi.postgres.hyperdrive

import dev.quwac.kagiroi.core.CapabilityKey
import dev.quwac.kagiroi.core.DatabaseCapability
import dev.quwac.kagiroi.postgres.PostgresDialect
import dev.quwac.kagiroi.postgres.PostgresExtension
import dev.quwac.kagiroi.postgres.PostgresStandardTypes
import dev.quwac.kagiroi.postgres.PostgresValueCodec
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.sql.ConnectionFailure
import dev.quwac.kagiroi.sql.ConstraintKind
import dev.quwac.kagiroi.sql.ConstraintViolation
import dev.quwac.kagiroi.sql.DatabaseClosed
import dev.quwac.kagiroi.sql.DatabaseError
import dev.quwac.kagiroi.sql.DatabaseErrorMetadata
import dev.quwac.kagiroi.sql.DeadlockFailure
import dev.quwac.kagiroi.sql.ExecuteResult
import dev.quwac.kagiroi.sql.ExecutionFailure
import dev.quwac.kagiroi.sql.InvalidBinding
import dev.quwac.kagiroi.sql.NestedTransactionUnsupported
import dev.quwac.kagiroi.sql.RawSqlRow
import dev.quwac.kagiroi.sql.RenderedSql
import dev.quwac.kagiroi.sql.ResultConversionFailure
import dev.quwac.kagiroi.sql.ReturningCapability
import dev.quwac.kagiroi.sql.RlsContextConnectionGuard
import dev.quwac.kagiroi.sql.RlsContextGuard
import dev.quwac.kagiroi.sql.SavepointCapability
import dev.quwac.kagiroi.sql.SavepointController
import dev.quwac.kagiroi.sql.SavepointScope
import dev.quwac.kagiroi.sql.SavepointSupport
import dev.quwac.kagiroi.sql.SerializationFailure
import dev.quwac.kagiroi.sql.SqlCapabilities
import dev.quwac.kagiroi.sql.SqlCapability
import dev.quwac.kagiroi.sql.SqlDatabase
import dev.quwac.kagiroi.sql.SqlDialect
import dev.quwac.kagiroi.sql.SqlParameter
import dev.quwac.kagiroi.sql.SqlRow
import dev.quwac.kagiroi.sql.SqlStreamingCapability
import dev.quwac.kagiroi.sql.SqlStreamingMode
import dev.quwac.kagiroi.sql.SqlTransaction
import dev.quwac.kagiroi.sql.SqlTypeCodecRegistry
import dev.quwac.kagiroi.sql.TransactionCapability
import dev.quwac.kagiroi.sql.TransactionFailure
import dev.quwac.kagiroi.sql.TransactionIsolation
import dev.quwac.kagiroi.sql.TransactionIsolationCapability
import dev.quwac.kagiroi.sql.rawSqlRow
import dev.quwac.kagiroi.sql.sqlRow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Creates a Hyperdrive database from the connection string supplied by the host binding. */
fun HyperdrivePostgresDatabase(
    connectionString: String,
    configure: HyperdrivePostgresDatabaseConfiguration.() -> Unit = {},
): HyperdrivePostgresDatabase {
    require(connectionString.isNotBlank()) { "Hyperdrive connection string must not be blank" }
    return HyperdrivePostgresDatabase.create(createHyperdrivePostgresEndpoint(connectionString), configure)
}

/** Kagiroi PostgreSQL database backed by a Cloudflare Hyperdrive binding. */
@Suppress("TooManyFunctions")
class HyperdrivePostgresDatabase private constructor(
    private val endpoint: PostgresEndpoint,
    codecs: SqlTypeCodecRegistry,
    override val dialect: SqlDialect,
) : SqlDatabase {
    private val execution = PostgresExecution(endpoint, codecs, ::ensureOpen)
    private val access = Mutex()
    private var closed = false
    private val isolationCapability = object : TransactionIsolationCapability {
        override val supported: Set<TransactionIsolation> = TransactionIsolation.entries.toSet()

        override suspend fun <T> transaction(
            isolation: TransactionIsolation,
            block: suspend SqlTransaction.() -> T,
        ): T = runTransaction(isolation, block)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <C : DatabaseCapability> capability(key: CapabilityKey<C>): C? = when (key) {
        SqlCapabilities.Sql -> SqlCapability as C
        SqlCapabilities.Returning -> ReturningCapability as C
        SqlCapabilities.Transactions -> TransactionCapability as C
        SqlCapabilities.Savepoints -> SavepointCapability as C
        SqlCapabilities.TransactionIsolation -> isolationCapability as C
        SqlCapabilities.Streaming -> SqlStreamingCapability(SqlStreamingMode.BUFFERED) as C
        else -> null
    }

    override suspend fun execute(statement: RenderedSql): ExecuteResult = locked {
        execution.execute(statement)
    }

    override fun query(statement: RenderedSql, columns: List<SqlExpression<*>>): Flow<SqlRow> = flow {
        val rows = locked { execution.queryRows(statement, columns) }
        for (row in rows) {
            currentCoroutineContext().ensureActive()
            emit(row)
        }
    }

    override suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult = locked {
        execution.executeRaw(sql, parameters)
    }

    override fun queryRaw(sql: String, parameters: List<Any?>): Flow<RawSqlRow> = flow {
        val rows = locked { execution.queryRawRows(sql, parameters) }
        for (row in rows) {
            currentCoroutineContext().ensureActive()
            emit(row)
        }
    }

    override suspend fun <T> transaction(block: suspend SqlTransaction.() -> T): T = runTransaction(null, block)

    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    private suspend fun <T> runTransaction(
        isolation: TransactionIsolation?,
        block: suspend SqlTransaction.() -> T,
    ): T {
        val inheritedTransactions = currentCoroutineContext()[ActivePostgresTransactions]
        if (inheritedTransactions?.contains(this) == true) throw NestedTransactionUnsupported()
        access.lock()
        val scope = TransactionScope()
        var transactionStarted = false
        try {
            ensureOpen()
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) { begin(isolation) }
            transactionStarted = true
            currentCoroutineContext().ensureActive()
            val result = try {
                withContext((inheritedTransactions ?: ActivePostgresTransactions()).including(this)) {
                    scope.block()
                }
            } catch (error: Throwable) {
                withContext(NonCancellable) { rollbackAfter(error) }
                transactionStarted = false
                throw error
            }
            var committed = false
            try {
                scope.requireUsable()
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) { execution.executeControl("COMMIT") }
                committed = true
                transactionStarted = false
                currentCoroutineContext().ensureActive()
            } catch (error: Throwable) {
                if (!committed) {
                    withContext(NonCancellable) { rollbackAfter(error) }
                    transactionStarted = false
                }
                when (error) {
                    is SerializationFailure, is DeadlockFailure -> throw error
                    is DatabaseError -> throw TransactionFailure("Failed to commit PostgreSQL transaction", error)
                    else -> throw error
                }
            }
            return result
        } catch (error: Throwable) {
            if (transactionStarted) {
                withContext(NonCancellable) { rollbackAfter(error) }
                transactionStarted = false
            }
            throw error
        } finally {
            scope.active = false
            access.unlock()
        }
    }

    override suspend fun close() {
        ensureOutsideTransactionScope()
        access.lock()
        try {
            if (!closed) {
                try {
                    endpoint.close()
                } catch (error: PostgresDriverException) {
                    throw error.toDatabaseError("Failed to close PostgreSQL database")
                }
                closed = true
            }
        } finally {
            access.unlock()
        }
    }

    private suspend fun begin(isolation: TransactionIsolation?) {
        val sql = isolation?.let { "BEGIN ISOLATION LEVEL ${it.sql}" } ?: "BEGIN"
        try {
            execution.executeControl(sql)
        } catch (error: DatabaseError) {
            throw TransactionFailure("Failed to begin PostgreSQL transaction", error)
        }
    }

    @Suppress("TooGenericExceptionCaught") // A rollback failure is deliberately retained on the original failure.
    private suspend fun rollbackAfter(original: Throwable) {
        try {
            execution.executeControl("ROLLBACK")
        } catch (rollback: Throwable) {
            original.addSuppressed(TransactionFailure("Failed to roll back PostgreSQL transaction", rollback))
        }
    }

    private suspend fun <T> locked(block: suspend () -> T): T {
        ensureOutsideTransactionScope()
        access.lock()
        return try {
            ensureOpen()
            block()
        } finally {
            access.unlock()
        }
    }

    private suspend fun ensureOutsideTransactionScope() {
        if (currentCoroutineContext()[ActivePostgresTransactions]?.contains(this) == true) {
            throw ExecutionFailure("Use the transaction scope for database operations inside a transaction")
        }
    }

    private fun ensureOpen() {
        if (closed) throw DatabaseClosed()
    }

    private inner class TransactionScope(val rlsGuard: RlsContextGuard = RlsContextGuard()) :
        SqlTransaction,
        RlsContextConnectionGuard by rlsGuard {
        override val dialect = this@HyperdrivePostgresDatabase.dialect
        var active = true
        private val savepoints = SavepointSupport(
            SavepointController(
                create = { name -> this@HyperdrivePostgresDatabase.execution.executeControl("SAVEPOINT $name") },
                rollbackTo = { name ->
                    this@HyperdrivePostgresDatabase.execution.executeControl("ROLLBACK TO SAVEPOINT $name")
                },
                release = { name ->
                    this@HyperdrivePostgresDatabase.execution.executeControl(
                        "RELEASE SAVEPOINT $name",
                    )
                },
            ),
        )

        override suspend fun <T> savepoint(block: suspend SavepointScope.() -> T): T = inScope {
            savepoints.savepoint(this@TransactionScope, block)
        }

        override suspend fun execute(statement: RenderedSql): ExecuteResult = inScope {
            this@HyperdrivePostgresDatabase.execution.execute(statement)
        }

        override fun query(statement: RenderedSql, columns: List<SqlExpression<*>>): Flow<SqlRow> = flow {
            requireActive()
            for (row in this@HyperdrivePostgresDatabase.execution.queryRows(statement, columns)) {
                currentCoroutineContext().ensureActive()
                emit(row)
            }
        }

        override suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult = inScope {
            this@HyperdrivePostgresDatabase.execution.executeRaw(sql, parameters)
        }

        override fun queryRaw(sql: String, parameters: List<Any?>): Flow<RawSqlRow> = flow {
            requireActive()
            for (row in this@HyperdrivePostgresDatabase.execution.queryRawRows(sql, parameters)) {
                currentCoroutineContext().ensureActive()
                emit(row)
            }
        }

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

    internal companion object {
        fun create(
            endpoint: PostgresEndpoint,
            configure: HyperdrivePostgresDatabaseConfiguration.() -> Unit = {},
        ): HyperdrivePostgresDatabase {
            val configuration = HyperdrivePostgresDatabaseConfiguration().apply(configure)
            return HyperdrivePostgresDatabase(
                endpoint,
                SqlTypeCodecRegistry(configuration.typeCodecs()),
                configuration.dialect(),
            )
        }
    }
}

/** Driver configuration without exposing the underlying client or Hyperdrive pool. */
class HyperdrivePostgresDatabaseConfiguration internal constructor() {
    private val extensions = mutableListOf<PostgresExtension>()

    /** Provides the install result. */
    fun install(extension: PostgresExtension) {
        extensions += extension
    }

    internal fun typeCodecs() = PostgresStandardTypes.typeCodecs + extensions.flatMap(PostgresExtension::typeCodecs)

    internal fun dialect(): SqlDialect = PostgresDialect.configured(extensions)
}

private class ActivePostgresTransactions(private val databases: List<HyperdrivePostgresDatabase> = emptyList()) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ActivePostgresTransactions>

    fun contains(database: HyperdrivePostgresDatabase): Boolean = databases.any { it === database }

    fun including(database: HyperdrivePostgresDatabase): ActivePostgresTransactions =
        ActivePostgresTransactions(databases + database)
}

private class PostgresExecution(
    private val endpoint: PostgresEndpoint,
    codecs: SqlTypeCodecRegistry,
    private val ensureOpen: () -> Unit,
) {
    private val values = PostgresValueCodec(codecs)

    suspend fun execute(statement: RenderedSql): ExecuteResult {
        ensureOpen()
        currentCoroutineContext().ensureActive()
        val result = translateFailure { endpoint.execute(toStatement(statement, prepare = true)) }
        currentCoroutineContext().ensureActive()
        return ExecuteResult(result.affectedRows)
    }

    suspend fun queryRows(statement: RenderedSql, columns: List<SqlExpression<*>>): List<SqlRow> {
        ensureOpen()
        currentCoroutineContext().ensureActive()
        val result = translateFailure { endpoint.query(toStatement(statement, prepare = true)) }
        if (result.rows.any { it.size != columns.size }) {
            throw ResultConversionFailure("PostgreSQL returned a row whose value count does not match selected columns")
        }
        return result.rows.map { row -> sqlRow(columns, columns.indices.map { values.decode(columns[it], row[it]) }) }
    }

    suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult {
        val statement = RenderedSql(sql, parameters.map { SqlParameter(it, null) })
        ensureOpen()
        val result = translateFailure { endpoint.execute(toStatement(statement, prepare = false)) }
        return ExecuteResult(result.affectedRows)
    }

    suspend fun queryRawRows(sql: String, parameters: List<Any?>): List<RawSqlRow> {
        val statement = RenderedSql(sql, parameters.map { SqlParameter(it, null) })
        ensureOpen()
        val result = translateFailure { endpoint.query(toStatement(statement, prepare = false)) }
        return result.rows.map { row ->
            if (row.size != result.columnNames.size) {
                throw ResultConversionFailure("PostgreSQL returned a raw row whose value count does not match columns")
            }
            rawSqlRow(result.columnNames, row.map(values::rawValue))
        }
    }

    suspend fun executeControl(sql: String) {
        ensureOpen()
        translateFailure { endpoint.execute(PostgresStatement(sql, emptyList())) }
    }

    private fun toStatement(statement: RenderedSql, prepare: Boolean): PostgresStatement = PostgresStatement(
        statement.sql,
        statement.parameters.mapIndexed { index, parameter -> bind(parameter, index) },
        preparedName = if (prepare) preparedName(statement.sql) else null,
    )

    @Suppress("TooGenericExceptionCaught")
    private fun bind(parameter: SqlParameter<*>, index: Int) = try {
        values.binding(parameter.value, parameter.expression)
    } catch (error: Throwable) {
        throw InvalidBinding(
            "PostgreSQL cannot bind ${parameter.expression?.toString() ?: "parameter ${index + 1}"}",
            error,
        )
    }

    private suspend inline fun <T> translateFailure(crossinline block: suspend () -> T): T = try {
        block()
    } catch (error: PostgresDriverException) {
        throw error.toDatabaseError("PostgreSQL execution failed")
    }
}

private fun PostgresDriverException.toDatabaseError(prefix: String): DatabaseError {
    val metadata = DatabaseErrorMetadata(sqlState = sqlState, vendorCode = sqlState, attributes = attributes)
    val message = "$prefix: ${message ?: "unknown error"}"
    return when (sqlState) {
        SQLSTATE_UNIQUE_VIOLATION -> ConstraintViolation(message, this, sqlState, ConstraintKind.UNIQUE, metadata)

        SQLSTATE_FOREIGN_KEY_VIOLATION ->
            ConstraintViolation(message, this, sqlState, ConstraintKind.FOREIGN_KEY, metadata)

        SQLSTATE_NOT_NULL_VIOLATION -> ConstraintViolation(message, this, sqlState, ConstraintKind.NOT_NULL, metadata)

        SQLSTATE_CHECK_VIOLATION -> ConstraintViolation(message, this, sqlState, ConstraintKind.CHECK, metadata)

        SQLSTATE_SERIALIZATION_FAILURE -> SerializationFailure(message, this, metadata)

        SQLSTATE_DEADLOCK_DETECTED -> DeadlockFailure(message, this, metadata)

        else -> when {
            kind == PostgresFailureKind.CONNECTION || sqlState?.startsWith(SQLSTATE_CONNECTION_CLASS) == true ->
                ConnectionFailure(message, this, metadata)

            sqlState?.startsWith(SQLSTATE_DATA_EXCEPTION_CLASS) == true ->
                InvalidBinding(message, this, sqlState, metadata)

            kind == PostgresFailureKind.RESULT_CONVERSION ->
                ResultConversionFailure(message, this, sqlState, metadata)

            else -> ExecutionFailure(message, this, sqlState, metadata)
        }
    }
}

private val TransactionIsolation.sql: String
    get() = when (this) {
        TransactionIsolation.READ_COMMITTED -> "READ COMMITTED"
        TransactionIsolation.REPEATABLE_READ -> "REPEATABLE READ"
        TransactionIsolation.SERIALIZABLE -> "SERIALIZABLE"
    }

private fun preparedName(sql: String): String {
    var first = PREPARED_HASH_FIRST_SEED
    var second = PREPARED_HASH_SECOND_SEED
    for (character in sql) {
        first = (first xor character.code.toUInt()) * PREPARED_HASH_FIRST_MULTIPLIER
        second = (second + character.code.toUInt()) * PREPARED_HASH_SECOND_MULTIPLIER
    }
    return "kagiroi_${first.toString(PREPARED_HASH_RADIX)}_${second.toString(PREPARED_HASH_RADIX)}_${sql.length}"
}

private const val SQLSTATE_UNIQUE_VIOLATION = "23505"
private const val SQLSTATE_FOREIGN_KEY_VIOLATION = "23503"
private const val SQLSTATE_NOT_NULL_VIOLATION = "23502"
private const val SQLSTATE_CHECK_VIOLATION = "23514"
private const val SQLSTATE_SERIALIZATION_FAILURE = "40001"
private const val SQLSTATE_DEADLOCK_DETECTED = "40P01"
private const val SQLSTATE_CONNECTION_CLASS = "08"
private const val SQLSTATE_DATA_EXCEPTION_CLASS = "22"
private const val PREPARED_HASH_FIRST_SEED = 0x811c9dc5u
private const val PREPARED_HASH_SECOND_SEED = 0x9e3779b9u
private const val PREPARED_HASH_FIRST_MULTIPLIER = 0x01000193u
private const val PREPARED_HASH_SECOND_MULTIPLIER = 0x85ebca6bu
private const val PREPARED_HASH_RADIX = 16
