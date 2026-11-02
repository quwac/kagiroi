// Typed SQL-only write builders intentionally expose their clause-specific overloads.
@file:Suppress("TooManyFunctions")

package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.schema.RelationSource
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.TableRelationSource
import dev.quwac.kagiroi.schema.WritableColumn

/** One typed assignment to a writable column. */
sealed interface WriteAssignment {
    val column: WritableColumn<*>
}

/** A typed value assigned to one column by an INSERT or UPDATE query. */
class ColumnAssignment<T> internal constructor(override val column: WritableColumn<T>, val value: T) :
    WriteAssignment {
    internal fun storageValue(): Any? = column.toStorageValue(value)
}

/** A typed SQL expression assigned to one writable UPDATE-style target column. */
class ExpressionAssignment<T> internal constructor(
    override val column: WritableColumn<T>,
    val expression: SqlExpression<T>,
) : WriteAssignment

/** One row of explicitly assigned INSERT values. Unassigned columns are absent from [assignments]. */
class InsertRow internal constructor(val assignments: List<ColumnAssignment<*>>)

/** The mutually exclusive source of an INSERT statement. */
internal sealed interface InsertSource {
    /** One or more explicit VALUES rows. */
    class Values(val rows: List<InsertRow>) : InsertSource

    /** A typed query whose projections are inserted positionally into [targetColumns]. */
    class Query(val targetColumns: List<WritableColumn<*>>, val query: QueryExpression<*, *>) : InsertSource
}

/** Compile-time state for an INSERT query. */
sealed interface InsertQueryState {
    sealed interface ValuesAllowed : InsertQueryState

    sealed interface QueryAllowed : InsertQueryState

    sealed interface Renderable : InsertQueryState

    sealed interface Start :
        ValuesAllowed,
        QueryAllowed

    sealed interface WithValues :
        ValuesAllowed,
        Renderable

    sealed interface WithQuery : Renderable
}

/**
 * An immutable INSERT query model.
 *
 * [source] is deliberately a sum type: one statement has either VALUES rows or one query source,
 * never both. Query source target columns are retained with the AST rather than interpolating a
 * rendered SELECT string into an INSERT.
 */
class InsertQuery<S : InsertQueryState> internal constructor(
    val into: Table,
    internal val source: InsertSource? = null,
    internal val withClause: List<CommonTableExpression> = emptyList(),
    /** Explicit target columns reserved for a subsequent INSERT ... SELECT source. */
    internal val pendingTargetColumns: List<WritableColumn<*>> = emptyList(),
    /** Dialect-specific insert-conflict behavior attached by a dialect extension. */
    internal val conflictBehavior: InsertConflictBehavior? = null,
) : WithCteClause<InsertQuery<S>> {
    /** Compatibility view of the explicit VALUES rows; empty for INSERT ... SELECT. */
    val rows: List<InsertRow> get() = (source as? InsertSource.Values)?.rows.orEmpty()

    override fun withCtes(vararg definitions: CommonTableExpression): InsertQuery<S> = with(*definitions)
}

/** Collects one row of typed INSERT assignments. */
class InsertValuesBuilder internal constructor(private val table: Table) {
    private val assignments = mutableListOf<ColumnAssignment<*>>()

    fun <T> set(column: WritableColumn<T>, value: T) {
        table.requireRegisteredColumn(column, "INSERT")
        require(assignments.none { it.column === column }) { "INSERT column is assigned more than once: $column" }
        assignments += ColumnAssignment(column, value)
    }

    internal fun build(): InsertRow {
        require(assignments.isNotEmpty()) { "INSERT values must assign at least one column" }
        return InsertRow(assignments.toList())
    }
}

fun insertInto(table: Table): InsertQuery<InsertQueryState.Start> = InsertQuery(table)

/** Starts a typed INSERT ... SELECT with its explicit writable target columns. */
fun insertInto(
    table: Table,
    column: WritableColumn<*>,
    vararg additionalColumns: WritableColumn<*>,
): InsertQuery<InsertQueryState.Start> {
    val targetColumns = listOf(column, *additionalColumns)
    targetColumns.forEach { target -> table.requireRegisteredColumn(target, "INSERT") }
    require(targetColumns.distinct().size == targetColumns.size) {
        "INSERT target column appears more than once"
    }
    return InsertQuery(table, pendingTargetColumns = targetColumns)
}

