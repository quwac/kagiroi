package dev.quwac.kagiroi.mysql.node

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

/** TLS behavior for direct mysql2 connections. */
enum class NodeMySqlTlsMode {
    /** Do not negotiate TLS. */
    DISABLED,

    /** Require encryption without authenticating the server certificate. */
    REQUIRE,

    /** Require TLS and authenticate both the certificate chain and server identity. */
    VERIFY_FULL,
}

/** TLS inputs kept independent of Node.js TLS object types. */
class NodeMySqlTlsConfiguration internal constructor() {
    /** Value associated with mode. */
    var mode: NodeMySqlTlsMode = NodeMySqlTlsMode.VERIFY_FULL

    /** Value associated with certificate authority. */
    var certificateAuthority: String? = null

    /** Value associated with client certificate. */
    var clientCertificate: String? = null

    /** Value associated with client private key. */
    var clientPrivateKey: String? = null

    internal fun snapshot(): NodeMySqlTlsSettings {
        require((clientCertificate == null) == (clientPrivateKey == null)) {
            "clientCertificate and clientPrivateKey must be configured together"
        }
        require(
            mode != NodeMySqlTlsMode.DISABLED || listOf(
                certificateAuthority,
                clientCertificate,
                clientPrivateKey,
            ).all { it == null },
        ) {
            "TLS certificates cannot be configured when TLS is disabled"
        }
        require(mode == NodeMySqlTlsMode.VERIFY_FULL || certificateAuthority == null) {
            "certificateAuthority requires VERIFY_FULL TLS mode"
        }
        return NodeMySqlTlsSettings(mode, certificateAuthority, clientCertificate, clientPrivateKey)
    }
}

/** Connection-pool settings implemented by mysql2 plus a Kagiroi acquisition deadline. */
class NodeMySqlPoolConfiguration internal constructor() {
    /** Value associated with max connections. */
    var maxConnections: Int = DEFAULT_MAX_CONNECTIONS

    /** Value associated with connection timeout. */
    var connectionTimeout: Duration = DEFAULT_CONNECTION_TIMEOUT

    /** Value associated with acquisition timeout. */
    var acquisitionTimeout: Duration = Duration.ZERO

    /** Value associated with idle timeout. */
    var idleTimeout: Duration = DEFAULT_IDLE_TIMEOUT
    private var configuredMaxIdleConnections: Int? = null

    /** Value associated with max idle connections. */
    var maxIdleConnections: Int
        get() = configuredMaxIdleConnections ?: maxConnections
        set(value) {
            configuredMaxIdleConnections = value
        }

    internal fun snapshot(): NodeMySqlPoolSettings {
        require(maxConnections > 0) { "maxConnections must be positive" }
        require(maxIdleConnections >= 0) { "maxIdleConnections must not be negative" }
        require(maxIdleConnections <= maxConnections) { "maxIdleConnections must not exceed maxConnections" }
        return NodeMySqlPoolSettings(
            maxConnections = maxConnections,
            maxIdleConnections = maxIdleConnections,
            connectionTimeoutMilliseconds = connectionTimeout.milliseconds("connectionTimeout"),
            acquisitionTimeoutMilliseconds = acquisitionTimeout.milliseconds("acquisitionTimeout"),
            idleTimeoutMilliseconds = idleTimeout.milliseconds("idleTimeout"),
        )
    }
}

/** Direct MySQL settings without exposing mysql2 pools, connections, or Node.js TLS values. */
class NodeMySqlDatabaseConfiguration internal constructor(private val connectionUrl: String?) {
    /** Value associated with host. */
    var host: String? = null

    /** Value associated with port. */
    var port: Int = DEFAULT_MYSQL_PORT

    /** Value associated with database. */
    var database: String? = null

    /** Value associated with user. */
    var user: String? = null

    /** Value associated with password. */
    var password: String? = null

    private var poolConfiguration = NodeMySqlPoolConfiguration()
    private var tlsConfiguration: NodeMySqlTlsConfiguration? = null
    private val extensions = mutableListOf<MySqlExtension>()
    private var configuredDialect: SqlDialect? = null

    /** Provides the pool result. */
    fun pool(configure: NodeMySqlPoolConfiguration.() -> Unit) {
        poolConfiguration = NodeMySqlPoolConfiguration().apply(configure)
    }

