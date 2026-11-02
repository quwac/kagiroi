package dev.quwac.kagiroi.mysql.hyperdrive

import dev.quwac.kagiroi.core.CapabilityKey
import dev.quwac.kagiroi.core.DatabaseCapability
import dev.quwac.kagiroi.mysql.MySqlDialect
import dev.quwac.kagiroi.mysql.MySqlExtension
import dev.quwac.kagiroi.mysql.MySqlStandardTypes
import dev.quwac.kagiroi.mysql.MySqlValueCodec
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
import dev.quwac.kagiroi.sql.SavepointCapability
import dev.quwac.kagiroi.sql.SavepointController
import dev.quwac.kagiroi.sql.SavepointScope
import dev.quwac.kagiroi.sql.SavepointSupport
import dev.quwac.kagiroi.sql.SerializationFailure
import dev.quwac.kagiroi.sql.SqlCapabilities
import dev.quwac.kagiroi.sql.SqlCapability
import dev.quwac.kagiroi.sql.SqlDatabase
import dev.quwac.kagiroi.sql.SqlDdlDialect
import dev.quwac.kagiroi.sql.SqlDialect
import dev.quwac.kagiroi.sql.SqlParameter
import dev.quwac.kagiroi.sql.SqlRow
import dev.quwac.kagiroi.sql.SqlSelectPagination
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