fun <S : InsertQueryState.ValuesAllowed> InsertQuery<S>.values(
    block: InsertValuesBuilder.() -> Unit,
): InsertQuery<InsertQueryState.WithValues> {
    require(pendingTargetColumns.isEmpty()) {
        "INSERT target columns declared for INSERT ... SELECT cannot be used with VALUES"
    }
    val row = InsertValuesBuilder(into).apply(block).build()
    val priorRows = (source as? InsertSource.Values)?.rows.orEmpty()
    val normalizedRow = if (priorRows.isEmpty()) row else row.withColumnOrder(priorRows.first())
    return InsertQuery(
        into,
        InsertSource.Values(priorRows + normalizedRow),
        withClause,
        conflictBehavior = conflictBehavior,
    )
}

/** Adds one INSERT row for every value in [values], using the same typed assignment block. */
fun <S : InsertQueryState.ValuesAllowed, T> InsertQuery<S>.values(
    values: NonEmptyList<T>,
    block: InsertValuesBuilder.(T) -> Unit,
): InsertQuery<InsertQueryState.WithValues> {
    val iterator = values.iterator()

    var query = this.values {
        block(iterator.next())
    }
    while (iterator.hasNext()) {
        query = query.values {
            block(iterator.next())
        }
    }
    return query
}

/** Attaches a typed query source to an INSERT with explicit target columns. */
fun <S> InsertQuery<S>.from(
    query: QueryExpression<*, *>,
): InsertQuery<InsertQueryState.WithQuery>
    where S : InsertQueryState,
          S : InsertQueryState.QueryAllowed {
    require(source == null) { "INSERT already has a source" }
    require(pendingTargetColumns.isNotEmpty()) { "INSERT ... SELECT requires explicit target columns" }
    requireInsertProjectionCompatibility(pendingTargetColumns, query.columns)
    return InsertQuery(
        into,
        InsertSource.Query(pendingTargetColumns, query),
        withClause,
        conflictBehavior = conflictBehavior,
    )
}

/** Attaches a lexical WITH scope to this INSERT without changing its result type or source. */
fun <S : InsertQueryState> InsertQuery<S>.with(vararg definitions: CommonTableExpression): InsertQuery<S> {
    require(withClause.isEmpty()) { "INSERT already has a WITH clause" }
    val ctes = definitions.toList()
    validateCteDefinitions(ctes)
    return InsertQuery(into, source, ctes, pendingTargetColumns, conflictBehavior)
}

/** Compile-time state for an UPDATE query. */
sealed interface UpdateQueryState {
    sealed interface WhereAllowed : UpdateQueryState

    sealed interface FromAllowed : UpdateQueryState

    sealed interface Renderable : UpdateQueryState

    sealed interface AfterSet :
        WhereAllowed,
        FromAllowed,
        Renderable

    sealed interface AfterFrom :
        WhereAllowed,
        Renderable

    sealed interface AfterWhere : Renderable
}

/** An immutable UPDATE query model. */
class UpdateQuery<S : UpdateQueryState> internal constructor(
    val table: Table,
    val assignments: List<WriteAssignment>,
    val predicate: SqlPredicate? = null,
    internal val withClause: List<CommonTableExpression> = emptyList(),
    /** Direct FROM sources that are available to UPDATE assignments and predicates. */
    internal val fromSources: List<RelationSource> = emptyList(),
) : WithCteClause<UpdateQuery<S>> {
    override fun withCtes(vararg definitions: CommonTableExpression): UpdateQuery<S> = with(*definitions)
}

/** Collects the typed assignments of an UPDATE query. */
class UpdateSetBuilder internal constructor(private val table: Table) {
    private val assignments = mutableListOf<WriteAssignment>()

    fun <T> set(column: WritableColumn<T>, value: T) {
        table.requireRegisteredColumn(column, "UPDATE")
        require(assignments.none { it.column === column }) { "UPDATE column is assigned more than once: $column" }
        assignments += ColumnAssignment(column, value)
    }

