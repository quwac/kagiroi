@file:Suppress("TooManyFunctions") // Conflict actions share validation and scoped builders.

package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.BooleanExpression
import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.WritableColumn

/**
 * A semantic insert-conflict clause installed only by a dialect-specific INSERT extension.
 *
 * This is deliberately not a common `upsert` API. The two behavior families retain whether a
 * dialect selects an arbiter or reacts to any duplicate key.
 */
sealed interface InsertConflictBehavior

/** PostgreSQL/SQLite-style behavior with an optional arbiter target and one conflict action. */
class TargetedInsertConflictBehavior internal constructor(
    val target: InsertConflictTarget?,
    val action: TargetedInsertConflictAction,
) : InsertConflictBehavior

/** Native REPLACE statement semantics. */
data object ReplaceInsertBehavior : InsertConflictBehavior

/** Ordered SQLite conflict clauses. A target-less clause may only appear last. */
class MultipleTargetedInsertConflictBehavior internal constructor(val clauses: List<TargetedInsertConflictBehavior>) :
    InsertConflictBehavior

/** MySQL/MariaDB behavior for any primary-key or unique-key duplicate. */
class DuplicateKeyUpdateBehavior internal constructor(val update: InsertConflictUpdate) : InsertConflictBehavior

/** A PostgreSQL/SQLite conflict arbiter target. */
sealed interface InsertConflictTarget {
    /** Native unique-index inference using one or more writable columns. */
    class Columns internal constructor(val columns: List<WritableColumn<*>>, val predicate: SqlPredicate?) :
        InsertConflictTarget

    /** PostgreSQL's named unique or exclusion constraint syntax. */
    class Constraint internal constructor(val name: String) : InsertConflictTarget
}

/** One PostgreSQL/SQLite action selected for a targeted conflict. */
sealed interface TargetedInsertConflictAction {
    data object DoNothing : TargetedInsertConflictAction

    class DoUpdate internal constructor(val update: InsertConflictUpdate) : TargetedInsertConflictAction
}

/** Typed assignments and the optional update predicate of a conflict-update action. */
class InsertConflictUpdate internal constructor(val assignments: List<WriteAssignment>, val predicate: SqlPredicate?)

/**
 * A proposed inserted-row column expression.
 *
 * Its constructor is intentionally internal: only the scoped dialect conflict-update builders
 * can create it. It keeps the original column's logical type, nullability, and storage mapping.
 */
class ProposedInsertColumn<T> internal constructor(val column: Column<T>) : SqlExpression<T> {
    override val referencedColumns: List<Column<*>> get() = listOf(column)
    override val type get() = column.type
    override val storageType get() = column.storageType
    override val isNullable get() = column.isNullable

    override fun toStorageValue(value: T): Any? = column.toStorageValue(value)

    override fun fromStorageValue(value: Any?): T = column.fromStorageValue(value)

    override fun toString(): String = "proposed(${column.name})"
}

/** Shared typed SET collector used only within an insert-conflict update action. */
internal class InsertConflictUpdateBuilder(private val table: Table) {
    private val assignments = mutableListOf<WriteAssignment>()
    private var predicate: SqlPredicate? = null

    fun <T> set(column: WritableColumn<T>, value: T) {
        table.requireConflictWritableColumn(column, "conflict UPDATE")
        require(assignments.none { it.column === column }) {
            "Conflict UPDATE column is assigned more than once: $column"
        }
        assignments += ColumnAssignment(column, value)
    }

    fun <T> set(column: WritableColumn<T>, expression: SqlExpression<T>) {
        table.requireConflictWritableColumn(column, "conflict UPDATE")
        require(assignments.none { it.column === column }) {
            "Conflict UPDATE column is assigned more than once: $column"
        }
        requireCompatibleWriteExpression(column, expression, "Conflict UPDATE")
        expression.requireConflictExpressionScope(table, "Conflict UPDATE")
        assignments += ExpressionAssignment(column, expression)
    }

    fun <T> proposed(column: Column<T>): SqlExpression<T> {
        table.requireConflictCurrentColumn(column, "proposed inserted-row")
        return ProposedInsertColumn(column)
    }

    fun where(predicate: SqlPredicate) {
        require(this.predicate == null) { "Conflict UPDATE already has a WHERE predicate" }
        predicate.requireConflictPredicateScope(table, "Conflict UPDATE WHERE")
        this.predicate = predicate
    }

    fun build(): InsertConflictUpdate {
        require(assignments.isNotEmpty()) { "Conflict UPDATE must assign at least one column" }
        return InsertConflictUpdate(assignments.toList(), predicate)
    }
}

/** Scoped update receiver for dialects whose proposed row is named `excluded`. */
class ExcludedConflictUpdateBuilder internal constructor(private val delegate: InsertConflictUpdateBuilder) {
    fun <T> set(column: WritableColumn<T>, value: T) = delegate.set(column, value)

