@file:Suppress("SwallowedException", "ThrowsCount", "TooGenericExceptionCaught", "TooManyFunctions")

package dev.quwac.kagiroi.sqlserver.node

import dev.quwac.kagiroi.core.CapabilityKey
import dev.quwac.kagiroi.core.DatabaseCapability
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
import dev.quwac.kagiroi.sql.OutputCapability
import dev.quwac.kagiroi.sql.RawSqlRow
import dev.quwac.kagiroi.sql.RenderedSql
import dev.quwac.kagiroi.sql.ResultConversionFailure
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
import dev.quwac.kagiroi.sql.SqlTypeCodec
import dev.quwac.kagiroi.sql.SqlTypeCodecRegistry
import dev.quwac.kagiroi.sql.TransactionCapability
import dev.quwac.kagiroi.sql.TransactionFailure
import dev.quwac.kagiroi.sql.TransactionIsolation
import dev.quwac.kagiroi.sql.TransactionIsolationCapability
import dev.quwac.kagiroi.sql.rawSqlRow
import dev.quwac.kagiroi.sql.sqlRow
import dev.quwac.kagiroi.sqlserver.SqlServerDialect
import dev.quwac.kagiroi.sqlserver.SqlServerExtension
import dev.quwac.kagiroi.sqlserver.SqlServerStandardTypes
import dev.quwac.kagiroi.sqlserver.SqlServerValueCodec
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** TLS behavior for direct mssql connections. Secure certificate verification is the default. */
enum class NodeSqlServerTlsMode {
    DISABLED,
    REQUIRE,
    VERIFY_FULL,
}

/** TLS inputs kept independent from Node.js TLS and tedious option types. */
class NodeSqlServerTlsConfiguration internal constructor() {
    var mode: NodeSqlServerTlsMode = NodeSqlServerTlsMode.VERIFY_FULL
    var certificateAuthority: String? = null
    var hostNameInCertificate: String? = null

    internal fun snapshot(): NodeSqlServerTlsSettings {
        require(mode != NodeSqlServerTlsMode.DISABLED || certificateAuthority == null) {
            "certificateAuthority cannot be configured when TLS is disabled"
        }
        require(mode == NodeSqlServerTlsMode.VERIFY_FULL || hostNameInCertificate == null) {
            "hostNameInCertificate requires VERIFY_FULL TLS mode"
        }
        return NodeSqlServerTlsSettings(mode, certificateAuthority, hostNameInCertificate)
    }
}

/** Connection-pool settings implemented by node-mssql. */
class NodeSqlServerPoolConfiguration internal constructor() {
    var minConnections: Int = 0
    var maxConnections: Int = DEFAULT_MAX_CONNECTIONS
    var connectionTimeout: Duration = DEFAULT_CONNECTION_TIMEOUT
    var idleTimeout: Duration = DEFAULT_IDLE_TIMEOUT

    internal fun snapshot(): NodeSqlServerPoolSettings {
        require(minConnections >= 0) { "minConnections must not be negative" }
        require(maxConnections > 0) { "maxConnections must be positive" }
        require(minConnections <= maxConnections) { "minConnections must not exceed maxConnections" }
        return NodeSqlServerPoolSettings(
            minConnections,
            maxConnections,
            connectionTimeout.milliseconds("connectionTimeout"),
            idleTimeout.milliseconds("idleTimeout"),
        )
    }
}

/** Direct Node SQL Server configuration without exposing mssql pools, requests, or tedious types. */
class NodeSqlServerDatabaseConfiguration internal constructor() {
    var host: String? = null
    var port: Int = DEFAULT_SQL_SERVER_PORT
    var database: String? = null
    var user: String? = null
    var password: String? = null
    var requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT

    private var poolConfiguration = NodeSqlServerPoolConfiguration()
    private var tlsConfiguration = NodeSqlServerTlsConfiguration()
    private val extensions = mutableListOf<SqlServerExtension>()

    fun pool(configure: NodeSqlServerPoolConfiguration.() -> Unit) {
        poolConfiguration = NodeSqlServerPoolConfiguration().apply(configure)
    }

    fun tls(configure: NodeSqlServerTlsConfiguration.() -> Unit = {}) {
        tlsConfiguration = NodeSqlServerTlsConfiguration().apply(configure)
    }

