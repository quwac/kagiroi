@file:Suppress("TooManyFunctions") // The immutable SELECT DSL keeps its clause builders together.

package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.schema.GroupByElement
import dev.quwac.kagiroi.schema.IndexDirection
import dev.quwac.kagiroi.schema.IndexOrderedExpression
import dev.quwac.kagiroi.schema.RelationSource
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.SqlFunction
import dev.quwac.kagiroi.schema.SqlFunctionExpression
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.TableRelationSource

/** Direction applied to one SQL ordering expression.
 *
 * @property sql SQL ordering token.
 */
enum class SortDirection(val sql: String) {
    /** Sort values from lowest to highest. */
    ASCENDING("ASC"),

    /** Sort values from highest to lowest. */
    DESCENDING("DESC"),
}

/** The explicit placement of SQL NULL values within one ordered result.
 *
 * @property sql SQL null-ordering clause.
 */
enum class NullOrdering(val sql: String) {
    /** Place null values before non-null values. */
    FIRST("NULLS FIRST"),

    /** Place null values after non-null values. */
    LAST("NULLS LAST"),
}

/** One typed SQL ordering expression.
 *
 * @property expression value used to order rows.
 * @property direction ascending or descending order.
 * @property nullOrdering explicit NULL placement, or `null` for the database default.
 */
class Order<T> internal constructor(
    val expression: SqlExpression<T>,
    val direction: SortDirection,
    val nullOrdering: NullOrdering? = null,
) : IndexOrderedExpression {
    /** The ordered column for existing column-only callers; expression orders have no column view. */
    val column: Column<*> get() = expression as? Column<*> ?: error("ORDER BY expression is not a column")

    override val indexExpression: SqlExpression<*> get() = expression

    override val indexDirection: IndexDirection
        get() = when (direction) {
            SortDirection.ASCENDING -> IndexDirection.Ascending
            SortDirection.DESCENDING -> IndexDirection.Descending
        }

    override val indexHasNullOrdering: Boolean get() = nullOrdering != null
}

/** Orders this expression in ascending order. */
fun <T> SqlExpression<T>.asc(): Order<T> = Order(this, SortDirection.ASCENDING)

/** Orders this expression in descending order. */
fun <T> SqlExpression<T>.desc(): Order<T> = Order(this, SortDirection.DESCENDING)

/** Places SQL NULL values before non-NULL values where the selected dialect supports this syntax. */
fun <T> Order<T>.nullsFirst(): Order<T> = Order(expression, direction, NullOrdering.FIRST)

/** Places SQL NULL values after non-NULL values where the selected dialect supports this syntax. */
fun <T> Order<T>.nullsLast(): Order<T> = Order(expression, direction, NullOrdering.LAST)

/** The explicit SQL join kinds supported by the SELECT AST. */
enum class JoinType(internal val sql: String) {
    /** Inner join. */
    Inner("INNER JOIN"),

    /** Left outer join. */
    Left("LEFT JOIN"),

    /** Right outer join. */
    Right("RIGHT JOIN"),

    /** Full outer join. */
    Full("FULL OUTER JOIN"),

    /** Cartesian join without an ON condition. */
    Cross("CROSS JOIN"),

    /** A correlation-aware relation edge whose physical spelling belongs to the dialect. */
    CrossLateral(""),

    /** A null-supplying correlation-aware relation edge with no user-provided ON predicate. */
    LeftLateral(""),
}

/** The portable semantic kinds of a left-correlated derived-relation join.
 *
 * @property unsupportedCapability identifier used if the dialect cannot render this join.
 */
enum class LateralJoinType(val unsupportedCapability: String) {
    /** Cross-like correlated relation join. */
    Cross("lateral-cross"),

    /** Left-like correlated relation join. */
    Left("lateral-left"),
}

internal val JoinType.lateralType: LateralJoinType?
    get() = when (this) {
        JoinType.CrossLateral -> LateralJoinType.Cross
        JoinType.LeftLateral -> LateralJoinType.Left
        else -> null
    }

/** One immutable edge in a SELECT FROM tree.
 *
 * @property type kind of join.
 * @property right relation introduced by this join.
 * @property condition ON or USING condition, or `null` for CROSS and LATERAL joins.
 */
