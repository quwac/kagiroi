@file:Suppress("TooManyFunctions") // Execution-bound SELECT builders mirror the immutable query DSL.

package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.GroupByElement
import dev.quwac.kagiroi.schema.RelationSource
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import kotlinx.coroutines.flow.Flow

/** An execution-bound typed query expression. */
interface ExecutableQueryExpression<T, P : SelectProjectionShape> {
    /** Database or transaction used to execute this query. */
    val execution: SqlExecution

    /** Immutable query AST associated with [execution]. */
    val expression: QueryExpression<T, P>
}

/** An immutable SELECT query with its execution target attached. */
class ExecutableSelectQuery<S : SelectQueryState, T, P : SelectProjectionShape> internal constructor(
    override val execution: SqlExecution,
    internal val query: SelectQuery<S, T, P>,
) : ExecutableQueryExpression<T, P>,
    WithCteClause<ExecutableSelectQuery<S, T, P>> {
    override val expression: QueryExpression<T, P> get() = query

    override fun withCtes(vararg definitions: CommonTableExpression): ExecutableSelectQuery<S, T, P> =
        ExecutableSelectQuery(execution, query.with(*definitions))

    /** Adds [table] as the base source of this SELECT. */
    fun from(table: Table): ExecutableSelectQuery<SelectQueryState.Start, T, P> =
        ExecutableSelectQuery(execution, query.from(table))

    /** Adds the one base source to a source-less execution-bound SELECT. */
    fun from(source: RelationSource): ExecutableSelectQuery<SelectQueryState.Start, T, P> =
        ExecutableSelectQuery(execution, query.from(source))

    /** Streams every result row as it is received. */
    fun stream(): Flow<SqlRow> = result().stream()

    /** Returns all result rows. */
    suspend fun all(): List<SqlRow> = result().all()

    /** Returns the sole result row, failing when the query returns zero or multiple rows. */
    suspend fun one(): SqlRow = result().one()

    /** Maps the sole result row, failing when the query returns zero or multiple rows. */
    suspend fun <T> one(mapper: (SqlRow) -> T): T = result().one(mapper)

    /** Returns the sole result row, or `null` when there are no rows; fails for multiple rows. */
    suspend fun oneOrNull(): SqlRow? = result().oneOrNull()

    /** Maps the sole result row, or returns `null` when there are no rows. */
    suspend fun <T> oneOrNull(mapper: (SqlRow) -> T): T? = result().oneOrNull(mapper)

    private fun result(): SqlQuery<SqlRow> = execution.query(query)
}

/** An immutable set query with its execution target attached. */
class ExecutableSetOperationQuery<T, P : SelectProjectionShape> internal constructor(
    override val execution: SqlExecution,
    internal val query: SetOperationQuery<T, P>,
) : ExecutableQueryExpression<T, P>,
    WithCteClause<ExecutableSetOperationQuery<T, P>> {
    override val expression: QueryExpression<T, P> get() = query

    override fun withCtes(vararg definitions: CommonTableExpression): ExecutableSetOperationQuery<T, P> =
        ExecutableSetOperationQuery(execution, query.with(*definitions))

    /** Streams every result row as it is received. */
    fun stream(): Flow<SqlRow> = result().stream()

    /** Returns all result rows. */
    suspend fun all(): List<SqlRow> = result().all()

    /** Returns the sole result row, failing when the query returns zero or multiple rows. */
    suspend fun one(): SqlRow = result().one()

    /** Maps the sole result row, failing when the query returns zero or multiple rows. */
    suspend fun <R> one(mapper: (SqlRow) -> R): R = result().one(mapper)

    /** Returns the sole result row, or `null` when there are no rows; fails for multiple rows. */
    suspend fun oneOrNull(): SqlRow? = result().oneOrNull()

    /** Maps the sole result row, or returns `null` when there are no rows. */
    suspend fun <R> oneOrNull(mapper: (SqlRow) -> R): R? = result().oneOrNull(mapper)

    private fun result(): SqlQuery<SqlRow> = execution.query(query)
}

/** Combines execution-bound query expressions that belong to the same database or transaction. */
fun <T, P : SelectProjectionShape> ExecutableQueryExpression<T, P>.union(
    other: ExecutableQueryExpression<T, P>,
): ExecutableSetOperationQuery<T, P> = executableSetOperation(other) { left, right -> left.union(right) }

