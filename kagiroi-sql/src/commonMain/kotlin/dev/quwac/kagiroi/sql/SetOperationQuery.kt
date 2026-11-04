package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.SqlExpression

/**
 * A typed row-producing query expression.
 *
 * [T] retains the first projection's Kotlin type and [P] retains whether a query is scalar.
 * The complete ordered projection schema remains in [columns], which is shared by SELECT,
 * derived-table, CTE, row-mapping, and set-operation code.
 */
interface QueryExpression<T, P : SelectProjectionShape> {
    val columns: List<SqlExpression<*>>
}

/** SQL duplicate semantics selected by a typed set-operation method.
 *
 * @property sql SQL operator clause.
 */
enum class SetOperation(val sql: String, internal val unsupportedCapability: String) {
    /** Combines results and removes duplicate rows. */
    Union("UNION", "union"),

    /** Combines results and retains duplicate rows. */
    UnionAll("UNION ALL", "union-all"),

    /** Returns rows present in both inputs and removes duplicates. */
    Intersect("INTERSECT", "intersect"),

    /** Returns the duplicate-preserving intersection of the inputs. */
    IntersectAll("INTERSECT ALL", "intersect-all"),

    /** Returns rows from the left input that are absent from the right input. */
    Except("EXCEPT", "except"),

    /** Returns the duplicate-preserving difference of the inputs. */
    ExceptAll("EXCEPT ALL", "except-all"),
}

/**
 * An immutable binary set-operation AST.
 *
 * Its output schema is exactly [left]'s projection schema: SQL output names, typed projection
 * handles, and row decoders therefore remain stable even if [right] uses other aliases.
 *
 * @property order ordering applied after the set operation.
 * @property limit maximum number of result rows, when specified.
 * @property offset number of result rows skipped, when specified.
 */
class SetOperationQuery<T, P : SelectProjectionShape> internal constructor(
    internal val left: QueryExpression<T, P>,
    internal val operation: SetOperation,
    internal val right: QueryExpression<T, P>,
    val order: List<Order<*>> = emptyList(),
    val limit: Int? = null,
    val offset: Int? = null,
    internal val withClause: List<CommonTableExpression> = emptyList(),
) : QueryExpression<T, P>,
    WithCteClause<SetOperationQuery<T, P>> {
    override val columns: List<SqlExpression<*>> = left.columns

    override fun withCtes(vararg definitions: CommonTableExpression): SetOperationQuery<T, P> = with(*definitions)
}

/** Combines compatible query schemas and removes duplicate rows. */
fun <T, P : SelectProjectionShape> QueryExpression<T, P>.union(other: QueryExpression<T, P>): SetOperationQuery<T, P> =
    setOperation(SetOperation.Union, other)

/** Combines compatible query schemas while retaining duplicate rows. */
fun <T, P : SelectProjectionShape> QueryExpression<T, P>.unionAll(
    other: QueryExpression<T, P>,
): SetOperationQuery<T, P> = setOperation(SetOperation.UnionAll, other)

/** Returns rows present in both compatible query schemas. */
fun <T, P : SelectProjectionShape> QueryExpression<T, P>.intersect(
    other: QueryExpression<T, P>,
): SetOperationQuery<T, P> = setOperation(SetOperation.Intersect, other)

/** Returns the duplicate-preserving intersection of compatible query schemas. */
fun <T, P : SelectProjectionShape> QueryExpression<T, P>.intersectAll(
    other: QueryExpression<T, P>,
): SetOperationQuery<T, P> = setOperation(SetOperation.IntersectAll, other)

/** Returns rows present in this schema but absent from the compatible right schema. */
fun <T, P : SelectProjectionShape> QueryExpression<T, P>.except(
    other: QueryExpression<T, P>,
): SetOperationQuery<T, P> = setOperation(SetOperation.Except, other)

/** Returns the duplicate-preserving difference of compatible query schemas. */
fun <T, P : SelectProjectionShape> QueryExpression<T, P>.exceptAll(
    other: QueryExpression<T, P>,
): SetOperationQuery<T, P> = setOperation(SetOperation.ExceptAll, other)

private fun <T, P : SelectProjectionShape> QueryExpression<T, P>.setOperation(
    operation: SetOperation,
    other: QueryExpression<T, P>,
): SetOperationQuery<T, P> {
    requireSetOperationCompatibility(this, other)
    requirePortableSetOperand(this)
    requirePortableSetOperand(other)
    return SetOperationQuery(this, operation, other)
}

/** Adds an outer ORDER BY to the complete set result, never to either operand. */
fun <T, P : SelectProjectionShape> SetOperationQuery<T, P>.orderBy(
    order: Order<*>,
    vararg additionalOrders: Order<*>,
): SetOperationQuery<T, P> {
    val orders = listOf(order) + additionalOrders
    requireSetOrder(orders)
    return SetOperationQuery(left, operation, right, this.order + orders, limit, offset, withClause)
}

/** Limits the complete set result. */
fun <T, P : SelectProjectionShape> SetOperationQuery<T, P>.limit(limit: Int): SetOperationQuery<T, P> {
    require(limit >= 0) { "LIMIT must not be negative" }
    require(this.limit == null) { "Set operation already has a LIMIT" }
    return SetOperationQuery(left, operation, right, order, limit, offset, withClause)
}

/** Offsets the complete set result. */
fun <T, P : SelectProjectionShape> SetOperationQuery<T, P>.offset(offset: Int): SetOperationQuery<T, P> {
    require(offset >= 0) { "OFFSET must not be negative" }
    require(this.offset == null) { "Set operation already has an OFFSET" }
    return SetOperationQuery(left, operation, right, order, limit, offset, withClause)
}

/** Attaches ordered non-recursive CTE definitions to this set expression. */
fun <T, P : SelectProjectionShape> SetOperationQuery<T, P>.with(
    vararg definitions: CommonTableExpression,
): SetOperationQuery<T, P> {
    require(withClause.isEmpty()) { "Set operation already has a WITH clause" }
    val ctes = definitions.toList()
    validateCteDefinitions(ctes)
    requireCteSources(ctes, "set operation")
    return SetOperationQuery(left, operation, right, order, limit, offset, ctes)
}