class Join internal constructor(
    val type: JoinType,
    val right: RelationSource,
    /** Null only for CROSS or LATERAL joins, which have no user-supplied condition in this AST. */
    val condition: JoinCondition?,
) {
    init {
        when (type) {
            JoinType.Cross -> require(condition == null && right !is LateralRelation) {
                "CROSS JOIN has no condition and cannot attach a lateral relation"
            }

            JoinType.CrossLateral,
            JoinType.LeftLateral,
            -> require(condition == null && right is LateralRelation) {
                "Lateral joins require a LateralRelation and have no user-supplied ON condition"
            }

            else -> require(condition != null && right !is LateralRelation) {
                "Ordinary joins require an ON or USING condition and cannot attach a lateral relation"
            }
        }
    }
}

/** Compile-time state that exposes only clauses valid at the current position. */
sealed interface SelectQueryState {
    /** A clause position where joins are permitted. */
    sealed interface JoinAllowed : SelectQueryState

    /** A clause position where WHERE is permitted. */
    sealed interface WhereAllowed : SelectQueryState

    /** A clause position where GROUP BY is permitted. */
    sealed interface GroupByAllowed : SelectQueryState

    /** A clause position where HAVING is permitted. */
    sealed interface HavingAllowed : SelectQueryState

    /** A clause position where ORDER BY is permitted. */
    sealed interface OrderByAllowed : SelectQueryState

    /** A clause position where LIMIT is permitted. */
    sealed interface LimitAllowed : SelectQueryState

    /** A clause position where OFFSET is permitted. */
    sealed interface OffsetAllowed : SelectQueryState

    /** Initial query state after a FROM source has been supplied. */
    sealed interface Start :
        JoinAllowed,
        WhereAllowed,
        GroupByAllowed,
        HavingAllowed,
        OrderByAllowed,
        LimitAllowed,
        OffsetAllowed

    /** A completed source-less SELECT which may optionally receive its one FROM source. */
    sealed interface SourceLessStart :
        WhereAllowed,
        GroupByAllowed,
        HavingAllowed,
        OrderByAllowed,
        LimitAllowed,
        OffsetAllowed

    /** Query state after WHERE has been added. */
    sealed interface AfterWhere :
        GroupByAllowed,
        HavingAllowed,
        OrderByAllowed,
        LimitAllowed,
        OffsetAllowed

    /** Query state after GROUP BY has been added. */
    sealed interface AfterGroupBy :
        HavingAllowed,
        OrderByAllowed,
        LimitAllowed,
        OffsetAllowed

    /** Query state after HAVING has been added. */
    sealed interface AfterHaving :
        OrderByAllowed,
        LimitAllowed,
        OffsetAllowed

    /** Query state after ORDER BY has been added. */
    sealed interface AfterOrderBy :
        OrderByAllowed,
        LimitAllowed,
        OffsetAllowed

    /** Query state after LIMIT has been added. */
    sealed interface AfterLimit : OffsetAllowed

    /** Query state after OFFSET has been added. */
    sealed interface AfterOffset : SelectQueryState
}

/** Compile-time projection cardinality retained while a SELECT is composed. */
sealed interface SelectProjectionShape

/** A SELECT with exactly one projected expression. */
data object SingleProjection : SelectProjectionShape

/** A SELECT with two or more projected expressions. */
data object MultipleProjections : SelectProjectionShape

/** An immutable SELECT query model with an optional base source and zero or more typed joins.
 *
 * @property joins ordered joins after the base source.
 * @property predicate optional WHERE predicate.
 * @property groupBy grouping elements.
 * @property having optional HAVING predicate.
 * @property order result ordering expressions.
 * @property limit maximum number of rows returned.
 * @property offset number of rows skipped.
 */