    fun install(extension: SqlServerExtension) {
        extensions += extension
    }

    internal fun snapshot(): NodeSqlServerSettings {
        require(!host.isNullOrBlank()) { "host is required" }
        require(port in MIN_PORT..MAX_PORT) { "port must be between $MIN_PORT and $MAX_PORT" }
        require(!database.isNullOrBlank()) { "database is required" }
        require(!user.isNullOrBlank()) { "user is required" }
        return NodeSqlServerSettings(
            host = requireNotNull(host),
            port = port,
            database = requireNotNull(database),
            user = requireNotNull(user),
            password = password,
            pool = poolConfiguration.snapshot(),
            tls = tlsConfiguration.snapshot(),
            requestTimeoutMilliseconds = requestTimeout.milliseconds("requestTimeout"),
            typeCodecs = SqlServerStandardTypes.typeCodecs + extensions.flatMap(SqlServerExtension::typeCodecs),
            dialect = SqlServerDialect.configured(extensions),
        )
    }
}

internal data class NodeSqlServerPoolSettings(
    val minConnections: Int,
    val maxConnections: Int,
    val connectionTimeoutMilliseconds: Int,
    val idleTimeoutMilliseconds: Int,
)

internal data class NodeSqlServerTlsSettings(
    val mode: NodeSqlServerTlsMode,
    val certificateAuthority: String?,
    val hostNameInCertificate: String?,
)

internal data class NodeSqlServerSettings(
    val host: String,
    val port: Int,
    val database: String,
    val user: String,
    val password: String?,
    val pool: NodeSqlServerPoolSettings,
    val tls: NodeSqlServerTlsSettings,
    val requestTimeoutMilliseconds: Int,
    val typeCodecs: List<SqlTypeCodec<*, *>>,
    val dialect: SqlDialect,
)

/** Creates a pooled direct SQL Server database backed by node-mssql's default tedious transport. */
fun NodeSqlServerDatabase(configure: NodeSqlServerDatabaseConfiguration.() -> Unit): NodeSqlServerDatabase =
    NodeSqlServerDatabase.createConfigured(NodeSqlServerDatabaseConfiguration().apply(configure))

/** Opens a pooled SQL Server database from the mssql driver's connection string format. */
fun NodeSqlServerDatabase(connectionString: String): NodeSqlServerDatabase {
    require(connectionString.isNotBlank()) { "SQL Server connection string must not be blank" }
    return NodeSqlServerDatabase.create(
        createNodeSqlServerPool(connectionString),
        SqlServerStandardTypes.typeCodecs,
        SqlServerDialect,
    )
}

