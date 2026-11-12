package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.core.CapabilityKey
import dev.quwac.kagiroi.core.Database
import dev.quwac.kagiroi.core.DatabaseCapability
import dev.quwac.kagiroi.schema.Sequence
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext

/** The outcome of a statement which does not return rows.
 *
 * @property affectedRows number of rows reported as affected by the database.
 */
data class ExecuteResult(val affectedRows: Long)

/** A row returned by the explicit raw SQL escape hatch.
 *
 * @property columnNames result column names in database order.
 */
class RawSqlRow internal constructor(val columnNames: List<String>, private val values: List<Any?>) {
    init {
        require(columnNames.size == values.size) { "Raw SQL row names and values have different sizes" }
    }

    /** Number of values in this row. */
    val size: Int get() = values.size

    /** Returns a value by zero-based column position. */
    operator fun get(index: Int): Any? = values[index]

    /** Returns a value by result column name. */
    operator fun get(name: String): Any? {
        val index = columnNames.indexOf(name)
        require(index >= 0) { "Raw SQL row does not contain column: $name" }
        return values[index]
    }

    /** Reads a nullable string value by column name. */
    fun string(name: String): String? = typed(name, "String")

    /** Reads a nullable signed 64-bit value by column name. */
    fun long(name: String): Long? = typed(name, "Long")

    /** Reads a nullable double-precision value by column name. */
    fun double(name: String): Double? = typed(name, "Double")

    /** Reads a nullable byte array by column name. */
    fun bytes(name: String): ByteArray? = typed(name, "ByteArray")

    private inline fun <reified T> typed(name: String, expectedType: String): T? {
        val value = get(name) ?: return null
        return value as? T ?: throw InvalidValueConversion(
            column = name,
            value = value,
            expectedType = expectedType,
        )
    }
}

/** Creates a raw result row. Driver implementations use this at their public boundary. */
fun rawSqlRow(columnNames: List<String>, values: List<Any?>): RawSqlRow = RawSqlRow(columnNames, values)

/** Operations available both on a database and inside an explicit transaction. */
interface SqlExecution {
    /** Selects this table's declared columns using the current database or transaction receiver. */
    @Suppress("UNCHECKED_CAST") // Scalar access remains disabled by the MultipleProjections shape.
    fun Table.select(): ExecutableSelectQuery<SelectQueryState.Start, Any?, MultipleProjections> {
        val projections = columns
        require(projections.isNotEmpty()) { "Table SELECT requires at least one column" }
        return ExecutableSelectQuery(
            this@SqlExecution,
            SelectQuery(projections.first() as SqlExpression<Any?>, projections, this),
        )
    }

    /** SQL syntax and capability model used for rendering statements. */
    val dialect: SqlDialect

    /** Executes a rendered statement that does not return rows. */
    suspend fun execute(statement: RenderedSql): ExecuteResult

    /** Executes a rendered row-returning statement and decodes each row using [columns]. */
    fun query(statement: RenderedSql, columns: List<SqlExpression<*>>): Flow<SqlRow>

    /** Executes raw SQL with positional parameter values. */
    suspend fun executeRaw(sql: String, parameters: List<Any?> = emptyList()): ExecuteResult

    /** Executes raw SQL and returns untyped rows. */
    fun queryRaw(sql: String, parameters: List<Any?> = emptyList()): Flow<RawSqlRow>
}

/**
 * Raw SQL execution bound to one dedicated driver session for the duration of a callback.
 *
 * The driver retains connection ownership and host-specific values. The callback only receives
 * Kagiroi's regular SQL execution surface.
 */
@SqlExecutionDsl
interface DedicatedSqlSession : SqlExecution

/** A driver capability that runs a callback on one physical SQL session. */
interface DedicatedSqlSessionCapability : DatabaseCapability {
    /** Runs [block] using one driver-owned physical SQL session. */
    suspend fun <T> use(block: suspend DedicatedSqlSession.() -> T): T
}

/** Prevents an outer database receiver from being used as an implicit nested transaction entry point. */
@DslMarker
annotation class SqlExecutionDsl

/** SQL database entry point. Connections and auto-commit controls are intentionally not exposed. */
@SqlExecutionDsl
interface SqlDatabase :
    SqlExecution,
    Database {
    /** Runs [block] in a transaction and returns its result. */
    suspend fun <T> transaction(block: suspend SqlTransaction.() -> T): T

    /** Closes the database and releases its resources. */
    suspend fun close()
}

