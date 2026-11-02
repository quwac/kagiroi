@file:Suppress("TooManyFunctions")

package dev.quwac.kagiroi.pglite

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
import dev.quwac.kagiroi.sql.LockTimeoutFailure
import dev.quwac.kagiroi.sql.NestedTransactionUnsupported
import dev.quwac.kagiroi.sql.RawSqlRow
import dev.quwac.kagiroi.sql.RenderedSql
import dev.quwac.kagiroi.sql.ResultConversionFailure
import dev.quwac.kagiroi.sql.ReturningCapability
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

/** Typed PGlite storage choices without leaking its JavaScript filesystem values. */
sealed interface PGliteStorage {
    /** Ephemeral storage available on every PGlite host. */
    data object Memory : PGliteStorage

    /** A Node.js or Bun filesystem directory. */
    data class FileSystem(val path: String) : PGliteStorage

    /** A browser IndexedDB database name. */
    data class IndexedDb(val name: String) : PGliteStorage
}

/** PGlite-only initialization settings. */
class PGliteDatabaseConfiguration internal constructor() {
    /** Let PGlite defer persisted filesystem flushes when its host supports that mode. */
    var relaxedDurability: Boolean = false

    private val extensions = mutableListOf<PostgresExtension>()

    /** Installs PostgreSQL type mappings and codecs without changing PostgreSQL SQL semantics. */
    fun install(extension: PostgresExtension) {
        extensions += extension
    }

    internal fun typeCodecs() = PostgresStandardTypes.typeCodecs + extensions.flatMap(PostgresExtension::typeCodecs)

    internal fun dialect(): SqlDialect = PostgresDialect.configured(extensions)
}

