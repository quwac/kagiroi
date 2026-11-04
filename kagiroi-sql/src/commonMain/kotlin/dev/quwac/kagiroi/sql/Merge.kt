package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.BooleanExpression
import dev.quwac.kagiroi.schema.RelationSource
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.WritableColumn

/** Dialect-specific MERGE grammar. */
enum class SqlMergeKind {
    /** SQL Server's `MERGE` grammar. */
    SQL_SERVER,

    /** Oracle's `MERGE` grammar. */
    ORACLE,
}

/** A dialect that can render a specific native MERGE grammar. */
interface SqlMergeDialect {
    /** Value associated with merge kind. */
    val mergeKind: SqlMergeKind
}

/** A typed native MERGE with an aliased table or derived query source.
 *
 * @property kind dialect-specific MERGE grammar to render.
 * @property target table whose rows are changed.
 * @property source relation supplying candidate rows.
 * @property on predicate matching source and target rows.
 * @property clauses ordered actions for matched and unmatched rows.
 */
class MergeQuery internal constructor(
    val kind: SqlMergeKind,
    val target: Table,
    val source: RelationSource,
    val on: SqlPredicate,
    internal val clauses: List<MergeClause>,
)

internal enum class MergeMatch { MATCHED, NOT_MATCHED, NOT_MATCHED_BY_SOURCE }
internal sealed interface MergeAction {
    class Update(val assignments: List<WriteAssignment>, val deleteWhere: SqlPredicate? = null) : MergeAction
    data object Delete : MergeAction
    class Insert(val assignments: List<WriteAssignment>) : MergeAction
}
internal class MergeClause(val match: MergeMatch, val predicate: SqlPredicate?, val action: MergeAction)

/** Typed assignments used in a MERGE action. */
class MergeAssignments internal constructor(private val target: Table, private val sources: List<RelationSource>) {
    private val assignments = mutableListOf<WriteAssignment>()

    /** Provides the set result. */
    fun <T> set(column: WritableColumn<T>, value: T) {
        validateColumn(column)
        assignments += ColumnAssignment(column, value)
    }

    /** Provides the set result. */
    fun <T> set(column: WritableColumn<T>, expression: SqlExpression<T>) {
        validateColumn(column)
        requireCompatibleWriteExpression(column, expression, "MERGE")
        expression.requireMergeScope(sources)
        assignments += ExpressionAssignment(column, expression)
    }

    private fun validateColumn(column: WritableColumn<*>) {
        require(
            column.table === target && target.hasColumn(column),
        ) { "MERGE assignment must target a registered column" }
        require(!column.isComputed && !column.isGenerated) { "MERGE cannot assign a computed or generated column" }
        require(assignments.none { it.column === column }) { "MERGE column is assigned more than once" }
    }

    internal fun build(): List<WriteAssignment> = assignments.toList().also {
        require(it.isNotEmpty()) { "MERGE action requires at least one assignment" }
    }
}

private fun SqlExpression<*>.requireMergeScope(sources: List<RelationSource>) {
    require(!containsAggregate() && !containsWindowExpression() && nestedQueries().isEmpty()) {
        "MERGE expressions cannot contain aggregates, windows, or subqueries"
    }
    requireMergeReferences(relationReferences(), sources)
}

private fun BooleanExpression.requireMergeScope(sources: List<RelationSource>) {
    require(!containsAggregate() && !containsWindowExpression() && nestedQueries().isEmpty()) {
        "MERGE predicates cannot contain aggregates, windows, or subqueries"
    }
    requireMergeReferences(relationReferences(), sources)
}

private fun requireMergeReferences(references: List<RelationReference>, sources: List<RelationSource>) {
    require(
        references.all { reference ->
            sources.any { it === reference.source } && reference.source.contains(
                reference.expression,
            )
        },
    ) {
        "MERGE expression references a source outside its scope"
    }
}

private fun mergeAssignments(target: Table, sources: List<RelationSource>, block: MergeAssignments.() -> Unit) =
    MergeAssignments(target, sources).apply(block).build()

/** SQL Server's MATCHED, NOT MATCHED BY TARGET, and NOT MATCHED BY SOURCE actions. */
class SqlServerMergeBuilder internal constructor(private val target: Table, private val source: RelationSource) {
    private val clauses = mutableListOf<MergeClause>()
    private val both get() = listOf(target, source)

    /** Provides the when matched update result. */
    fun whenMatchedUpdate(where: SqlPredicate? = null, block: MergeAssignments.() -> Unit) =
        add(MergeMatch.MATCHED, where, MergeAction.Update(mergeAssignments(target, both, block)))

    /** Provides the when matched delete result. */
    fun whenMatchedDelete(where: SqlPredicate? = null) = add(MergeMatch.MATCHED, where, MergeAction.Delete)

    /** Provides the when not matched insert result. */
    fun whenNotMatchedInsert(where: SqlPredicate? = null, block: MergeAssignments.() -> Unit) =
        add(MergeMatch.NOT_MATCHED, where, MergeAction.Insert(mergeAssignments(target, listOf(source), block)))

    /** Provides the when not matched by source update result. */
    fun whenNotMatchedBySourceUpdate(where: SqlPredicate? = null, block: MergeAssignments.() -> Unit) = add(
        MergeMatch.NOT_MATCHED_BY_SOURCE,
        where,
        MergeAction.Update(mergeAssignments(target, listOf(target), block)),
    )

