package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.Sequence

/** Dialect capability for rendering explicit DDL statements. */
interface SqlDdlDialect : SqlDialect {
    /**
     * Renders the primary statement for backwards-compatible direct rendering.
     *
     * Use [renderDdl] when the complete executable DDL sequence is required.
     */
    fun render(statement: DdlStatement): RenderedSql

    /** Renders every statement required to apply [statement]. */
    fun renderDdl(statement: DdlStatement): RenderedDdl = when (statement) {
        is AlterTable -> RenderedDdl(
            requireNotNull(statement.operations.map { render(statement.singleOperation(it)) }.toNonEmptyListOrNull()),
        )

        else -> RenderedDdl(nonEmptyListOf(render(statement)))
    }
}

/** Dialect capability for allocating and reading an independent sequence value. */
interface SqlSequenceDialect : SqlDialect {
    /** Renders the one-row query that consumes and returns the next [sequence] value. */
    fun renderNextValue(sequence: Sequence): RenderedSql
}

/**
 * An ordered DDL execution sequence.
 *
 * A sequence is not an atomic transaction. Engines retain their own DDL transaction semantics, so a
 * later statement can fail after earlier statements have succeeded.
 */
data class RenderedDdl(val statements: NonEmptyList<RenderedSql>) {
    companion object {
        fun of(first: RenderedSql, remaining: List<RenderedSql> = emptyList()): RenderedDdl =
            RenderedDdl(nonEmptyListOf(first, *remaining.toTypedArray()))
    }
}
