package dev.quwac.kagiroi.sql

/**
 * A named SELECT result relation for a [with] statement scope.
 *
 * Its name is always rendered as a quoted identifier. Keep names in application-owned source
 * code; this API does not make externally supplied identifiers safe to use as application input.
 */
open class CommonTableExpression internal constructor(val name: String, query: QueryExpression<*, *>) :
    QueryResultRelation(query) {
    init {
        require(name.isNotBlank()) { "CTE name must not be blank" }
    }

    /** The CTE name is the relation name used by its enclosing WITH query. */
    override val alias: String get() = name

    override val sourceName: String get() = name

    /** Whether this definition supplies one direct recursive member to its WITH clause. */
    internal open val isRecursive: Boolean get() = false

    override fun toString(): String = "WITH $name AS ($query)"
}

/**
 * The private-to-a-recursive-member relation that represents the recursive CTE's current rows.
 *
 * It deliberately is not the completed [RecursiveCommonTableExpression]. Both relations retain
 * the anchor projection schema, but scope validation permits this source only while rendering the
 * recursive member that created it. Keeping the two relation objects separate prevents a normal
 * WITH scope from accidentally treating an unfinished recursive reference as a CTE definition.
 */
class RecursiveCteSelf internal constructor(private val cteName: String, anchor: QueryExpression<*, *>) :
    QueryResultRelation(anchor) {
    override val alias: String get() = cteName

    override val sourceName: String get() = cteName

    override fun toString(): String = "recursive self $cteName"
}

/**
 * A direct recursive CTE AST with an anchor schema and one `UNION ALL` recursive member.
 *
 * The inherited query result relation is intentionally the anchor. Its projection handles are the
 * completed CTE's schema and row-mapping identity; aliases in [recursive] cannot change them.
 */
class RecursiveCommonTableExpression internal constructor(
    name: String,
    anchor: QueryExpression<*, *>,
    internal val recursive: QueryExpression<*, *>,
    internal val self: RecursiveCteSelf,
) : CommonTableExpression(name, anchor) {
    internal override val isRecursive: Boolean get() = true

    internal val anchor: QueryExpression<*, *> get() = query

    override fun toString(): String = "WITH $name AS ($anchor UNION ALL $recursive)"
}

/**
 * Creates a direct recursive CTE whose output schema is fixed by [anchor].
 *
 * [recursive] receives a typed self relation with the anchor's projection handles. The relation
 * can occur exactly once as a direct FROM or JOIN source in the returned member. The completed
 * result is an ordinary [CommonTableExpression] relation and is used through [with].
 */
fun <T, P : SelectProjectionShape> recursiveCte(
    name: String,
    anchor: QueryExpression<T, P>,
    recursive: (RecursiveCteSelf) -> QueryExpression<T, P>,
): RecursiveCommonTableExpression {
    require(name.isNotBlank()) { "CTE name must not be blank" }
    val self = RecursiveCteSelf(name, anchor)
    val member = recursive(self)
    requireSetOperationCompatibility(anchor, member)
    requirePortableSetOperand(anchor)
    requirePortableSetOperand(member)
    return RecursiveCommonTableExpression(name, anchor, member, self)
}

/** A query value to which an ordered WITH clause can be attached. */
interface WithCteClause<Q> {
    fun withCtes(vararg definitions: CommonTableExpression): Q
}

/**
 * Attaches one or more CTE definitions to this SELECT.
 *
 * Definitions retain their supplied order. Each body can use only CTEs preceding it, while the
 * completed SELECT can use every supplied definition.
 */
fun <S : SelectQueryState, T, P : SelectProjectionShape> SelectQuery<S, T, P>.with(
    vararg definitions: CommonTableExpression,
): SelectQuery<S, T, P> {
    require(withClause.isEmpty()) { "SELECT already has a WITH clause" }
    val ctes = definitions.toList()
    validateCteDefinitions(ctes)
    requireCteSources(ctes, "SELECT")
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
        ctes,
        distinct,
        windows,
        distinctOn = distinctOn,
    )
}

/** Builds a typed statement with one ordered lexical CTE scope. */
fun <Q : WithCteClause<Q>> with(vararg definitions: CommonTableExpression, query: () -> Q): Q =
    query().withCtes(*definitions)

internal fun validateCteDefinitions(
    definitions: List<CommonTableExpression>,
    outerCtes: List<CommonTableExpression> = emptyList(),
) {
    val duplicate = definitions.groupBy(CommonTableExpression::name).keys.firstOrNull { name ->
        definitions.count { it.name == name } > 1
    }
    require(duplicate == null) { "WITH clause contains duplicate CTE name: $duplicate" }

    val preceding = outerCtes.toMutableList()
    definitions.forEach { definition ->
        if (definition is RecursiveCommonTableExpression) {
            definition.anchor.requireValidScope(availableCtes = preceding)
            definition.recursive.requireValidScope(availableCtes = preceding, recursiveSelf = definition.self)
        } else {
            definition.query.requireValidScope(availableCtes = preceding)
        }
        preceding += definition
    }
}