class SelectQuery<S : SelectQueryState, T, P : SelectProjectionShape> internal constructor(
    /** The first projection retains the query's scalar result type. */
    internal val scalarProjection: SqlExpression<T>,
    override val columns: List<SqlExpression<*>>,
    internal val fromSource: RelationSource?,
    val joins: List<Join> = emptyList(),
    val predicate: SqlPredicate? = null,
    /** Typed ordinary or advanced grouping elements, retained without SQL-string expansion. */
    val groupBy: List<GroupByElement> = emptyList(),
    val having: SqlPredicate? = null,
    val order: List<Order<*>> = emptyList(),
    val limit: Int? = null,
    val offset: Int? = null,
    /** Ordered non-recursive CTE definitions attached to this SELECT. */
    internal val withClause: List<CommonTableExpression> = emptyList(),
    /** Whether this query removes duplicate projected rows with SELECT DISTINCT. */
    val distinct: Boolean = false,
    /** Query-local named windows, rendered after HAVING and before the outer ORDER BY. */
    val windows: List<NamedWindow> = emptyList(),
    /** PostgreSQL-style DISTINCT ON expressions, exposed only through dialect extensions. */
    internal val distinctOn: List<SqlExpression<*>> = emptyList(),
) : QueryExpression<T, P>,
    WithCteClause<SelectQuery<S, T, P>> {
    override fun withCtes(vararg definitions: CommonTableExpression): SelectQuery<S, T, P> = with(*definitions)

    /** Compatibility view of the underlying schema table used by the base relation source. */
    val from: Table
        get() = (fromSource as? TableRelationSource)?.table
            ?: error("The FROM source is not a physical table: $fromSource")

    /** Adds [table] as the base FROM source. */
    fun from(table: Table): SelectQuery<SelectQueryState.Start, T, P> = from(table as RelationSource)

    /** Adds the one base source to a source-less SELECT. */
    fun from(source: RelationSource): SelectQuery<SelectQueryState.Start, T, P> {
        require(source !is LateralRelation) { "Lateral relations require crossJoinLateral or leftJoinLateral" }
        require(
            fromSource == null && joins.isEmpty() && predicate == null && groupBy.isEmpty() && having == null &&
                order.isEmpty() && limit == null && offset == null,
        ) { "FROM can only be added to a fresh source-less SELECT" }
        return SelectQuery(
            scalarProjection,
            columns,
            source,
            joins,
            predicate,
            groupBy,
            having,
            order,
            limit,
            offset,
            withClause,
            distinct,
            windows,
            distinctOn = distinctOn,
        )
    }

    internal val sources: List<RelationSource> get() = listOfNotNull(fromSource) + joins.map(Join::right)

    internal fun requireSources(
        references: Iterable<RelationReference>,
        clause: String,
        allowedSources: List<RelationSource> = sources,
    ) {
        val invalid = references.firstOrNull { reference ->
            allowedSources.none { it === reference.source } || !reference.source.contains(reference.expression)
        } ?: return
        throw IllegalArgumentException(
            "$clause reference is not registered by the query scope: ${invalid.expression}",
        )
    }

    /** Validates the whole completed FROM tree immediately before a query is rendered or executed. */
    @Suppress("CyclomaticComplexMethod") // One validation boundary intentionally covers every completed SELECT clause.
    internal fun requireSelectValidScope(
        parentSources: List<RelationSource> = emptyList(),
        availableCtes: List<CommonTableExpression> = emptyList(),
        recursiveSelf: RecursiveCteSelf? = null,
    ) {
        validateCteDefinitions(withClause, availableCtes)
        val visibleCtes = availableCtes + withClause
        requireCteSources(visibleCtes, "SELECT")
        requireRecursiveSelfSource(recursiveSelf)
        val visibleSources = sources + parentSources
        columns.forEach { expression ->
            require(!expression.containsIncompleteWindowFunction()) {
                "SELECT window functions must be completed with .over { ... }"
            }
        }
        distinctOn.forEach { expression ->
            require(!expression.containsIncompleteWindowFunction()) {
                "DISTINCT ON cannot contain an incomplete window function"
            }
            requireSources(expression.relationReferences(), "DISTINCT ON", visibleSources)
        }
        order.forEach { entry ->
            require(!entry.expression.containsIncompleteWindowFunction()) {
                "ORDER BY window functions must be completed with .over { ... }"
            }
        }
        requireSources(columns.flatMap(SqlExpression<*>::relationReferences), "SELECT", visibleSources)
        validateNamedWindows(visibleSources)
        predicate?.let { requireSources(it.relationReferences(), "WHERE", visibleSources) }
        groupBy.flatMap(GroupByElement::groupingExpressions).forEach { grouped ->
            requireSources(grouped.relationReferences(), "GROUP BY", visibleSources)
        }
        having?.let { requireSources(it.relationReferences(), "HAVING", visibleSources) }
        order.forEach { requireSources(it.expression.relationReferences(), "ORDER BY", visibleSources) }
        joins.forEach { join ->
            join.condition?.let { condition -> requireSources(condition.relationReferences(), "JOIN", visibleSources) }
        }

        requirePortableGroupedProjections()

        requireRelationScopes(visibleCtes)
        val nestedParentSources = visibleSources.filterNot { it === recursiveSelf }
        nestedQueries().forEach { nested -> nested.requireValidScope(nestedParentSources, visibleCtes) }
        windows.flatMap { named -> named.specification.expressions() }
            .flatMap(SqlExpression<*>::nestedQueries)
            .forEach { nested -> nested.requireValidScope(nestedParentSources, visibleCtes) }

        val nullableSources = nullableSources()
        columns.forEach { expression ->
            val usesOuterSource = expression.relationReferences().any { nullableSources[it.source] == true }
            require(!usesOuterSource || expression.isNullable || expression.isOuterJoinSafe(nullableSources)) {
                "SELECT expression can be NULL because an outer join preserves one of its sources; " +
                    "select that source through relation[column].optional"
            }
        }
    }

    private fun validateNamedWindows(visibleSources: List<RelationSource>) {
        val duplicate = windows.groupBy(NamedWindow::name).keys.firstOrNull { candidate ->
            windows.count { it.name == candidate } > 1
        }
        require(duplicate == null) { "WINDOW clause contains duplicate window name: $duplicate" }
        windows.forEach { named ->
            requireSources(
                named.specification.expressions().flatMap(SqlExpression<*>::relationReferences),
                "WINDOW ${named.name}",
                visibleSources,
            )
        }
        val referenced = (columns + order.map { it.expression }).flatMap(SqlExpression<*>::namedWindows)
        referenced.forEach { named ->
            require(windows.any { it === named }) {
                "Named window is not registered by this query: ${named.name}"
            }
        }
    }

    /** Validates each relation body with the lexical scope that is valid at its declaration. */
    private fun requireRelationScopes(visibleCtes: List<CommonTableExpression>) {
        sources.forEachIndexed { index, source ->
            when (source) {
                is DerivedTable -> source.query.requireValidScope(availableCtes = visibleCtes)
                is LateralRelation -> source.requireValidLeftScope(sources.take(index), visibleCtes)
            }
        }
    }

    /** Rejects CTE relations unless their definition is attached to this lexical WITH scope. */
    internal fun requireCteSources(availableCtes: List<CommonTableExpression>, clause: String) {
        val unavailable = sources.filterIsInstance<CommonTableExpression>().firstOrNull { source ->
            availableCtes.none { it === source }
        }
        require(unavailable == null) {
            "$clause CTE source is not registered by this WITH scope: ${requireNotNull(unavailable).name}"
        }
        val shadowedTable = sources.filterIsInstance<TableRelationSource>().firstOrNull { source ->
            source.table.schemaName == null && availableCtes.any { cte -> cte.name == source.table.tableName }
        }
        require(shadowedTable == null) {
            "$clause physical table source is shadowed by a CTE in this WITH scope: " +
                requireNotNull(shadowedTable).table.tableName
        }
    }

    /**
     * Recursive self references are valid only in their defining member, once, and only as a
     * direct source. Nested queries and derived tables are independently validated without this
     * token, which rejects indirect recursive references instead of relying on dialect quirks.
     */
    private fun requireRecursiveSelfSource(recursiveSelf: RecursiveCteSelf?) {
        val recursiveSources = sources.filterIsInstance<RecursiveCteSelf>()
        if (recursiveSelf == null) {
            require(recursiveSources.isEmpty()) {
                "Recursive CTE self relation is only valid inside its recursive member"
            }
            return
        }
        require(recursiveSources.size == 1 && recursiveSources.single() === recursiveSelf) {
            "Recursive CTE member must reference its self relation exactly once as a direct FROM or JOIN source"
        }
    }

    /** Whether an outer edge can synthesize a NULL row for each source in this completed join tree. */
    internal fun nullableSources(): Map<RelationSource, Boolean> {
        val nullable = mutableMapOf<RelationSource, Boolean>()
        fromSource?.let { nullable[it] = false }
        joins.forEach { join ->
            when (join.type) {
                JoinType.Inner -> nullable[join.right] = false

                JoinType.Cross -> nullable[join.right] = false

                JoinType.CrossLateral -> nullable[join.right] = false

                JoinType.Left -> nullable[join.right] = true

                JoinType.LeftLateral -> nullable[join.right] = true

                JoinType.Right -> {
                    nullable.keys.toList().forEach { nullable[it] = true }
                    nullable[join.right] = false
                }

                JoinType.Full -> {
                    nullable.keys.toList().forEach { nullable[it] = true }
                    nullable[join.right] = true
                }
            }
        }
        return nullable
    }
}

