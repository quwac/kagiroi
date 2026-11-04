package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.Column
import kotlinx.coroutines.flow.Flow

/** A returning write query with its execution target attached. */
class ExecutableReturningQuery internal constructor(
    private val execution: SqlExecution,
    private val query: ReturningQuery,
) {
    /** Streams the stream result. */
    fun stream(): Flow<SqlRow> = result().stream()

    /** Returns the all result. */
    suspend fun all(): List<SqlRow> = result().all()

    /** Returns the all result. */
    suspend fun <T> all(mapper: (SqlRow) -> T): List<T> = result().all(mapper)

    /** Returns the one result. */
    suspend fun one(): SqlRow = result().one()

    /** Returns the one result. */
    suspend fun <T> one(mapper: (SqlRow) -> T): T = result().one(mapper)

    /** Returns the one or null result. */
    suspend fun oneOrNull(): SqlRow? = result().oneOrNull()

    /** Returns the one or null result. */
    suspend fun <T> oneOrNull(mapper: (SqlRow) -> T): T? = result().oneOrNull(mapper)

    private fun result(): SqlQuery<SqlRow> = execution.query(query)
}

/** Returns the returning result. */
fun <S : InsertQueryState.Renderable> ExecutableInsertQuery<S>.returning(
    column: Column<*>,
    vararg additionalColumns: Column<*>,
): ExecutableReturningQuery = ExecutableReturningQuery(execution, query.returning(column, *additionalColumns))

/** Returns the returning result. */
fun <S : UpdateQueryState.Renderable> ExecutableUpdateQuery<S>.returning(
    column: Column<*>,
    vararg additionalColumns: Column<*>,
): ExecutableReturningQuery = ExecutableReturningQuery(execution, query.returning(column, *additionalColumns))

/** Returns the returning result. */
fun <S : DeleteQueryState.Renderable> ExecutableDeleteQuery<S>.returning(
    column: Column<*>,
    vararg additionalColumns: Column<*>,
): ExecutableReturningQuery = ExecutableReturningQuery(execution, query.returning(column, *additionalColumns))
