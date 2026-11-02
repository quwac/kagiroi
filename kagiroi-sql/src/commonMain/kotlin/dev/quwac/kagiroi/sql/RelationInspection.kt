@file:Suppress("TooManyFunctions") // One cohesive AST inspection boundary walks expressions and predicates.

package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.BooleanExpression
import dev.quwac.kagiroi.schema.CaseExpression
import dev.quwac.kagiroi.schema.CastExpression
import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.schema.ExpressionComparison
import dev.quwac.kagiroi.schema.ExpressionNullComparison
import dev.quwac.kagiroi.schema.ExpressionValueComparison
import dev.quwac.kagiroi.schema.GeneratedColumnBinaryExpression
import dev.quwac.kagiroi.schema.GroupByElement
import dev.quwac.kagiroi.schema.LogicalPredicate
import dev.quwac.kagiroi.schema.NullComparison
import dev.quwac.kagiroi.schema.NullSafeComparison
import dev.quwac.kagiroi.schema.NullSafeValueComparison
import dev.quwac.kagiroi.schema.NullableRelationColumn
import dev.quwac.kagiroi.schema.NullableSqlExpression
import dev.quwac.kagiroi.schema.NumericBinaryExpression
import dev.quwac.kagiroi.schema.RelationColumn
import dev.quwac.kagiroi.schema.RelationSource
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.SqlExpressionAlias
import dev.quwac.kagiroi.schema.SqlFunction
import dev.quwac.kagiroi.schema.SqlFunctionExpression
import dev.quwac.kagiroi.schema.SqlLiteral
import dev.quwac.kagiroi.schema.ValueComparison
import dev.quwac.kagiroi.schema.ValueWindowFunctionExpression
import dev.quwac.kagiroi.schema.WindowFunctionExpression

/** One expression reference qualified by its query source. */
internal data class RelationReference(val source: RelationSource, val expression: SqlExpression<*>)

/** Recovers source ownership through the existing expression AST without introducing a parallel tree. */
@Suppress("CyclomaticComplexMethod")
internal fun SqlExpression<*>.relationReferences(): List<RelationReference> = when (this) {
    is NullableSqlExpression<*> -> expression.relationReferences()

    is RelationColumn<*> -> listOf(RelationReference(source, column))

    is NullableRelationColumn<*> -> listOf(RelationReference(source, column))

    is DerivedColumn<*> -> listOf(RelationReference(source, projection))

    is NullableDerivedColumn<*> -> listOf(RelationReference(source, column.projection))

    is Column<*> -> listOf(RelationReference(table, this))

    is ScalarSubquery<*> -> emptyList()

    is SqlLiteral<*> -> emptyList()

    is CastExpression<*> -> expression.relationReferences()

    is CaseExpression<*> -> branches.flatMap { branch ->
        branch.condition.relationReferences() + branch.result.relationReferences()
    } + elseResult.relationReferences()

    is WindowExpression<*> -> function.relationReferences() +
        specification.partitionBy.flatMap(SqlExpression<*>::relationReferences) +
        specification.orderBy.flatMap { it.expression.relationReferences() }

    is WithinGroupAggregateExpression<*> -> directArguments.flatMap(SqlExpression<*>::relationReferences) +
        withinGroup.orderBy.flatMap { it.expression.relationReferences() }

    is SqlFunctionExpression<*> -> arguments.flatMap(SqlExpression<*>::relationReferences)

    is SqlExpressionAlias<*> -> expression.relationReferences()

    is GeneratedColumnBinaryExpression<*> -> left.relationReferences() + right.relationReferences()

    is NumericBinaryExpression<*> -> left.relationReferences() + right.relationReferences()

    else -> referencedColumns.map { RelationReference(it.table, it) }
}

/** Recovers source ownership through the existing Boolean expression AST. */
internal fun BooleanExpression.relationReferences(): List<RelationReference> = when (this) {
    is ValueComparison<*> -> listOf(RelationReference(column.table, column))
    is ExpressionValueComparison<*> -> expression.relationReferences()
    is ExpressionComparison<*> -> left.relationReferences() + right.relationReferences()
    is NullSafeComparison<*> -> left.relationReferences() + right.relationReferences()
    is NullSafeValueComparison<*> -> expression.relationReferences()
    is NullComparison -> listOf(RelationReference(column.table, column))
    is ExpressionNullComparison -> expression.relationReferences()
    is LogicalPredicate -> left.relationReferences() + right.relationReferences()
    is InSubqueryPredicate -> expression.relationReferences()
    is ExistsPredicate -> emptyList()
    else -> referencedColumns.map { RelationReference(it.table, it) }
}