private fun SqlExpression<*>.isOuterJoinSafe(nullableSources: Map<RelationSource, Boolean>): Boolean = when (this) {
    is SqlFunctionExpression<*> -> when (function) {
        SqlFunction.Count -> true

        SqlFunction.Coalesce -> arguments.any { argument ->
            !argument.isNullable && argument.relationReferences().none { nullableSources[it.source] == true }
        }

        else -> false
    }

    else -> false
}

/** Starts a source-less SELECT with one typed projection. */
fun <T> select(expression: SqlExpression<T>): SelectQuery<SelectQueryState.SourceLessStart, T, SingleProjection> =
    SelectQuery(expression, listOf(expression), fromSource = null)

/** Selects the select result. */
fun <T> select(
    expression: SqlExpression<T>,
    additionalExpression: SqlExpression<*>,
    vararg additionalExpressions: SqlExpression<*>,
): SelectQuery<SelectQueryState.SourceLessStart, T, MultipleProjections> =
    SelectQuery(expression, listOf(expression, additionalExpression) + additionalExpressions, fromSource = null)

/** Removes duplicate projected rows without changing the projection or SELECT clause typestate. */
fun <S : SelectQueryState, T, P : SelectProjectionShape> SelectQuery<S, T, P>.distinct(): SelectQuery<S, T, P> {
    require(!distinct) { "SELECT query already has DISTINCT" }
    require(distinctOn.isEmpty()) { "SELECT query already has DISTINCT ON" }
    return SelectQuery(
        scalarProjection,
        columns,
        fromSource,
        joins,
        predicate,
        groupBy,
        having,
        order,
        limit,
        offset,
        withClause,
        distinct = true,
        windows = windows,
        distinctOn = distinctOn,
    )
}

