package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.core.CapabilityKey
import dev.quwac.kagiroi.core.Database
import dev.quwac.kagiroi.core.DatabaseCapability
import dev.quwac.kagiroi.schema.Sequence
import dev.quwac.kagiroi.schema.SqlExpression
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext

/** The outcome of a statement which does not return rows. */
data class ExecuteResult(val affectedRows: Long)

/** A row returned by the explicit raw SQL escape hatch. */
class RawSqlRow internal constructor(val columnNames: List<String>, private val values: List<Any?>) {
    init {
        require(columnNames.size == values.size) { "Raw SQL row names and values have different sizes" }
    }

    val size: Int get() = values.size

    operator fun get(index: Int): Any? = values[index]

    operator fun get(name: String): Any? {
        val index = columnNames.indexOf(name)
        require(index >= 0) { "Raw SQL row does not contain column: $name" }
        return values[index]
    }

    fun string(name: String): String? = typed(name, "String")

    fun long(name: String): Long? = typed(name, "Long")

    fun double(name: String): Double? = typed(name, "Double")

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
    val dialect: SqlDialect

    suspend fun execute(statement: RenderedSql): ExecuteResult

    fun query(statement: RenderedSql, columns: List<SqlExpression<*>>): Flow<SqlRow>

    suspend fun executeRaw(sql: String, parameters: List<Any?> = emptyList()): ExecuteResult

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
    suspend fun <T> transaction(block: suspend SqlTransaction.() -> T): T

    suspend fun close()
}

data object SqlCapability : DatabaseCapability

data object TransactionCapability : DatabaseCapability

/** Advertises that transactions created by this driver can create savepoints. */
data object SavepointCapability : DatabaseCapability

/** Advertises that this driver implements DML RETURNING through typed write queries. */
data object ReturningCapability : DatabaseCapability

/** Advertises that this driver implements typed dialect-specific DML output rows. */
data object OutputCapability : DatabaseCapability

/** Advertises that this driver implements typed dialect-specific DML RETURNING INTO out binds. */
data object ReturningIntoCapability : DatabaseCapability

enum class TransactionIsolation {
    READ_COMMITTED,
    REPEATABLE_READ,
    SERIALIZABLE,
}

/** Optional transaction-isolation entry point. Drivers expose only the levels they implement. */
interface TransactionIsolationCapability : DatabaseCapability {
    val supported: Set<TransactionIsolation>

    suspend fun <T> transaction(isolation: TransactionIsolation, block: suspend SqlTransaction.() -> T): T
}

enum class SqlStreamingMode {
    NATIVE,
    BUFFERED,
}

data class SqlStreamingCapability(val mode: SqlStreamingMode) : DatabaseCapability

/** Typed capability keys shared by SQL database implementations. */
object SqlCapabilities {
    val Sql = CapabilityKey<SqlCapability>("sql")
    val Transactions = CapabilityKey<TransactionCapability>("transactions")
    val Savepoints = CapabilityKey<SavepointCapability>("savepoints")
    val Returning = CapabilityKey<ReturningCapability>("returning")
    val Output = CapabilityKey<OutputCapability>("output")
    val ReturningInto = CapabilityKey<ReturningIntoCapability>("returning-into")
    val TransactionIsolation = CapabilityKey<TransactionIsolationCapability>("transaction-isolation")
    val Streaming = CapabilityKey<SqlStreamingCapability>("streaming")
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
    fun stream(): Flow<T> = rows

    suspend fun all(): List<T> = rows.toList()

    suspend fun <R> all(mapper: (T) -> R): List<R> = rows.map(mapper).toList()

    suspend fun one(): T {
        val found = rows.take(2).toList()
        return when (found.size) {
            0 -> throw ExpectedOneRowButFoundZero()
            1 -> found.single()
            else -> throw ExpectedOneRowButFoundMultiple(found.size)
        }
    }

    suspend fun <R> one(mapper: (T) -> R): R = mapper(one())

    suspend fun oneOrNull(): T? {
        val found = rows.take(2).toList()
        return when (found.size) {
            0 -> null
            1 -> found.single()
            else -> throw ExpectedOneRowButFoundMultiple(found.size)
        }
    }

    suspend fun <R> oneOrNull(mapper: (T) -> R): R? = oneOrNull()?.let(mapper)
}

fun SqlExecution.query(query: QueryExpression<*, *>): SqlQuery<SqlRow> {
    val rendered = SqlRenderer(dialect).render(query)
    return SqlQuery(query(rendered, query.columns))
}

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

suspend fun <S : InsertQueryState.Renderable> SqlExecution.execute(query: InsertQuery<S>): ExecuteResult =
    execute(SqlRenderer(dialect).render(query))

suspend fun <S : UpdateQueryState.Renderable> SqlExecution.execute(query: UpdateQuery<S>): ExecuteResult =
    execute(SqlRenderer(dialect).render(query))

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