/** Kagiroi MySQL database backed by a Cloudflare Hyperdrive binding. */
@Suppress("TooManyFunctions")
class HyperdriveMySqlDatabase private constructor(
    private val endpoint: MySqlEndpoint,
    codecs: SqlTypeCodecRegistry,
    override val dialect: SqlDialect,
) : SqlDatabase {
    private val execution = MySqlExecution(endpoint, codecs, ::ensureOpen)
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
        val inheritedTransactions = currentCoroutineContext()[ActiveMySqlTransactions]
        if (inheritedTransactions?.contains(this) == true) throw NestedTransactionUnsupported()
        access.lock()
        val scope = TransactionScope()
        try {
            ensureOpen()
            begin(isolation)
            val result = try {
                withContext((inheritedTransactions ?: ActiveMySqlTransactions()).including(this)) {
                    scope.block()
                }
            } catch (error: Throwable) {
                withContext(NonCancellable) { rollbackAfter(error) }
                throw error
            }
            try {
                scope.requireUsable()
                execution.executeControl("COMMIT")
            } catch (error: Throwable) {
                withContext(NonCancellable) { rollbackAfter(error) }
                when (error) {
                    is SerializationFailure, is DeadlockFailure, is LockTimeoutFailure -> throw error
                    is DatabaseError -> throw TransactionFailure("Failed to commit MySQL transaction", error)
                    else -> throw error
                }
            }
            return result
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
                } catch (error: MySqlDriverException) {
                    throw error.toDatabaseError("Failed to close MySQL database")
                }
                closed = true
            }
        } finally {
            access.unlock()
        }
    }

    private suspend fun begin(isolation: TransactionIsolation?) {
        try {
            if (isolation != null) execution.executeControl("SET TRANSACTION ISOLATION LEVEL ${isolation.sql}")
            execution.executeControl("START TRANSACTION")
        } catch (error: DatabaseError) {
            throw TransactionFailure("Failed to begin MySQL transaction", error)
        }
    }

    private suspend fun rollbackAfter(original: Throwable) {
        try {
            execution.executeControl("ROLLBACK")
        } catch (rollback: DatabaseError) {
            throw TransactionFailure(
                "Failed to roll back MySQL transaction after: ${original.message}",
                rollback,
            )
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
        if (currentCoroutineContext()[ActiveMySqlTransactions]?.contains(this) == true) {
            throw ExecutionFailure("Use the transaction scope for database operations inside a transaction")
        }
    }

    private fun ensureOpen() {
        if (closed) throw DatabaseClosed()
    }

    private inner class TransactionScope : SqlTransaction {
        override val dialect = this@HyperdriveMySqlDatabase.dialect
        var active = true
        private val savepoints = SavepointSupport(
            SavepointController(
                create = { name -> this@HyperdriveMySqlDatabase.execution.executeControl("SAVEPOINT $name") },
                rollbackTo = { name ->
                    this@HyperdriveMySqlDatabase.execution.executeControl("ROLLBACK TO SAVEPOINT $name")
                },
                release = { name -> this@HyperdriveMySqlDatabase.execution.executeControl("RELEASE SAVEPOINT $name") },
            ),
        )

        override suspend fun <T> savepoint(block: suspend SavepointScope.() -> T): T = inScope {
            savepoints.savepoint(this@TransactionScope, block)
        }

        override suspend fun execute(statement: RenderedSql): ExecuteResult = inScope {
            this@HyperdriveMySqlDatabase.execution.execute(statement)
        }

        override fun query(statement: RenderedSql, columns: List<SqlExpression<*>>): Flow<SqlRow> = flow {
            requireActive()
            for (row in this@HyperdriveMySqlDatabase.execution.queryRows(statement, columns)) {
                currentCoroutineContext().ensureActive()
                emit(row)
            }
        }

        override suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult = inScope {
            this@HyperdriveMySqlDatabase.execution.executeRaw(sql, parameters)
        }

        override fun queryRaw(sql: String, parameters: List<Any?>): Flow<RawSqlRow> = flow {
            requireActive()
            for (row in this@HyperdriveMySqlDatabase.execution.queryRawRows(sql, parameters)) {
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
            endpoint: MySqlEndpoint,
            configure: HyperdriveMySqlDatabaseConfiguration.() -> Unit = {},
        ): HyperdriveMySqlDatabase {
            val configuration = HyperdriveMySqlDatabaseConfiguration().apply(configure)
            return HyperdriveMySqlDatabase(
                endpoint,
                SqlTypeCodecRegistry(configuration.typeCodecs()),
                configuration.dialect(),
            )
        }
    }
}

/** Driver configuration without exposing the underlying mysql2 connection or Hyperdrive pool. */
class HyperdriveMySqlDatabaseConfiguration internal constructor() {
    private val extensions = mutableListOf<MySqlExtension>()
    private var configuredDialect: SqlDialect? = null

    fun install(extension: MySqlExtension) {
        extensions += extension
    }

    /**
     * Selects a dialect for a MySQL-protocol-compatible Hyperdrive origin.
     *
     * The Cloudflare binding and mysql2 query transport do not acquire database SQL semantics.
     */
    fun dialect(value: SqlDialect) {
        configuredDialect = value
    }

    internal fun typeCodecs() = MySqlStandardTypes.typeCodecs + extensions.flatMap(MySqlExtension::typeCodecs)

    internal fun dialect(): SqlDialect = hyperdrivePaginationDialect(
        configuredDialect ?: MySqlDialect.configured(extensions),
    )
}

/**
 * mysql2's Cloudflare-compatible query transport serializes positional parameters as strings.
 *
 * MySQL rejects quoted values in a LIMIT or OFFSET clause, so this adapter writes the already
 * validated query-model values into those clauses. All application values remain parameters.
 */
private fun hyperdrivePaginationDialect(delegate: SqlDialect): SqlDialect = when (delegate) {
    is SqlDdlDialect -> HyperdriveMySqlDdlDialect(delegate)
    else -> HyperdriveMySqlDialect(delegate)
}

private class HyperdriveMySqlDialect(private val delegate: SqlDialect) : SqlDialect by delegate {
    override fun selectPagination(limit: Int?, offset: Int?, hasOrderBy: Boolean): SqlSelectPagination =
        HyperdriveMySqlPagination(limit, offset)
}

private class HyperdriveMySqlDdlDialect(private val delegate: SqlDdlDialect) : SqlDdlDialect by delegate {
    override fun selectPagination(limit: Int?, offset: Int?, hasOrderBy: Boolean): SqlSelectPagination =
        HyperdriveMySqlPagination(limit, offset)
}

private class HyperdriveMySqlPagination(private val limit: Int?, private val offset: Int?) : SqlSelectPagination {
    override val selectParameters: List<Int> = emptyList()
    override val trailingParameters: List<Int> = emptyList()

    override fun renderAfterSelect(markers: List<String>): String = ""

    override fun renderAfterOrderBy(markers: List<String>): String = buildString {
        limit?.let { append(" LIMIT ").append(it) }
        offset?.let { append(" OFFSET ").append(it) }
    }
}

private class ActiveMySqlTransactions(private val databases: List<HyperdriveMySqlDatabase> = emptyList()) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ActiveMySqlTransactions>

    fun contains(database: HyperdriveMySqlDatabase): Boolean = databases.any { it === database }

    fun including(database: HyperdriveMySqlDatabase): ActiveMySqlTransactions =
        ActiveMySqlTransactions(databases + database)
}