    /** Assigns a same-mapped SQL expression without converting it through a storage value. */
    fun <T> set(column: WritableColumn<T>, expression: SqlExpression<T>) {
        table.requireRegisteredColumn(column, "UPDATE")
        require(assignments.none { it.column === column }) { "UPDATE column is assigned more than once: $column" }
        requireCompatibleWriteExpression(column, expression, "UPDATE")
        assignments += ExpressionAssignment(column, expression)
    }

    internal fun build(): List<WriteAssignment> {
        require(assignments.isNotEmpty()) { "UPDATE must assign at least one column" }
        return assignments.toList()
    }
}

fun update(table: Table, block: UpdateSetBuilder.() -> Unit): UpdateQuery<UpdateQueryState.AfterSet> =
    UpdateQuery(table, UpdateSetBuilder(table).apply(block).build())

/** Adds native UPDATE ... FROM sources after SET and before WHERE. */
fun <S> UpdateQuery<S>.from(
    source: RelationSource,
    vararg additionalSources: RelationSource,
): UpdateQuery<UpdateQueryState.AfterFrom>
    where S : UpdateQueryState,
          S : UpdateQueryState.FromAllowed {
    val additions = listOf(source) + additionalSources
    require(additions.none { it is LateralRelation }) {
        "UPDATE FROM does not accept lateral relations"
    }
    val allSources = fromSources + additions
    require(allSources.none { it === table }) {
        "UPDATE FROM cannot repeat the target table without an alias"
    }
    additions.forEach { candidate ->
        require(allSources.count { it === candidate } == 1) {
            "UPDATE FROM relation source appears more than once: $candidate"
        }
        requireNoAmbiguousUpdateFromSourceName(candidate, allSources)
    }
    return UpdateQuery(table, assignments, predicate, withClause, allSources)
}

fun <S : UpdateQueryState.WhereAllowed> UpdateQuery<S>.where(
    predicate: SqlPredicate,
): UpdateQuery<UpdateQueryState.AfterWhere> {
    require(!predicate.containsAggregate()) { "WHERE cannot contain an aggregate expression" }
    require(!predicate.containsWindowExpression()) { "WHERE cannot contain a window expression" }
    if (fromSources.isEmpty()) {
        // The enclosing `with(...)` builder attaches its CTE scope after this query is built.
        // Retain the established eager target-column validation without trying to resolve that
        // lexical scope yet; full nested-query validation occurs at rendering time.
        table.requireRegisteredColumns(predicate.referencedColumns(), "WHERE")
    } else {
        predicate.requireUpdateDirectPredicateScope(listOf<RelationSource>(table) + fromSources)
    }
    return UpdateQuery(table, assignments, predicate, withClause, fromSources)
}

/** Attaches a lexical WITH scope to this UPDATE without changing its typestate or result API. */
fun <S : UpdateQueryState> UpdateQuery<S>.with(vararg definitions: CommonTableExpression): UpdateQuery<S> {
    require(withClause.isEmpty()) { "UPDATE already has a WITH clause" }
    val ctes = definitions.toList()
    validateCteDefinitions(ctes)
    return UpdateQuery(table, assignments, predicate, ctes, fromSources)
}

/** Compile-time state for a DELETE query. */
sealed interface DeleteQueryState {
    sealed interface WhereAllowed : DeleteQueryState

    sealed interface Renderable : DeleteQueryState

    sealed interface Start : WhereAllowed

    sealed interface AfterWhere : Renderable

    sealed interface AllRows : Renderable
}

/** An immutable DELETE query model. */
class DeleteQuery<S : DeleteQueryState> internal constructor(
    val from: Table,
    val predicate: SqlPredicate? = null,
    internal val withClause: List<CommonTableExpression> = emptyList(),
) : WithCteClause<DeleteQuery<S>> {
    override fun withCtes(vararg definitions: CommonTableExpression): DeleteQuery<S> = with(*definitions)
}

fun deleteFrom(table: Table): DeleteQuery<DeleteQueryState.Start> = DeleteQuery(table)

fun deleteAllFrom(table: Table): DeleteQuery<DeleteQueryState.AllRows> = DeleteQuery(table)