/** Capability value indicating that an implementation supports SQL execution. */
data object SqlCapability : DatabaseCapability

/** Capability value indicating that an implementation supports transactions. */
data object TransactionCapability : DatabaseCapability

/** Advertises that transactions created by this driver can create savepoints. */
data object SavepointCapability : DatabaseCapability

/** Advertises that this driver implements DML RETURNING through typed write queries. */
data object ReturningCapability : DatabaseCapability

/** Advertises that this driver implements typed dialect-specific DML output rows. */
data object OutputCapability : DatabaseCapability

/** Advertises that this driver implements typed dialect-specific DML RETURNING INTO out binds. */
data object ReturningIntoCapability : DatabaseCapability

/** Transaction isolation levels that can be requested from a database. */
enum class TransactionIsolation {
    /** Reads only committed data and prevents dirty reads. */
    READ_COMMITTED,

    /** Repeated reads in one transaction return stable values. */
    REPEATABLE_READ,

    /** Transactions behave as if executed serially. */
    SERIALIZABLE,
}

/** Optional transaction-isolation entry point. Drivers expose only the levels they implement. */
interface TransactionIsolationCapability : DatabaseCapability {
    /** Isolation levels implemented by this database. */
    val supported: Set<TransactionIsolation>

    /** Runs [block] at [isolation] level. */
    suspend fun <T> transaction(isolation: TransactionIsolation, block: suspend SqlTransaction.() -> T): T
}

/** Whether query rows are streamed from the driver or buffered before delivery. */
enum class SqlStreamingMode {
    /** Rows are delivered incrementally by the driver. */
    NATIVE,

    /** Rows are buffered before they are exposed to the caller. */
    BUFFERED,
}

/** Description of the query row-delivery behavior.
 *
 * @property mode active row-delivery mode.
 */
data class SqlStreamingCapability(val mode: SqlStreamingMode) : DatabaseCapability

/** Typed capability keys shared by SQL database implementations. */
object SqlCapabilities {
    /** Capability key for SQL execution. */
    val Sql = CapabilityKey<SqlCapability>("sql")

    /** Capability key for transactions. */
    val Transactions = CapabilityKey<TransactionCapability>("transactions")

    /** Capability key for savepoints. */
    val Savepoints = CapabilityKey<SavepointCapability>("savepoints")

    /** Capability key for DML RETURNING. */
    val Returning = CapabilityKey<ReturningCapability>("returning")

    /** Capability key for SQL Server-style DML OUTPUT. */
    val Output = CapabilityKey<OutputCapability>("output")

    /** Capability key for Oracle-style RETURNING INTO. */
    val ReturningInto = CapabilityKey<ReturningIntoCapability>("returning-into")

    /** Capability key for requested transaction isolation. */
    val TransactionIsolation = CapabilityKey<TransactionIsolationCapability>("transaction-isolation")

    /** Capability key for streaming behavior. */
    val Streaming = CapabilityKey<SqlStreamingCapability>("streaming")

    /** Capability key for dedicated physical sessions. */
    val DedicatedSession = CapabilityKey<DedicatedSqlSessionCapability>("dedicated-session")
}

/** Runs [block] on one driver-owned session when the selected driver supports it. */
suspend fun <T> SqlDatabase.dedicatedSession(block: suspend DedicatedSqlSession.() -> T): T {
    val capability = capability(SqlCapabilities.DedicatedSession)
        ?: throw UnsupportedCapability(SqlCapabilities.DedicatedSession.name)
    return capability.use(block)
}

/** Starts an explicitly isolated transaction when the database advertises that capability. */
suspend fun <T> SqlDatabase.transaction(isolation: TransactionIsolation, block: suspend SqlTransaction.() -> T): T {
    val capability = capability(SqlCapabilities.TransactionIsolation)
        ?: throw UnsupportedCapability(SqlCapabilities.TransactionIsolation.name)
    if (isolation !in capability.supported) {
        throw UnsupportedCapability("${SqlCapabilities.TransactionIsolation.name}:${isolation.name}")
    }
    return capability.transaction(isolation, block)
}

/**
 * Operations scoped to one savepoint inside a transaction.
 *
 * A savepoint has the same SQL execution surface as its parent transaction. It is not a nested
 * transaction: an exception rolls back only to this savepoint before it is propagated.
 */
