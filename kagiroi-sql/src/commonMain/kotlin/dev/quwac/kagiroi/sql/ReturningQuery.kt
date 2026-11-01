package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.schema.Table

/** A typed write query whose affected rows are returned to the application. */
sealed interface ReturningQuery {
    val table: Table

    val columns: List<Column<*>>
}

/** An INSERT query with a standard SQL RETURNING projection. */
class ReturningInsertQuery<S : InsertQueryState.Renderable> internal constructor(
    internal val query: InsertQuery<S>,
    override val columns: List<Column<*>>,
) : ReturningQuery {
    override val table: Table = query.into
}

/** An UPDATE query with a standard SQL RETURNING projection. */
class ReturningUpdateQuery<S : UpdateQueryState.Renderable> internal constructor(
    internal val query: UpdateQuery<S>,
    override val columns: List<Column<*>>,
) : ReturningQuery {
    override val table: Table = query.table
}

/** A DELETE query with a standard SQL RETURNING projection. */
class ReturningDeleteQuery<S : DeleteQueryState.Renderable> internal constructor(
    internal val query: DeleteQuery<S>,
    override val columns: List<Column<*>>,
) : ReturningQuery {
    override val table: Table = query.from
}

fun <S : InsertQueryState.Renderable> InsertQuery<S>.returning(
    column: Column<*>,
    vararg additionalColumns: Column<*>,
): ReturningInsertQuery<S> = ReturningInsertQuery(this, returningColumns(into, column, additionalColumns))

fun <S : UpdateQueryState.Renderable> UpdateQuery<S>.returning(
    column: Column<*>,
    vararg additionalColumns: Column<*>,
): ReturningUpdateQuery<S> = ReturningUpdateQuery(this, returningColumns(table, column, additionalColumns))

fun <S : DeleteQueryState.Renderable> DeleteQuery<S>.returning(
    column: Column<*>,
    vararg additionalColumns: Column<*>,
): ReturningDeleteQuery<S> = ReturningDeleteQuery(this, returningColumns(from, column, additionalColumns))

private fun returningColumns(
    table: Table,
    column: Column<*>,
    additionalColumns: Array<out Column<*>>,
): List<Column<*>> {
    val columns = listOf(column, *additionalColumns)
    columns.forEach { table.requireReturningColumn(it) }
    require(columns.distinctBy { it }.size == columns.size) { "RETURNING column appears more than once" }
    return columns
}

private fun Table.requireReturningColumn(column: Column<*>) {
    require(column.table === this && hasColumn(column)) {
        "RETURNING column is not registered by table $this: $column"
    }
}
