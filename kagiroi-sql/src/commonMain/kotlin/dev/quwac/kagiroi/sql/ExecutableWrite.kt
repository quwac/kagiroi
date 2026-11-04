// Typed executable write overloads intentionally mirror the SQL-only write DSL.
@file:Suppress("TooManyFunctions")

package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.RelationSource
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.WritableColumn

/** An immutable INSERT query with its execution target attached. */
class ExecutableInsertQuery<S : InsertQueryState> internal constructor(
    internal val execution: SqlExecution,
    internal val query: InsertQuery<S>,
) : WithCteClause<ExecutableInsertQuery<S>> {
    override fun withCtes(vararg definitions: CommonTableExpression): ExecutableInsertQuery<S> =
        ExecutableInsertQuery(execution, query.with(*definitions))
}

/** An immutable UPDATE query with its execution target attached. */
class ExecutableUpdateQuery<S : UpdateQueryState> internal constructor(
    internal val execution: SqlExecution,
    internal val query: UpdateQuery<S>,
) : WithCteClause<ExecutableUpdateQuery<S>> {
    override fun withCtes(vararg definitions: CommonTableExpression): ExecutableUpdateQuery<S> =
        ExecutableUpdateQuery(execution, query.with(*definitions))
}

/** An immutable DELETE query with its execution target attached. */
class ExecutableDeleteQuery<S : DeleteQueryState> internal constructor(
    internal val execution: SqlExecution,
    internal val query: DeleteQuery<S>,
) : WithCteClause<ExecutableDeleteQuery<S>> {
    override fun withCtes(vararg definitions: CommonTableExpression): ExecutableDeleteQuery<S> =
        ExecutableDeleteQuery(execution, query.with(*definitions))
}

fun SqlExecution.insertInto(table: Table): ExecutableInsertQuery<InsertQueryState.Start> =
    ExecutableInsertQuery(this, dev.quwac.kagiroi.sql.insertInto(table))

/** Starts an execution-bound typed INSERT ... SELECT with explicit writable target columns. */
fun SqlExecution.insertInto(
    table: Table,
    column: WritableColumn<*>,
    vararg additionalColumns: WritableColumn<*>,
): ExecutableInsertQuery<InsertQueryState.Start> =
    ExecutableInsertQuery(this, dev.quwac.kagiroi.sql.insertInto(table, column, *additionalColumns))

fun <S : InsertQueryState.ValuesAllowed> ExecutableInsertQuery<S>.values(
    block: InsertValuesBuilder.() -> Unit,
): ExecutableInsertQuery<InsertQueryState.WithValues> = ExecutableInsertQuery(execution, query.values(block))

/** Adds one executable INSERT row for every value in [values]. */
fun <S : InsertQueryState.ValuesAllowed, T> ExecutableInsertQuery<S>.values(
    values: NonEmptyList<T>,
    block: InsertValuesBuilder.(T) -> Unit,
): ExecutableInsertQuery<InsertQueryState.WithValues> = ExecutableInsertQuery(execution, query.values(values, block))

/** Uses a typed SQL-only query as this INSERT's source. */
fun <S> ExecutableInsertQuery<S>.from(
    source: QueryExpression<*, *>,
): ExecutableInsertQuery<InsertQueryState.WithQuery>
    where S : InsertQueryState,
          S : InsertQueryState.QueryAllowed =
    ExecutableInsertQuery(execution, query.from(source))

/** Uses a query from the same execution scope as this INSERT's source. */
fun <S, T, P : SelectProjectionShape> ExecutableInsertQuery<S>.from(
    source: ExecutableQueryExpression<T, P>,
): ExecutableInsertQuery<InsertQueryState.WithQuery>
    where S : InsertQueryState,
          S : InsertQueryState.QueryAllowed {
    require(execution === source.execution) { "INSERT query source must use the same execution target" }
    return ExecutableInsertQuery(execution, query.from(source.expression))
}

