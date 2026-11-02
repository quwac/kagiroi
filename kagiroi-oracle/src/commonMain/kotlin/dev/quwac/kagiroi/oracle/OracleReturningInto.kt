package dev.quwac.kagiroi.oracle

import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.sql.DeleteQueryState
import dev.quwac.kagiroi.sql.ExecutableDeleteQuery
import dev.quwac.kagiroi.sql.ExecutableDmlReturningIntoQuery
import dev.quwac.kagiroi.sql.ExecutableInsertQuery
import dev.quwac.kagiroi.sql.ExecutableUpdateQuery
import dev.quwac.kagiroi.sql.InsertQueryState
import dev.quwac.kagiroi.sql.SqlDmlReturningInto
import dev.quwac.kagiroi.sql.UpdateQueryState
import dev.quwac.kagiroi.sql.dmlReturningInto
import dev.quwac.kagiroi.sql.nonEmptyListOf

/** Adds Oracle's DML RETURNING INTO out-bind result to a typed INSERT statement. */
fun <S : InsertQueryState.Renderable> ExecutableInsertQuery<S>.returningInto(
    column: Column<*>,
    vararg additionalColumns: Column<*>,
): ExecutableDmlReturningIntoQuery = dmlReturningInto(returningIntoProjection(column, additionalColumns))

/** Adds Oracle's DML RETURNING INTO out-bind result to a typed UPDATE statement. */
fun <S : UpdateQueryState.Renderable> ExecutableUpdateQuery<S>.returningInto(
    column: Column<*>,
    vararg additionalColumns: Column<*>,
): ExecutableDmlReturningIntoQuery = dmlReturningInto(returningIntoProjection(column, additionalColumns))

/** Adds Oracle's DML RETURNING INTO out-bind result to a typed DELETE statement. */
fun <S : DeleteQueryState.Renderable> ExecutableDeleteQuery<S>.returningInto(
    column: Column<*>,
    vararg additionalColumns: Column<*>,
): ExecutableDmlReturningIntoQuery = dmlReturningInto(returningIntoProjection(column, additionalColumns))

private fun returningIntoProjection(column: Column<*>, additionalColumns: Array<out Column<*>>): SqlDmlReturningInto =
    SqlDmlReturningInto(nonEmptyListOf(column, *additionalColumns))