@SqlExecutionDsl
interface SavepointScope : SqlExecution {
    /** Runs [block] inside a savepoint nested within this transaction scope. */
    suspend fun <T> savepoint(block: suspend SavepointScope.() -> T): T
}

/** Operations scoped to one transaction. Nested transactions are not part of this API. */
@SqlExecutionDsl
interface SqlTransaction : SavepointScope

/**
 * Internal driver control for transaction-local savepoints.
 *
 * Drivers provide only the operations their database supports. In particular, `release` may be a
 * no-op for databases such as SQL Server and Oracle which do not expose a release statement.
 */
class SavepointController(
    private val create: suspend (String) -> Unit,
    private val rollbackTo: suspend (String) -> Unit,
    private val release: (suspend (String) -> Unit)? = null,
) {
    internal suspend fun create(name: String) = create.invoke(name)

    internal suspend fun rollbackTo(name: String) = rollbackTo.invoke(name)

    internal suspend fun release(name: String) {
        release?.invoke(name)
    }
}

/**
 * Reusable transaction-local savepoint lifecycle for driver transaction scopes.
 *
 * The supplied [SqlExecution] is always the caller's transaction scope, so typed and raw
 * statements remain on the same physical transaction context rather than returning to the
 * database-level execution path.
 */
class SavepointSupport(private val controller: SavepointController) {
    private var nextName = 0
    private var recoveryFailure: Throwable? = null

