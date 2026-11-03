package dev.quwac.kagiroi.d1

import dev.quwac.kagiroi.core.CapabilityKey
import dev.quwac.kagiroi.core.DatabaseCapability
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.sql.BatchFailure
import dev.quwac.kagiroi.sql.ConstraintViolation
import dev.quwac.kagiroi.sql.DatabaseClosed
import dev.quwac.kagiroi.sql.ExecuteResult
import dev.quwac.kagiroi.sql.ExecutionFailure
import dev.quwac.kagiroi.sql.InvalidBinding
import dev.quwac.kagiroi.sql.NonEmptyList
import dev.quwac.kagiroi.sql.RawSqlRow
import dev.quwac.kagiroi.sql.RenderedSql
import dev.quwac.kagiroi.sql.ResultConversionFailure
import dev.quwac.kagiroi.sql.ReturningCapability
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
import dev.quwac.kagiroi.sql.UnsupportedCapability
import dev.quwac.kagiroi.sql.nonEmptyListOf
import dev.quwac.kagiroi.sql.rawSqlRow
import dev.quwac.kagiroi.sql.sqlRow
import dev.quwac.kagiroi.sqlite.SqliteDialect
import dev.quwac.kagiroi.sqlite.SqliteExtension
import dev.quwac.kagiroi.sqlite.SqlitePhysicalValue
import dev.quwac.kagiroi.sqlite.SqliteStandardTypes
import dev.quwac.kagiroi.sqlite.SqliteValueCodec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Kagiroi driver for a Cloudflare D1 Workers binding. */
@Suppress("TooManyFunctions") // Implements the generic SQL surface plus D1 batch and session entry points.
class D1Database internal constructor(
    private val endpoint: D1Endpoint,
    codecs: SqlTypeCodecRegistry,
    override val dialect: SqlDialect,
) : SqlDatabase {
    private var closed = false
    private val execution = D1Execution(endpoint, codecs, ::ensureOpen)

    @Suppress("UNCHECKED_CAST")
    override fun <C : DatabaseCapability> capability(key: CapabilityKey<C>): C? = when (key) {
        SqlCapabilities.Sql -> SqlCapability as C
        SqlCapabilities.Returning -> ReturningCapability as C
        SqlCapabilities.Streaming -> SqlStreamingCapability(SqlStreamingMode.BUFFERED) as C
        D1Capabilities.Batch -> D1BatchCapability as C
        D1Capabilities.Sessions -> if (endpoint.supportsSessions) D1SessionsCapability as C else null
        else -> null
    }

    override suspend fun execute(statement: RenderedSql): ExecuteResult = execution.execute(statement)

    override fun query(statement: RenderedSql, columns: List<SqlExpression<*>>): Flow<SqlRow> =
        execution.query(statement, columns)

    override suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult =
        execution.executeRaw(sql, parameters)

    override fun queryRaw(sql: String, parameters: List<Any?>): Flow<RawSqlRow> = execution.queryRaw(sql, parameters)

    override suspend fun <T> transaction(block: suspend SqlTransaction.() -> T): T =
        throw UnsupportedCapability(SqlCapabilities.Transactions.name)

    suspend fun batch(statements: NonEmptyList<D1BatchStatement>): List<D1BatchResult> = execution.batch(statements)

    suspend fun batch(first: D1BatchStatement, vararg remaining: D1BatchStatement): List<D1BatchResult> =
        batch(nonEmptyListOf(first, *remaining))

    suspend fun batch(block: D1BatchBuilder.() -> Unit): List<D1BatchResult> =
        execution.batch(D1BatchBuilder().apply(block).build())

    fun session(start: D1SessionStart = D1SessionStart.Unconstrained): D1Session {
        ensureOpen()
        check(endpoint.supportsSessions) { "D1 Sessions are not available for this D1 endpoint" }
        val sessionEndpoint = endpoint.session(start)
        return D1Session(D1Execution(sessionEndpoint, execution.codecs, ::ensureOpen), sessionEndpoint, dialect)
    }

    fun session(after: D1Bookmark): D1Session = session(D1SessionStart.After(after))

    override suspend fun close() {
        closed = true
    }

    private fun ensureOpen() {
        if (closed) throw DatabaseClosed()
    }

    internal companion object {
        fun create(endpoint: D1Endpoint, configure: D1DatabaseConfiguration.() -> Unit = {}): D1Database {
            val configuration = D1DatabaseConfiguration().apply(configure)
            return D1Database(
                endpoint,
                SqlTypeCodecRegistry(configuration.typeCodecs()),
                configuration.dialect(),
            )
        }
    }
}

