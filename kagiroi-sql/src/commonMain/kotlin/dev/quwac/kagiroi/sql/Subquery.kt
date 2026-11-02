package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.BooleanExpression
import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.schema.ColumnType
import dev.quwac.kagiroi.schema.GroupByElement
import dev.quwac.kagiroi.schema.NamedRelationColumn
import dev.quwac.kagiroi.schema.NullableRelationColumn
import dev.quwac.kagiroi.schema.NullableSqlExpression
import dev.quwac.kagiroi.schema.RelationColumn
import dev.quwac.kagiroi.schema.RelationSource
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.SqlExpressionAlias

/** A one-column SELECT used as a nullable scalar SQL expression. */
class ScalarSubquery<T> internal constructor(
    internal val query: QueryExpression<*, SingleProjection>,
    private val projection: SqlExpression<*>,
) : SqlExpression<T> {

    override val referencedColumns: List<Column<*>> get() = query.referencedColumns()
    override val type: ColumnType<*> get() = projection.type
    override val storageType: ColumnType<*> get() = projection.storageType
    override val isNullable: Boolean get() = true

    @Suppress("UNCHECKED_CAST") // The projection fixes the scalar subquery's logical type.
    override fun toStorageValue(value: T): Any? =
        if (value == null) null else (projection as SqlExpression<T>).toStorageValue(value)

    @Suppress("UNCHECKED_CAST") // SQL scalar subqueries produce NULL when they return no rows.
    override fun fromStorageValue(value: Any?): T =
        if (value == null) null as T else (projection as SqlExpression<T>).fromStorageValue(value)
}

/** Returns a scalar expression only when this query has exactly one projection. */
fun <T> QueryExpression<T, SingleProjection>.scalar(): ScalarSubquery<T?> = ScalarSubquery(this, columns.single())

/** A typed SQL predicate whose left expression is tested against one subquery projection. */
class InSubqueryPredicate internal constructor(
    val expression: SqlExpression<*>,
    internal val query: QueryExpression<*, SingleProjection>,
    val negated: Boolean,
) : BooleanExpression {
    override val referencedColumns: List<Column<*>> get() = expression.referencedColumns + query.referencedColumns()
}

/** A typed SQL EXISTS or NOT EXISTS predicate. */
class ExistsPredicate internal constructor(internal val query: QueryExpression<*, *>, val negated: Boolean) :
    BooleanExpression {
    override val referencedColumns: List<Column<*>> get() = query.referencedColumns()
}

/** Tests this expression against the single projection of [query]. */
fun <T> SqlExpression<T>.inSubquery(query: QueryExpression<T, SingleProjection>): SqlPredicate =
    InSubqueryPredicate(this, query, negated = false)

/** Tests this nullable expression against the single non-null projection of [query]. */
fun <T : Any> SqlExpression<T?>.inSubquery(query: QueryExpression<T, SingleProjection>): SqlPredicate =
    InSubqueryPredicate(this, query, negated = false)

/** Tests this expression against the single projection of [query] using SQL NOT IN semantics. */
fun <T> SqlExpression<T>.notInSubquery(query: QueryExpression<T, SingleProjection>): SqlPredicate =
    InSubqueryPredicate(this, query, negated = true)

/** Tests this nullable expression against the single non-null projection of [query] using SQL NOT IN semantics. */
fun <T : Any> SqlExpression<T?>.notInSubquery(query: QueryExpression<T, SingleProjection>): SqlPredicate =
    InSubqueryPredicate(this, query, negated = true)

/** Returns true when [query] produces at least one row. */
fun exists(query: QueryExpression<*, *>): SqlPredicate = ExistsPredicate(query, negated = false)

/** Returns true when [query] produces no rows. */
fun notExists(query: QueryExpression<*, *>): SqlPredicate = ExistsPredicate(query, negated = true)

/**
 * A named relation whose columns are projections from a SELECT result.
 *
 * Derived tables and common table expressions deliberately share this relation and projection
 * model. Their SQL placement is different, but a typed result projection has the same ownership,
 * nullability, storage mapping, and row-access behavior in either case.
 */
abstract class QueryResultRelation internal constructor(internal val query: QueryExpression<*, *>) : RelationSource {
    private val projections: List<Pair<SqlExpression<*>, String>> = query.columns.map { projection ->
        projection to projectionName(projection)
    }

    init {
        val duplicate = projections.groupBy(Pair<SqlExpression<*>, String>::second).keys.firstOrNull { name ->
            projections.count { it.second == name } > 1
        }
        require(duplicate == null) {
            "Query result projections must have distinct output names; duplicate: $duplicate"
        }
    }

    final override fun contains(expression: SqlExpression<*>): Boolean = projections.any { it.first === expression }

    /** References one selected projection while preserving its Kotlin result type. */
    operator fun <T> get(projection: SqlExpression<T>): DerivedColumn<T> {
        val name = projections.firstOrNull { it.first === projection }?.second
            ?: throw IllegalArgumentException("Expression is not projected by query result $sourceName: $projection")
        return DerivedColumn(this, projection, name)
    }
}

/** A SELECT used as an aliased FROM source. */
class DerivedTable internal constructor(query: QueryExpression<*, *>, override val alias: String) :
    QueryResultRelation(query) {
    init {
        require(alias.isNotBlank()) { "Derived table alias must not be blank" }
    }

    override val sourceName: String get() = alias

    override fun toString(): String = "($query) AS $alias"
}