/** Kagiroi SQL Server database with a private node-mssql pool and explicit transaction scopes. */
class NodeSqlServerDatabase private constructor(
    private val pool: NodeSqlServerPool,
    codecs: SqlTypeCodecRegistry,
    override val dialect: SqlDialect,
) : SqlDatabase {
    private val typeCodecs = codecs
    private val state = Mutex()
    private var closed = false
    private val isolationCapability = object : TransactionIsolationCapability {
        override val supported = TransactionIsolation.entries.toSet()

        override suspend fun <T> transaction(
            isolation: TransactionIsolation,
            block: suspend SqlTransaction.() -> T,
        ): T = runTransaction(isolation, block)
    }
    private val dedicatedSessionCapability = object : DedicatedSqlSessionCapability {
        override suspend fun <T> use(block: suspend DedicatedSqlSession.() -> T): T = pool.dedicatedSession { session ->
            block(LeasedSession(SqlServerExecution(session, typeCodecs)))
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <C : DatabaseCapability> capability(key: CapabilityKey<C>): C? = when (key) {
        SqlCapabilities.Sql -> SqlCapability as C
        SqlCapabilities.Transactions -> TransactionCapability as C
        SqlCapabilities.Savepoints -> SavepointCapability as C
        SqlCapabilities.Output -> OutputCapability as C
        SqlCapabilities.TransactionIsolation -> isolationCapability as C
        SqlCapabilities.Streaming -> SqlStreamingCapability(SqlStreamingMode.BUFFERED) as C
        SqlCapabilities.DedicatedSession -> if (pool.supportsDedicatedSession) dedicatedSessionCapability as C else null
        else -> null
    }

    override suspend fun execute(statement: RenderedSql): ExecuteResult = outsideTransaction {
        SqlServerExecution(pool, typeCodecs).execute(statement)
    }

    override fun query(statement: RenderedSql, columns: List<SqlExpression<*>>): Flow<SqlRow> = flow {
        val rows = outsideTransaction { SqlServerExecution(pool, typeCodecs).queryRows(statement, columns) }
        rows.forEach {
            currentCoroutineContext().ensureActive()
            emit(it)
        }
    }

    override suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult = outsideTransaction {
        SqlServerExecution(pool, typeCodecs).executeRaw(sql, parameters)
    }

    override fun queryRaw(sql: String, parameters: List<Any?>): Flow<RawSqlRow> = flow {
        val rows = outsideTransaction { SqlServerExecution(pool, typeCodecs).queryRawRows(sql, parameters) }
        rows.forEach {
            currentCoroutineContext().ensureActive()
            emit(it)
        }
    }

    override suspend fun <T> transaction(block: suspend SqlTransaction.() -> T): T = runTransaction(null, block)

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> runTransaction(
        isolation: TransactionIsolation?,
        block: suspend SqlTransaction.() -> T,
    ): T {
        val inherited = currentCoroutineContext()[ActiveNodeSqlServerTransactions]
        if (inherited?.contains(this) == true) throw NestedTransactionUnsupported()
        currentCoroutineContext().ensureActive()
        val transaction = withContext(NonCancellable) {
            try {
                ensureOpen()
                pool.begin(isolation?.toNodeIsolation())
            } catch (error: SqlServerDriverException) {
                throw error.toDatabaseError("Failed to begin SQL Server transaction")
            }
        }
        val execution = SqlServerExecution(transaction, typeCodecs)
        val scope = TransactionScope(execution)
        var transactionStarted = true
        try {
            currentCoroutineContext().ensureActive()
            val result = try {
                withContext((inherited ?: ActiveNodeSqlServerTransactions()).including(this)) { scope.block() }
            } catch (error: Throwable) {
                withContext(NonCancellable) { rollbackAfter(transaction, error) }
                transactionStarted = false
                throw error
            }
            var committed = false
            try {
                scope.requireUsable()
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) { transaction.commit() }
                committed = true
                transactionStarted = false
                currentCoroutineContext().ensureActive()
            } catch (error: Throwable) {
                if (!committed) {
                    withContext(NonCancellable) { rollbackAfter(transaction, error) }
                    transactionStarted = false
                }
                when (error) {
                    is SqlServerDriverException -> throw TransactionFailure(
                        "Failed to commit SQL Server transaction",
                        error.toDatabaseError("SQL Server commit failed"),
                    )

                    is DatabaseError -> throw TransactionFailure("Failed to commit SQL Server transaction", error)

                    else -> throw error
                }
            }
            return result
        } catch (error: Throwable) {
            if (transactionStarted) {
                withContext(NonCancellable) { rollbackAfter(transaction, error) }
                transactionStarted = false
            }
            throw error
        } finally {
            scope.active = false
        }
    }

    override suspend fun close() {
        ensureOutsideTransactionScope()
        state.lock()
        try {
            if (closed) return
            closed = true
            try {
                pool.close()
            } catch (error: SqlServerDriverException) {
                throw error.toDatabaseError("Failed to close SQL Server database")
            }
        } finally {
            state.unlock()
        }
    }

    private suspend fun rollbackAfter(transaction: NodeSqlServerTransaction, original: Throwable) {
        try {
            transaction.rollback()
        } catch (rollback: Throwable) {
            val failure = when (rollback) {
                is SqlServerDriverException -> rollback.toDatabaseError("SQL Server rollback failed")
                else -> rollback
            }
            original.addSuppressed(TransactionFailure("Failed to roll back SQL Server transaction", failure))
        }
    }

    private suspend fun <T> outsideTransaction(block: suspend () -> T): T {
        ensureOutsideTransactionScope()
        state.lock()
        try {
            ensureOpen()
        } finally {
            state.unlock()
        }
        return block()
    }

    private suspend fun ensureOutsideTransactionScope() {
        if (currentCoroutineContext()[ActiveNodeSqlServerTransactions]?.contains(this) == true) {
            throw ExecutionFailure("Use the transaction scope for database operations inside a transaction")
        }
    }

    private fun ensureOpen() {
        if (closed) throw DatabaseClosed()
    }

    private inner class TransactionScope(private val execution: SqlServerExecution) : SqlTransaction {
        override val dialect = this@NodeSqlServerDatabase.dialect
        var active = true
        private val savepoints = SavepointSupport(
            SavepointController(
                create = { name -> execution.executeRaw("SAVE TRANSACTION $name", emptyList()) },
                rollbackTo = { name -> execution.executeRaw("ROLLBACK TRANSACTION $name", emptyList()) },
            ),
        )

        override suspend fun <T> savepoint(block: suspend SavepointScope.() -> T): T = inScope {
            savepoints.savepoint(this@TransactionScope, block)
        }

        override suspend fun execute(statement: RenderedSql): ExecuteResult = inScope { execution.execute(statement) }

        override fun query(statement: RenderedSql, columns: List<SqlExpression<*>>): Flow<SqlRow> = flow {
            requireActive()
            execution.queryRows(statement, columns).forEach {
                currentCoroutineContext().ensureActive()
                emit(it)
            }
        }

        override suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult =
            inScope { execution.executeRaw(sql, parameters) }

        override fun queryRaw(sql: String, parameters: List<Any?>): Flow<RawSqlRow> = flow {
            requireActive()
            execution.queryRawRows(sql, parameters).forEach {
                currentCoroutineContext().ensureActive()
                emit(it)
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

    private inner class LeasedSession(private val execution: SqlServerExecution) : DedicatedSqlSession {
        override val dialect: SqlDialect = this@NodeSqlServerDatabase.dialect

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
            pool: NodeSqlServerPool,
            codecs: List<SqlTypeCodec<*, *>> = SqlServerStandardTypes.typeCodecs,
            dialect: SqlDialect = SqlServerDialect,
        ): NodeSqlServerDatabase = NodeSqlServerDatabase(pool, SqlTypeCodecRegistry(codecs), dialect)

        internal fun createConfigured(configuration: NodeSqlServerDatabaseConfiguration): NodeSqlServerDatabase {
            val settings = configuration.snapshot()
            return NodeSqlServerDatabase(
                createNodeSqlServerPool(settings),
                SqlTypeCodecRegistry(settings.typeCodecs),
                settings.dialect,
            )
        }
    }
}

private class ActiveNodeSqlServerTransactions(private val databases: List<NodeSqlServerDatabase> = emptyList()) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ActiveNodeSqlServerTransactions>

    fun contains(database: NodeSqlServerDatabase): Boolean = databases.any { it === database }

    fun including(database: NodeSqlServerDatabase): ActiveNodeSqlServerTransactions =
        ActiveNodeSqlServerTransactions(databases + database)
}

private class SqlServerExecution(private val session: NodeSqlServerSession, codecs: SqlTypeCodecRegistry) {
    private val values = SqlServerValueCodec(codecs)

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
            throw ResultConversionFailure("SQL Server returned a row whose value count does not match selected columns")
        }
        return result.rows.map { row -> sqlRow(columns, columns.indices.map { values.decode(columns[it], row[it]) }) }
    }

    suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult =
        execute(RenderedSql(sql, parameters.map { SqlParameter(it, null) }))

    suspend fun queryRawRows(sql: String, parameters: List<Any?>): List<RawSqlRow> {
        val result = translate {
            session.query(
                toStatement(RenderedSql(sql, parameters.map { SqlParameter(it, null) })),
            )
        }
        return result.rows.map { row ->
            if (row.size != result.columnNames.size) {
                throw ResultConversionFailure("SQL Server returned a raw row whose value count does not match columns")
            }
            rawSqlRow(result.columnNames, row.map(values::rawValue))
        }
    }

    private fun toStatement(statement: RenderedSql): SqlServerStatement = SqlServerStatement(
        statement.sql,
        statement.parameters.mapIndexed { index, parameter ->
            try {
                values.binding(parameter.value, parameter.expression)
            } catch (error: Throwable) {
                throw InvalidBinding(
                    "SQL Server cannot bind " + (parameter.expression?.toString() ?: "parameter " + (index + 1)),
                    error,
                )
            }
        },
    )

    private suspend inline fun <T> translate(crossinline block: suspend () -> T): T = try {
        block()
    } catch (error: SqlServerDriverException) {
        throw error.toDatabaseError("SQL Server execution failed")
    }
}

