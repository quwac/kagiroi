package dev.quwac.kagiroi.postgres.node

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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** TLS behavior for direct node-postgres connections. */
enum class NodePostgresTlsMode {
    /** Do not negotiate TLS. */
    DISABLED,

    /** Require encryption without authenticating the server certificate. */
    REQUIRE,

    /** Require TLS and authenticate the server with the host or configured CA roots. */
    VERIFY_FULL,
}

/** TLS inputs kept independent of Node.js TLS object types. */
class NodePostgresTlsConfiguration internal constructor() {
    /** Value associated with mode. */
    var mode: NodePostgresTlsMode = NodePostgresTlsMode.VERIFY_FULL

    /** Value associated with certificate authority. */
    var certificateAuthority: String? = null

    /** Value associated with client certificate. */
    var clientCertificate: String? = null

    /** Value associated with client private key. */
    var clientPrivateKey: String? = null

    internal fun snapshot(): NodePostgresTlsSettings {
        require((clientCertificate == null) == (clientPrivateKey == null)) {
            "clientCertificate and clientPrivateKey must be configured together"
        }
        require(
            mode != NodePostgresTlsMode.DISABLED || listOf(
                certificateAuthority,
                clientCertificate,
                clientPrivateKey,
            ).all { it == null },
        ) {
            "TLS certificates cannot be configured when TLS is disabled"
        }
        require(mode == NodePostgresTlsMode.VERIFY_FULL || certificateAuthority == null) {
            "certificateAuthority requires VERIFY_FULL TLS mode"
        }
        return NodePostgresTlsSettings(mode, certificateAuthority, clientCertificate, clientPrivateKey)
    }
}

/** Connection-pool settings implemented by node-postgres. */
class NodePostgresPoolConfiguration internal constructor() {
    /** Value associated with min connections. */
    var minConnections: Int = 0

    /** Value associated with max connections. */
    var maxConnections: Int = DEFAULT_MAX_CONNECTIONS

    /** Value associated with connection timeout. */
    var connectionTimeout: Duration = Duration.ZERO

    /** Value associated with idle timeout. */
    var idleTimeout: Duration = DEFAULT_IDLE_TIMEOUT

    internal fun snapshot(): NodePostgresPoolSettings {
        require(minConnections >= 0) { "minConnections must not be negative" }
        require(maxConnections > 0) { "maxConnections must be positive" }
        require(minConnections <= maxConnections) { "minConnections must not exceed maxConnections" }
        return NodePostgresPoolSettings(
            minConnections,
            maxConnections,
            connectionTimeout.milliseconds("connectionTimeout"),
            idleTimeout.milliseconds("idleTimeout"),
        )
    }
}

/** Direct PostgreSQL settings without exposing pg.Pool, pg.Client, or Node.js TLS values. */
class NodePostgresDatabaseConfiguration internal constructor(private val connectionUrl: String?) {
    /** Value associated with host. */
    var host: String? = null

    /** Value associated with port. */
    var port: Int = DEFAULT_POSTGRES_PORT

    /** Value associated with database. */
    var database: String? = null

    /** Value associated with user. */
    var user: String? = null

    /** Value associated with password. */
    var password: String? = null

    private var poolConfiguration = NodePostgresPoolConfiguration()
    private var tlsConfiguration: NodePostgresTlsConfiguration? = null
    private val extensions = mutableListOf<PostgresExtension>()

    /** Provides the pool result. */
    fun pool(configure: NodePostgresPoolConfiguration.() -> Unit) {
        poolConfiguration = NodePostgresPoolConfiguration().apply(configure)
    }

    /** Provides the tls result. */
    fun tls(configure: NodePostgresTlsConfiguration.() -> Unit = {}) {
        tlsConfiguration = NodePostgresTlsConfiguration().apply(configure)
    }

    /** Provides the install result. */
    fun install(extension: PostgresExtension) {
        extensions += extension
    }

    internal fun snapshot(): NodePostgresSettings {
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
        require(tls == null || connectionUrl.tlsQueryOptions().isEmpty()) {
            "Configure TLS either in the connection URL or with tls { }, not both"
        }
        return NodePostgresSettings(
            connectionUrl = connectionUrl,
            host = host,
            port = port.takeIf { connectionUrl == null },
            database = database,
            user = user,
            password = password,
            pool = poolConfiguration.snapshot(),
            tls = tls,
            typeCodecs = PostgresStandardTypes.typeCodecs + extensions.flatMap(PostgresExtension::typeCodecs),
            dialect = PostgresDialect.configured(extensions),
        )
    }
}

