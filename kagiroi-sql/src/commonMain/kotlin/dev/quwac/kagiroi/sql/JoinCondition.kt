package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.schema.NamedRelationColumn
import dev.quwac.kagiroi.schema.RelationColumn
import dev.quwac.kagiroi.schema.RelationSource
import dev.quwac.kagiroi.schema.SqlExpression

/**
 * The mutually exclusive condition forms accepted by an ordinary SQL JOIN.
 *
 * `USING` remains a relation-column semantic node. It is deliberately not lowered into an
 * equality predicate, because SQL gives a USING column a common output-column meaning that an
 * ON predicate does not have.
 */
sealed interface JoinCondition {
    /** A regular Boolean predicate rendered after `ON`.
     *
     * @property predicate predicate evaluated for the joined rows.
     */
    class On internal constructor(val predicate: SqlPredicate) : JoinCondition

    /** One or more same-named relation columns rendered after `USING`.
     *
     * @property pairs ordered column pairs in the USING clause.
     */
    class Using internal constructor(val pairs: NonEmptyList<UsingColumnPair<*>>) : JoinCondition
}

/**
 * One explicit, typed pair in a JOIN USING clause.
 *
 * Construction is kept inside the JOIN builder so both operands can be checked against the left
 * query scope and the relation introduced by the current JOIN.
 *
 * @property left named column from the existing query relations.
 * @property right same-named column from the relation being joined.
 */
class UsingColumnPair<T : Any> internal constructor(
    val left: NamedRelationColumn<T>,
    val right: NamedRelationColumn<T>,
) {
    internal val leftReference = left.toUsingColumnReference()
    internal val rightReference = right.toUsingColumnReference()

    /** The unqualified relation-output name emitted in the USING identifier list. */
    val name: String = leftReference.name

    init {
        require(leftReference.name == rightReference.name) {
            "JOIN USING columns must have the same physical output name: " +
                "${leftReference.name} and ${rightReference.name}"
        }
        require(
            leftReference.expression.type === rightReference.expression.type &&
                leftReference.expression.storageType === rightReference.expression.storageType,
        ) {
            "JOIN USING columns must use compatible logical and storage mappings: " +
                "${leftReference.name}"
        }
    }
}

/**
 * Builder for an explicit JOIN USING column list.
 *
 * A `using` call accepts only named relation columns. This keeps functions, arithmetic, aliases
 * before relationization, and other arbitrary SQL expressions out of the USING grammar.
 */
class JoinUsingBuilder internal constructor(
    private val leftSources: List<RelationSource>,
    private val rightSource: RelationSource,
) {
    private val pairs = mutableListOf<UsingColumnPair<*>>()
    private val names = mutableSetOf<String>()

    /** Adds one explicit same-named left/right relation-column pair. */
    fun <T : Any> using(left: NamedRelationColumn<T>, right: NamedRelationColumn<T>) {
        val pair = UsingColumnPair(left, right)
        require(leftSources.any { source -> source === pair.leftReference.source }) {
            "JOIN USING left column must belong to a relation already present in the query: " +
                pair.leftReference.name
        }
        require(pair.rightReference.source === rightSource) {
            "JOIN USING right column must belong to the relation being joined: ${pair.rightReference.name}"
        }
        require(names.add(pair.name)) { "JOIN USING contains duplicate column: ${pair.name}" }
        pairs += pair
    }

    internal fun build(): JoinCondition.Using = JoinCondition.Using(
        requireNotNull(NonEmptyList.from(pairs)) { "JOIN USING requires at least one column pair" },
    )
}

/** A dialect capability whose native syntax is required to preserve JOIN USING semantics.
 *
 * @property unsupportedCapability identifier used when the dialect rejects this capability.
 */
enum class SqlJoinCapability(val unsupportedCapability: String) {
    /** Native `JOIN ... USING (...)` syntax. */
    Using("join-using"),
}

internal data class UsingColumnReference(
    val source: RelationSource,
    val expression: SqlExpression<*>,
    val name: String,
)

/**
 * Normalizes the supported named relation handles only for validation and identifier rendering.
 * It never converts a USING pair into an ON predicate.
 */
private fun NamedRelationColumn<*>.toUsingColumnReference(): UsingColumnReference = when (this) {
    is Column<*> -> UsingColumnReference(table, this, name)
    is RelationColumn<*> -> UsingColumnReference(source, column, column.name)
    is DerivedColumn<*> -> UsingColumnReference(source, projection, name)
    else -> throw IllegalArgumentException("JOIN USING accepts only relation output columns")
}.also { reference ->
    require(reference.source.contains(reference.expression)) {
        "JOIN USING column is not a current output column of relation source ${reference.source}: ${reference.name}"
    }
}

internal fun JoinCondition.relationReferences(): List<RelationReference> = when (this) {
    is JoinCondition.On -> predicate.relationReferences()

    is JoinCondition.Using -> pairs.flatMap { pair ->
        listOf(
            RelationReference(pair.leftReference.source, pair.leftReference.expression),
            RelationReference(pair.rightReference.source, pair.rightReference.expression),
        )
    }
}

internal fun JoinCondition.nestedQueries(): List<QueryExpression<*, *>> = when (this) {
    is JoinCondition.On -> predicate.nestedQueries()
    is JoinCondition.Using -> emptyList()
}

internal fun JoinCondition.referencedColumns(): List<Column<*>> = when (this) {
    is JoinCondition.On -> predicate.referencedColumns

    is JoinCondition.Using -> pairs.flatMap { pair ->
        pair.leftReference.expression.referencedColumns + pair.rightReference.expression.referencedColumns
    }
}