@Suppress("CyclomaticComplexMethod")
private fun SqlServerDriverException.toDatabaseError(prefix: String): DatabaseError {
    val metadata = DatabaseErrorMetadata(
        vendorCode = errorNumber?.toString() ?: code,
        attributes = attributes + mapOfNotNull("number" to errorNumber?.toString(), "code" to code),
    )
    val detail = "$prefix: ${message ?: "unknown error"}"
    return when (errorNumber) {
        SQLSERVER_UNIQUE_CONSTRAINT, SQLSERVER_UNIQUE_INDEX ->
            ConstraintViolation(detail, this, errorNumber.toString(), ConstraintKind.UNIQUE, metadata)

        SQLSERVER_NOT_NULL ->
            ConstraintViolation(detail, this, errorNumber.toString(), ConstraintKind.NOT_NULL, metadata)

        SQLSERVER_FOREIGN_KEY_OR_CHECK ->
            ConstraintViolation(detail, this, errorNumber.toString(), ConstraintKind.OTHER, metadata)

        SQLSERVER_DEADLOCK -> DeadlockFailure(detail, this, metadata)

        SQLSERVER_LOCK_TIMEOUT, SQLSERVER_QUERY_TIMEOUT -> LockTimeoutFailure(detail, this, metadata)

        SQLSERVER_CONVERSION, SQLSERVER_ARITHMETIC_OVERFLOW, SQLSERVER_OPERAND_TYPE_CONFLICT ->
            InvalidBinding(detail, this, errorNumber.toString(), metadata)

        else -> when {
            kind == SqlServerFailureKind.CONNECTION || code in CONNECTION_ERROR_CODES ->
                ConnectionFailure(detail, this, metadata)

            kind == SqlServerFailureKind.RESULT_CONVERSION ->
                ResultConversionFailure(detail, this, errorNumber?.toString(), metadata)

            code == REQUEST_TIMEOUT_CODE -> LockTimeoutFailure(detail, this, metadata)

            else -> ExecutionFailure(detail, this, errorNumber?.toString() ?: code, metadata)
        }
    }
}

