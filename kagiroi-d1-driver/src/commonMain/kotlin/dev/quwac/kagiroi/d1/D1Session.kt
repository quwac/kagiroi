package dev.quwac.kagiroi.d1

import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.sql.ExecuteResult
import dev.quwac.kagiroi.sql.NonEmptyList
import dev.quwac.kagiroi.sql.RawSqlRow
import dev.quwac.kagiroi.sql.RenderedSql
import dev.quwac.kagiroi.sql.SqlDialect
import dev.quwac.kagiroi.sql.SqlExecution
import dev.quwac.kagiroi.sql.SqlRow
import dev.quwac.kagiroi.sql.nonEmptyListOf
import kotlinx.coroutines.flow.Flow

data class D1Bookmark(val value: String) {
    init {
        require(value.isNotBlank()) { "D1 bookmark must not be blank" }
    }
}

sealed interface D1SessionStart {
    data object Unconstrained : D1SessionStart

    data object Primary : D1SessionStart

    data class After(val bookmark: D1Bookmark) : D1SessionStart
}

/** Sequentially consistent D1 execution scope. This is not a connection or transaction. */
class D1Session internal constructor(
    private val execution: D1Execution,
    private val endpoint: D1SessionEndpoint,
    override val dialect: SqlDialect,
) : SqlExecution {

    override suspend fun execute(statement: RenderedSql): ExecuteResult = execution.execute(statement)

    override fun query(statement: RenderedSql, columns: List<SqlExpression<*>>): Flow<SqlRow> =
        execution.query(statement, columns)

    override suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult =
        execution.executeRaw(sql, parameters)

    override fun queryRaw(sql: String, parameters: List<Any?>): Flow<RawSqlRow> = execution.queryRaw(sql, parameters)

    suspend fun batch(statements: NonEmptyList<D1BatchStatement>): List<D1BatchResult> = execution.batch(statements)

    suspend fun batch(first: D1BatchStatement, vararg remaining: D1BatchStatement): List<D1BatchResult> =
        batch(nonEmptyListOf(first, *remaining))

    suspend fun batch(block: D1BatchBuilder.() -> Unit): List<D1BatchResult> =
        execution.batch(D1BatchBuilder().apply(block).build())

    fun bookmark(): D1Bookmark? = endpoint.bookmark()
}