class D1DatabaseConfiguration internal constructor() {
    private val extensions = mutableListOf<SqliteExtension>()

    fun install(extension: SqliteExtension) {
        extensions += extension
    }

    internal fun typeCodecs() = SqliteStandardTypes.typeCodecs + extensions.flatMap(SqliteExtension::typeCodecs)

    internal fun dialect(): SqlDialect = SqliteDialect.configured(extensions)
}

internal class D1Execution(
    private val endpoint: D1Endpoint,
    internal val codecs: SqlTypeCodecRegistry,
    private val ensureOpen: () -> Unit,
) {
    private val values = SqliteValueCodec(codecs)

    suspend fun execute(statement: RenderedSql): ExecuteResult {
        ensureOpen()
        currentCoroutineContext().ensureActive()
        val metadata = translateFailure { endpoint.execute(toStatement(statement)) }
        currentCoroutineContext().ensureActive()
        return ExecuteResult(metadata.changes)
    }

    fun query(statement: RenderedSql, columns: List<SqlExpression<*>>): Flow<SqlRow> = flow {
        ensureOpen()
        currentCoroutineContext().ensureActive()
        val result = translateFailure { endpoint.query(toStatement(statement)) }
        if (result.rows.any { it.size != columns.size }) {
            throw ExecutionFailure("D1 returned a row whose value count does not match the selected columns")
        }
        for (row in result.rows) {
            currentCoroutineContext().ensureActive()
            emit(sqlRow(columns, columns.indices.map { values.decode(columns[it], row[it]) }))
        }
    }

    suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult =
        execute(RenderedSql(sql, parameters.map { SqlParameter(it, null) }))

    fun queryRaw(sql: String, parameters: List<Any?>): Flow<RawSqlRow> = flow {
        ensureOpen()
        currentCoroutineContext().ensureActive()
        val statement = RenderedSql(sql, parameters.map { SqlParameter(it, null) })
        val result = translateFailure { endpoint.query(toStatement(statement)) }
        for (row in result.rows) {
            currentCoroutineContext().ensureActive()
            emit(rawSqlRow(result.columnNames, row.map(values::rawValue)))
        }
    }

    suspend fun batch(statements: NonEmptyList<D1BatchStatement>): List<D1BatchResult> {
        ensureOpen()
        currentCoroutineContext().ensureActive()
        val metadata = translateFailure { endpoint.batch(statements.map { toStatement(it.rendered) }) }
        currentCoroutineContext().ensureActive()
        if (metadata.size != statements.size) {
            throw BatchFailure("D1 returned ${metadata.size} batch results for ${statements.size} statements")
        }
        return metadata.map { outcome ->
            val rows = outcome.rows?.let { rawRows ->
                rawRows.rows.map { row ->
                    if (row.size != rawRows.columnNames.size) {
                        throw ResultConversionFailure(
                            "D1 returned a batch row whose value count does not match its columns",
                        )
                    }
                    rawSqlRow(rawRows.columnNames, row.map(values::rawValue))
                }
            }.orEmpty()
            D1BatchResult(ExecuteResult(outcome.metadata.changes), outcome.metadata, rows)
        }
    }

    private fun toStatement(statement: RenderedSql): D1Statement = D1Statement(
        statement.sql,
        statement.parameters.mapIndexed { index, parameter -> bind(parameter, index) },
    )

    @Suppress("TooGenericExceptionCaught")
    private fun bind(parameter: SqlParameter<*>, index: Int): SqlitePhysicalValue = try {
        values.binding(parameter.value, parameter.expression)
    } catch (error: Throwable) {
        throw InvalidBinding(
            "D1 cannot bind ${parameter.column?.toString() ?: "parameter ${index + 1}"}",
            error,
        )
    }

    private suspend inline fun <T> translateFailure(crossinline block: suspend () -> T): T = try {
        block()
    } catch (error: D1DriverException) {
        val message = "D1 execution failed: ${error.message}"
        when (error.kind) {
            D1FailureKind.CONSTRAINT -> throw ConstraintViolation(message, error, error.vendorCode)
            D1FailureKind.INVALID_BINDING -> throw InvalidBinding(message, error, error.vendorCode)
            D1FailureKind.RESULT_CONVERSION -> throw ResultConversionFailure(message, error, error.vendorCode)
            D1FailureKind.BATCH -> throw BatchFailure(message, error, error.vendorCode)
            D1FailureKind.EXECUTION -> throw ExecutionFailure(message, error, error.vendorCode)
        }
    }
}