/**
 * Retains dialect-specific DISTINCT ON expressions in the shared SELECT model.
 *
 * Dialect modules expose their own public syntax and select a renderer that supports it.
 */
fun <S : SelectQueryState, T, P : SelectProjectionShape> SelectQuery<S, T, P>.withDistinctOn(
    expression: SqlExpression<*>,
    vararg additionalExpressions: SqlExpression<*>,
): SelectQuery<S, T, P> {
    require(!distinct) { "SELECT query already has DISTINCT" }
    require(distinctOn.isEmpty()) { "SELECT query already has DISTINCT ON" }
    return SelectQuery(
        scalarProjection,
        columns,
        fromSource,
        joins,
        predicate,
        groupBy,
        having,
        order,
        limit,
        offset,
        withClause,
        distinct,
        windows,
        distinctOn = listOf(expression) + additionalExpressions,
    )
}

/**
 * Registers complete named window definitions for this query only. The same [NamedWindow] can be
 * used by several `over(namedWindow)` expressions, but it never escapes into a derived query,
 * CTE, set operand, or outer query.
 */
fun <S : SelectQueryState, T, P : SelectProjectionShape> SelectQuery<S, T, P>.window(
    definition: NamedWindow,
    vararg additionalDefinitions: NamedWindow,
): SelectQuery<S, T, P> {
    val additions = listOf(definition) + additionalDefinitions
    val all = windows + additions
    val duplicate = all.groupBy(NamedWindow::name).keys.firstOrNull { candidate ->
        all.count { it.name == candidate } > 1
    }
    require(duplicate == null) { "WINDOW clause contains duplicate window name: $duplicate" }
    return SelectQuery(
        scalarProjection,
        columns,
        fromSource,
        joins,
        predicate,
        groupBy,
        having,
        order,
        limit,
        offset,
        withClause,
        distinct,
        all,
        distinctOn = distinctOn,
    )
}