private fun TransactionIsolation.toNodeIsolation(): NodeSqlServerIsolation = when (this) {
    TransactionIsolation.READ_COMMITTED -> NodeSqlServerIsolation.READ_COMMITTED
    TransactionIsolation.REPEATABLE_READ -> NodeSqlServerIsolation.REPEATABLE_READ
    TransactionIsolation.SERIALIZABLE -> NodeSqlServerIsolation.SERIALIZABLE
}

private fun Duration.milliseconds(name: String): Int {
    require(!isNegative() && isFinite()) { "$name must be a finite non-negative duration" }
    require(inWholeMilliseconds <= Int.MAX_VALUE) { "$name is too large" }
    return inWholeMilliseconds.toInt()
}

private fun mapOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> =
    pairs.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()

private const val DEFAULT_SQL_SERVER_PORT = 1433
private const val MIN_PORT = 1
private const val MAX_PORT = 65_535
private const val DEFAULT_MAX_CONNECTIONS = 10
private val DEFAULT_CONNECTION_TIMEOUT = 15.seconds
private val DEFAULT_IDLE_TIMEOUT = 30.seconds
private val DEFAULT_REQUEST_TIMEOUT = 30.seconds
private const val SQLSERVER_UNIQUE_CONSTRAINT = 2627
private const val SQLSERVER_UNIQUE_INDEX = 2601
private const val SQLSERVER_NOT_NULL = 515
private const val SQLSERVER_FOREIGN_KEY_OR_CHECK = 547
private const val SQLSERVER_DEADLOCK = 1205
private const val SQLSERVER_LOCK_TIMEOUT = 1222
private const val SQLSERVER_QUERY_TIMEOUT = -2
private const val SQLSERVER_CONVERSION = 245
private const val SQLSERVER_ARITHMETIC_OVERFLOW = 8115
private const val SQLSERVER_OPERAND_TYPE_CONFLICT = 206
private const val REQUEST_TIMEOUT_CODE = "ETIMEOUT"
private val CONNECTION_ERROR_CODES = setOf("ELOGIN", "ESOCKET", "ECONNCLOSED", "ENOCONN", "ECONNRESET")