    /** Provides the tls result. */
    fun tls(configure: NodeMySqlTlsConfiguration.() -> Unit = {}) {
        tlsConfiguration = NodeMySqlTlsConfiguration().apply(configure)
    }

    /** Provides the install result. */
    fun install(extension: MySqlExtension) {
        extensions += extension
    }

    /**
     * Selects a dialect for a MySQL-protocol-compatible server.
     *
     * This changes SQL rendering only. The mysql2 pool, parameter transport, TLS, and session
     * lifecycle remain the shared execution adapter.
     */
    fun dialect(value: SqlDialect) {
        configuredDialect = value
    }

    internal fun snapshot(): NodeMySqlSettings {
        require(port in MIN_PORT..MAX_PORT) { "port must be between $MIN_PORT and $MAX_PORT" }
        if (connectionUrl == null) {
            require(!host.isNullOrBlank()) { "host is required" }
            require(!database.isNullOrBlank()) { "database is required" }
            require(!user.isNullOrBlank()) { "user is required" }
        } else {
            require(listOf(host, database, user, password).all { it == null }) {
                "A connection URL cannot be combined with individual connection parameters"
            }
        }
        val tls = tlsConfiguration?.snapshot()
        require(tls == null || !connectionUrl.hasUrlOption("ssl")) {
            "Configure TLS either in the connection URL or with tls { }, not both"
        }
        return NodeMySqlSettings(
            connectionUrl = connectionUrl,
            host = host,
            port = port.takeIf { connectionUrl == null },
            database = database,
            user = user,
            password = password,
            pool = poolConfiguration.snapshot(),
            tls = tls,
            typeCodecs = MySqlStandardTypes.typeCodecs + extensions.flatMap(MySqlExtension::typeCodecs),
            dialect = configuredDialect ?: MySqlDialect.configured(extensions),
        )
    }
}

internal data class NodeMySqlPoolSettings(
    val maxConnections: Int,
    val maxIdleConnections: Int,
    val connectionTimeoutMilliseconds: Int,
    val acquisitionTimeoutMilliseconds: Int,
    val idleTimeoutMilliseconds: Int,
)

internal data class NodeMySqlTlsSettings(
    val mode: NodeMySqlTlsMode,
    val certificateAuthority: String?,
    val clientCertificate: String?,
    val clientPrivateKey: String?,
)

internal data class NodeMySqlSettings(
    val connectionUrl: String?,
    val host: String?,
    val port: Int?,
    val database: String?,
    val user: String?,
    val password: String?,
    val pool: NodeMySqlPoolSettings,
    val tls: NodeMySqlTlsSettings?,
    val typeCodecs: List<SqlTypeCodec<*, *>>,
    val dialect: SqlDialect,
)

/** Creates a pooled direct MySQL database from a connection URL. */
fun NodeMySqlDatabase(url: String, configure: NodeMySqlDatabaseConfiguration.() -> Unit = {}): NodeMySqlDatabase {
    require(url.isNotBlank()) { "MySQL connection URL must not be blank" }
    return configuredNodeMySqlDatabase(NodeMySqlDatabaseConfiguration(url).apply(configure))
}

/** Creates a pooled direct MySQL database from individual connection parameters. */
fun NodeMySqlDatabase(configure: NodeMySqlDatabaseConfiguration.() -> Unit): NodeMySqlDatabase =
    configuredNodeMySqlDatabase(NodeMySqlDatabaseConfiguration(null).apply(configure))

private fun configuredNodeMySqlDatabase(configuration: NodeMySqlDatabaseConfiguration): NodeMySqlDatabase {
    val settings = configuration.snapshot()
    return NodeMySqlDatabase.create(
        pool = createNodeMySqlPool(settings),
        codecs = settings.typeCodecs,
        acquisitionTimeoutMilliseconds = settings.pool.acquisitionTimeoutMilliseconds,
        dialect = settings.dialect,
    )
}