internal data class NodePostgresPoolSettings(
    val minConnections: Int,
    val maxConnections: Int,
    val connectionTimeoutMilliseconds: Int,
    val idleTimeoutMilliseconds: Int,
)

internal data class NodePostgresTlsSettings(
    val mode: NodePostgresTlsMode,
    val certificateAuthority: String?,
    val clientCertificate: String?,
    val clientPrivateKey: String?,
)

internal data class NodePostgresSettings(
    val connectionUrl: String?,
    val host: String?,
    val port: Int?,
    val database: String?,
    val user: String?,
    val password: String?,
    val pool: NodePostgresPoolSettings,
    val tls: NodePostgresTlsSettings?,
    val typeCodecs: List<dev.quwac.kagiroi.sql.SqlTypeCodec<*, *>>,
    val dialect: SqlDialect,
)

/** Creates a pooled direct PostgreSQL database from a connection URL. */
fun NodePostgresDatabase(
    url: String,
    configure: NodePostgresDatabaseConfiguration.() -> Unit = {},
): NodePostgresDatabase {
    require(url.isNotBlank()) { "PostgreSQL connection URL must not be blank" }
    return configuredNodePostgresDatabase(NodePostgresDatabaseConfiguration(url).apply(configure))
}

/** Creates a pooled direct PostgreSQL database from individual connection parameters. */
fun NodePostgresDatabase(configure: NodePostgresDatabaseConfiguration.() -> Unit): NodePostgresDatabase =
    configuredNodePostgresDatabase(NodePostgresDatabaseConfiguration(null).apply(configure))

private fun configuredNodePostgresDatabase(configuration: NodePostgresDatabaseConfiguration): NodePostgresDatabase {
    val settings = configuration.snapshot()
    return NodePostgresDatabase.create(createNodePostgresPool(settings), settings.typeCodecs, settings.dialect)
}

/** Kagiroi PostgreSQL database backed by the JavaScript host's node-postgres pool. */
@Suppress("TooManyFunctions")
class NodePostgresDatabase private constructor(
    private val pool: NodePostgresPool,
    codecs: SqlTypeCodecRegistry,
    override val dialect: SqlDialect,
) : SqlDatabase {
    private val typeCodecs = codecs
    private val state = Mutex()
    private var closed = false
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
        SqlCapabilities.Returning -> ReturningCapability as C
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
        val inheritedTransactions = currentCoroutineContext()[ActiveNodePostgresTransactions]
        if (inheritedTransactions?.contains(this) == true) throw NestedTransactionUnsupported()
        val session = acquireSession()
        val execution = PostgresExecution(session, typeCodecs)
        val scope = TransactionScope(execution)
        var discard = false
        var transactionStarted = false
        try {
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) { begin(execution, isolation) }
            transactionStarted = true
            currentCoroutineContext().ensureActive()
            val result = try {
                withContext((inheritedTransactions ?: ActiveNodePostgresTransactions()).including(this)) {
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
                    is SerializationFailure, is DeadlockFailure -> throw error
                    is DatabaseError -> throw TransactionFailure("Failed to commit PostgreSQL transaction", error)
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
            session.release(discard)
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
            } catch (error: PostgresDriverException) {
                throw error.toDatabaseError("Failed to close PostgreSQL database")
            }
        } finally {
            state.unlock()
        }
    }

    private suspend fun acquireSession(): NodePostgresSession {
        state.lock()
        return try {
            ensureOpen()
            try {
                pool.acquire()
            } catch (error: PostgresDriverException) {
                throw error.toDatabaseError("Failed to acquire PostgreSQL pool connection")
            }
        } finally {
            state.unlock()
        }
    }

    private suspend fun begin(execution: PostgresExecution, isolation: TransactionIsolation?) {
        val sql = isolation?.let { "BEGIN ISOLATION LEVEL ${it.sql}" } ?: "BEGIN"
        try {
            execution.executeControl(sql)
        } catch (error: DatabaseError) {
            throw TransactionFailure("Failed to begin PostgreSQL transaction", error)
        }
    }

    /** Returns whether the session must be discarded after an unsuccessful rollback. */
    @Suppress("TooGenericExceptionCaught") // A rollback failure is deliberately retained on the original failure.
    private suspend fun rollbackAfter(execution: PostgresExecution, original: Throwable): Boolean = try {
        execution.executeControl("ROLLBACK")
        false
    } catch (rollback: Throwable) {
        original.addSuppressed(
            TransactionFailure("Failed to roll back PostgreSQL transaction", rollback),
        )
        true
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> withSession(block: suspend (PostgresExecution) -> T): T {
        ensureOutsideTransactionScope()
        val session = acquireSession()
        var discard = false
        return try {
            block(PostgresExecution(session, typeCodecs))
        } catch (error: Throwable) {
            discard = error.hasConnectionFailure()
            throw error
        } finally {
            session.release(discard)
        }
    }

    private suspend fun ensureOutsideTransactionScope() {
        if (currentCoroutineContext()[ActiveNodePostgresTransactions]?.contains(this) == true) {
            throw ExecutionFailure("Use the transaction scope for database operations inside a transaction")
        }
    }

    private fun ensureOpen() {
        if (closed) throw DatabaseClosed()
    }

    private inner class TransactionScope(
        private val execution: PostgresExecution,
        val rlsGuard: RlsContextGuard = RlsContextGuard(),
    ) : SqlTransaction,
        RlsContextConnectionGuard by rlsGuard {
        override val dialect = this@NodePostgresDatabase.dialect
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

    private inner class LeasedSession(private val execution: PostgresExecution) : DedicatedSqlSession {
        override val dialect: SqlDialect = this@NodePostgresDatabase.dialect

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
            pool: NodePostgresPool,
            codecs: List<dev.quwac.kagiroi.sql.SqlTypeCodec<*, *>> = PostgresStandardTypes.typeCodecs,
            dialect: SqlDialect = PostgresDialect,
        ): NodePostgresDatabase = NodePostgresDatabase(pool, SqlTypeCodecRegistry(codecs), dialect)
    }
}