fun <S : DeleteQueryState.WhereAllowed> DeleteQuery<S>.where(
    predicate: SqlPredicate,
): DeleteQuery<DeleteQueryState.AfterWhere> {
    require(!predicate.containsAggregate()) { "WHERE cannot contain an aggregate expression" }
    require(!predicate.containsWindowExpression()) { "WHERE cannot contain a window expression" }
    from.requireRegisteredColumns(predicate.referencedColumns(), "WHERE")
    return DeleteQuery(from, predicate, withClause)
}

/** Attaches a lexical WITH scope to this DELETE without weakening its WHERE/all-rows typestate. */
fun <S : DeleteQueryState> DeleteQuery<S>.with(vararg definitions: CommonTableExpression): DeleteQuery<S> {
    require(withClause.isEmpty()) { "DELETE already has a WITH clause" }
    val ctes = definitions.toList()
    validateCteDefinitions(ctes)
    return DeleteQuery(from, predicate, ctes)
}

/** Validates CTE visibility and keeps DML subqueries non-correlated with the changed row. */
internal fun UpdateQuery<*>.requireWriteValidScope() {
    validateCteDefinitions(withClause)
    requireUpdateFromSources()
    val visibleSources = listOf<RelationSource>(table) + fromSources
    assignments.forEach { assignment ->
        table.requireRegisteredColumn(assignment.column, "UPDATE")
        (assignment as? ExpressionAssignment<*>)?.expression?.requireUpdateExpressionScope(visibleSources, withClause)
    }
    predicate?.requireWritePredicateScope(visibleSources, "UPDATE", withClause)
}

/** Validates CTE visibility and keeps DML subqueries non-correlated with the deleted row. */
internal fun DeleteQuery<*>.requireWriteValidScope() {
    validateCteDefinitions(withClause)
    predicate?.requireWritePredicateScope(listOf(from), "DELETE", withClause)
}

/** Validates a query INSERT source as an independent SELECT scope with this INSERT's CTEs. */
internal fun InsertQuery<*>.requireWriteValidScope() {
    validateCteDefinitions(withClause)
    val source = source as? InsertSource.Query
    source?.query?.requireValidScope(availableCtes = withClause)
    conflictBehavior?.requireValidScope(into)
}

private fun SqlPredicate.requireWritePredicateScope(
    allowedSources: List<RelationSource>,
    statement: String,
    availableCtes: List<CommonTableExpression>,
) {
    val invalidDirectReference = relationReferences().firstOrNull { reference ->
        allowedSources.none { source -> source === reference.source } || !reference.source.contains(
            reference.expression,
        )
    }
    require(invalidDirectReference == null) {
        "$statement predicate reference is not registered by the statement scope: " +
            requireNotNull(invalidDirectReference).expression
    }
    nestedQueries().forEach { query ->
        query.requireValidScope(parentSources = emptyList(), availableCtes = availableCtes)
    }
}

/** Validates only direct predicate references while a following WITH scope may not be attached yet. */
private fun SqlPredicate.requireUpdateDirectPredicateScope(allowedSources: List<RelationSource>) {
    val invalidDirectReference = relationReferences().firstOrNull { reference ->
        allowedSources.none { source -> source === reference.source } || !reference.source.contains(
            reference.expression,
        )
    }
    require(invalidDirectReference == null) {
        "UPDATE predicate reference is not registered by the statement scope: " +
            requireNotNull(invalidDirectReference).expression
    }
}

/** Validates an expression assignment against the UPDATE target and its direct FROM sources. */
private fun SqlExpression<*>.requireUpdateExpressionScope(
    allowedSources: List<RelationSource>,
    availableCtes: List<CommonTableExpression>,
) {
    require(!containsAggregate()) { "UPDATE assignment cannot contain aggregate expressions" }
    require(!containsWindowExpression()) { "UPDATE assignment cannot contain window expressions" }
    val invalidDirectReference = relationReferences().firstOrNull { reference ->
        allowedSources.none { source -> source === reference.source } || !reference.source.contains(
            reference.expression,
        )
    }
    require(invalidDirectReference == null) {
        "UPDATE assignment reference is not registered by the statement scope: " +
            requireNotNull(invalidDirectReference).expression
    }
    nestedQueries().forEach { query ->
        query.requireValidScope(parentSources = emptyList(), availableCtes = availableCtes)
    }
}