private class MySqlExecution(
    private val endpoint: MySqlEndpoint,
    codecs: SqlTypeCodecRegistry,
    private val ensureOpen: () -> Unit,
) {
    private val values = MySqlValueCodec(codecs)

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
        if (result.rows.any { it.size != columns.size }) {
            throw ResultConversionFailure("MySQL returned a row whose value count does not match selected columns")
        }
        return result.rows.map { row -> sqlRow(columns, columns.indices.map { values.decode(columns[it], row[it]) }) }
    }

    suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult {
        val statement = RenderedSql(sql, parameters.map { SqlParameter(it, null) })
        ensureOpen()
        val result = translateFailure { endpoint.execute(toStatement(statement)) }
        return ExecuteResult(result.affectedRows)
    }

    suspend fun queryRawRows(sql: String, parameters: List<Any?>): List<RawSqlRow> {
        val statement = RenderedSql(sql, parameters.map { SqlParameter(it, null) })
        ensureOpen()
        val result = translateFailure { endpoint.query(toStatement(statement)) }
        return result.rows.map { row ->
            if (row.size != result.columnNames.size) {
                throw ResultConversionFailure("MySQL returned a raw row whose value count does not match columns")
            }
            rawSqlRow(result.columnNames, row.map(values::rawValue))
        }
    }

    suspend fun executeControl(sql: String) {
        ensureOpen()
        translateFailure { endpoint.execute(MySqlStatement(sql, emptyList())) }
    }

    private fun toStatement(statement: RenderedSql): MySqlStatement = MySqlStatement(
        statement.sql,
        statement.parameters.mapIndexed { index, parameter -> bind(parameter, index) },
    )

    @Suppress("TooGenericExceptionCaught")
    private fun bind(parameter: SqlParameter<*>, index: Int) = try {
        values.binding(parameter.value, parameter.expression)
    } catch (error: Throwable) {
        throw InvalidBinding(
            "MySQL cannot bind ${parameter.expression?.toString() ?: "parameter ${index + 1}"}",
            error,
        )
    }

    private suspend inline fun <T> translateFailure(crossinline block: suspend () -> T): T = try {
        block()
    } catch (error: MySqlDriverException) {
        throw error.toDatabaseError("MySQL execution failed")
    }
}

@Suppress("CyclomaticComplexMethod")
private fun MySqlDriverException.toDatabaseError(prefix: String): DatabaseError {
    val metadata = DatabaseErrorMetadata(sqlState = sqlState, vendorCode = vendorCode, attributes = attributes)
    val message = "$prefix: ${message ?: "unknown error"}"
    return when (errorNumber) {
        ERROR_DUPLICATE_ENTRY -> ConstraintViolation(message, this, vendorCode, ConstraintKind.UNIQUE, metadata)

        ERROR_FOREIGN_KEY_REFERENCED, ERROR_FOREIGN_KEY_CHILD ->
            ConstraintViolation(message, this, vendorCode, ConstraintKind.FOREIGN_KEY, metadata)

        ERROR_BAD_NULL -> ConstraintViolation(message, this, vendorCode, ConstraintKind.NOT_NULL, metadata)

        ERROR_CHECK_CONSTRAINT_MYSQL,
        ERROR_CHECK_CONSTRAINT_MARIADB,
        -> ConstraintViolation(message, this, vendorCode, ConstraintKind.CHECK, metadata)

        ERROR_LOCK_DEADLOCK -> DeadlockFailure(message, this, metadata)

        ERROR_LOCK_WAIT_TIMEOUT -> LockTimeoutFailure(message, this, metadata)

        else -> when {
            kind == MySqlFailureKind.CONNECTION || sqlState?.startsWith(SQLSTATE_CONNECTION_CLASS) == true ||
                errorNumber in CONNECTION_ERROR_NUMBERS -> ConnectionFailure(message, this, metadata)

            sqlState == SQLSTATE_SERIALIZATION_FAILURE -> SerializationFailure(message, this, metadata)

            sqlState?.startsWith(SQLSTATE_DATA_EXCEPTION_CLASS) == true ->
                InvalidBinding(message, this, vendorCode, metadata)

            kind == MySqlFailureKind.RESULT_CONVERSION ->
                ResultConversionFailure(message, this, vendorCode, metadata)

            else -> ExecutionFailure(message, this, vendorCode, metadata)
        }
    }
}

private val TransactionIsolation.sql: String
    get() = when (this) {
        TransactionIsolation.READ_COMMITTED -> "READ COMMITTED"
        TransactionIsolation.REPEATABLE_READ -> "REPEATABLE READ"
        TransactionIsolation.SERIALIZABLE -> "SERIALIZABLE"
    }

private const val ERROR_DUPLICATE_ENTRY = 1062
private const val ERROR_BAD_NULL = 1048
private const val ERROR_LOCK_WAIT_TIMEOUT = 1205
private const val ERROR_LOCK_DEADLOCK = 1213
private const val ERROR_FOREIGN_KEY_REFERENCED = 1451
private const val ERROR_FOREIGN_KEY_CHILD = 1452
private const val ERROR_CHECK_CONSTRAINT_MYSQL = 3819
private const val ERROR_CHECK_CONSTRAINT_MARIADB = 4025
private const val SQLSTATE_CONNECTION_CLASS = "08"
private const val SQLSTATE_DATA_EXCEPTION_CLASS = "22"
private const val SQLSTATE_SERIALIZATION_FAILURE = "40001"
private val CONNECTION_ERROR_NUMBERS = setOf(2002, 2003, 2006, 2013, 2055)
