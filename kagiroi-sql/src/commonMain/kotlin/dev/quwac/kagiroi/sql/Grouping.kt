@file:Suppress("TooManyFunctions") // Grouping AST traversal stays colocated with its public constructors.

package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.GroupByElement
import dev.quwac.kagiroi.schema.GroupingItem
import dev.quwac.kagiroi.schema.SqlExpression

/** A parenthesized list which is one grouping unit inside `ROLLUP`, `CUBE`, or a grouping set. */
class CompositeGroupingItem internal constructor(val expressions: List<SqlExpression<*>>) : GroupingItem {
    init {
        require(expressions.size >= 2) { "A composite grouping item requires at least two expressions" }
    }
}

/** Creates one composite grouping unit, rendered as `(first, second, ...)`. */
fun group(
    first: SqlExpression<*>,
    second: SqlExpression<*>,
    vararg additional: SqlExpression<*>,
): CompositeGroupingItem = CompositeGroupingItem(listOf(first, second) + additional)

/** Native SQL `ROLLUP`, whose items are grouped from left to right and finally as a grand total. */
class Rollup internal constructor(val items: List<GroupingItem>) : GroupByElement {
    init {
        require(items.isNotEmpty()) { "ROLLUP requires at least one grouping item; use grandTotal() for ()" }
    }
}

/** Creates a native SQL `ROLLUP` grouping element. */
fun rollup(first: GroupingItem, vararg additional: GroupingItem): Rollup = Rollup(listOf(first) + additional)

/** Native SQL `CUBE`, which creates every subset of its grouping items. */
class Cube internal constructor(val items: List<GroupingItem>) : GroupByElement {
    init {
        require(items.isNotEmpty()) { "CUBE requires at least one grouping item" }
    }
}

/** Creates a native SQL `CUBE` grouping element. */
fun cube(first: GroupingItem, vararg additional: GroupingItem): Cube = Cube(listOf(first) + additional)

/** One explicit grouping set. An empty list is the SQL grand-total grouping set `()`. */
class GroupingSet internal constructor(val items: List<GroupingItem>)

/** Creates one explicit grouping set, including the empty set when called without arguments. */
fun groupingSet(vararg items: GroupingItem): GroupingSet = GroupingSet(items.toList())

/** Creates the explicit SQL grand-total grouping set `()`. */
fun grandTotal(): GroupingSet = GroupingSet(emptyList())

/** Native SQL `GROUPING SETS`, retaining its declared order and duplicate sets. */
class GroupingSets internal constructor(val sets: List<GroupingSet>) : GroupByElement {
    init {
        require(sets.isNotEmpty()) { "GROUPING SETS requires at least one grouping set" }
    }
}

/** Creates native SQL `GROUPING SETS`, preserving duplicate sets as written. */
fun groupingSets(first: GroupingSet, vararg additional: GroupingSet): GroupingSets =
    GroupingSets(listOf(first) + additional)

/** Dialect-level grouping capabilities are intentionally independent so unsupported forms are never emulated. */
enum class SqlGroupingCapability(val unsupportedCapability: String) {
    Rollup("grouping-rollup"),
    Cube("grouping-cube"),
    GroupingSets("grouping-sets"),
    GroupingFunction("grouping-function"),
    Composite("grouping-composite"),
}

internal fun GroupByElement.groupingExpressions(): List<SqlExpression<*>> = when (this) {
    is SqlExpression<*> -> listOf(this)
    is Rollup -> items.flatMap(GroupingItem::groupingExpressions)
    is Cube -> items.flatMap(GroupingItem::groupingExpressions)
    is GroupingSets -> sets.flatMap(GroupingSet::groupingExpressions)
    else -> error("Unsupported GROUP BY element: $this")
}

internal fun GroupingSet.groupingExpressions(): List<SqlExpression<*>> = items.flatMap(
    GroupingItem::groupingExpressions,
)

internal fun GroupingItem.groupingExpressions(): List<SqlExpression<*>> = when (this) {
    is SqlExpression<*> -> listOf(this)
    is CompositeGroupingItem -> expressions
    else -> error("Unsupported grouping item: $this")
}

internal fun GroupByElement.containsGroupingExpression(expression: SqlExpression<*>): Boolean =
    groupingExpressions().any { it.matchesGroupingIdentity(expression) }

/** Whether this one top-level element can remove [expression] from one of its output grouping sets. */
internal fun GroupByElement.canSynthesizeNullFor(expression: SqlExpression<*>): Boolean = when (this) {
    is SqlExpression<*> -> false
    is Rollup, is Cube -> containsGroupingExpression(expression)
    is GroupingSets -> sets.any { set -> set.groupingExpressions().none { it.matchesGroupingIdentity(expression) } }
    else -> error("Unsupported GROUP BY element: $this")
}

internal fun GroupByElement.requiredCapabilities(): Set<SqlGroupingCapability> = when (this) {
    is SqlExpression<*> -> emptySet()

    is Rollup -> buildSet {
        add(SqlGroupingCapability.Rollup)
        addAll(items.requiredCapabilities())
    }

    is Cube -> buildSet {
        add(SqlGroupingCapability.Cube)
        addAll(items.requiredCapabilities())
    }

    is GroupingSets -> buildSet {
        add(SqlGroupingCapability.GroupingSets)
        addAll(sets.flatMap(GroupingSet::requiredCapabilities))
    }

    else -> error("Unsupported GROUP BY element: $this")
}

private fun List<GroupingItem>.requiredCapabilities(): Set<SqlGroupingCapability> =
    flatMap(GroupingItem::requiredCapabilities).toSet()

private fun GroupingSet.requiredCapabilities(): Set<SqlGroupingCapability> =
    items.flatMap(GroupingItem::requiredCapabilities).toSet()

private fun GroupingItem.requiredCapabilities(): Set<SqlGroupingCapability> = when (this) {
    is SqlExpression<*> -> emptySet()
    is CompositeGroupingItem -> setOf(SqlGroupingCapability.Composite)
    else -> error("Unsupported grouping item: $this")
}

internal fun GroupByElement.renderStandardGrouping(renderExpression: (SqlExpression<*>) -> String): String =
    when (this) {
        is SqlExpression<*> -> renderExpression(this)
        is Rollup -> "ROLLUP(${items.joinToString(", ") { it.renderGroupingItem(renderExpression) }})"
        is Cube -> "CUBE(${items.joinToString(", ") { it.renderGroupingItem(renderExpression) }})"
        is GroupingSets -> "GROUPING SETS (${sets.joinToString(", ") { it.renderGroupingSet(renderExpression) }})"
        else -> error("Unsupported GROUP BY element: $this")
    }

internal fun GroupingItem.renderGroupingItem(renderExpression: (SqlExpression<*>) -> String): String = when (this) {
    is SqlExpression<*> -> renderExpression(this)

    is CompositeGroupingItem -> expressions.joinToString(
        ", ",
        prefix = "(",
        postfix = ")",
        transform = renderExpression,
    )

    else -> error("Unsupported grouping item: $this")
}

private fun GroupingSet.renderGroupingSet(renderExpression: (SqlExpression<*>) -> String): String =
    items.joinToString(", ", prefix = "(", postfix = ")") { it.renderGroupingItem(renderExpression) }
