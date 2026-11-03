package dev.quwac.kagiroi.sqlite.file

import dev.quwac.kagiroi.core.CapabilityKey
import dev.quwac.kagiroi.core.DatabaseCapability
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.sql.ConnectionFailure
import dev.quwac.kagiroi.sql.ConstraintKind
import dev.quwac.kagiroi.sql.ConstraintViolation
import dev.quwac.kagiroi.sql.DatabaseClosed
import dev.quwac.kagiroi.sql.DatabaseError
import dev.quwac.kagiroi.sql.DatabaseErrorMetadata
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
import dev.quwac.kagiroi.sql.TransactionCapability
import dev.quwac.kagiroi.sql.TransactionFailure
import dev.quwac.kagiroi.sql.rawSqlRow
import dev.quwac.kagiroi.sql.sqlRow
import dev.quwac.kagiroi.sqlite.SqliteDialect
import dev.quwac.kagiroi.sqlite.SqliteExtension
import dev.quwac.kagiroi.sqlite.SqliteStandardTypes
import dev.quwac.kagiroi.sqlite.SqliteValueCodec
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

/** File-backed SQLite settings without exposing JavaScript host values or client objects. */
class FileSqliteDatabaseConfiguration internal constructor() {
    /** Opens the file without write access when true. */
    var readOnly: Boolean = false

    /** Rejects a missing database file instead of creating it. */
    var fileMustExist: Boolean = false

    /** Maximum host SQLite lock wait before a busy or locked failure is reported. */
    var busyTimeout: Duration = DEFAULT_BUSY_TIMEOUT

    private val extensions = mutableListOf<SqliteExtension>()

    /** Adds an explicit SQLite storage mapping to this database. */
    fun install(extension: SqliteExtension) {
        extensions += extension
    }

    internal fun snapshot(path: String): FileSqliteDatabaseSettings {
        require(path.isNotBlank()) { "SQLite database path must not be blank" }
        require(path != IN_MEMORY_PATH) { "FileSqliteDatabase requires a filesystem database path" }
        require(!busyTimeout.isNegative() && busyTimeout.isFinite()) {
            "busyTimeout must be a finite non-negative duration"
        }
        require(busyTimeout.inWholeMilliseconds <= Int.MAX_VALUE) { "busyTimeout is too large" }
        return FileSqliteDatabaseSettings(
            host = FileSqliteSettings(
                path = path,
                readOnly = readOnly,
                fileMustExist = fileMustExist,
                busyTimeoutMilliseconds = busyTimeout.inWholeMilliseconds.toInt(),
            ),
            typeCodecs = SqliteStandardTypes.typeCodecs + extensions.flatMap(SqliteExtension::typeCodecs),
            dialect = SqliteDialect.configured(extensions),
        )
    }
}

internal data class FileSqliteDatabaseSettings(
    val host: FileSqliteSettings,
    val typeCodecs: List<dev.quwac.kagiroi.sql.SqlTypeCodec<*, *>>,
    val dialect: SqlDialect,
)

/** Opens one SQLite database file through the JavaScript host's better-sqlite3 client. */
fun FileSqliteDatabase(path: String, configure: FileSqliteDatabaseConfiguration.() -> Unit = {}): FileSqliteDatabase {
    val settings = FileSqliteDatabaseConfiguration().apply(configure).snapshot(path)
    val engine = try {
        openFileSqliteEngine(settings.host)
    } catch (error: FileSqliteDriverException) {
        throw error.toDatabaseError("Failed to open SQLite database")
    }
    return FileSqliteDatabase(engine, SqlTypeCodecRegistry(settings.typeCodecs), settings.dialect)
}

/**
 * Kagiroi SQLite database backed by one filesystem database handle owned by the JavaScript host.
 *
 * Operations are serialized because the underlying client is synchronous. Query results are buffered
 * before they are exposed as a Flow.
 */