/** Adds an INNER JOIN using [on] as its ON condition. */
fun <S, T, P : SelectProjectionShape> SelectQuery<S, T, P>.innerJoin(
    source: RelationSource,
    on: SqlPredicate,
): SelectQuery<S, T, P> where S : SelectQueryState, S : SelectQueryState.JoinAllowed = join(JoinType.Inner, source, on)

/** Adds an INNER JOIN whose explicitly paired relation columns are rendered as native USING syntax. */
fun <S, T, P : SelectProjectionShape> SelectQuery<S, T, P>.innerJoinUsing(
    source: RelationSource,
    using: JoinUsingBuilder.() -> Unit,
): SelectQuery<S, T, P> where S : SelectQueryState, S : SelectQueryState.JoinAllowed =
    joinUsing(JoinType.Inner, source, using)

/** Adds a LEFT JOIN using [on] as its ON condition. */
fun <S, T, P : SelectProjectionShape> SelectQuery<S, T, P>.leftJoin(
    source: RelationSource,
    on: SqlPredicate,
): SelectQuery<S, T, P> where S : SelectQueryState, S : SelectQueryState.JoinAllowed = join(JoinType.Left, source, on)

/** Adds a LEFT JOIN whose explicitly paired relation columns are rendered as native USING syntax. */
fun <S, T, P : SelectProjectionShape> SelectQuery<S, T, P>.leftJoinUsing(
    source: RelationSource,
    using: JoinUsingBuilder.() -> Unit,
): SelectQuery<S, T, P> where S : SelectQueryState, S : SelectQueryState.JoinAllowed =
    joinUsing(JoinType.Left, source, using)

/** Adds a RIGHT JOIN using [on] as its ON condition. */
fun <S, T, P : SelectProjectionShape> SelectQuery<S, T, P>.rightJoin(
    source: RelationSource,
    on: SqlPredicate,
): SelectQuery<S, T, P> where S : SelectQueryState, S : SelectQueryState.JoinAllowed = join(JoinType.Right, source, on)

/** Adds a RIGHT JOIN whose explicitly paired relation columns are rendered as native USING syntax. */
fun <S, T, P : SelectProjectionShape> SelectQuery<S, T, P>.rightJoinUsing(
    source: RelationSource,
    using: JoinUsingBuilder.() -> Unit,
): SelectQuery<S, T, P> where S : SelectQueryState, S : SelectQueryState.JoinAllowed =
    joinUsing(JoinType.Right, source, using)

/** Adds a FULL OUTER JOIN using [on] as its ON condition. */
fun <S, T, P : SelectProjectionShape> SelectQuery<S, T, P>.fullOuterJoin(
    source: RelationSource,
    on: SqlPredicate,
): SelectQuery<S, T, P> where S : SelectQueryState, S : SelectQueryState.JoinAllowed = join(JoinType.Full, source, on)

/** Adds a FULL OUTER JOIN whose explicitly paired relation columns are rendered as native USING syntax. */
fun <S, T, P : SelectProjectionShape> SelectQuery<S, T, P>.fullOuterJoinUsing(
    source: RelationSource,
    using: JoinUsingBuilder.() -> Unit,
): SelectQuery<S, T, P> where S : SelectQueryState, S : SelectQueryState.JoinAllowed =
    joinUsing(JoinType.Full, source, using)

/** Adds a native CROSS JOIN without an ON condition. */
fun <S, T, P : SelectProjectionShape> SelectQuery<S, T, P>.crossJoin(
    source: RelationSource,
): SelectQuery<S, T, P> where S : SelectQueryState, S : SelectQueryState.JoinAllowed =
    appendJoin(Join(JoinType.Cross, source, condition = null))