/**
 * Explicit construction scope for a derived relation which may depend on relations to its left.
 *
 * The scope does not change normal SELECT validation. It marks the returned [LateralRelation] as
 * the one query-result relation form whose direct non-local source references are retained for
 * validation at the point where it is attached to a FROM tree.
 */
class LateralScope internal constructor()

/**
 * A named, correlated derived relation whose body may reference relations introduced to its left.
 *
 * This remains a [QueryResultRelation], so output naming, projection identity, typed
 * `relation[projection]` access, and outer-join nullable handles are identical to a normal
 * [DerivedTable]. The recorded [outerDependencies] are source identities, not SQL parameters.
 */
class LateralRelation internal constructor(query: QueryExpression<*, *>, override val alias: String) :
    QueryResultRelation(query) {
    /** Direct non-local relation sources used by this relation body, in first-reference order. */
    internal val outerDependencies: List<RelationSource> = query.directOuterDependencies()

    init {
        require(alias.isNotBlank()) { "Lateral relation alias must not be blank" }
    }

    override val sourceName: String get() = alias

    override fun toString(): String = "LATERAL ($query) AS $alias"

    /**
     * Validates the relation at its join point instead of widening normal derived-table scope.
     * Only directly recorded dependencies are passed into the body as correlation parents.
     */
    internal fun requireValidLeftScope(
        precedingSources: List<RelationSource>,
        availableCtes: List<CommonTableExpression>,
    ) {
        requireLeftDependencies(precedingSources)
        query.requireValidScope(outerDependencies, availableCtes)
    }

    /** Checks the join-point rule without prematurely resolving an outer lexical WITH scope. */
    internal fun requireLeftDependencies(precedingSources: List<RelationSource>) {
        val unavailable = outerDependencies.firstOrNull { dependency ->
            precedingSources.none { source -> source === dependency }
        }
        require(unavailable == null) {
            "Lateral relation references a source that is not available to its left: " +
                requireNotNull(unavailable).sourceName
        }
    }
}

/**
 * Creates a correlated derived relation with an explicit alias and typed projection handles.
 *
 * Unlike [QueryExpression.asTable], this construction records direct references that are not
 * local to [body]. They must be supplied by [crossJoinLateral] or [leftJoinLateral] from the
 * left side of the final query.
 */
fun lateral(alias: String, body: LateralScope.() -> QueryExpression<*, *>): LateralRelation =
    LateralRelation(LateralScope().body(), alias)

/**
 * A typed projection reference qualified by a [QueryResultRelation].
 *
 * The historical name remains because the same projection handle is used by both derived tables
 * and CTEs; no CTE-specific column representation is introduced.
 */
class DerivedColumn<T> internal constructor(
    val source: QueryResultRelation,
    internal val projection: SqlExpression<T>,
    val name: String,
) : SqlExpression<T>,
    NamedRelationColumn<T & Any> {
    override val referencedColumns: List<Column<*>> get() = projection.referencedColumns
    override val type: ColumnType<*> get() = projection.type
    override val storageType: ColumnType<*> get() = projection.storageType
    override val isNullable: Boolean get() = projection.isNullable

    override fun toStorageValue(value: T): Any? = projection.toStorageValue(value)

    override fun fromStorageValue(value: Any?): T = projection.fromStorageValue(value)

    override fun toString(): String = "$source.$name"
}

/** A derived-table projection made nullable by an outer join. */
class NullableDerivedColumn<T> internal constructor(val column: DerivedColumn<T>) : SqlExpression<T?> {
    val source: QueryResultRelation get() = column.source
    val name: String get() = column.name

    override val referencedColumns: List<Column<*>> get() = column.referencedColumns
    override val type: ColumnType<*> get() = column.type
    override val storageType: ColumnType<*> get() = column.storageType
    override val isNullable: Boolean get() = true

    override fun toStorageValue(value: T?): Any? = if (value == null) null else column.toStorageValue(value)

    override fun fromStorageValue(value: Any?): T? = if (value == null) null else column.fromStorageValue(value)

    override fun toString(): String = "$column?"
}

/** Marks a derived-table projection nullable because an outer join can synthesize SQL NULL. */
val <T> DerivedColumn<T>.optional: NullableDerivedColumn<T>
    get() = NullableDerivedColumn(this)

internal fun projectionName(projection: SqlExpression<*>): String = when (projection) {
    is SqlExpressionAlias<*> -> projection.name

    is Column<*> -> projection.name

    is RelationColumn<*> -> projection.column.name

    is NullableRelationColumn<*> -> projection.column.name

    is DerivedColumn<*> -> projection.name

    is NullableDerivedColumn<*> -> projection.name

    is NullableSqlExpression<*> -> projectionName(projection.expression)

    else -> throw IllegalArgumentException(
        "Query result projection requires an explicit alias: $projection",
    )
}

internal fun SelectQuery<*, *, *>.referencedSelectColumns(): List<Column<*>> = buildList {
    addAll(columns.flatMap(SqlExpression<*>::referencedColumns))
    predicate?.let { addAll(it.referencedColumns) }
    groupBy.flatMap(GroupByElement::groupingExpressions).forEach { addAll(it.referencedColumns) }
    having?.let { addAll(it.referencedColumns) }
    joins.forEach { join -> join.condition?.let { condition -> addAll(condition.referencedColumns()) } }
    order.forEach { addAll(it.expression.referencedColumns) }
}