/** Nested SELECTs validate against the current query sources plus any correlation parents. */
internal fun SqlExpression<*>.nestedQueries(): List<QueryExpression<*, *>> = when (this) {
    is NullableSqlExpression<*> -> expression.nestedQueries()

    is ScalarSubquery<*> -> listOf(query)

    is WindowExpression<*> -> function.nestedQueries() +
        specification.partitionBy.flatMap(SqlExpression<*>::nestedQueries) +
        specification.orderBy.flatMap { it.expression.nestedQueries() }

    is WithinGroupAggregateExpression<*> -> directArguments.flatMap(SqlExpression<*>::nestedQueries) +
        withinGroup.orderBy.flatMap { it.expression.nestedQueries() }

    is SqlFunctionExpression<*> -> arguments.flatMap(SqlExpression<*>::nestedQueries)

    is CastExpression<*> -> expression.nestedQueries()

    is CaseExpression<*> -> branches.flatMap { branch ->
        branch.condition.nestedQueries() + branch.result.nestedQueries()
    } + elseResult.nestedQueries()

    is SqlExpressionAlias<*> -> expression.nestedQueries()

    is GeneratedColumnBinaryExpression<*> -> left.nestedQueries() + right.nestedQueries()

    is NumericBinaryExpression<*> -> left.nestedQueries() + right.nestedQueries()

    else -> emptyList()
}

/** Named-window references remain visible through aliases and normal expression composition. */
@Suppress("CyclomaticComplexMethod")
internal fun SqlExpression<*>.namedWindows(): List<NamedWindow> = when (this) {
    is NullableSqlExpression<*> -> expression.namedWindows()

    is WindowExpression<*> -> listOfNotNull(namedWindow) + function.namedWindows()

    is WithinGroupAggregateExpression<*> -> directArguments.flatMap(SqlExpression<*>::namedWindows) +
        withinGroup.orderBy.flatMap { it.expression.namedWindows() }

    is SqlFunctionExpression<*> -> arguments.flatMap(SqlExpression<*>::namedWindows)

    is CastExpression<*> -> expression.namedWindows()

    is CaseExpression<*> -> branches.flatMap { branch ->
        branch.condition.namedWindows() + branch.result.namedWindows()
    } + elseResult.namedWindows()

    is SqlExpressionAlias<*> -> expression.namedWindows()

    is GeneratedColumnBinaryExpression<*> -> left.namedWindows() + right.namedWindows()

    is NumericBinaryExpression<*> -> left.namedWindows() + right.namedWindows()

    else -> emptyList()
}

/** Nested SELECTs in query predicates remain scoped independently from their containing predicate. */
internal fun BooleanExpression.nestedQueries(): List<QueryExpression<*, *>> = when (this) {
    is ExpressionValueComparison<*> -> expression.nestedQueries()
    is ExpressionComparison<*> -> left.nestedQueries() + right.nestedQueries()
    is NullSafeComparison<*> -> left.nestedQueries() + right.nestedQueries()
    is NullSafeValueComparison<*> -> expression.nestedQueries()
    is ExpressionNullComparison -> expression.nestedQueries()
    is LogicalPredicate -> left.nestedQueries() + right.nestedQueries()
    is InSubqueryPredicate -> expression.nestedQueries() + listOf(query)
    is ExistsPredicate -> listOf(query)
    else -> emptyList()
}

private fun BooleanExpression.namedWindows(): List<NamedWindow> = when (this) {
    is ExpressionValueComparison<*> -> expression.namedWindows()
    is ExpressionComparison<*> -> left.namedWindows() + right.namedWindows()
    is NullSafeComparison<*> -> left.namedWindows() + right.namedWindows()
    is NullSafeValueComparison<*> -> expression.namedWindows()
    is ExpressionNullComparison -> expression.namedWindows()
    is LogicalPredicate -> left.namedWindows() + right.namedWindows()
    else -> emptyList()
}