/** Adds an inner-like correlated derived relation that removes outer rows with no body result. */
fun <S, T, P : SelectProjectionShape> SelectQuery<S, T, P>.crossJoinLateral(
    relation: LateralRelation,
): SelectQuery<S, T, P> where S : SelectQueryState, S : SelectQueryState.JoinAllowed =
    appendJoin(Join(JoinType.CrossLateral, relation, condition = null))

/** Adds a left-like correlated derived relation with renderer-owned neutral join syntax where needed. */
fun <S, T, P : SelectProjectionShape> SelectQuery<S, T, P>.leftJoinLateral(
    relation: LateralRelation,
): SelectQuery<S, T, P> where S : SelectQueryState, S : SelectQueryState.JoinAllowed =
    appendJoin(Join(JoinType.LeftLateral, relation, condition = null))

private fun <S, T, P : SelectProjectionShape> SelectQuery<S, T, P>.join(
    type: JoinType,
    source: RelationSource,
    on: SqlPredicate,
): SelectQuery<S, T, P>
    where S : SelectQueryState, S : SelectQueryState.JoinAllowed {
    require(fromSource != null) { "JOIN requires a FROM source" }
    require(sources.none { it === source }) { "Relation source is already present in this query: $source" }
    requireNoAmbiguousSourceName(source)
    val references = on.relationReferences()
    require(references.any { it.source === source }) { "JOIN condition must reference the joined source: $source" }
    require(!on.containsAggregate()) { "JOIN condition cannot contain an aggregate expression" }
    require(!on.containsWindowExpression()) { "JOIN condition cannot contain a window expression" }
    return appendJoin(Join(type, source, JoinCondition.On(on)))
}

private fun <S, T, P : SelectProjectionShape> SelectQuery<S, T, P>.joinUsing(
    type: JoinType,
    source: RelationSource,
    build: JoinUsingBuilder.() -> Unit,
): SelectQuery<S, T, P>
    where S : SelectQueryState, S : SelectQueryState.JoinAllowed {
    require(fromSource != null) { "JOIN requires a FROM source" }
    require(source !is LateralRelation) { "Lateral relations require crossJoinLateral or leftJoinLateral" }
    require(sources.none { it === source }) { "Relation source is already present in this query: $source" }
    requireNoAmbiguousSourceName(source)
    val condition = JoinUsingBuilder(sources, source).apply(build).build()
    return appendJoin(Join(type, source, condition))
}

private fun <S, T, P : SelectProjectionShape> SelectQuery<S, T, P>.appendJoin(
    join: Join,
): SelectQuery<S, T, P>
    where S : SelectQueryState, S : SelectQueryState.JoinAllowed {
    val source = join.right
    require(fromSource != null) { "JOIN requires a FROM source" }
    require(sources.none { it === source }) { "Relation source is already present in this query: $source" }
    requireNoAmbiguousSourceName(source)
    if (source is LateralRelation) {
        require(join.type.lateralType != null) { "Lateral relations require crossJoinLateral or leftJoinLateral" }
        source.requireLeftDependencies(sources)
    } else {
        require(join.type.lateralType == null) { "Lateral joins require a LateralRelation" }
    }
    return SelectQuery(
        scalarProjection,
        columns,
        fromSource,
        joins + join,
        predicate,
        groupBy,
        having,
        order,
        limit,
        offset,
        withClause,
        distinct,
        windows,
        distinctOn = distinctOn,
    )
}

private fun SelectQuery<*, *, *>.requireNoAmbiguousSourceName(candidate: RelationSource) {
    val candidateName = candidate.alias ?: candidate.sourceName
    val existingAliasCollision = sources.any { source ->
        (source.alias != null || candidate.alias != null) && (source.alias ?: source.sourceName) == candidateName
    }
    require(!existingAliasCollision) { "Relation source name is ambiguous in this query: $candidateName" }
}

/** Restricts result rows to those matching [predicate]. */
fun <S : SelectQueryState.WhereAllowed, T, P : SelectProjectionShape> SelectQuery<S, T, P>.where(
    predicate: SqlPredicate,
): SelectQuery<SelectQueryState.AfterWhere, T, P> = SelectQuery(
    scalarProjection,
    columns,
    fromSource,
    joins,
    predicate.also {
        require(
            !it.containsAggregate(),
        ) { "WHERE cannot contain an aggregate expression; use HAVING or a derived table or CTE" }
        require(
            !it.containsWindowExpression(),
        ) { "WHERE cannot contain a window expression; use a derived table or CTE" }
    },
    groupBy,
    having,
    order,
    limit,
    offset,
    withClause,
    distinct,
    windows,
    distinctOn = distinctOn,
)