/** Validates direct UPDATE FROM sources independently while retaining the statement's CTE scope. */
private fun UpdateQuery<*>.requireUpdateFromSources() {
    require(fromSources.none { it is LateralRelation }) { "UPDATE FROM does not accept lateral relations" }
    require(fromSources.none { it === table }) { "UPDATE FROM cannot repeat the target table without an alias" }
    val unavailableCte = fromSources.filterIsInstance<CommonTableExpression>().firstOrNull { source ->
        withClause.none { it === source }
    }
    require(unavailableCte == null) {
        "UPDATE FROM CTE source is not registered by this WITH scope: ${requireNotNull(unavailableCte).name}"
    }
    val shadowedTable = fromSources.filterIsInstance<TableRelationSource>().firstOrNull { source ->
        source.table.schemaName == null && withClause.any { cte -> cte.name == source.table.tableName }
    }
    require(shadowedTable == null) {
        "UPDATE FROM physical table source is shadowed by a CTE in this WITH scope: " +
            requireNotNull(shadowedTable).table.tableName
    }
    fromSources.filterIsInstance<DerivedTable>().forEach { source ->
        source.query.requireValidScope(availableCtes = withClause)
    }
}

private fun UpdateQuery<*>.requireNoAmbiguousUpdateFromSourceName(
    candidate: RelationSource,
    sources: List<RelationSource>,
) {
    val candidateName = candidate.alias ?: candidate.sourceName
    require(candidate.alias == null || candidateName != table.tableName) {
        "UPDATE FROM relation source name is ambiguous with the target table: $candidateName"
    }
    val duplicate = sources.any { source ->
        source !== candidate &&
            (source.alias != null || candidate.alias != null) &&
            (source.alias ?: source.sourceName) == candidateName
    }
    require(!duplicate) { "UPDATE FROM relation source name is ambiguous: $candidateName" }
}

private fun requireInsertProjectionCompatibility(
    targetColumns: List<WritableColumn<*>>,
    projections: List<SqlExpression<*>>,
) {
    require(targetColumns.size == projections.size) {
        "INSERT ... SELECT requires equal target and projection counts: targets have ${targetColumns.size}, " +
            "source has ${projections.size}"
    }
    targetColumns.zip(projections).forEachIndexed { index, (target, projection) ->
        require(target.type === projection.type && target.storageType === projection.storageType) {
            "INSERT ... SELECT projection ${index + 1} has incompatible logical or storage types"
        }
        require(target.isNullable || !projection.isNullable) {
            "INSERT ... SELECT projection ${index + 1} is nullable for a non-null target column"
        }
    }
}

private fun InsertRow.withColumnOrder(firstRow: InsertRow): InsertRow {
    require(assignments.size == firstRow.assignments.size) {
        "Every INSERT row must assign the same columns"
    }
    val orderedAssignments = firstRow.assignments.map { expected ->
        assignments.firstOrNull { it.column === expected.column }
            ?: throw IllegalArgumentException("Every INSERT row must assign the same columns")
    }
    return InsertRow(orderedAssignments)
}

private fun Table.requireRegisteredColumn(column: Column<*>, clause: String) {
    require(column.table === this && hasColumn(column)) {
        "$clause column is not registered by table $this: $column"
    }
}

private fun Table.requireRegisteredColumns(columns: Iterable<Column<*>>, clause: String) {
    val invalidColumn = columns.firstOrNull { it.table !== this || !hasColumn(it) } ?: return
    throw IllegalArgumentException("$clause column is not registered by table $this: $invalidColumn")
}

internal fun <T> requireCompatibleWriteExpression(
    column: WritableColumn<T>,
    expression: SqlExpression<T>,
    clause: String,
) {
    require(column.type === expression.type && column.storageType === expression.storageType) {
        "$clause assignment expression has incompatible logical or storage types for $column"
    }
    require(column.isNullable || !expression.isNullable) {
        "$clause assignment expression is nullable for a non-null column: $column"
    }
}