    /** Provides the when not matched by source delete result. */
    fun whenNotMatchedBySourceDelete(where: SqlPredicate? = null) =
        add(MergeMatch.NOT_MATCHED_BY_SOURCE, where, MergeAction.Delete)

    private fun add(match: MergeMatch, predicate: SqlPredicate?, action: MergeAction) {
        predicate?.requireMergeScope(
            when (match) {
                MergeMatch.MATCHED -> both
                MergeMatch.NOT_MATCHED -> listOf(source)
                MergeMatch.NOT_MATCHED_BY_SOURCE -> listOf(target)
            },
        )
        val previous = clauses.filter { it.match == match }
        val maximum = if (match == MergeMatch.NOT_MATCHED) 1 else 2
        require(previous.size < maximum) { "Too many MERGE clauses for $match" }
        if (previous.isNotEmpty()) {
            require(previous.single().predicate != null) { "The first of two MERGE clauses requires a predicate" }
            require((previous.single().action is MergeAction.Update) != (action is MergeAction.Update)) {
                "Two MERGE clauses must use different actions"
            }
        }
        clauses += MergeClause(match, predicate, action)
    }

    internal fun build(on: SqlPredicate): MergeQuery = buildMerge(SqlMergeKind.SQL_SERVER, target, source, on, clauses)
}

/** Oracle's separate update, post-update DELETE WHERE, and insert predicates. */
class OracleMergeBuilder internal constructor(private val target: Table, private val source: RelationSource) {
    private val clauses = mutableListOf<MergeClause>()
    private val both get() = listOf(target, source)

    /** Provides the when matched update result. */
    fun whenMatchedUpdate(
        where: SqlPredicate? = null,
        deleteWhere: SqlPredicate? = null,
        block: MergeAssignments.() -> Unit,
    ) {
        require(clauses.none { it.match == MergeMatch.MATCHED }) { "Oracle MERGE already has an update clause" }
        where?.requireMergeScope(both)
        deleteWhere?.requireMergeScope(both)
        clauses += MergeClause(
            MergeMatch.MATCHED,
            where,
            MergeAction.Update(mergeAssignments(target, both, block), deleteWhere),
        )
    }

    /** Provides the when not matched insert result. */
    fun whenNotMatchedInsert(where: SqlPredicate? = null, block: MergeAssignments.() -> Unit) {
        require(clauses.none { it.match == MergeMatch.NOT_MATCHED }) { "Oracle MERGE already has an insert clause" }
        where?.requireMergeScope(listOf(source))
        clauses += MergeClause(
            MergeMatch.NOT_MATCHED,
            where,
            MergeAction.Insert(mergeAssignments(target, listOf(source), block)),
        )
    }

    internal fun build(on: SqlPredicate): MergeQuery = buildMerge(SqlMergeKind.ORACLE, target, source, on, clauses)
}

private fun buildMerge(
    kind: SqlMergeKind,
    target: Table,
    source: RelationSource,
    on: SqlPredicate,
    clauses: List<MergeClause>,
): MergeQuery {
    require(source is dev.quwac.kagiroi.schema.TableAlias || source is DerivedTable) {
        "MERGE source must be an aliased table or derived query"
    }
    if (source is DerivedTable) source.query.requireValidScope()
    require(source.alias != target.tableName) { "MERGE source alias must differ from the target name" }
    on.requireMergeScope(listOf(target, source))
    require(clauses.isNotEmpty()) { "MERGE requires at least one action" }
    if (kind == SqlMergeKind.ORACLE) {
        val onColumns = on.relationReferences().filter { it.source === target }.map { it.expression }
        val updated = clauses.mapNotNull { it.action as? MergeAction.Update }.flatMap { it.assignments }
        require(updated.none { it.column in onColumns }) { "Oracle MERGE cannot update a column referenced by ON" }
    }
    return MergeQuery(kind, target, source, on, clauses.toList())
}

/** Creates a SQL Server MERGE query. */
fun sqlServerMerge(
    target: Table,
    source: RelationSource,
    on: SqlPredicate,
    block: SqlServerMergeBuilder.() -> Unit,
): MergeQuery = SqlServerMergeBuilder(target, source).apply(block).build(on)

/** Creates an Oracle MERGE query. */
fun oracleMerge(
    target: Table,
    source: RelationSource,
    on: SqlPredicate,
    block: OracleMergeBuilder.() -> Unit,
): MergeQuery = OracleMergeBuilder(target, source).apply(block).build(on)

/** Executes a typed MERGE using this execution target's dialect. */
suspend fun SqlExecution.execute(query: MergeQuery): ExecuteResult = execute(SqlRenderer(dialect).render(query))

/** A typed MERGE bound to its execution target. */
class ExecutableMergeQuery internal constructor(internal val execution: SqlExecution, internal val query: MergeQuery)

/** Binds a dialect-specific MERGE to this execution target. */
fun SqlExecution.bindMerge(query: MergeQuery): ExecutableMergeQuery = ExecutableMergeQuery(this, query)

/** Executes this MERGE on its bound execution target. */
suspend fun ExecutableMergeQuery.execute(): ExecuteResult = execution.execute(query)
