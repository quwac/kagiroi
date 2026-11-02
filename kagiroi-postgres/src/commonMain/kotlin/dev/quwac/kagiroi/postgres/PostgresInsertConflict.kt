package dev.quwac.kagiroi.postgres

import dev.quwac.kagiroi.schema.WritableColumn
import dev.quwac.kagiroi.sql.ExecutableInsertQuery
import dev.quwac.kagiroi.sql.InsertQuery
import dev.quwac.kagiroi.sql.InsertQueryState
import dev.quwac.kagiroi.sql.SqlPredicate
import dev.quwac.kagiroi.sql.TargetedInsertConflictBuilder
import dev.quwac.kagiroi.sql.withTargetedConflict

/** Adds PostgreSQL `ON CONFLICT (columns) [WHERE predicate]` to a completed typed INSERT. */
fun <S> InsertQuery<S>.onConflict(
    column: WritableColumn<*>,
    vararg additionalColumns: WritableColumn<*>,
    where: SqlPredicate? = null,
    block: TargetedInsertConflictBuilder.() -> Unit,
): InsertQuery<S>
    where S : InsertQueryState.Renderable = withTargetedConflict(
    targetColumns = listOf(column, *additionalColumns),
    targetPredicate = where,
    updateRequiresTarget = true,
    block = block,
)

/** Adds PostgreSQL's target-less `ON CONFLICT` form. Only `doNothing()` is valid in this form. */
fun <S> InsertQuery<S>.onConflict(
    block: TargetedInsertConflictBuilder.() -> Unit,
): InsertQuery<S>
    where S : InsertQueryState.Renderable =
    withTargetedConflict(updateRequiresTarget = true, block = block)

/** Adds PostgreSQL `ON CONFLICT ON CONSTRAINT name` without a catalog lookup. */
fun <S> InsertQuery<S>.onConflictConstraint(
    name: String,
    block: TargetedInsertConflictBuilder.() -> Unit,
): InsertQuery<S>
    where S : InsertQueryState.Renderable =
    withTargetedConflict(constraintName = name, updateRequiresTarget = true, block = block)

/** Execution-bound PostgreSQL `ON CONFLICT (columns)` extension. */
fun <S> ExecutableInsertQuery<S>.onConflict(
    column: WritableColumn<*>,
    vararg additionalColumns: WritableColumn<*>,
    where: SqlPredicate? = null,
    block: TargetedInsertConflictBuilder.() -> Unit,
): ExecutableInsertQuery<S>
    where S : InsertQueryState.Renderable = withTargetedConflict(
    targetColumns = listOf(column, *additionalColumns),
    targetPredicate = where,
    updateRequiresTarget = true,
    block = block,
)

/** Execution-bound target-less PostgreSQL `ON CONFLICT` extension. */
fun <S> ExecutableInsertQuery<S>.onConflict(
    block: TargetedInsertConflictBuilder.() -> Unit,
): ExecutableInsertQuery<S>
    where S : InsertQueryState.Renderable =
    withTargetedConflict(updateRequiresTarget = true, block = block)

/** Execution-bound PostgreSQL `ON CONFLICT ON CONSTRAINT name` extension. */
fun <S> ExecutableInsertQuery<S>.onConflictConstraint(
    name: String,
    block: TargetedInsertConflictBuilder.() -> Unit,
): ExecutableInsertQuery<S>
    where S : InsertQueryState.Renderable =
    withTargetedConflict(constraintName = name, updateRequiresTarget = true, block = block)