@Suppress("TooManyFunctions")
class FileSqliteDatabase internal constructor(
    private val engine: FileSqliteEngine,
    codecs: SqlTypeCodecRegistry,
    override val dialect: SqlDialect,
) : SqlDatabase {
    private val typeCodecs = codecs
    private val access = Mutex()
    private val state = Mutex()
    private var closing = false
    private var closed = false
    private var activeOperations = 0
    private var drainWaiter: CompletableDeferred<Unit>? = null
    private var closeCompletion: CompletableDeferred<Unit>? = null
    private val dedicatedSessionCapability = object : DedicatedSqlSessionCapability {
        override suspend fun <T> use(block: suspend DedicatedSqlSession.() -> T): T = withDedicatedSession {
            block(LeasedSession())
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <C : DatabaseCapability> capability(key: CapabilityKey<C>): C? = when (key) {
        SqlCapabilities.Sql -> SqlCapability as C
        SqlCapabilities.Returning -> ReturningCapability as C
        SqlCapabilities.Transactions -> TransactionCapability as C
        SqlCapabilities.Savepoints -> SavepointCapability as C
        SqlCapabilities.Streaming -> SqlStreamingCapability(SqlStreamingMode.BUFFERED) as C
        SqlCapabilities.DedicatedSession -> dedicatedSessionCapability as C
        else -> null
    }

    override suspend fun execute(statement: RenderedSql): ExecuteResult = withDatabaseAccess {
        executeEngine(statement.sql, bindings(statement.parameters))
    }

    override fun query(statement: RenderedSql, columns: List<SqlExpression<*>>): Flow<SqlRow> = flow {
        val rows = withDatabaseAccess {
            queryEngine(statement.sql, bindings(statement.parameters)).map { toSqlRow(it, columns) }
        }
        for (row in rows) {
            currentCoroutineContext().ensureActive()
            emit(row)
        }
    }

    override suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult = withDatabaseAccess {
        executeEngine(sql, rawBindings(parameters))
    }

    override fun queryRaw(sql: String, parameters: List<Any?>): Flow<RawSqlRow> = flow {
        val rows = withDatabaseAccess {
            queryEngine(sql, rawBindings(parameters)).map { row ->
                rawSqlRow(row.columnNames, row.values.map(::rawValue))
            }
        }
        for (row in rows) {
            currentCoroutineContext().ensureActive()
            emit(row)
        }
    }

    /** Executes one callback on the same physical handle between BEGIN and COMMIT. */
    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    override suspend fun <T> transaction(block: suspend SqlTransaction.() -> T): T {
        val inheritedTransactions = currentCoroutineContext()[ActiveFileSqliteTransactions]
        if (inheritedTransactions?.contains(this) == true) throw NestedTransactionUnsupported()
        enterOperation()
        var accessAcquired = false
        val scope = TransactionScope()
        var transactionStarted = false
        try {
            access.lock()
            accessAcquired = true
            ensureOpen()
            try {
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) { executeEngine("BEGIN", emptyList()) }
                transactionStarted = true
                currentCoroutineContext().ensureActive()
            } catch (error: DatabaseError) {
                throw TransactionFailure("Failed to begin SQLite transaction", error)
            }
            val result = try {
                withContext((inheritedTransactions ?: ActiveFileSqliteTransactions()).including(this)) {
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
                when (error) {
                    is DatabaseError -> throw TransactionFailure("Failed to commit SQLite transaction", error)
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
            if (accessAcquired) access.unlock()
            exitOperation()
        }
    }

    /** Closes the host handle after operations admitted before close have completed. */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun close() {
        ensureOutsideTransactionScope()
        var ownsClose = false
        val completion = state.withLock {
            closeCompletion ?: CompletableDeferred<Unit>().also {
                closing = true
                closeCompletion = it
                ownsClose = true
            }
        }
        if (ownsClose) {
            withContext(NonCancellable) {
                try {
                    awaitDrain()
                    access.lock()
                    try {
                        if (!closed) engine.close()
                        closed = true
                        completion.complete(Unit)
                    } finally {
                        access.unlock()
                    }
                } catch (error: FileSqliteDriverException) {
                    closed = true
                    completion.completeExceptionally(error.toDatabaseError("Failed to close SQLite database"))
                } catch (error: Throwable) {
                    closed = true
                    completion.completeExceptionally(error)
                }
            }
        }
        completion.await()
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun rollbackAfter(original: Throwable) {
        try {
            executeEngine("ROLLBACK", emptyList())
        } catch (rollback: Throwable) {
            original.addSuppressed(rollback)
        }
    }

    private suspend fun <T> withDatabaseAccess(block: () -> T): T {
        ensureOutsideTransactionScope()
        enterOperation()
        var accessAcquired = false
        try {
            access.lock()
            accessAcquired = true
            ensureOpen()
            return block()
        } finally {
            if (accessAcquired) access.unlock()
            exitOperation()
        }
    }

    private suspend fun <T> withDedicatedSession(block: suspend () -> T): T {
        ensureOutsideTransactionScope()
        enterOperation()
        var accessAcquired = false
        try {
            access.lock()
            accessAcquired = true
            ensureOpen()
            return block()
        } finally {
            if (accessAcquired) access.unlock()
            exitOperation()
        }
    }

    private suspend fun enterOperation() {
        state.withLock {
            if (closing || closed) throw DatabaseClosed()
            activeOperations++
        }
    }

    private suspend fun exitOperation() {
        state.withLock {
            activeOperations--
            if (closing && activeOperations == 0) {
                drainWaiter?.complete(Unit)
                drainWaiter = null
            }
        }
    }

    private suspend fun awaitDrain() {
        val waiter = state.withLock {
            if (activeOperations == 0) null else drainWaiter ?: CompletableDeferred<Unit>().also { drainWaiter = it }
        }
        waiter?.await()
    }

    private suspend fun ensureOutsideTransactionScope() {
        if (currentCoroutineContext()[ActiveFileSqliteTransactions]?.contains(this) == true) {
            throw ExecutionFailure("Use the transaction scope for database operations inside a transaction")
        }
    }

    private fun ensureOpen() {
        if (closed) throw DatabaseClosed()
    }

    private fun executeEngine(sql: String, parameters: List<FileSqliteBinding>): ExecuteResult =
        translateFailure { ExecuteResult(engine.execute(sql, parameters)) }

    private fun queryEngine(sql: String, parameters: List<FileSqliteBinding>): List<FileSqliteEngineRow> =
        translateFailure { engine.query(sql, parameters) }

    private fun bindings(parameters: List<SqlParameter<*>>): List<FileSqliteBinding> =
        parameters.mapIndexed { index, parameter ->
            bindValue(
                parameter.value,
                parameter.expression?.toString() ?: "parameter ${index + 1}",
                parameter.expression,
            )
        }

    private fun rawBindings(parameters: List<Any?>): List<FileSqliteBinding> =
        parameters.mapIndexed { index, value -> bindValue(value, "parameter ${index + 1}", null) }

    @Suppress("TooGenericExceptionCaught")
    private fun bindValue(value: Any?, label: String, expression: SqlExpression<*>?): FileSqliteBinding = try {
        SqliteValueCodec(typeCodecs).binding(value, expression)
    } catch (error: Throwable) {
        throw InvalidBinding("SQLite cannot bind $label", error)
    }

    private inline fun <T> translateFailure(block: () -> T): T = try {
        block()
    } catch (error: FileSqliteDriverException) {
        throw error.toDatabaseError("SQLite execution failed")
    }

    @Suppress("TooGenericExceptionCaught")
    private fun toSqlRow(row: FileSqliteEngineRow, columns: List<SqlExpression<*>>): SqlRow {
        if (row.values.size != columns.size) {
            throw ResultConversionFailure(
                "SQLite returned ${row.values.size} values for ${columns.size} selected columns",
            )
        }
        val values = SqliteValueCodec(typeCodecs)
        return try {
            sqlRow(columns, columns.indices.map { index -> values.decode(columns[index], row.values[index]) })
        } catch (error: Throwable) {
            throw ResultConversionFailure("SQLite result cannot be converted to the selected columns", error)
        }
    }

    private fun rawValue(value: FileSqliteValue): Any? = SqliteValueCodec(
        SqlTypeCodecRegistry(emptyList()),
    ).rawValue(value)

    private inner class TransactionScope : SqlTransaction {
        override val dialect: SqlDialect = this@FileSqliteDatabase.dialect
        var active = true
        private val savepoints = SavepointSupport(
            SavepointController(
                create = { name -> this@FileSqliteDatabase.executeEngine("SAVEPOINT $name", emptyList()) },
                rollbackTo = { name ->
                    this@FileSqliteDatabase.executeEngine("ROLLBACK TO SAVEPOINT $name", emptyList())
                },
                release = { name -> this@FileSqliteDatabase.executeEngine("RELEASE SAVEPOINT $name", emptyList()) },
            ),
        )

        override suspend fun <T> savepoint(block: suspend SavepointScope.() -> T): T = inScope {
            savepoints.savepoint(this@TransactionScope, block)
        }

        override suspend fun execute(statement: RenderedSql): ExecuteResult = inScope {
            this@FileSqliteDatabase.executeEngine(
                statement.sql,
                this@FileSqliteDatabase.bindings(statement.parameters),
            )
        }

        override fun query(statement: RenderedSql, columns: List<SqlExpression<*>>): Flow<SqlRow> = flow {
            requireActive()
            val rows = this@FileSqliteDatabase.queryEngine(
                statement.sql,
                this@FileSqliteDatabase.bindings(statement.parameters),
            )
            for (row in rows) {
                currentCoroutineContext().ensureActive()
                emit(this@FileSqliteDatabase.toSqlRow(row, columns))
            }
        }

        override suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult = inScope {
            this@FileSqliteDatabase.executeEngine(sql, this@FileSqliteDatabase.rawBindings(parameters))
        }

        override fun queryRaw(sql: String, parameters: List<Any?>): Flow<RawSqlRow> = flow {
            requireActive()
            for (row in this@FileSqliteDatabase.queryEngine(sql, this@FileSqliteDatabase.rawBindings(parameters))) {
                currentCoroutineContext().ensureActive()
                emit(rawSqlRow(row.columnNames, row.values.map(this@FileSqliteDatabase::rawValue)))
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

    private inner class LeasedSession : DedicatedSqlSession {
        override val dialect: SqlDialect = this@FileSqliteDatabase.dialect

        override suspend fun execute(statement: RenderedSql): ExecuteResult = this@FileSqliteDatabase.executeEngine(
            statement.sql,
            this@FileSqliteDatabase.bindings(statement.parameters),
        )

        override fun query(statement: RenderedSql, columns: List<SqlExpression<*>>): Flow<SqlRow> = flow {
            this@FileSqliteDatabase.queryEngine(
                statement.sql,
                this@FileSqliteDatabase.bindings(statement.parameters),
            ).forEach { row ->
                emit(this@FileSqliteDatabase.toSqlRow(row, columns))
            }
        }

        override suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult =
            this@FileSqliteDatabase.executeEngine(sql, this@FileSqliteDatabase.rawBindings(parameters))

        override fun queryRaw(sql: String, parameters: List<Any?>): Flow<RawSqlRow> = flow {
            this@FileSqliteDatabase.queryEngine(sql, this@FileSqliteDatabase.rawBindings(parameters)).forEach { row ->
                emit(rawSqlRow(row.columnNames, row.values.map(this@FileSqliteDatabase::rawValue)))
            }
        }
    }
}

private class ActiveFileSqliteTransactions(private val databases: List<FileSqliteDatabase> = emptyList()) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ActiveFileSqliteTransactions>

    fun contains(database: FileSqliteDatabase): Boolean = databases.any { it === database }

    fun including(database: FileSqliteDatabase): ActiveFileSqliteTransactions =
        ActiveFileSqliteTransactions(databases + database)
}

@Suppress("CyclomaticComplexMethod")
private fun FileSqliteDriverException.toDatabaseError(prefix: String): DatabaseError {
    val attributes = attributes + buildMap {
        sqliteCode?.let { put("sqliteCode", it) }
        extendedResultCode?.let { put("extendedResultCode", it.toString()) }
    }
    val metadata = DatabaseErrorMetadata(vendorCode = sqliteCode, attributes = attributes)
    val message = "$prefix: ${message ?: "unknown error"}"
    return when {
        sqliteCode.isConstraint("UNIQUE") || sqliteCode.isConstraint("PRIMARYKEY") ->
            ConstraintViolation(message, this, sqliteCode, ConstraintKind.UNIQUE, metadata)

        sqliteCode.isConstraint("FOREIGNKEY") ->
            ConstraintViolation(message, this, sqliteCode, ConstraintKind.FOREIGN_KEY, metadata)

        sqliteCode.isConstraint("NOTNULL") ->
            ConstraintViolation(message, this, sqliteCode, ConstraintKind.NOT_NULL, metadata)

        sqliteCode.isConstraint("CHECK") ->
            ConstraintViolation(message, this, sqliteCode, ConstraintKind.CHECK, metadata)

        sqliteCode == SQLITE_BUSY || sqliteCode == SQLITE_LOCKED || sqliteCode?.startsWith("${SQLITE_BUSY}_") == true ||
            sqliteCode?.startsWith("${SQLITE_LOCKED}_") == true ->
            LockTimeoutFailure(message, this, metadata)

        kind == FileSqliteFailureKind.OPEN || sqliteCode == SQLITE_CANTOPEN || sqliteCode?.startsWith(
            SQLITE_IOERR,
        ) == true ->
            ConnectionFailure(message, this, metadata)

        sqliteCode == SQLITE_RANGE || sqliteCode == SQLITE_MISMATCH || sqliteCode == SQLITE_TOOBIG ->
            InvalidBinding(message, this, sqliteCode, metadata)

        kind == FileSqliteFailureKind.RESULT_CONVERSION ->
            ResultConversionFailure(message, this, sqliteCode, metadata)

        else -> ExecutionFailure(message, this, sqliteCode, metadata)
    }
}

private fun String?.isConstraint(suffix: String): Boolean = this == "${SQLITE_CONSTRAINT}_$suffix"

private const val IN_MEMORY_PATH = ":memory:"
private const val SQLITE_BUSY = "SQLITE_BUSY"
private const val SQLITE_CANTOPEN = "SQLITE_CANTOPEN"
private const val SQLITE_CONSTRAINT = "SQLITE_CONSTRAINT"
private const val SQLITE_IOERR = "SQLITE_IOERR"
private const val SQLITE_LOCKED = "SQLITE_LOCKED"
private const val SQLITE_MISMATCH = "SQLITE_MISMATCH"
private const val SQLITE_RANGE = "SQLITE_RANGE"
private const val SQLITE_TOOBIG = "SQLITE_TOOBIG"
private val DEFAULT_BUSY_TIMEOUT = 5.seconds
