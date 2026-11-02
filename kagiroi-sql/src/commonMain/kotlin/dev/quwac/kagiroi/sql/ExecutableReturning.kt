package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.Column
import kotlinx.coroutines.flow.Flow

/** A returning write query with its execution target attached. */
class ExecutableReturningQuery internal constructor(
    private val execution: SqlExecution,
    private val query: ReturningQuery,
) {
    fun stream(): Flow<SqlRow> = result().stream()

    suspend fun all(): List<SqlRow> = result().all()

    suspend fun <T> all(mapper: (SqlRow) -> T): List<T> = result().all(mapper)

    suspend fun one(): SqlRow = result().one()

    suspend fun <T> one(mapper: (SqlRow) -> T): T = result().one(mapper)

    suspend fun oneOrNull(): SqlRow? = result().oneOrNull()

    suspend fun <T> oneOrNull(mapper: (SqlRow) -> T): T? = result().oneOrNull(mapper)

    private fun result(): SqlQuery<SqlRow> = execution.query(query)
}

fun <S : InsertQueryState.Renderable> ExecutableInsertQuery<S>.returning(
    column: Column<*>,
    vararg additionalColumns: Column<*>,
): ExecutableReturningQuery = ExecutableReturningQuery(execution, query.returning(column, *additionalColumns))

fun <S : UpdateQueryState.Renderable> ExecutableUpdateQuery<S>.returning(
    column: Column<*>,
    vararg additionalColumns: Column<*>,
): ExecutableReturningQuery = ExecutableReturningQuery(execution, query.returning(column, *additionalColumns))

fun <S : DeleteQueryState.Renderable> ExecutableDeleteQuery<S>.returning(
    column: Column<*>,
    vararg additionalColumns: Column<*>,
): ExecutableReturningQuery = ExecutableReturningQuery(execution, query.returning(column, *additionalColumns))