/** A direct window result is legal in SELECT and outer ORDER BY, but not row filtering clauses. */
internal fun BooleanExpression.containsWindowExpression(): Boolean = when (this) {
    is ValueComparison<*> -> false
    is ExpressionValueComparison<*> -> expression.containsWindowExpression()
    is ExpressionComparison<*> -> left.containsWindowExpression() || right.containsWindowExpression()
    is NullSafeComparison<*> -> left.containsWindowExpression() || right.containsWindowExpression()
    is NullSafeValueComparison<*> -> expression.containsWindowExpression()
    is NullComparison -> false
    is ExpressionNullComparison -> expression.containsWindowExpression()
    is LogicalPredicate -> left.containsWindowExpression() || right.containsWindowExpression()
    is InSubqueryPredicate, is ExistsPredicate -> false
    else -> false
}

/** An uncompleted ranking function is invalid even when it is nested in a CASE predicate. */
private fun BooleanExpression.containsIncompleteWindowFunction(): Boolean = when (this) {
    is ValueComparison<*>, is NullComparison -> false

    is ExpressionValueComparison<*> -> expression.containsIncompleteWindowFunction()

    is ExpressionComparison<*> ->
        left.containsIncompleteWindowFunction() || right.containsIncompleteWindowFunction()

    is NullSafeComparison<*> ->
        left.containsIncompleteWindowFunction() || right.containsIncompleteWindowFunction()

    is NullSafeValueComparison<*> -> expression.containsIncompleteWindowFunction()

    is ExpressionNullComparison -> expression.containsIncompleteWindowFunction()

    is LogicalPredicate ->
        left.containsIncompleteWindowFunction() || right.containsIncompleteWindowFunction()

    is InSubqueryPredicate, is ExistsPredicate -> false

    else -> false
}

/** CASE predicates participate in the same aggregate validation as their result branches. */
internal fun BooleanExpression.containsAggregate(): Boolean = when (this) {
    is ValueComparison<*>, is NullComparison -> false
    is ExpressionValueComparison<*> -> expression.containsAggregate()
    is ExpressionComparison<*> -> left.containsAggregate() || right.containsAggregate()
    is NullSafeComparison<*> -> left.containsAggregate() || right.containsAggregate()
    is NullSafeValueComparison<*> -> expression.containsAggregate()
    is ExpressionNullComparison -> expression.containsAggregate()
    is LogicalPredicate -> left.containsAggregate() || right.containsAggregate()
    is InSubqueryPredicate, is ExistsPredicate -> false
    else -> false
}

/** A CASE predicate must meet the same portable GROUP BY restrictions as its result branches. */
private fun BooleanExpression.isGroupable(): Boolean = when (this) {
    is ValueComparison<*>, is NullComparison -> true
    is ExpressionValueComparison<*> -> expression.isGroupable()
    is ExpressionComparison<*> -> left.isGroupable() && right.isGroupable()
    is NullSafeComparison<*> -> left.isGroupable() && right.isGroupable()
    is NullSafeValueComparison<*> -> expression.isGroupable()
    is ExpressionNullComparison -> expression.isGroupable()
    is LogicalPredicate -> left.isGroupable() && right.isGroupable()
    is InSubqueryPredicate, is ExistsPredicate -> false
    else -> false
}

@Suppress("CyclomaticComplexMethod") // Exhaustive expression-tree traversal over the closed AST.
internal fun SqlExpression<*>.containsWindowExpression(): Boolean = when (this) {
    is NullableSqlExpression<*> -> expression.containsWindowExpression()

    is WindowExpression<*> -> true

    is WindowFunctionExpression<*> -> true

    is ValueWindowFunctionExpression<*> -> true

    is WithinGroupAggregateExpression<*> ->
        directArguments.any(SqlExpression<*>::containsWindowExpression) ||
            withinGroup.orderBy.any { it.expression.containsWindowExpression() }

    is SqlExpressionAlias<*> -> expression.containsWindowExpression()

    is SqlFunctionExpression<*> -> arguments.any(SqlExpression<*>::containsWindowExpression)

    is CastExpression<*> -> expression.containsWindowExpression()

    is CaseExpression<*> -> branches.any { branch ->
        branch.condition.containsWindowExpression() || branch.result.containsWindowExpression()
    } || elseResult.containsWindowExpression()

    is GeneratedColumnBinaryExpression<*> -> left.containsWindowExpression() || right.containsWindowExpression()

    is NumericBinaryExpression<*> -> left.containsWindowExpression() || right.containsWindowExpression()

    else -> false
}

