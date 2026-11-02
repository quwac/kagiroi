package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.Table
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A typed DML row-image query with its execution target attached. */
class ExecutableDmlOutputQuery internal constructor(
    private val execution: SqlExecution,
    private val statement: RenderedSql,
    private val output: SqlDmlOutput,
) {
    fun stream(): Flow<SqlDmlOutputRow> = execution.query(statement, output.columns.map(SqlDmlOutputColumn<*>::column))
        .map { row -> SqlDmlOutputRow(output.columns, row::storageValueAt) }

    suspend fun all(): List<SqlDmlOutputRow> = SqlQuery(stream()).all()

    suspend fun <T> all(mapper: (SqlDmlOutputRow) -> T): List<T> = SqlQuery(stream()).all(mapper)

    suspend fun one(): SqlDmlOutputRow = SqlQuery(stream()).one()

    suspend fun <T> one(mapper: (SqlDmlOutputRow) -> T): T = SqlQuery(stream()).one(mapper)

    suspend fun oneOrNull(): SqlDmlOutputRow? = SqlQuery(stream()).oneOrNull()

    suspend fun <T> oneOrNull(mapper: (SqlDmlOutputRow) -> T): T? = SqlQuery(stream()).oneOrNull(mapper)
}

fun <S : InsertQueryState.Renderable> ExecutableInsertQuery<S>.dmlOutput(
    output: SqlDmlOutput,
    allowedImages: Set<SqlDmlOutputImage>,
): ExecutableDmlOutputQuery {
    output.requireFor(query.into, allowedImages)
    return execution.executeDmlOutput(DmlOutputRenderer(execution.dialect).render(query, output), output)
}

fun <S : UpdateQueryState.Renderable> ExecutableUpdateQuery<S>.dmlOutput(
    output: SqlDmlOutput,
    allowedImages: Set<SqlDmlOutputImage>,
): ExecutableDmlOutputQuery {
    output.requireFor(query.table, allowedImages)
    return execution.executeDmlOutput(DmlOutputRenderer(execution.dialect).render(query, output), output)
}

fun <S : DeleteQueryState.Renderable> ExecutableDeleteQuery<S>.dmlOutput(
    output: SqlDmlOutput,
    allowedImages: Set<SqlDmlOutputImage>,
): ExecutableDmlOutputQuery {
    output.requireFor(query.from, allowedImages)
    return execution.executeDmlOutput(DmlOutputRenderer(execution.dialect).render(query, output), output)
}

private fun SqlExecution.executeDmlOutput(statement: RenderedSql, output: SqlDmlOutput): ExecutableDmlOutputQuery =
    ExecutableDmlOutputQuery(this, statement, output)

/** A DML row whose values are selected by dialect-specific row-image references. */
class SqlDmlOutputRow internal constructor(
    private val columns: List<SqlDmlOutputColumn<*>>,
    private val storageValueAt: (Int) -> Any?,
) {
    /** Returns the value selected by [reference], preserving its image and Kotlin column type. */
    operator fun <T> get(reference: SqlDmlOutputReference<T>): T {
        val column = reference.dmlOutputColumn
        val index = columns.indexOfFirst { it == column }
        require(index >= 0) { "DML output reference was not selected: $reference" }
        return column.column.fromStorageValue(storageValueAt(index))
    }
}

private fun SqlDmlOutput.requireFor(table: Table, allowedImages: Set<SqlDmlOutputImage>) {
    columns.forEach { reference ->
        require(reference.column.table === table && table.hasColumn(reference.column)) {
            "DML output column is not registered by table $table: ${reference.column}"
        }
        require(reference.image in allowedImages) {
            "DML output row image is not available for this statement: ${reference.image}"
        }
    }
}