/** Combines execution-bound query expressions while retaining duplicate rows. */
fun <T, P : SelectProjectionShape> ExecutableQueryExpression<T, P>.unionAll(
    other: ExecutableQueryExpression<T, P>,
): ExecutableSetOperationQuery<T, P> = executableSetOperation(other) { left, right -> left.unionAll(right) }

/** Intersects execution-bound query expressions. */
fun <T, P : SelectProjectionShape> ExecutableQueryExpression<T, P>.intersect(
    other: ExecutableQueryExpression<T, P>,
): ExecutableSetOperationQuery<T, P> = executableSetOperation(other) { left, right -> left.intersect(right) }

/** Intersects execution-bound query expressions while retaining duplicate rows. */
fun <T, P : SelectProjectionShape> ExecutableQueryExpression<T, P>.intersectAll(
    other: ExecutableQueryExpression<T, P>,
): ExecutableSetOperationQuery<T, P> = executableSetOperation(other) { left, right -> left.intersectAll(right) }

/** Subtracts the right execution-bound query expression from this one. */
fun <T, P : SelectProjectionShape> ExecutableQueryExpression<T, P>.except(
    other: ExecutableQueryExpression<T, P>,
): ExecutableSetOperationQuery<T, P> = executableSetOperation(other) { left, right -> left.except(right) }

/** Subtracts the right execution-bound query expression while retaining left duplicates. */
fun <T, P : SelectProjectionShape> ExecutableQueryExpression<T, P>.exceptAll(
    other: ExecutableQueryExpression<T, P>,
): ExecutableSetOperationQuery<T, P> = executableSetOperation(other) { left, right -> left.exceptAll(right) }

private fun <T, P : SelectProjectionShape> ExecutableQueryExpression<T, P>.executableSetOperation(
    other: ExecutableQueryExpression<T, P>,
    compose: (QueryExpression<T, P>, QueryExpression<T, P>) -> SetOperationQuery<T, P>,
): ExecutableSetOperationQuery<T, P> {
    require(execution === other.execution) { "Set operation queries must use the same execution target" }
    return ExecutableSetOperationQuery(execution, compose(expression, other.expression))
}

/** Adds an outer ORDER BY to the complete execution-bound set result. */
fun <T, P : SelectProjectionShape> ExecutableSetOperationQuery<T, P>.orderBy(
    order: Order<*>,
    vararg additionalOrders: Order<*>,
): ExecutableSetOperationQuery<T, P> = ExecutableSetOperationQuery(execution, query.orderBy(order, *additionalOrders))

/** Limits the complete execution-bound set result. */
fun <T, P : SelectProjectionShape> ExecutableSetOperationQuery<T, P>.limit(
    limit: Int,
): ExecutableSetOperationQuery<T, P> = ExecutableSetOperationQuery(execution, query.limit(limit))

/** Offsets the complete execution-bound set result. */
fun <T, P : SelectProjectionShape> ExecutableSetOperationQuery<T, P>.offset(
    offset: Int,
): ExecutableSetOperationQuery<T, P> = ExecutableSetOperationQuery(execution, query.offset(offset))

/** Creates a CTE body from an execution-bound query without capturing its execution target. */
fun <T, P : SelectProjectionShape> ExecutableQueryExpression<T, P>.asCte(name: String): CommonTableExpression =
    expression.asCte(name)

/** Starts an execution-bound SELECT with one typed projection. */
fun <T> SqlExecution.select(
    expression: SqlExpression<T>,
): ExecutableSelectQuery<SelectQueryState.SourceLessStart, T, SingleProjection> =
    ExecutableSelectQuery(this, dev.quwac.kagiroi.sql.select(expression))

/** Starts an execution-bound SELECT with multiple typed projections. */
fun <T> SqlExecution.select(
    expression: SqlExpression<T>,
    additionalExpression: SqlExpression<*>,
    vararg additionalExpressions: SqlExpression<*>,
): ExecutableSelectQuery<SelectQueryState.SourceLessStart, T, MultipleProjections> = ExecutableSelectQuery(
    this,
    dev.quwac.kagiroi.sql.select(expression, additionalExpression, *additionalExpressions),
)