/** An incomplete window function without its required `.over { ... }` has no valid standalone SQL form. */
@Suppress("CyclomaticComplexMethod") // Exhaustive expression-tree traversal over the closed AST.
internal fun SqlExpression<*>.containsIncompleteWindowFunction(): Boolean = when (this) {
    is NullableSqlExpression<*> -> expression.containsIncompleteWindowFunction()

    is WindowFunctionExpression<*> -> true

    is ValueWindowFunctionExpression<*> -> true

    is WithinGroupAggregateExpression<*> ->
        directArguments.any(SqlExpression<*>::containsIncompleteWindowFunction) ||
            withinGroup.orderBy.any { it.expression.containsIncompleteWindowFunction() }

    is SqlExpressionAlias<*> -> expression.containsIncompleteWindowFunction()

    is SqlFunctionExpression<*> -> arguments.any(SqlExpression<*>::containsIncompleteWindowFunction)

    is CastExpression<*> -> expression.containsIncompleteWindowFunction()

    is CaseExpression<*> -> branches.any { branch ->
        branch.condition.containsIncompleteWindowFunction() || branch.result.containsIncompleteWindowFunction()
    } || elseResult.containsIncompleteWindowFunction()

    is GeneratedColumnBinaryExpression<*> ->
        left.containsIncompleteWindowFunction() || right.containsIncompleteWindowFunction()

    is NumericBinaryExpression<*> ->
        left.containsIncompleteWindowFunction() || right.containsIncompleteWindowFunction()

    else -> false
}

internal fun SelectQuery<*, *, *>.nestedQueries(): List<QueryExpression<*, *>> = buildList {
    addAll(columns.flatMap(SqlExpression<*>::nestedQueries))
    predicate?.let { addAll(it.nestedQueries()) }
    groupBy.flatMap(GroupByElement::groupingExpressions).forEach { addAll(it.nestedQueries()) }
    having?.let { addAll(it.nestedQueries()) }
    joins.forEach { join -> join.condition?.let { condition -> addAll(condition.nestedQueries()) } }
    order.forEach { addAll(it.expression.nestedQueries()) }
}

/**
 * Collects references owned directly by this SELECT node, without traversing nested query bodies.
 * Lateral relation construction uses this same expression inspection as scalar-subquery scope
 * validation; a nested query receives its own scope validation later.
 */
internal fun SelectQuery<*, *, *>.directRelationReferences(): List<RelationReference> = buildList {
    addAll(columns.flatMap(SqlExpression<*>::relationReferences))
    predicate?.let { addAll(it.relationReferences()) }
    groupBy.flatMap(GroupByElement::groupingExpressions).forEach { addAll(it.relationReferences()) }
    having?.let { addAll(it.relationReferences()) }
    joins.forEach { join -> join.condition?.let { condition -> addAll(condition.relationReferences()) } }
    order.forEach { addAll(it.expression.relationReferences()) }
}

/** Whether an expression tree contains a built-in aggregate at any depth. */
@Suppress("CyclomaticComplexMethod") // Exhaustive AST traversal.
internal fun SqlExpression<*>.containsAggregate(): Boolean = when (this) {
    is NullableSqlExpression<*> -> expression.containsAggregate()

    is SqlExpressionAlias<*> -> expression.containsAggregate()

    // A window aggregate evaluates after grouping; only aggregate expressions within its
    // specification make it aggregate-bearing for the portable grouped-projection guard.
    is WindowExpression<*> -> specification.partitionBy.any(SqlExpression<*>::containsAggregate) ||
        specification.orderBy.any { it.expression.containsAggregate() }

    is WithinGroupAggregateExpression<*> -> true

    is SqlFunctionExpression<*> -> function.isAggregate() || arguments.any(SqlExpression<*>::containsAggregate)

    is CastExpression<*> -> expression.containsAggregate()

    is CaseExpression<*> -> branches.any { branch ->
        branch.condition.containsAggregate() || branch.result.containsAggregate()
    } || elseResult.containsAggregate()

    is GeneratedColumnBinaryExpression<*> -> left.containsAggregate() || right.containsAggregate()

    is NumericBinaryExpression<*> -> left.containsAggregate() || right.containsAggregate()

    else -> false
}

