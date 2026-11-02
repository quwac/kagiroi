package dev.quwac.kagiroi.sqlite.inmemory

import dev.quwac.kagiroi.core.CapabilityKey
import dev.quwac.kagiroi.core.DatabaseCapability
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.sql.ConstraintViolation
import dev.quwac.kagiroi.sql.DatabaseClosed
import dev.quwac.kagiroi.sql.ExecuteResult
import dev.quwac.kagiroi.sql.ExecutionFailure
import dev.quwac.kagiroi.sql.NestedTransactionUnsupported
import dev.quwac.kagiroi.sql.RawSqlRow
import dev.quwac.kagiroi.sql.RenderedSql
import dev.quwac.kagiroi.sql.ReturningCapability
import dev.quwac.kagiroi.sql.SavepointCapability
import dev.quwac.kagiroi.sql.SavepointController
import dev.quwac.kagiroi.sql.SavepointScope
import dev.quwac.kagiroi.sql.SavepointSupport
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
import dev.quwac.kagiroi.sql.TransactionFailure
import dev.quwac.kagiroi.sql.rawSqlRow
import dev.quwac.kagiroi.sql.sqlRow
import dev.quwac.kagiroi.sqlite.SqliteDialect
import dev.quwac.kagiroi.sqlite.SqliteExtension
import dev.quwac.kagiroi.sqlite.SqliteStandardTypes
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** In-memory SQLite WASM database backed by the official SQLite WASM package. */
@Suppress("TooManyFunctions") // Implements both public execution forms and their transaction-scoped equivalents.
class InMemorySqliteDatabase private constructor(
    private val engine: SqliteEngine,
    private val codecs: SqlTypeCodecRegistry,
    override val dialect: SqlDialect,
) : SqlDatabase {

    @Suppress("UNCHECKED_CAST")
    override fun <C : DatabaseCapability> capability(key: CapabilityKey<C>): C? = when (key) {
        SqlCapabilities.Sql -> SqlCapability as C
        SqlCapabilities.Returning -> ReturningCapability as C
        SqlCapabilities.Transactions -> dev.quwac.kagiroi.sql.TransactionCapability as C
        SqlCapabilities.Savepoints -> SavepointCapability as C
        SqlCapabilities.Streaming -> SqlStreamingCapability(SqlStreamingMode.BUFFERED) as C
        else -> null
    }

    private val access = Mutex()
    private var closed = false

    override suspend fun execute(statement: RenderedSql): ExecuteResult = locked {
        executeEngine(statement.sql, bindings(statement.parameters))
    }

    override fun query(statement: RenderedSql, columns: List<SqlExpression<*>>): Flow<SqlRow> = flow {
        val rows = locked {
            queryEngine(statement.sql, bindings(statement.parameters)).map { toSqlRow(it, columns) }
        }
        for (row in rows) {
            currentCoroutineContext().ensureActive()
            emit(row)
        }
    }

    override suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult = locked {
        executeEngine(sql, rawBindings(parameters))
    }

    override fun queryRaw(sql: String, parameters: List<Any?>): Flow<RawSqlRow> = flow {
        val rows = locked {
            queryEngine(sql, rawBindings(parameters)).map { rawSqlRow(it.columnNames, it.values.map(::rawValue)) }
        }
        for (row in rows) {
            currentCoroutineContext().ensureActive()
            emit(row)
        }
    }

    // Every failure from user code, including cancellation, must trigger rollback before propagation.
    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    override suspend fun <T> transaction(block: suspend SqlTransaction.() -> T): T {
        val inheritedTransactions = currentCoroutineContext()[ActiveInMemorySqliteTransactions]
        if (inheritedTransactions?.contains(this) == true) throw NestedTransactionUnsupported()
        access.lock()
        val scope = TransactionScope()
        var transactionStarted = false
        try {
            ensureOpen()
            try {
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) { executeEngine("BEGIN", emptyList()) }
                transactionStarted = true
                currentCoroutineContext().ensureActive()
            } catch (error: dev.quwac.kagiroi.sql.DatabaseError) {
                throw TransactionFailure("Failed to begin transaction", error)
            }
            val result = try {
                withContext((inheritedTransactions ?: ActiveInMemorySqliteTransactions()).including(this)) {
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
                withContext(NonCancellable) { executeEngine("COMMIT", emptyList()) }
                committed = true
                transactionStarted = false
                currentCoroutineContext().ensureActive()
            } catch (error: Throwable) {
                if (!committed) {
                    withContext(NonCancellable) { rollbackAfter(error) }
                    transactionStarted = false
                }
                throw when (error) {
                    is dev.quwac.kagiroi.sql.DatabaseError -> TransactionFailure("Failed to commit transaction", error)
                    else -> error
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
                    engine.close()
                } catch (error: SqliteEngineException) {
                    throw ExecutionFailure(
                        "Failed to close SQLite database: ${error.message}",
                        error,
                        error.vendorCode(),
                    )
                }
                closed = true
            }
        } finally {
            access.unlock()
        }
    }

    suspend fun dispose() = close()

    @Suppress("TooGenericExceptionCaught") // A rollback failure is deliberately retained on the original failure.
    private suspend fun rollbackAfter(original: Throwable) {
        try {
            executeEngine("ROLLBACK", emptyList())
        } catch (rollback: Throwable) {
            original.addSuppressed(TransactionFailure("Failed to roll back transaction", rollback))
        }
    }

    private suspend fun <T> locked(block: () -> T): T {
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
        if (currentCoroutineContext()[ActiveInMemorySqliteTransactions]?.contains(this) == true) {
            throw ExecutionFailure("Use the transaction scope for database operations inside a transaction")
        }
    }

    private fun ensureOpen() {
        if (closed) throw DatabaseClosed()
    }

    private fun executeEngine(sql: String, parameters: List<SqliteBinding>): ExecuteResult =
        translateFailure { ExecuteResult(engine.execute(sql, parameters)) }

    private fun queryEngine(sql: String, parameters: List<SqliteBinding>): List<SqliteEngineRow> =
        translateFailure { engine.query(sql, parameters) }

    private fun bindings(parameters: List<SqlParameter<*>>): List<SqliteBinding> =
        parameters.mapIndexed { index, parameter ->
            bindValue(
                parameter.value,
                parameter.expression?.toString() ?: "parameter ${index + 1}",
                parameter.expression,
            )
        }

    private fun rawBindings(parameters: List<Any?>): List<SqliteBinding> =
        parameters.mapIndexed { index, value -> bindValue(value, "parameter ${index + 1}", null) }

    @Suppress("TooGenericExceptionCaught")
    private fun bindValue(value: Any?, label: String, expression: SqlExpression<*>?): SqliteBinding = try {
        binding(value, expression, codecs)
    } catch (error: Throwable) {
        throw dev.quwac.kagiroi.sql.InvalidValueConversion(label, value, "SQLite bindable value", error)
    }

    private inline fun <T> translateFailure(block: () -> T): T = try {
        block()
    } catch (error: SqliteEngineException) {
        val message = "SQLite execution failed: ${error.message}"
        if (error.resultCode?.and(SQLITE_PRIMARY_RESULT_CODE_MASK) == SQLITE_CONSTRAINT) {
            throw ConstraintViolation(message, error, error.vendorCode())
        }
        throw ExecutionFailure(message, error, error.vendorCode())
    }

    private fun toSqlRow(row: SqliteEngineRow, columns: List<SqlExpression<*>>): SqlRow {
        if (row.values.size != columns.size) {
            throw ExecutionFailure("SQLite returned ${row.values.size} values for ${columns.size} selected columns")
        }
        return sqlRow(columns, columns.indices.map { decode(columns[it], row.values[it], codecs) })
    }

    private inner class TransactionScope : SqlTransaction {
        override val dialect = this@InMemorySqliteDatabase.dialect
        var active = true
        private val savepoints = SavepointSupport(
            SavepointController(
                create = { name -> this@InMemorySqliteDatabase.executeEngine("SAVEPOINT $name", emptyList()) },
                rollbackTo = { name ->
                    this@InMemorySqliteDatabase.executeEngine("ROLLBACK TO SAVEPOINT $name", emptyList())
                },
                release = { name -> this@InMemorySqliteDatabase.executeEngine("RELEASE SAVEPOINT $name", emptyList()) },
            ),
        )

        override suspend fun <T> savepoint(block: suspend SavepointScope.() -> T): T = inScope {
            savepoints.savepoint(this@TransactionScope, block)
        }

        override suspend fun execute(statement: RenderedSql): ExecuteResult = inScope {
            this@InMemorySqliteDatabase.executeEngine(
                statement.sql,
                this@InMemorySqliteDatabase.bindings(statement.parameters),
            )
        }

        override fun query(statement: RenderedSql, columns: List<SqlExpression<*>>): Flow<SqlRow> = flow {
            requireActive()
            val rows = this@InMemorySqliteDatabase.queryEngine(
                statement.sql,
                this@InMemorySqliteDatabase.bindings(statement.parameters),
            )
            for (row in rows) {
                currentCoroutineContext().ensureActive()
                emit(this@InMemorySqliteDatabase.toSqlRow(row, columns))
            }
        }

        override suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult = inScope {
            this@InMemorySqliteDatabase.executeEngine(sql, this@InMemorySqliteDatabase.rawBindings(parameters))
        }

        override fun queryRaw(sql: String, parameters: List<Any?>): Flow<RawSqlRow> = flow {
            requireActive()
            for (row in this@InMemorySqliteDatabase.queryEngine(
                sql,
                this@InMemorySqliteDatabase.rawBindings(parameters),
            )) {
                currentCoroutineContext().ensureActive()
                emit(rawSqlRow(row.columnNames, row.values.map(::rawValue)))
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
        suspend fun inMemory(configure: InMemorySqliteDatabaseConfiguration.() -> Unit = {}): InMemorySqliteDatabase {
            val configuration = InMemorySqliteDatabaseConfiguration().apply(configure)
            val codecs = SqlTypeCodecRegistry(configuration.typeCodecs())
            val engine = try {
                openInMemorySqliteEngine()
            } catch (error: SqliteEngineException) {
                throw ExecutionFailure(
                    "Failed to open in-memory SQLite database: ${error.message}",
                    error,
                    error.vendorCode(),
                )
            }
            return InMemorySqliteDatabase(engine, codecs, configuration.dialect())
        }
    }
}

private class ActiveInMemorySqliteTransactions(private val databases: List<InMemorySqliteDatabase> = emptyList()) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ActiveInMemorySqliteTransactions>

    fun contains(database: InMemorySqliteDatabase): Boolean = databases.any { it === database }

    fun including(database: InMemorySqliteDatabase): ActiveInMemorySqliteTransactions =
        ActiveInMemorySqliteTransactions(
            databases + database,
        )
}

private fun SqliteEngineException.vendorCode(): String? = resultCode?.toString()

class InMemorySqliteDatabaseConfiguration internal constructor() {
    private val extensions = mutableListOf<SqliteExtension>()

    fun install(extension: SqliteExtension) {
        extensions += extension
    }

    internal fun typeCodecs() = SqliteStandardTypes.typeCodecs + extensions.flatMap(SqliteExtension::typeCodecs)

    internal fun dialect(): SqlDialect = SqliteDialect.configured(extensions)
}

private const val SQLITE_CONSTRAINT = 19
private const val SQLITE_PRIMARY_RESULT_CODE_MASK = 0xff
