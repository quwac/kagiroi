package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.NotNullBackfillAssignment
import dev.quwac.kagiroi.schema.NotNullBackfillStatement
import dev.quwac.kagiroi.schema.WritableColumn
import dev.quwac.kagiroi.schema.literal
import dev.quwac.kagiroi.schema.notNullBackfill as attachBackfill

/**
 * Collects typed INSERT/UPDATE statements for a column's one-time migration backfill.
 * @property column the new non-null column, usable inside its own property initializer.
 */
class NotNullBackfillBuilder<T : Any> internal constructor(val column: WritableColumn<T>) {
    private val statements = mutableListOf<NotNullBackfillStatement>()

    /** Records an INSERT VALUES statement without executing it or opening a connection. */
    fun <S : InsertQueryState.Renderable> execute(query: InsertQuery<S>) {
        require(query.withClause.isEmpty() && query.conflictBehavior == null) {
            "Backfill INSERT currently supports plain VALUES without CTE or conflict clauses"
        }
        val source = query.source as? InsertSource.Values
            ?: error("Backfill INSERT currently supports VALUES, not INSERT SELECT")
        statements += NotNullBackfillStatement.Insert(
            query.into,
            source.rows.map { row ->
                row.assignments.map { it.toBackfillAssignment() }
            },
        )
    }

    /** Records a target-table UPDATE, optionally restricted by WHERE, without executing it. */
    fun <S : UpdateQueryState.Renderable> execute(query: UpdateQuery<S>) {
        require(query.withClause.isEmpty() && query.fromSources.isEmpty()) {
            "Backfill UPDATE currently supports the target table without CTE or FROM clauses"
        }
        statements += NotNullBackfillStatement.Update(
            query.table,
            query.assignments.map { assignment ->
                when (assignment) {
                    is ColumnAssignment<*> -> assignment.toBackfillAssignment()
                    is ExpressionAssignment<*> -> NotNullBackfillAssignment(assignment.column, assignment.expression)
                }
            },
            query.predicate,
        )
    }

    internal fun build(): List<NotNullBackfillStatement> = statements.toList()
}

/**
 * Declares typed data changes for adding this NOT NULL column to an existing table.
 * The Kotlin column stays non-null; only the generated migration temporarily permits SQL NULL.
 * The block builds metadata once and must not perform application-side IO.
 */
fun <T : Any> WritableColumn<T>.notNullBackfill(block: NotNullBackfillBuilder<T>.() -> Unit): WritableColumn<T> =
    attachBackfill(*NotNullBackfillBuilder(this).apply(block).build().toTypedArray())

private fun <T> ColumnAssignment<T>.toBackfillAssignment(): NotNullBackfillAssignment =
    NotNullBackfillAssignment(column, literal(value, column))
