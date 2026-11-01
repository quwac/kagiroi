package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.schema.RelationSource

/** Creates a non-recursive CTE from any typed query expression. */
fun <T, P : SelectProjectionShape> QueryExpression<T, P>.asCte(name: String): CommonTableExpression =
    CommonTableExpression(name, this)

/** Creates an explicitly aliased derived relation from any typed query expression. */
fun <T, P : SelectProjectionShape> QueryExpression<T, P>.asTable(alias: String): DerivedTable = DerivedTable(
    this,
    alias,
)

internal fun QueryExpression<*, *>.requireValidScope(
    parentSources: List<RelationSource> = emptyList(),
    availableCtes: List<CommonTableExpression> = emptyList(),
    recursiveSelf: RecursiveCteSelf? = null,
) {
    when (this) {
        is SelectQuery<*, *, *> -> requireSelectValidScope(parentSources, availableCtes, recursiveSelf)
        is SetOperationQuery<*, *> -> requireSetOperationValidScope(parentSources, availableCtes, recursiveSelf)
    }
}

internal fun QueryExpression<*, *>.withClause(): List<CommonTableExpression> = when (this) {
    is SelectQuery<*, *, *> -> withClause
    is SetOperationQuery<*, *> -> withClause
    else -> error("Unsupported query expression: $this")
}

internal fun QueryExpression<*, *>.requireCteSources(availableCtes: List<CommonTableExpression>, clause: String) {
    when (this) {
        is SelectQuery<*, *, *> -> requireCteSources(availableCtes, clause)

        is SetOperationQuery<*, *> -> {
            left.requireCteSources(availableCtes, clause)
            right.requireCteSources(availableCtes, clause)
        }
    }
}

internal fun QueryExpression<*, *>.referencedColumns(): List<Column<*>> = when (this) {
    is SelectQuery<*, *, *> -> referencedSelectColumns()

    is SetOperationQuery<*, *> -> left.referencedColumns() + right.referencedColumns() +
        order.flatMap { it.expression.referencedColumns }

    else -> error("Unsupported query expression: $this")
}

/**
 * Finds source identities referenced directly by a query expression but not introduced by it.
 *
 * This intentionally stops at nested scalar/predicate queries. Their correlation is validated by
 * the established parent-source chain once the lateral body itself has an attached left scope.
 */
internal fun QueryExpression<*, *>.directOuterDependencies(): List<RelationSource> = buildList {
    fun addDependency(source: RelationSource) {
        if (none { existing -> existing === source }) add(source)
    }

    fun addSelectDependencies(query: SelectQuery<*, *, *>) {
        query.directRelationReferences()
            .filter { reference -> query.sources.none { source -> source === reference.source } }
            .forEach { reference -> addDependency(reference.source) }
    }

    fun addDependencies(query: QueryExpression<*, *>) {
        when (query) {
            is SelectQuery<*, *, *> -> addSelectDependencies(query)

            is SetOperationQuery<*, *> -> {
                addDependencies(query.left)
                addDependencies(query.right)
            }
        }
    }

    addDependencies(this@directOuterDependencies)
}

internal fun requireSetOperationCompatibility(left: QueryExpression<*, *>, right: QueryExpression<*, *>) {
    require(left.columns.size == right.columns.size) {
        "Set operation requires equal projection counts: left has ${left.columns.size}, right has ${right.columns.size}"
    }
    left.columns.zip(right.columns).forEachIndexed { index, (leftProjection, rightProjection) ->
        require(
            leftProjection.type === rightProjection.type && leftProjection.storageType === rightProjection.storageType,
        ) {
            "Set operation projection ${index + 1} has incompatible logical or storage types"
        }
        require(leftProjection.isNullable == rightProjection.isNullable) {
            "Set operation projection ${index + 1} has incompatible nullability"
        }
    }
}

/**
 * Bare ORDER BY inside a compound operand is not portable. A bounded operand is rendered as a
 * derived boundary, which keeps its ORDER BY/LIMIT semantics legal across supported dialects.
 */
internal fun requirePortableSetOperand(query: QueryExpression<*, *>) {
    when (query) {
        is SelectQuery<*, *, *> -> require(query.order.isEmpty() || query.limit != null || query.offset != null) {
            "Set operation operands cannot use ORDER BY without LIMIT or OFFSET"
        }

        is SetOperationQuery<*, *> -> {
            require(query.order.isEmpty() || query.limit != null || query.offset != null) {
                "Nested set operation operands cannot use ORDER BY without LIMIT or OFFSET"
            }
        }
    }
}

private fun SetOperationQuery<*, *>.requireSetOperationValidScope(
    parentSources: List<RelationSource>,
    availableCtes: List<CommonTableExpression>,
    recursiveSelf: RecursiveCteSelf?,
) {
    validateCteDefinitions(withClause, availableCtes)
    val visibleCtes = availableCtes + withClause
    requireCteSources(visibleCtes, "set operation")
    requireSetOrder(order)
    requirePortableSetOperand(left)
    requirePortableSetOperand(right)
    left.requireValidScope(parentSources, visibleCtes, recursiveSelf)
    right.requireValidScope(parentSources, visibleCtes, recursiveSelf)
}

internal fun SetOperationQuery<*, *>.requireSetOrder(orders: List<Order<*>>) {
    orders.forEach { order ->
        require(columns.any { it === order.expression }) {
            "Set operation ORDER BY must reference an output projection from the left query"
        }
    }
}