private class ActiveNodePostgresTransactions(private val databases: List<NodePostgresDatabase> = emptyList()) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ActiveNodePostgresTransactions>

    fun contains(database: NodePostgresDatabase): Boolean = databases.any { it === database }

    fun including(database: NodePostgresDatabase): ActiveNodePostgresTransactions =
        ActiveNodePostgresTransactions(databases + database)
}

private class PostgresExecution(private val session: NodePostgresSession, codecs: SqlTypeCodecRegistry) {
    private val values = PostgresValueCodec(codecs)

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
            throw ResultConversionFailure("PostgreSQL returned a row whose value count does not match selected columns")
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
                throw ResultConversionFailure("PostgreSQL returned a raw row whose value count does not match columns")
            }
            rawSqlRow(result.columnNames, row.map(values::rawValue))
        }
    }

    suspend fun executeControl(sql: String) {
        currentCoroutineContext().ensureActive()
        translateFailure { session.execute(PostgresStatement(sql, emptyList())) }
        currentCoroutineContext().ensureActive()
    }

    private fun toStatement(statement: RenderedSql): PostgresStatement = PostgresStatement(
        statement.sql,
        statement.parameters.mapIndexed { index, parameter -> bind(parameter, index) },
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

@Suppress("CyclomaticComplexMethod")
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

        SQLSTATE_LOCK_NOT_AVAILABLE -> LockTimeoutFailure(message, this, metadata)

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

private fun Throwable.hasConnectionFailure(): Boolean = this is ConnectionFailure ||
    cause?.hasConnectionFailure() == true

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

private fun String?.tlsQueryOptions(): List<String> {
    val query = this?.substringAfter('?', "")?.substringBefore('#')?.lowercase().orEmpty()
    return TLS_URL_OPTIONS.filter { option -> Regex("(^|&)$option=").containsMatchIn(query) }
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
private const val DEFAULT_POSTGRES_PORT = 5432
private const val MIN_PORT = 1
private const val MAX_PORT = 65_535
private const val DEFAULT_MAX_CONNECTIONS = 10
private val DEFAULT_IDLE_TIMEOUT = 10.seconds
private val TLS_URL_OPTIONS = listOf("sslmode", "sslcert", "sslkey", "sslrootcert")