    /** Provides the savepoint result. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun <T> savepoint(execution: SqlExecution, block: suspend SavepointScope.() -> T): T {
        requireUsable()
        val name = nextName()
        controller.create(name)
        val scope = SavepointScopeExecution(execution, this)
        return try {
            val result = try {
                scope.block()
            } catch (original: Throwable) {
                recover(name, original)
                throw original
            }
            try {
                controller.release(name)
            } catch (releaseFailure: Throwable) {
                markUnusable(releaseFailure)
                throw TransactionFailure("Failed to complete savepoint", releaseFailure)
            }
            result
        } finally {
            scope.active = false
        }
    }

    /** Rejects further use after savepoint recovery could not establish a known transaction state. */
    fun requireUsable() {
        recoveryFailure?.let { failure ->
            throw TransactionFailure("Transaction scope is no longer usable after savepoint failure", failure)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun recover(name: String, original: Throwable) {
        try {
            withContext(NonCancellable) {
                controller.rollbackTo(name)
                controller.release(name)
            }
        } catch (recoveryFailure: Throwable) {
            original.addSuppressed(recoveryFailure)
            markUnusable(recoveryFailure)
        }
    }

    private fun nextName(): String {
        nextName += 1
        return "kagiroi_sp_$nextName"
    }

    private fun markUnusable(failure: Throwable) {
        if (recoveryFailure == null) recoveryFailure = failure
    }
}

private class SavepointScopeExecution(private val execution: SqlExecution, private val support: SavepointSupport) :
    SavepointScope,
    SqlDmlReturningIntoExecution {
    var active = true

    override val dialect: SqlDialect
        get() = execution.dialect

    override suspend fun execute(statement: RenderedSql): ExecuteResult {
        requireActive()
        return execution.execute(statement)
    }

    override fun query(statement: RenderedSql, columns: List<SqlExpression<*>>): Flow<SqlRow> =
        kotlinx.coroutines.flow.flow {
            requireActive()
            execution.query(statement, columns).collect { emit(it) }
        }

    override suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult {
        requireActive()
        return execution.executeRaw(sql, parameters)
    }

    override fun queryRaw(sql: String, parameters: List<Any?>): Flow<RawSqlRow> = kotlinx.coroutines.flow.flow {
        requireActive()
        execution.queryRaw(sql, parameters).collect { emit(it) }
    }

    override suspend fun executeDmlReturningInto(
        statement: RenderedSql,
        returning: SqlDmlReturningInto,
    ): SqlDmlReturningIntoResult {
        requireActive()
        val returningExecution = execution as? SqlDmlReturningIntoExecution
            ?: throw UnsupportedCapability("returning-into")
        return returningExecution.executeDmlReturningInto(statement, returning)
    }

    override suspend fun <T> savepoint(block: suspend SavepointScope.() -> T): T {
        requireActive()
        return support.savepoint(this, block)
    }

    private fun requireActive() {
        if (!active) throw ExecutionFailure("Savepoint scope is no longer active")
        support.requireUsable()
    }
}

/** A cold query result which can be streamed or collected with strict cardinality checks. */
class SqlQuery<T> internal constructor(private val rows: Flow<T>) {
    /** Returns the query's cold row stream. */
    fun stream(): Flow<T> = rows

    /** Collects all rows into a list. */
    suspend fun all(): List<T> = rows.toList()

    /** Collects and maps all rows. */
    suspend fun <R> all(mapper: (T) -> R): List<R> = rows.map(mapper).toList()

    /** Returns exactly one row, failing when the result is empty or contains multiple rows. */
    suspend fun one(): T {
        val found = rows.take(2).toList()
        return when (found.size) {
            0 -> throw ExpectedOneRowButFoundZero()
            1 -> found.single()
            else -> throw ExpectedOneRowButFoundMultiple(found.size)
        }
    }

    /** Returns exactly one mapped row, failing when the result is empty or contains multiple rows. */
    suspend fun <R> one(mapper: (T) -> R): R = mapper(one())

    /** Returns the sole row, or `null` for an empty result; fails when multiple rows are returned. */
    suspend fun oneOrNull(): T? {
        val found = rows.take(2).toList()
        return when (found.size) {
            0 -> null
            1 -> found.single()
            else -> throw ExpectedOneRowButFoundMultiple(found.size)
        }
    }

    /** Returns the sole mapped row, or `null` for an empty result. */
    suspend fun <R> oneOrNull(mapper: (T) -> R): R? = oneOrNull()?.let(mapper)
}

/** Renders and executes [query], decoding rows with its typed projection schema. */
fun SqlExecution.query(query: QueryExpression<*, *>): SqlQuery<SqlRow> {
    val rendered = SqlRenderer(dialect).render(query)
    return SqlQuery(query(rendered, query.columns))
}

/** Executes a typed DML RETURNING query and decodes its result rows. */
fun SqlExecution.query(query: ReturningQuery): SqlQuery<SqlRow> {
    val rendered = SqlRenderer(dialect).render(query)
    return SqlQuery(query(rendered, query.columns))
}

/**
 * Allocates and returns the next signed 64-bit value from an independent [sequence].
 *
 * This is intentionally separate from identity generated-key delivery. The database consumes a
 * sequence value even if a surrounding transaction later rolls back when that is its native rule.
 */
suspend fun SqlExecution.nextSequenceValue(sequence: Sequence): Long {
    val sequenceDialect = dialect as? SqlSequenceDialect ?: throw UnsupportedCapability("sequence-next-value")
    val rendered = sequenceDialect.renderNextValue(sequence)
    require(rendered.parameters.isEmpty()) { "Sequence next-value query must not have parameters" }
    return SqlQuery(queryRaw(rendered.sql)).one().long(SEQUENCE_VALUE_COLUMN)
        ?: throw InvalidValueConversion(SEQUENCE_VALUE_COLUMN, null, "Long")
}

/** Renders and executes [query] as an INSERT statement. */
suspend fun <S : InsertQueryState.Renderable> SqlExecution.execute(query: InsertQuery<S>): ExecuteResult =
    execute(SqlRenderer(dialect).render(query))

/** Renders and executes [query] as an UPDATE statement. */
suspend fun <S : UpdateQueryState.Renderable> SqlExecution.execute(query: UpdateQuery<S>): ExecuteResult =
    execute(SqlRenderer(dialect).render(query))

/** Renders and executes [query] as a DELETE statement. */
suspend fun <S : DeleteQueryState.Renderable> SqlExecution.execute(query: DeleteQuery<S>): ExecuteResult =
    execute(SqlRenderer(dialect).render(query))

/**
 * Renders and executes an explicit DDL statement without assigning affected-row semantics to its result.
 *
 * One logical statement can render to an ordered sequence. The sequence is not made atomic by this API.
 */
suspend fun SqlExecution.execute(statement: DdlStatement) {
    val ddlDialect = dialect as? SqlDdlDialect
        ?: throw UnsupportedCapability("typed-ddl")
    // renderDdl builds the complete plan before this loop starts, so unsupported later ALTER
    // operations fail before an earlier rendered statement reaches the database.
    ddlDialect.renderDdl(statement).statements.forEach { rendered -> execute(rendered) }
}

private const val SEQUENCE_VALUE_COLUMN = "kagiroi_sequence_value"