/** Kagiroi MySQL database backed by the JavaScript host's mysql2 pool. */
@Suppress("TooManyFunctions")
class NodeMySqlDatabase private constructor(
    private val pool: NodeMySqlPool,
    codecs: SqlTypeCodecRegistry,
    private val acquisitionTimeoutMilliseconds: Int,
    override val dialect: SqlDialect,
) : SqlDatabase {
    private val typeCodecs = codecs
    private val state = Mutex()
    private var closed = false
    private var pendingAcquisitions = 0
    private var activeSessions = 0
    private var drainWaiter: CompletableDeferred<Unit>? = null
    private var closeCompletion: CompletableDeferred<Unit>? = null
    private val isolationCapability = object : TransactionIsolationCapability {
        override val supported: Set<TransactionIsolation> = TransactionIsolation.entries.toSet()

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
        SqlCapabilities.TransactionIsolation -> isolationCapability as C
        SqlCapabilities.Streaming -> SqlStreamingCapability(SqlStreamingMode.BUFFERED) as C
        SqlCapabilities.DedicatedSession -> dedicatedSessionCapability as C
        else -> null
    }

    override suspend fun execute(statement: RenderedSql): ExecuteResult = withSession { execution ->
        execution.execute(statement)
    }

    override fun query(statement: RenderedSql, columns: List<SqlExpression<*>>): Flow<SqlRow> = flow {
        val rows = withSession { execution -> execution.queryRows(statement, columns) }
        for (row in rows) {
            currentCoroutineContext().ensureActive()
            emit(row)
        }
    }

    override suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult = withSession { execution ->
        execution.executeRaw(sql, parameters)
    }

    override fun queryRaw(sql: String, parameters: List<Any?>): Flow<RawSqlRow> = flow {
        val rows = withSession { execution -> execution.queryRawRows(sql, parameters) }
        for (row in rows) {
            currentCoroutineContext().ensureActive()
            emit(row)
        }
    }

    override suspend fun <T> transaction(block: suspend SqlTransaction.() -> T): T = runTransaction(null, block)

    @Suppress("CyclomaticComplexMethod", "ThrowsCount", "TooGenericExceptionCaught")
    private suspend fun <T> runTransaction(
        isolation: TransactionIsolation?,
        block: suspend SqlTransaction.() -> T,
    ): T {
        val inheritedTransactions = currentCoroutineContext()[ActiveNodeMySqlTransactions]
        if (inheritedTransactions?.contains(this) == true) throw NestedTransactionUnsupported()
        val session = acquireSession()
        val execution = MySqlExecution(session, typeCodecs)
        val scope = TransactionScope(execution)
        var discard = false
        var transactionStarted = false
        try {
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) { begin(execution, isolation) }
            transactionStarted = true
            currentCoroutineContext().ensureActive()
            val result = try {
                withContext((inheritedTransactions ?: ActiveNodeMySqlTransactions()).including(this)) {
                    scope.block()
                }
            } catch (error: Throwable) {
                discard = withContext(NonCancellable) { rollbackAfter(execution, error) } || discard
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
                    discard = withContext(NonCancellable) { rollbackAfter(execution, error) } || discard
                    transactionStarted = false
                }
                when (error) {
                    is SerializationFailure, is DeadlockFailure, is LockTimeoutFailure -> throw error
                    is DatabaseError -> throw TransactionFailure("Failed to commit MySQL transaction", error)
                    else -> throw error
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
            withContext(NonCancellable) { releaseSession(session, discard) }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun close() {
        ensureOutsideTransactionScope()
        var ownsClose = false
        val completion = state.withLock {
            closeCompletion ?: CompletableDeferred<Unit>().also {
                closeCompletion = it
                closed = true
                ownsClose = true
            }
        }
        if (ownsClose) {
            withContext(NonCancellable) {
                try {
                    awaitDrain()
                    pool.close()
                    completion.complete(Unit)
                } catch (error: MySqlDriverException) {
                    completion.completeExceptionally(error.toDatabaseError("Failed to close MySQL database"))
                } catch (error: Throwable) {
                    completion.completeExceptionally(error)
                }
            }
        }
        completion.await()
    }

    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    private suspend fun acquireSession(): NodeMySqlSession {
        state.withLock {
            ensureOpen()
            pendingAcquisitions++
        }
        val session = try {
            pool.acquire(acquisitionTimeoutMilliseconds)
        } catch (error: MySqlDriverException) {
            acquisitionFinished()
            throw error.toDatabaseError("Failed to acquire MySQL pool connection")
        } catch (error: Throwable) {
            acquisitionFinished()
            throw error
        }
        val accepted = state.withLock {
            pendingAcquisitions--
            val accept = !closed
            if (accept) activeSessions++
            signalDrainIfReady()
            accept
        }
        if (!accepted) {
            session.release(false)
            throw DatabaseClosed()
        }
        return session
    }

    private suspend fun acquisitionFinished() {
        state.withLock {
            pendingAcquisitions--
            signalDrainIfReady()
        }
    }

    private suspend fun releaseSession(session: NodeMySqlSession, discard: Boolean) {
        try {
            session.release(discard)
        } finally {
            state.withLock {
                activeSessions--
                signalDrainIfReady()
            }
        }
    }

    private suspend fun awaitDrain() {
        val waiter = state.withLock {
            if (pendingAcquisitions == 0 && activeSessions == 0) {
                null
            } else {
                drainWaiter ?: CompletableDeferred<Unit>().also { drainWaiter = it }
            }
        }
        waiter?.await()
    }

    private fun signalDrainIfReady() {
        if (closed && pendingAcquisitions == 0 && activeSessions == 0) {
            drainWaiter?.complete(Unit)
            drainWaiter = null
        }
    }

    private suspend fun begin(execution: MySqlExecution, isolation: TransactionIsolation?) {
        try {
            if (isolation != null) execution.executeControl("SET TRANSACTION ISOLATION LEVEL ${isolation.sql}")
            execution.executeControl("START TRANSACTION")
        } catch (error: DatabaseError) {
            throw TransactionFailure("Failed to begin MySQL transaction", error)
        }
    }

    /** Returns whether the session must be discarded after an unsuccessful rollback. */
    @Suppress("TooGenericExceptionCaught") // A rollback failure is deliberately retained on the original failure.
    private suspend fun rollbackAfter(execution: MySqlExecution, original: Throwable): Boolean = try {
        execution.executeControl("ROLLBACK")
        false
    } catch (rollback: Throwable) {
        original.addSuppressed(TransactionFailure("Failed to roll back MySQL transaction", rollback))
        true
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> withSession(block: suspend (MySqlExecution) -> T): T {
        ensureOutsideTransactionScope()
        val session = acquireSession()
        var discard = false
        return try {
            block(MySqlExecution(session, typeCodecs))
        } catch (error: Throwable) {
            discard = error.hasConnectionFailure()
            throw error
        } finally {
            withContext(NonCancellable) { releaseSession(session, discard) }
        }
    }

    private suspend fun ensureOutsideTransactionScope() {
        if (currentCoroutineContext()[ActiveNodeMySqlTransactions]?.contains(this) == true) {
            throw ExecutionFailure("Use the transaction scope for database operations inside a transaction")
        }
    }

    private fun ensureOpen() {
        if (closed) throw DatabaseClosed()
    }

    private inner class TransactionScope(private val execution: MySqlExecution) : SqlTransaction {
        override val dialect = this@NodeMySqlDatabase.dialect
        var active = true
        private val savepoints = SavepointSupport(
            SavepointController(
                create = { name -> execution.executeControl("SAVEPOINT $name") },
                rollbackTo = { name -> execution.executeControl("ROLLBACK TO SAVEPOINT $name") },
                release = { name -> execution.executeControl("RELEASE SAVEPOINT $name") },
            ),
        )

        override suspend fun <T> savepoint(block: suspend SavepointScope.() -> T): T = inScope {
            savepoints.savepoint(this@TransactionScope, block)
        }

        override suspend fun execute(statement: RenderedSql): ExecuteResult = inScope {
            execution.execute(statement)
        }

        override fun query(statement: RenderedSql, columns: List<SqlExpression<*>>): Flow<SqlRow> = flow {
            requireActive()
            for (row in execution.queryRows(statement, columns)) {
                currentCoroutineContext().ensureActive()
                emit(row)
            }
        }

        override suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult = inScope {
            execution.executeRaw(sql, parameters)
        }

        override fun queryRaw(sql: String, parameters: List<Any?>): Flow<RawSqlRow> = flow {
            requireActive()
            for (row in execution.queryRawRows(sql, parameters)) {
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

    private inner class LeasedSession(private val execution: MySqlExecution) : DedicatedSqlSession {
        override val dialect: SqlDialect = this@NodeMySqlDatabase.dialect

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
            pool: NodeMySqlPool,
            codecs: List<SqlTypeCodec<*, *>> = MySqlStandardTypes.typeCodecs,
            acquisitionTimeoutMilliseconds: Int = 0,
            dialect: SqlDialect = MySqlDialect,
        ): NodeMySqlDatabase = NodeMySqlDatabase(
            pool,
            SqlTypeCodecRegistry(codecs),
            acquisitionTimeoutMilliseconds,
            dialect,
        )
    }
}

private class ActiveNodeMySqlTransactions(private val databases: List<NodeMySqlDatabase> = emptyList()) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ActiveNodeMySqlTransactions>

    fun contains(database: NodeMySqlDatabase): Boolean = databases.any { it === database }

    fun including(database: NodeMySqlDatabase): ActiveNodeMySqlTransactions =
        ActiveNodeMySqlTransactions(databases + database)
}

private class MySqlExecution(private val session: NodeMySqlSession, codecs: SqlTypeCodecRegistry) {
    private val values = MySqlValueCodec(codecs)

    suspend fun execute(statement: RenderedSql): ExecuteResult {
        currentCoroutineContext().ensureActive()
        val result = translateFailure { session.execute(toStatement(statement)) }
        currentCoroutineContext().ensureActive()
        return ExecuteResult(result.affectedRows)
    }

    suspend fun queryRows(statement: RenderedSql, columns: List<SqlExpression<*>>): List<SqlRow> {
        currentCoroutineContext().ensureActive()
        val result = translateFailure { session.query(toStatement(statement)) }
        currentCoroutineContext().ensureActive()
        if (result.rows.any { it.size != columns.size }) {
            throw ResultConversionFailure("MySQL returned a row whose value count does not match selected columns")
        }
        return result.rows.map { row -> sqlRow(columns, columns.indices.map { values.decode(columns[it], row[it]) }) }
    }

    suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult {
        val statement = RenderedSql(sql, parameters.map { SqlParameter(it, null) })
        currentCoroutineContext().ensureActive()
        val result = translateFailure { session.execute(toStatement(statement)) }
        currentCoroutineContext().ensureActive()
        return ExecuteResult(result.affectedRows)
    }

    suspend fun queryRawRows(sql: String, parameters: List<Any?>): List<RawSqlRow> {
        val statement = RenderedSql(sql, parameters.map { SqlParameter(it, null) })
        currentCoroutineContext().ensureActive()
        val result = translateFailure { session.query(toStatement(statement)) }
        currentCoroutineContext().ensureActive()
        return result.rows.map { row ->
            if (row.size != result.columnNames.size) {
                throw ResultConversionFailure("MySQL returned a raw row whose value count does not match columns")
            }
            rawSqlRow(result.columnNames, row.map(values::rawValue))
        }
    }

    suspend fun executeControl(sql: String) {
        currentCoroutineContext().ensureActive()
        translateFailure { session.execute(MySqlStatement(sql, emptyList())) }
        currentCoroutineContext().ensureActive()
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

private fun Throwable.hasConnectionFailure(): Boolean =
    this is ConnectionFailure || cause?.hasConnectionFailure() == true

private val TransactionIsolation.sql: String
    get() = when (this) {
        TransactionIsolation.READ_COMMITTED -> "READ COMMITTED"
        TransactionIsolation.REPEATABLE_READ -> "REPEATABLE READ"
        TransactionIsolation.SERIALIZABLE -> "SERIALIZABLE"
    }

private fun Duration.milliseconds(name: String): Int {
    require(!isNegative() && isFinite()) { "$name must be a finite non-negative duration" }
    require(inWholeMilliseconds <= Int.MAX_VALUE) { "$name is too large" }
    return inWholeMilliseconds.toInt()
}

private fun String?.hasUrlOption(option: String): Boolean {
    val query = this?.substringAfter('?', "")?.substringBefore('#').orEmpty()
    return query.split('&').any { entry -> entry.substringBefore('=').equals(option, ignoreCase = true) }
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
private const val DEFAULT_MYSQL_PORT = 3306
private const val MIN_PORT = 1
private const val MAX_PORT = 65_535
private const val DEFAULT_MAX_CONNECTIONS = 10
private val DEFAULT_CONNECTION_TIMEOUT = 10.seconds
private val DEFAULT_IDLE_TIMEOUT = 60.seconds
private val CONNECTION_ERROR_NUMBERS = setOf(1044, 1045, 2002, 2003, 2006, 2013, 2055)