    fun <T> set(column: WritableColumn<T>, expression: SqlExpression<T>) = delegate.set(column, expression)

    /** References this INSERT's proposed row using the dialect's `excluded` semantics. */
    fun <T> excluded(column: Column<T>): SqlExpression<T> = delegate.proposed(column)

    /** Adds the conditional predicate of `DO UPDATE`, not a conflict-target predicate. */
    fun where(predicate: SqlPredicate) = delegate.where(predicate)
}

/** Scoped update receiver for dialects whose proposed row is named `inserted`. */
class InsertedConflictUpdateBuilder internal constructor(private val delegate: InsertConflictUpdateBuilder) {
    fun <T> set(column: WritableColumn<T>, value: T) = delegate.set(column, value)

    fun <T> set(column: WritableColumn<T>, expression: SqlExpression<T>) = delegate.set(column, expression)

    /** References this INSERT's proposed row using the dialect's `inserted` semantics. */
    fun <T> inserted(column: Column<T>): SqlExpression<T> = delegate.proposed(column)
}

/** Scoped action receiver for PostgreSQL/SQLite-style conflict syntax. */
class TargetedInsertConflictBuilder internal constructor(
    private val table: Table,
    private val target: InsertConflictTarget?,
    private val updateRequiresTarget: Boolean,
) {
    private var action: TargetedInsertConflictAction? = null

    fun doNothing() {
        require(action == null) { "Conflict action is already selected" }
        action = TargetedInsertConflictAction.DoNothing
    }

    fun doUpdate(block: ExcludedConflictUpdateBuilder.() -> Unit) {
        require(action == null) { "Conflict action is already selected" }
        require(!updateRequiresTarget || target != null) { "DO UPDATE requires a conflict target" }
        val update = InsertConflictUpdateBuilder(table).let { builder ->
            ExcludedConflictUpdateBuilder(builder).block()
            builder.build()
        }
        action = TargetedInsertConflictAction.DoUpdate(update)
    }

    internal fun build(): TargetedInsertConflictAction =
        requireNotNull(action) { "Conflict clause must select DO NOTHING or DO UPDATE" }
}

/** Adds a dialect-specific targeted conflict behavior to a completed SQL-only INSERT. */
fun <S> InsertQuery<S>.withTargetedConflict(
    targetColumns: List<WritableColumn<*>>? = null,
    constraintName: String? = null,
    targetPredicate: SqlPredicate? = null,
    updateRequiresTarget: Boolean,
    block: TargetedInsertConflictBuilder.() -> Unit,
): InsertQuery<S> where S : InsertQueryState.Renderable =
    addTargetedConflict(targetColumns, constraintName, targetPredicate, updateRequiresTarget, false, block)

/** Appends an ordered SQLite conflict clause. */
fun <S> InsertQuery<S>.withSqliteConflict(
    targetColumns: List<WritableColumn<*>>? = null,
    targetPredicate: SqlPredicate? = null,
    block: TargetedInsertConflictBuilder.() -> Unit,
): InsertQuery<S> where S : InsertQueryState.Renderable =
    addTargetedConflict(targetColumns, null, targetPredicate, false, true, block)

private fun <S> InsertQuery<S>.addTargetedConflict(
    targetColumns: List<WritableColumn<*>>?,
    constraintName: String?,
    targetPredicate: SqlPredicate?,
    updateRequiresTarget: Boolean,
    allowMultiple: Boolean,
    block: TargetedInsertConflictBuilder.() -> Unit,
): InsertQuery<S> where S : InsertQueryState.Renderable {
    val previous = when (val existing = conflictBehavior) {
        null -> emptyList()

        is TargetedInsertConflictBehavior -> {
            require(allowMultiple) { "INSERT already has a conflict behavior" }
            require(
                existing.target !is InsertConflictTarget.Constraint,
            ) { "SQLite does not support constraint targets" }
            listOf(existing)
        }

        is MultipleTargetedInsertConflictBehavior -> {
            require(allowMultiple) { "INSERT already has a conflict behavior" }
            existing.clauses
        }

        else -> throw IllegalArgumentException("INSERT already has a conflict behavior")
    }
    require(previous.none { it.target == null }) { "A target-less conflict clause must be last" }
    require(
        targetColumns == null || constraintName == null,
    ) { "Conflict target cannot use columns and a constraint together" }
    require(targetColumns != null || constraintName == null || targetPredicate == null) {
        "A constraint conflict target cannot have a target predicate"
    }
    val target = when {
        targetColumns != null -> InsertConflictTarget.Columns(
            targetColumns.validateConflictTargetColumns(into),
            targetPredicate?.also { it.requireConflictPredicateScope(into, "Conflict target WHERE") },
        )

        constraintName != null -> InsertConflictTarget.Constraint(
            constraintName.also {
                require(it.isNotBlank()) { "Conflict constraint name must not be blank" }
            },
        )

        else -> {
            require(targetPredicate == null) { "A target-less conflict clause cannot have a target predicate" }
            null
        }
    }
    val action = TargetedInsertConflictBuilder(into, target, updateRequiresTarget).apply(block).build()
    val clause = TargetedInsertConflictBehavior(target, action)
    val behavior = if (previous.isEmpty()) clause else MultipleTargetedInsertConflictBehavior(previous + clause)
    return InsertQuery(into, source, withClause, pendingTargetColumns, behavior)
}

