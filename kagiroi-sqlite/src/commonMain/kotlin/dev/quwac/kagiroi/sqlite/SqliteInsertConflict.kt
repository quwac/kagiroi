package dev.quwac.kagiroi.sqlite

import dev.quwac.kagiroi.schema.WritableColumn
import dev.quwac.kagiroi.sql.ExecutableInsertQuery
import dev.quwac.kagiroi.sql.InsertQuery
import dev.quwac.kagiroi.sql.InsertQueryState
import dev.quwac.kagiroi.sql.SqlPredicate
import dev.quwac.kagiroi.sql.TargetedInsertConflictBuilder
import dev.quwac.kagiroi.sql.withTargetedConflict

/** Adds one SQLite `ON CONFLICT (columns) [WHERE predicate]` clause to a completed typed INSERT. */
fun <S> InsertQuery<S>.onConflict(
    column: WritableColumn<*>,
    vararg additionalColumns: WritableColumn<*>,
    where: SqlPredicate? = null,
    block: TargetedInsertConflictBuilder.() -> Unit,
): InsertQuery<S>
    where S : InsertQueryState.Renderable = withTargetedConflict(
    targetColumns = listOf(column, *additionalColumns),
    targetPredicate = where,
    updateRequiresTarget = false,
    block = block,
)

/** Adds SQLite's single final target-less `ON CONFLICT` clause to a completed typed INSERT. */
fun <S> InsertQuery<S>.onConflict(
    block: TargetedInsertConflictBuilder.() -> Unit,
): InsertQuery<S>
    where S : InsertQueryState.Renderable =
    withTargetedConflict(updateRequiresTarget = false, block = block)

/** Execution-bound SQLite `ON CONFLICT (columns)` extension. */
fun <S> ExecutableInsertQuery<S>.onConflict(
    column: WritableColumn<*>,
    vararg additionalColumns: WritableColumn<*>,
    where: SqlPredicate? = null,
    block: TargetedInsertConflictBuilder.() -> Unit,
): ExecutableInsertQuery<S>
    where S : InsertQueryState.Renderable = withTargetedConflict(
    targetColumns = listOf(column, *additionalColumns),
    targetPredicate = where,
    updateRequiresTarget = false,
    block = block,
)

/** Execution-bound SQLite target-less final `ON CONFLICT` extension. */
fun <S> ExecutableInsertQuery<S>.onConflict(
    block: TargetedInsertConflictBuilder.() -> Unit,
): ExecutableInsertQuery<S>
    where S : InsertQueryState.Renderable =
    withTargetedConflict(updateRequiresTarget = false, block = block)