/** GROUP BY is deliberately limited to ordinary expression trees, not scalar subquery trees. */
@Suppress("CyclomaticComplexMethod") // Exhaustive expression-tree traversal over the closed AST.
internal fun SqlExpression<*>.isGroupable(): Boolean = when (this) {
    is ScalarSubquery<*> -> false

    is WindowExpression<*> -> false

    is WithinGroupAggregateExpression<*> -> false

    is NullableSqlExpression<*> -> expression.isGroupable()

    is SqlExpressionAlias<*> -> expression.isGroupable()

    is SqlFunctionExpression<*> -> function != SqlFunction.Grouping && arguments.all(SqlExpression<*>::isGroupable)

    is CastExpression<*> -> expression.isGroupable()

    is CaseExpression<*> -> branches.all { branch ->
        branch.condition.isGroupable() && branch.result.isGroupable()
    } && elseResult.isGroupable()

    is GeneratedColumnBinaryExpression<*> -> left.isGroupable() && right.isGroupable()

    is NumericBinaryExpression<*> -> left.isGroupable() && right.isGroupable()

    else -> true
}

private fun SqlFunction.isAggregate(): Boolean = when (this) {
    SqlFunction.Count,
    SqlFunction.Sum,
    SqlFunction.Average,
    SqlFunction.Minimum,
    SqlFunction.Maximum,
    -> true

    else -> false
}

/**
 * Applies the portable grouped-projection rule without attempting functional-dependency or
 * expression-equivalence analysis. Reusing the same expression node is explicit and stable.
 */
internal fun SelectQuery<*, *, *>.requirePortableGroupedProjections() {
    require(predicate?.groupingFunctionArguments().isNullOrEmpty()) {
        "GROUPING() is not valid in WHERE"
    }
    require(
        joins.none { join ->
            (join.condition as? JoinCondition.On)?.predicate?.groupingFunctionArguments()?.isNotEmpty() == true
        },
    ) {
        "GROUPING() is not valid in JOIN conditions"
    }
    val groupingFunctions = buildList {
        addAll(columns.flatMap(SqlExpression<*>::groupingFunctionArguments))
        having?.let { addAll(it.groupingFunctionArguments()) }
        addAll(order.flatMap { it.expression.groupingFunctionArguments() })
    }
    if (groupingFunctions.isNotEmpty()) {
        require(groupBy.any { it !is SqlExpression<*> }) {
            "GROUPING() requires ROLLUP, CUBE, or GROUPING SETS"
        }
        groupingFunctions.forEach { argument ->
            require(groupBy.any { it.containsGroupingExpression(argument) }) {
                "GROUPING() argument must match a GROUP BY expression: $argument"
            }
        }
    }

    val isAggregateQuery = groupBy.isNotEmpty() || having != null || columns.any(SqlExpression<*>::containsAggregate)
    if (!isAggregateQuery) return

    columns.forEach { projection ->
        val expression = projection.unaliased()
        if (
            expression.containsAggregate() || expression.containsGroupingFunction() ||
            expression.relationReferences().isEmpty()
        ) {
            return@forEach
        }
        val matchingElements = groupBy.filter { grouped -> grouped.containsGroupingExpression(expression) }
        require(matchingElements.isNotEmpty()) {
            "SELECT projection must be an aggregate, independent of relation sources, or match a GROUP BY expression: $projection"
        }
        val canSynthesizeNull = matchingElements.all { it.canSynthesizeNullFor(expression) }
        require(!canSynthesizeNull || projection.isNullable) {
            "This grouping expression may be NULL in super-aggregate rows; select it through .optional: $projection"
        }
    }
}

private fun SqlExpression<*>.unaliased(): SqlExpression<*> = when (this) {
    is SqlExpressionAlias<*> -> expression.unaliased()
    else -> this
}

/** Checks stable source/projection identity while permitting aliases and explicit nullable views. */
internal fun SqlExpression<*>.matchesGroupingIdentity(other: SqlExpression<*>): Boolean {
    val left = groupingIdentityExpression()
    val right = other.groupingIdentityExpression()
    if (left === right) return true
    val leftReference = left.groupingReference() ?: return false
    val rightReference = right.groupingReference() ?: return false
    return leftReference.source === rightReference.source && leftReference.expression === rightReference.expression
}