/** Adds a dialect-specific targeted conflict behavior while retaining this execution target. */
fun <S> ExecutableInsertQuery<S>.withTargetedConflict(
    targetColumns: List<WritableColumn<*>>? = null,
    constraintName: String? = null,
    targetPredicate: SqlPredicate? = null,
    updateRequiresTarget: Boolean,
    block: TargetedInsertConflictBuilder.() -> Unit,
): ExecutableInsertQuery<S>
    where S : InsertQueryState.Renderable = ExecutableInsertQuery(
    execution,
    query.withTargetedConflict(
        targetColumns,
        constraintName,
        targetPredicate,
        updateRequiresTarget,
        block,
    ),
)

/** Appends an execution-bound SQLite conflict clause. */
fun <S> ExecutableInsertQuery<S>.withSqliteConflict(
    targetColumns: List<WritableColumn<*>>? = null,
    targetPredicate: SqlPredicate? = null,
    block: TargetedInsertConflictBuilder.() -> Unit,
): ExecutableInsertQuery<S> where S : InsertQueryState.Renderable =
    ExecutableInsertQuery(execution, query.withSqliteConflict(targetColumns, targetPredicate, block))

/** Adds a dialect-specific duplicate-key update behavior while retaining this execution target. */
fun <S> ExecutableInsertQuery<S>.withDuplicateKeyUpdate(
    block: InsertedConflictUpdateBuilder.() -> Unit,
): ExecutableInsertQuery<S>
    where S : InsertQueryState.Renderable =
    ExecutableInsertQuery(execution, query.withDuplicateKeyUpdate(block))

suspend fun <S : InsertQueryState.Renderable> ExecutableInsertQuery<S>.execute(): ExecuteResult =
    execution.execute(query)

fun SqlExecution.update(
    table: Table,
    block: UpdateSetBuilder.() -> Unit,
): ExecutableUpdateQuery<UpdateQueryState.AfterSet> = ExecutableUpdateQuery(
    this,
    dev.quwac.kagiroi.sql.update(table, block),
)

fun <S : UpdateQueryState.WhereAllowed> ExecutableUpdateQuery<S>.where(
    predicate: SqlPredicate,
): ExecutableUpdateQuery<UpdateQueryState.AfterWhere> = ExecutableUpdateQuery(execution, query.where(predicate))

/** Adds typed UPDATE ... FROM sources while retaining this execution target. */
fun <S> ExecutableUpdateQuery<S>.from(
    source: RelationSource,
    vararg additionalSources: RelationSource,
): ExecutableUpdateQuery<UpdateQueryState.AfterFrom>
    where S : UpdateQueryState,
          S : UpdateQueryState.FromAllowed =
    ExecutableUpdateQuery(execution, query.from(source, *additionalSources))

suspend fun <S : UpdateQueryState.Renderable> ExecutableUpdateQuery<S>.execute(): ExecuteResult =
    execution.execute(query)

fun SqlExecution.deleteFrom(table: Table): ExecutableDeleteQuery<DeleteQueryState.Start> =
    ExecutableDeleteQuery(this, dev.quwac.kagiroi.sql.deleteFrom(table))

fun SqlExecution.deleteAllFrom(table: Table): ExecutableDeleteQuery<DeleteQueryState.AllRows> =
    ExecutableDeleteQuery(this, dev.quwac.kagiroi.sql.deleteAllFrom(table))

fun <S : DeleteQueryState.WhereAllowed> ExecutableDeleteQuery<S>.where(
    predicate: SqlPredicate,
): ExecutableDeleteQuery<DeleteQueryState.AfterWhere> = ExecutableDeleteQuery(execution, query.where(predicate))

suspend fun <S : DeleteQueryState.Renderable> ExecutableDeleteQuery<S>.execute(): ExecuteResult =
    execution.execute(query)

/** Marks an execution-bound INSERT as a dialect-specific native REPLACE statement. */
fun <S : InsertQueryState> ExecutableInsertQuery<S>.asNativeReplace(): ExecutableInsertQuery<S> =
    ExecutableInsertQuery(execution, query.asNativeReplace())