/** Adds SELECT DISTINCT while retaining the execution target and existing clause typestate. */
fun <S : SelectQueryState, T, P : SelectProjectionShape> ExecutableSelectQuery<S, T, P>.distinct():
    ExecutableSelectQuery<S, T, P> =
    ExecutableSelectQuery(execution, query.distinct())

/** Retains dialect-specific DISTINCT ON expressions while keeping this execution target. */
fun <S : SelectQueryState, T, P : SelectProjectionShape> ExecutableSelectQuery<S, T, P>.withDistinctOn(
    expression: SqlExpression<*>,
    vararg additionalExpressions: SqlExpression<*>,
): ExecutableSelectQuery<S, T, P> =
    ExecutableSelectQuery(execution, query.withDistinctOn(expression, *additionalExpressions))

/** Registers query-scoped named window definitions while retaining this execution target. */
fun <S : SelectQueryState, T, P : SelectProjectionShape> ExecutableSelectQuery<S, T, P>.window(
    definition: NamedWindow,
    vararg additionalDefinitions: NamedWindow,
): ExecutableSelectQuery<S, T, P> = ExecutableSelectQuery(execution, query.window(definition, *additionalDefinitions))

/** Filters rows by [predicate] while retaining the execution target. */
fun <S : SelectQueryState.WhereAllowed, T, P : SelectProjectionShape> ExecutableSelectQuery<S, T, P>.where(
    predicate: SqlPredicate,
): ExecutableSelectQuery<SelectQueryState.AfterWhere, T, P> = ExecutableSelectQuery(execution, query.where(predicate))

/** Groups rows by the specified typed grouping elements. */
fun <S : SelectQueryState.GroupByAllowed, T, P : SelectProjectionShape> ExecutableSelectQuery<S, T, P>.groupBy(
    element: GroupByElement,
    vararg additionalElements: GroupByElement,
): ExecutableSelectQuery<SelectQueryState.AfterGroupBy, T, P> =
    ExecutableSelectQuery(execution, query.groupBy(element, *additionalElements))

/** Filters grouped rows by [predicate]. */
fun <S : SelectQueryState.HavingAllowed, T, P : SelectProjectionShape> ExecutableSelectQuery<S, T, P>.having(
    predicate: SqlPredicate,
): ExecutableSelectQuery<SelectQueryState.AfterHaving, T, P> = ExecutableSelectQuery(execution, query.having(predicate))

/** Adds an INNER JOIN using [on] as its ON condition. */
fun <S, T, P : SelectProjectionShape> ExecutableSelectQuery<S, T, P>.innerJoin(
    source: RelationSource,
    on: SqlPredicate,
): ExecutableSelectQuery<S, T, P> where S : SelectQueryState, S : SelectQueryState.JoinAllowed =
    ExecutableSelectQuery(execution, query.innerJoin(source, on))

/** Adds an execution-bound INNER JOIN USING while preserving the execution target. */
fun <S, T, P : SelectProjectionShape> ExecutableSelectQuery<S, T, P>.innerJoinUsing(
    source: RelationSource,
    using: JoinUsingBuilder.() -> Unit,
): ExecutableSelectQuery<S, T, P> where S : SelectQueryState, S : SelectQueryState.JoinAllowed =
    ExecutableSelectQuery(execution, query.innerJoinUsing(source, using))

/** Adds a LEFT JOIN using [on] as its ON condition. */
fun <S, T, P : SelectProjectionShape> ExecutableSelectQuery<S, T, P>.leftJoin(
    source: RelationSource,
    on: SqlPredicate,
): ExecutableSelectQuery<S, T, P> where S : SelectQueryState, S : SelectQueryState.JoinAllowed =
    ExecutableSelectQuery(execution, query.leftJoin(source, on))

/** Adds an execution-bound LEFT JOIN USING while preserving the execution target. */
fun <S, T, P : SelectProjectionShape> ExecutableSelectQuery<S, T, P>.leftJoinUsing(
    source: RelationSource,
    using: JoinUsingBuilder.() -> Unit,
): ExecutableSelectQuery<S, T, P> where S : SelectQueryState, S : SelectQueryState.JoinAllowed =
    ExecutableSelectQuery(execution, query.leftJoinUsing(source, using))