/**
 * Groups this query by one or more typed, non-aggregate expressions.
 *
 * The expressions remain part of the existing SELECT AST so source ownership, derived-table
 * references, parameter rendering, and future SELECT consumers use the same model.
 */
fun <S : SelectQueryState.GroupByAllowed, T, P : SelectProjectionShape> SelectQuery<S, T, P>.groupBy(
    element: GroupByElement,
    vararg additionalElements: GroupByElement,
): SelectQuery<SelectQueryState.AfterGroupBy, T, P> {
    val elements = listOf(element) + additionalElements
    require(elements.none { it is CompositeGroupingItem }) {
        "A composite grouping item is only valid inside ROLLUP, CUBE, or groupingSet"
    }
    elements.flatMap(GroupByElement::groupingExpressions).forEach { grouped ->
        require(grouped.isGroupable()) { "GROUP BY does not accept scalar subqueries: $grouped" }
        require(!grouped.containsAggregate()) { "GROUP BY does not accept aggregate expressions: $grouped" }
        require(!grouped.containsWindowExpression()) { "GROUP BY does not accept window expressions" }
    }
    return SelectQuery(
        scalarProjection,
        columns,
        fromSource,
        joins,
        predicate,
        elements,
        having,
        order,
        limit,
        offset,
        withClause,
        distinct,
        windows,
        distinctOn = distinctOn,
    )
}

/** Filters groups with a typed SQL Boolean predicate. */
fun <S : SelectQueryState.HavingAllowed, T, P : SelectProjectionShape> SelectQuery<S, T, P>.having(
    predicate: SqlPredicate,
): SelectQuery<SelectQueryState.AfterHaving, T, P> = SelectQuery(
    scalarProjection,
    columns,
    fromSource,
    joins,
    this.predicate,
    groupBy,
    predicate.also {
        require(
            !it.containsWindowExpression(),
        ) { "HAVING cannot contain a window expression; use a derived table or CTE" }
    },
    order,
    limit,
    offset,
    withClause,
    distinct,
    windows,
    distinctOn = distinctOn,
)

/** Orders the result rows by one or more typed expressions. */
fun <S : SelectQueryState.OrderByAllowed, T, P : SelectProjectionShape> SelectQuery<S, T, P>.orderBy(
    order: Order<*>,
    vararg additionalOrders: Order<*>,
): SelectQuery<SelectQueryState.AfterOrderBy, T, P> {
    val orders = listOf(order) + additionalOrders
    return SelectQuery(
        scalarProjection,
        columns,
        fromSource,
        joins,
        predicate,
        groupBy,
        having,
        this.order + orders,
        limit,
        offset,
        withClause,
        distinct,
        windows,
        distinctOn = distinctOn,
    )
}

/** Limits the number of result rows. */
fun <S : SelectQueryState.LimitAllowed, T, P : SelectProjectionShape> SelectQuery<S, T, P>.limit(
    limit: Int,
): SelectQuery<SelectQueryState.AfterLimit, T, P> {
    require(limit >= 0) { "LIMIT must not be negative" }
    return SelectQuery(
        scalarProjection,
        columns,
        fromSource,
        joins,
        predicate,
        groupBy,
        having,
        order,
        limit,
        offset,
        withClause,
        distinct,
        windows,
        distinctOn = distinctOn,
    )
}

/** Skips the specified number of result rows. */
fun <S : SelectQueryState.OffsetAllowed, T, P : SelectProjectionShape> SelectQuery<S, T, P>.offset(
    offset: Int,
): SelectQuery<SelectQueryState.AfterOffset, T, P> {
    require(offset >= 0) { "OFFSET must not be negative" }
    return SelectQuery(
        scalarProjection,
        columns,
        fromSource,
        joins,
        predicate,
        groupBy,
        having,
        order,
        limit,
        offset,
        withClause,
        distinct,
        windows,
        distinctOn = distinctOn,
    )
}