/** Embedded PostgreSQL execution through PGlite's JavaScript API. */
class PGliteDatabase private constructor(
    private val endpoint: PGliteEndpoint,
    codecs: SqlTypeCodecRegistry,
    override val dialect: SqlDialect,
) : SqlDatabase {
    private val execution = PGliteExecution(endpoint, codecs, ::ensureOpen)
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

    override suspend fun close() {
        ensureOutsideTransactionScope()
        access.lock()
        try {
            if (!closed) {
                try {
                    endpoint.close()
                } catch (error: PGliteDriverException) {
                    throw error.toDatabaseError("Failed to close PGlite database")
                }
                closed = true
            }
        } finally {
            access.unlock()
        }
    }

    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    private suspend fun <T> runTransaction(
        isolation: TransactionIsolation?,
        block: suspend SqlTransaction.() -> T,
    ): T {
        val inheritedTransactions = currentCoroutineContext()[ActivePGliteTransactions]
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
                withContext((inheritedTransactions ?: ActivePGliteTransactions()).including(this)) {
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
                    is DatabaseError -> throw TransactionFailure("Failed to commit PGlite transaction", error)
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

    private suspend fun begin(isolation: TransactionIsolation?) {
        val sql = isolation?.let { "BEGIN ISOLATION LEVEL ${it.sql}" } ?: "BEGIN"
        try {
            execution.executeControl(sql)
        } catch (error: DatabaseError) {
            throw TransactionFailure("Failed to begin PGlite transaction", error)
        }
    }

    @Suppress("TooGenericExceptionCaught") // A rollback failure is deliberately retained on the original failure.
    private suspend fun rollbackAfter(original: Throwable) {
        try {
            execution.executeControl("ROLLBACK")
        } catch (rollback: Throwable) {
            original.addSuppressed(TransactionFailure("Failed to roll back PGlite transaction", rollback))
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
        if (currentCoroutineContext()[ActivePGliteTransactions]?.contains(this) == true) {
            throw ExecutionFailure("Use the transaction scope for database operations inside a transaction")
        }
    }

    private fun ensureOpen() {
        if (closed) throw DatabaseClosed()
    }

    private inner class TransactionScope : SqlTransaction {
        override val dialect = this@PGliteDatabase.dialect
        var active = true
        private val savepoints = SavepointSupport(
            SavepointController(
                create = { name -> this@PGliteDatabase.execution.executeControl("SAVEPOINT $name") },
                rollbackTo = { name -> this@PGliteDatabase.execution.executeControl("ROLLBACK TO SAVEPOINT $name") },
                release = { name -> this@PGliteDatabase.execution.executeControl("RELEASE SAVEPOINT $name") },
            ),
        )

        override suspend fun <T> savepoint(block: suspend SavepointScope.() -> T): T = inScope {
            savepoints.savepoint(this@TransactionScope, block)
        }

        override suspend fun execute(statement: RenderedSql): ExecuteResult = inScope {
            this@PGliteDatabase.execution.execute(statement)
        }

        override fun query(statement: RenderedSql, columns: List<SqlExpression<*>>): Flow<SqlRow> = flow {
            requireActive()
            for (row in this@PGliteDatabase.execution.queryRows(statement, columns)) {
                currentCoroutineContext().ensureActive()
                emit(row)
            }
        }

        override suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult = inScope {
            this@PGliteDatabase.execution.executeRaw(sql, parameters)
        }

        override fun queryRaw(sql: String, parameters: List<Any?>): Flow<RawSqlRow> = flow {
            requireActive()
            for (row in this@PGliteDatabase.execution.queryRawRows(sql, parameters)) {
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

    companion object {
        /** Creates an ephemeral in-memory PGlite database. */
        suspend fun inMemory(configure: PGliteDatabaseConfiguration.() -> Unit = {}): PGliteDatabase =
            open(PGliteStorage.Memory, configure)

        /** Opens PGlite with explicit, typed persistence storage. */
        suspend fun open(
            storage: PGliteStorage,
            configure: PGliteDatabaseConfiguration.() -> Unit = {},
        ): PGliteDatabase {
            val configuration = PGliteDatabaseConfiguration().apply(configure)
            val endpoint = try {
                createPGliteEndpoint(PGliteSettings(storage.dataDirectory(), configuration.relaxedDurability))
            } catch (error: PGliteDriverException) {
                throw error.toDatabaseError("Failed to create PGlite database")
            }
            return PGliteDatabase(
                endpoint,
                SqlTypeCodecRegistry(configuration.typeCodecs()),
                configuration.dialect(),
            )
        }
    }
}

private fun PGliteStorage.dataDirectory(): String = when (this) {
    PGliteStorage.Memory -> "memory://"

    is PGliteStorage.FileSystem -> path.requirePGliteStorageName("filesystem path")

    is PGliteStorage.IndexedDb -> {
        val databaseName = name.requirePGliteStorageName("IndexedDB name")
        "idb://$databaseName"
    }
}

private fun String.requirePGliteStorageName(label: String): String {
    require(isNotBlank()) { "$label must not be blank" }
    require("://" !in this) { "$label must not contain a PGlite storage scheme" }
    return this
}

private class ActivePGliteTransactions(private val databases: List<PGliteDatabase> = emptyList()) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ActivePGliteTransactions>

    fun contains(database: PGliteDatabase): Boolean = databases.any { it === database }

    fun including(database: PGliteDatabase): ActivePGliteTransactions = ActivePGliteTransactions(databases + database)
}

private class PGliteExecution(
    private val endpoint: PGliteEndpoint,
    codecs: SqlTypeCodecRegistry,
    private val ensureOpen: () -> Unit,
) {
    private val values = PostgresValueCodec(codecs)

    suspend fun execute(statement: RenderedSql): ExecuteResult {
        ensureOpen()
        currentCoroutineContext().ensureActive()
        val result = translateFailure { endpoint.execute(toStatement(statement)) }
        currentCoroutineContext().ensureActive()
        return ExecuteResult(result.affectedRows)
    }

    suspend fun queryRows(statement: RenderedSql, columns: List<SqlExpression<*>>): List<SqlRow> {
        ensureOpen()
        currentCoroutineContext().ensureActive()
        val result = translateFailure { endpoint.query(toStatement(statement)) }
        currentCoroutineContext().ensureActive()
        if (result.rows.any { it.size != columns.size }) {
            throw ResultConversionFailure("PGlite returned a row whose value count does not match selected columns")
        }
        return result.rows.map { row -> sqlRow(columns, columns.indices.map { values.decode(columns[it], row[it]) }) }
    }

    suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult =
        execute(RenderedSql(sql, parameters.map { SqlParameter(it, null) }))

    suspend fun queryRawRows(sql: String, parameters: List<Any?>): List<RawSqlRow> {
        val result = translateFailure {
            endpoint.query(toStatement(RenderedSql(sql, parameters.map { SqlParameter(it, null) })))
        }
        return result.rows.map { row ->
            if (row.size != result.columnNames.size) {
                throw ResultConversionFailure("PGlite returned a raw row whose value count does not match columns")
            }
            rawSqlRow(result.columnNames, row.map(values::rawValue))
        }
    }

    suspend fun executeControl(sql: String) {
        ensureOpen()
        translateFailure { endpoint.execute(PGliteStatement(sql, emptyList())) }
    }

    private fun toStatement(statement: RenderedSql): PGliteStatement = PGliteStatement(
        statement.sql,
        statement.parameters.mapIndexed { index, parameter -> bind(parameter, index) },
    )

    @Suppress("TooGenericExceptionCaught")
    private fun bind(parameter: SqlParameter<*>, index: Int) = try {
        values.binding(parameter.value, parameter.expression)
    } catch (error: Throwable) {
        throw InvalidBinding(
            "PGlite cannot bind ${parameter.column?.toString() ?: "parameter ${index + 1}"}",
            error,
        )
    }

    private suspend inline fun <T> translateFailure(crossinline block: suspend () -> T): T = try {
        block()
    } catch (error: PGliteDriverException) {
        throw error.toDatabaseError("PGlite execution failed")
    }
}

private fun PGliteDriverException.toDatabaseError(prefix: String): DatabaseError {
    val metadata = DatabaseErrorMetadata(sqlState = sqlState, vendorCode = sqlState, attributes = attributes)
    val message = "$prefix: ${message ?: "unknown error"}"
    constraintKind()?.let { return ConstraintViolation(message, this, sqlState, it, metadata) }
    return when (sqlState) {
        SQLSTATE_SERIALIZATION_FAILURE -> SerializationFailure(message, this, metadata)
        SQLSTATE_DEADLOCK_DETECTED -> DeadlockFailure(message, this, metadata)
        SQLSTATE_LOCK_NOT_AVAILABLE -> LockTimeoutFailure(message, this, metadata)
        else -> nonConstraintError(message, metadata)
    }
}

private fun PGliteDriverException.constraintKind(): ConstraintKind? = when (sqlState) {
    SQLSTATE_UNIQUE_VIOLATION -> ConstraintKind.UNIQUE
    SQLSTATE_FOREIGN_KEY_VIOLATION -> ConstraintKind.FOREIGN_KEY
    SQLSTATE_NOT_NULL_VIOLATION -> ConstraintKind.NOT_NULL
    SQLSTATE_CHECK_VIOLATION -> ConstraintKind.CHECK
    else -> null
}

private fun PGliteDriverException.nonConstraintError(message: String, metadata: DatabaseErrorMetadata): DatabaseError =
    when {
        kind == PGliteFailureKind.INITIALIZATION || sqlState?.startsWith(SQLSTATE_CONNECTION_CLASS) == true ->
            ConnectionFailure(message, this, metadata)

        sqlState?.startsWith(SQLSTATE_DATA_EXCEPTION_CLASS) == true ->
            InvalidBinding(message, this, sqlState, metadata)

        kind == PGliteFailureKind.RESULT_CONVERSION -> ResultConversionFailure(message, this, sqlState, metadata)

        else -> ExecutionFailure(message, this, sqlState, metadata)
    }

private val TransactionIsolation.sql: String
    get() = when (this) {
        TransactionIsolation.READ_COMMITTED -> "READ COMMITTED"
        TransactionIsolation.REPEATABLE_READ -> "REPEATABLE READ"
        TransactionIsolation.SERIALIZABLE -> "SERIALIZABLE"
    }

private const val SQLSTATE_UNIQUE_VIOLATION = "23505"
private const val SQLSTATE_FOREIGN_KEY_VIOLATION = "23503"
private const val SQLSTATE_NOT_NULL_VIOLATION = "23502"
private const val SQLSTATE_CHECK_VIOLATION = "23514"
private const val SQLSTATE_SERIALIZATION_FAILURE = "40001"
private const val SQLSTATE_DEADLOCK_DETECTED = "40P01"
private const val SQLSTATE_LOCK_NOT_AVAILABLE = "55P03"
private const val SQLSTATE_CONNECTION_CLASS = "08"
private const val SQLSTATE_DATA_EXCEPTION_CLASS = "22"