/** Adds a dialect-specific duplicate-key update behavior to a completed SQL-only INSERT. */
fun <S> InsertQuery<S>.withDuplicateKeyUpdate(
    block: InsertedConflictUpdateBuilder.() -> Unit,
): InsertQuery<S>
    where S : InsertQueryState.Renderable {
    require(conflictBehavior == null) { "INSERT already has a conflict behavior" }
    val update = InsertConflictUpdateBuilder(into).let { builder ->
        InsertedConflictUpdateBuilder(builder).block()
        builder.build()
    }
    return InsertQuery(into, source, withClause, pendingTargetColumns, DuplicateKeyUpdateBehavior(update))
}

private fun List<WritableColumn<*>>.validateConflictTargetColumns(table: Table): List<WritableColumn<*>> {
    require(isNotEmpty()) { "Conflict target must contain at least one column" }
    forEach { table.requireConflictWritableColumn(it, "Conflict target") }
    require(distinctBy { it }.size == size) { "Conflict target column appears more than once" }
    return toList()
}

private fun Table.requireConflictWritableColumn(column: WritableColumn<*>, clause: String) {
    requireConflictCurrentColumn(column, clause)
    require(!column.isComputed && !column.isGenerated) { "$clause cannot use a computed or generated column: $column" }
}

private fun Table.requireConflictCurrentColumn(column: Column<*>, clause: String) {
    require(column.table === this && hasColumn(column)) {
        "$clause column is not registered by target table $this: $column"
    }
}

internal fun InsertConflictBehavior.requireValidScope(target: Table) {
    when (this) {
        is TargetedInsertConflictBehavior -> {
            (this.target as? InsertConflictTarget.Columns)?.predicate?.requireConflictPredicateScope(
                target,
                "Conflict target WHERE",
            )
            (action as? TargetedInsertConflictAction.DoUpdate)?.update?.requireValidScope(target)
        }

        ReplaceInsertBehavior -> Unit

        is MultipleTargetedInsertConflictBehavior -> clauses.forEach { it.requireValidScope(target) }

        is DuplicateKeyUpdateBehavior -> update.requireValidScope(target)
    }
}

private fun InsertConflictUpdate.requireValidScope(target: Table) {
    assignments.forEach { assignment ->
        target.requireConflictWritableColumn(assignment.column, "Conflict UPDATE")
        (assignment as? ExpressionAssignment<*>)?.expression?.requireConflictExpressionScope(target, "Conflict UPDATE")
    }
    predicate?.requireConflictPredicateScope(target, "Conflict UPDATE WHERE")
}

private fun SqlExpression<*>.requireConflictExpressionScope(target: Table, clause: String) {
    require(!containsAggregate()) { "$clause cannot contain aggregate expressions" }
    require(!containsWindowExpression()) { "$clause cannot contain window expressions" }
    require(nestedQueries().isEmpty()) { "$clause does not support subqueries" }
    requireConflictDirectReferences(relationReferences(), target, clause)
}

private fun BooleanExpression.requireConflictPredicateScope(target: Table, clause: String) {
    require(!containsAggregate()) { "$clause cannot contain aggregate expressions" }
    require(!containsWindowExpression()) { "$clause cannot contain window expressions" }
    require(nestedQueries().isEmpty()) { "$clause does not support subqueries" }
    requireConflictDirectReferences(relationReferences(), target, clause)
}

private fun requireConflictDirectReferences(references: Iterable<RelationReference>, target: Table, clause: String) {
    val invalid = references.firstOrNull { reference ->
        reference.source !== target || !target.hasColumn(reference.expression as? Column<*> ?: return@firstOrNull true)
    }
    require(invalid == null) {
        "$clause reference is not registered by target table $target: ${requireNotNull(invalid).expression}"
    }
}

/** Marks an INSERT source as a dialect-specific native REPLACE statement. */
fun <S : InsertQueryState> InsertQuery<S>.asNativeReplace(): InsertQuery<S> {
    require(conflictBehavior == null) { "INSERT already has a conflict behavior" }
    return InsertQuery(into, source, withClause, pendingTargetColumns, ReplaceInsertBehavior)
}
