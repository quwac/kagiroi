package dev.quwac.kagiroi.sqlserver

import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.sql.DeleteQueryState
import dev.quwac.kagiroi.sql.ExecutableDeleteQuery
import dev.quwac.kagiroi.sql.ExecutableDmlOutputQuery
import dev.quwac.kagiroi.sql.ExecutableInsertQuery
import dev.quwac.kagiroi.sql.ExecutableUpdateQuery
import dev.quwac.kagiroi.sql.InsertQueryState
import dev.quwac.kagiroi.sql.SqlDmlOutput
import dev.quwac.kagiroi.sql.SqlDmlOutputColumn
import dev.quwac.kagiroi.sql.SqlDmlOutputImage
import dev.quwac.kagiroi.sql.SqlDmlOutputReference
import dev.quwac.kagiroi.sql.UpdateQueryState
import dev.quwac.kagiroi.sql.dmlOutput
import dev.quwac.kagiroi.sql.nonEmptyListOf

/** A typed SQL Server `INSERTED` or `DELETED` reference in an OUTPUT projection. */
class SqlServerOutputColumn<T> internal constructor(override val dmlOutputColumn: SqlDmlOutputColumn<T>) :
    SqlDmlOutputReference<T>

/** Selects the post-write row image represented by SQL Server's `INSERTED` pseudo-table. */
fun <T> inserted(column: Column<T>): SqlServerOutputColumn<T> =
    SqlServerOutputColumn(SqlDmlOutputColumn(SqlDmlOutputImage.AFTER, column))

/** Selects the pre-write row image represented by SQL Server's `DELETED` pseudo-table. */
fun <T> deleted(column: Column<T>): SqlServerOutputColumn<T> =
    SqlServerOutputColumn(SqlDmlOutputColumn(SqlDmlOutputImage.BEFORE, column))

fun <S : InsertQueryState.Renderable> ExecutableInsertQuery<S>.output(
    column: SqlServerOutputColumn<*>,
    vararg additionalColumns: SqlServerOutputColumn<*>,
): ExecutableDmlOutputQuery = dmlOutput(
    outputProjection(column, additionalColumns),
    setOf(SqlDmlOutputImage.AFTER),
)

fun <S : UpdateQueryState.Renderable> ExecutableUpdateQuery<S>.output(
    column: SqlServerOutputColumn<*>,
    vararg additionalColumns: SqlServerOutputColumn<*>,
): ExecutableDmlOutputQuery = dmlOutput(
    outputProjection(column, additionalColumns),
    setOf(SqlDmlOutputImage.BEFORE, SqlDmlOutputImage.AFTER),
)

fun <S : DeleteQueryState.Renderable> ExecutableDeleteQuery<S>.output(
    column: SqlServerOutputColumn<*>,
    vararg additionalColumns: SqlServerOutputColumn<*>,
): ExecutableDmlOutputQuery = dmlOutput(
    outputProjection(column, additionalColumns),
    setOf(SqlDmlOutputImage.BEFORE),
)

private fun outputProjection(
    column: SqlServerOutputColumn<*>,
    additionalColumns: Array<out SqlServerOutputColumn<*>>,
): SqlDmlOutput = SqlDmlOutput(nonEmptyListOf(column, *additionalColumns))