/** Adds a RIGHT JOIN using [on] as its ON condition. */
fun <S, T, P : SelectProjectionShape> ExecutableSelectQuery<S, T, P>.rightJoin(
    source: RelationSource,
    on: SqlPredicate,
): ExecutableSelectQuery<S, T, P> where S : SelectQueryState, S : SelectQueryState.JoinAllowed =
    ExecutableSelectQuery(execution, query.rightJoin(source, on))

/** Adds an execution-bound RIGHT JOIN USING while preserving the execution target. */
fun <S, T, P : SelectProjectionShape> ExecutableSelectQuery<S, T, P>.rightJoinUsing(
    source: RelationSource,
    using: JoinUsingBuilder.() -> Unit,
): ExecutableSelectQuery<S, T, P> where S : SelectQueryState, S : SelectQueryState.JoinAllowed =
    ExecutableSelectQuery(execution, query.rightJoinUsing(source, using))

/** Adds a FULL OUTER JOIN using [on] as its ON condition. */
fun <S, T, P : SelectProjectionShape> ExecutableSelectQuery<S, T, P>.fullOuterJoin(
    source: RelationSource,
    on: SqlPredicate,
): ExecutableSelectQuery<S, T, P> where S : SelectQueryState, S : SelectQueryState.JoinAllowed =
    ExecutableSelectQuery(execution, query.fullOuterJoin(source, on))

/** Adds an execution-bound FULL OUTER JOIN USING while preserving the execution target. */
fun <S, T, P : SelectProjectionShape> ExecutableSelectQuery<S, T, P>.fullOuterJoinUsing(
    source: RelationSource,
    using: JoinUsingBuilder.() -> Unit,
): ExecutableSelectQuery<S, T, P> where S : SelectQueryState, S : SelectQueryState.JoinAllowed =
    ExecutableSelectQuery(execution, query.fullOuterJoinUsing(source, using))

/** Adds a native CROSS JOIN without an ON condition. */
fun <S, T, P : SelectProjectionShape> ExecutableSelectQuery<S, T, P>.crossJoin(
    source: RelationSource,
): ExecutableSelectQuery<S, T, P> where S : SelectQueryState, S : SelectQueryState.JoinAllowed =
    ExecutableSelectQuery(execution, query.crossJoin(source))

/** Adds an execution-bound inner-like correlated derived relation. */
fun <S, T, P : SelectProjectionShape> ExecutableSelectQuery<S, T, P>.crossJoinLateral(
    relation: LateralRelation,
): ExecutableSelectQuery<S, T, P> where S : SelectQueryState, S : SelectQueryState.JoinAllowed =
    ExecutableSelectQuery(execution, query.crossJoinLateral(relation))

/** Adds an execution-bound left-like correlated derived relation. */
fun <S, T, P : SelectProjectionShape> ExecutableSelectQuery<S, T, P>.leftJoinLateral(
    relation: LateralRelation,
): ExecutableSelectQuery<S, T, P> where S : SelectQueryState, S : SelectQueryState.JoinAllowed =
    ExecutableSelectQuery(execution, query.leftJoinLateral(relation))

/** Orders the result rows using the specified expressions. */
fun <S : SelectQueryState.OrderByAllowed, T, P : SelectProjectionShape> ExecutableSelectQuery<S, T, P>.orderBy(
    order: Order<*>,
    vararg additionalOrders: Order<*>,
): ExecutableSelectQuery<SelectQueryState.AfterOrderBy, T, P> =
    ExecutableSelectQuery(execution, query.orderBy(order, *additionalOrders))

/** Limits the number of result rows. */
fun <S : SelectQueryState.LimitAllowed, T, P : SelectProjectionShape> ExecutableSelectQuery<S, T, P>.limit(
    limit: Int,
): ExecutableSelectQuery<SelectQueryState.AfterLimit, T, P> = ExecutableSelectQuery(execution, query.limit(limit))

/** Skips the specified number of result rows. */
fun <S : SelectQueryState.OffsetAllowed, T, P : SelectProjectionShape> ExecutableSelectQuery<S, T, P>.offset(
    offset: Int,
): ExecutableSelectQuery<SelectQueryState.AfterOffset, T, P> = ExecutableSelectQuery(execution, query.offset(offset))