private fun SqlExpression<*>.groupingIdentityExpression(): SqlExpression<*> = when (this) {
    is SqlExpressionAlias<*> -> expression.groupingIdentityExpression()
    is NullableSqlExpression<*> -> expression.groupingIdentityExpression()
    else -> this
}

private fun SqlExpression<*>.groupingReference(): RelationReference? = when (this) {
    is Column<*> -> RelationReference(table, this)
    is RelationColumn<*> -> RelationReference(source, column)
    is NullableRelationColumn<*> -> RelationReference(source, column)
    is DerivedColumn<*> -> RelationReference(source, projection)
    is NullableDerivedColumn<*> -> RelationReference(source, column.projection)
    else -> null
}

@Suppress("CyclomaticComplexMethod") // Exhaustive expression-tree traversal over the closed AST.
private fun SqlExpression<*>.containsGroupingFunction(): Boolean = when (this) {
    is NullableSqlExpression<*> -> expression.containsGroupingFunction()

    is SqlExpressionAlias<*> -> expression.containsGroupingFunction()

    is SqlFunctionExpression<*> -> function == SqlFunction.Grouping || arguments.any(
        SqlExpression<*>::containsGroupingFunction,
    )

    is CastExpression<*> -> expression.containsGroupingFunction()

    is CaseExpression<*> -> branches.any { branch ->
        branch.condition.groupingFunctionArguments().isNotEmpty() || branch.result.containsGroupingFunction()
    } || elseResult.containsGroupingFunction()

    is GeneratedColumnBinaryExpression<*> -> left.containsGroupingFunction() || right.containsGroupingFunction()

    is NumericBinaryExpression<*> -> left.containsGroupingFunction() || right.containsGroupingFunction()

    is WindowExpression<*> -> function.containsGroupingFunction() ||
        specification.partitionBy.any(SqlExpression<*>::containsGroupingFunction) ||
        specification.orderBy.any { it.expression.containsGroupingFunction() }

    is WithinGroupAggregateExpression<*> ->
        directArguments.any(SqlExpression<*>::containsGroupingFunction) ||
            withinGroup.orderBy.any { it.expression.containsGroupingFunction() }

    else -> false
}

private fun SqlExpression<*>.groupingFunctionArguments(): List<SqlExpression<*>> = when (this) {
    is NullableSqlExpression<*> -> expression.groupingFunctionArguments()

    is SqlExpressionAlias<*> -> expression.groupingFunctionArguments()

    is SqlFunctionExpression<*> -> buildList {
        if (function == SqlFunction.Grouping) addAll(arguments)
        addAll(arguments.flatMap(SqlExpression<*>::groupingFunctionArguments))
    }

    is CastExpression<*> -> expression.groupingFunctionArguments()

    is CaseExpression<*> -> branches.flatMap { branch ->
        branch.condition.groupingFunctionArguments() + branch.result.groupingFunctionArguments()
    } + elseResult.groupingFunctionArguments()

    is GeneratedColumnBinaryExpression<*> -> left.groupingFunctionArguments() + right.groupingFunctionArguments()

    is NumericBinaryExpression<*> -> left.groupingFunctionArguments() + right.groupingFunctionArguments()

    is WindowExpression<*> -> function.groupingFunctionArguments() +
        specification.partitionBy.flatMap(SqlExpression<*>::groupingFunctionArguments) +
        specification.orderBy.flatMap { it.expression.groupingFunctionArguments() }

    is WithinGroupAggregateExpression<*> ->
        directArguments.flatMap(SqlExpression<*>::groupingFunctionArguments) +
            withinGroup.orderBy.flatMap { it.expression.groupingFunctionArguments() }

    else -> emptyList()
}

private fun BooleanExpression.groupingFunctionArguments(): List<SqlExpression<*>> = when (this) {
    is ExpressionValueComparison<*> -> expression.groupingFunctionArguments()
    is ExpressionComparison<*> -> left.groupingFunctionArguments() + right.groupingFunctionArguments()
    is NullSafeComparison<*> -> left.groupingFunctionArguments() + right.groupingFunctionArguments()
    is NullSafeValueComparison<*> -> expression.groupingFunctionArguments()
    is ExpressionNullComparison -> expression.groupingFunctionArguments()
    is LogicalPredicate -> left.groupingFunctionArguments() + right.groupingFunctionArguments()
    else -> emptyList()
}
