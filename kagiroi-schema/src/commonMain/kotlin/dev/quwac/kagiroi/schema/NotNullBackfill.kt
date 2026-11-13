package dev.quwac.kagiroi.schema

/**
 * One assignment in an explicitly declared migration backfill.
 * @property column physical writable target.
 * @property expression value expression with the target's logical and storage mapping.
 */
class NotNullBackfillAssignment(val column: WritableColumn<*>, val expression: SqlExpression<*>) {
    init {
        require(column.type === expression.type && column.storageType === expression.storageType) {
            "Backfill expression must use the column's logical and storage mapping: $column"
        }
    }
}

/** Structured data changes exported for migration generation; declaring them never executes SQL. */
sealed interface NotNullBackfillStatement {
    /** Table changed by this statement. */
    val table: Table

    /**
     * Explicit INSERT VALUES rows.
     * @property table target table.
     * @property rows ordered rows of typed assignments.
     */
    class Insert(override val table: Table, val rows: List<List<NotNullBackfillAssignment>>) :
        NotNullBackfillStatement {
        init {
            require(rows.isNotEmpty()) { "Backfill INSERT requires at least one row" }
            rows.forEach { row ->
                validateAssignments(table, row)
                require(row.all { it.expression.referencedColumns.isEmpty() }) {
                    "Backfill INSERT VALUES cannot reference relation columns"
                }
            }
            require(
                rows.all { row -> row.map { it.column.name }.toSet() == rows.first().map { it.column.name }.toSet() },
            ) {
                "Backfill INSERT rows must assign the same columns"
            }
        }
    }

    /**
     * UPDATE assignments with an optional row predicate.
     * @property table target table.
     * @property assignments ordered target assignments.
     * @property predicate row restriction; null explicitly updates every row.
     */
    class Update(
        override val table: Table,
        val assignments: List<NotNullBackfillAssignment>,
        val predicate: BooleanExpression? = null,
    ) : NotNullBackfillStatement {
        init {
            validateAssignments(table, assignments)
            val references =
                assignments.flatMap { it.expression.referencedColumns } + predicate?.referencedColumns.orEmpty()
            require(references.all { it.table === table }) { "Backfill UPDATE can reference only its target table" }
        }
    }
}

/**
 * Records data changes used only when adding this NOT NULL column to an existing table.
 * New tables are created directly with their final definition. These operations are evolution
 * metadata, not a DEFAULT and not part of the physical schema hash. Callers own data correctness.
 */
fun <T : Any> WritableColumn<T>.notNullBackfill(vararg statements: NotNullBackfillStatement): WritableColumn<T> {
    require(!isNullable) { "notNullBackfill requires a non-null column: $this" }
    require(!isIdentity) { "Identity columns cannot declare notNullBackfill: $this" }
    require(definition.notNullBackfill.isEmpty()) { "Column already declares notNullBackfill: $this" }
    require(statements.isNotEmpty()) { "notNullBackfill requires at least one statement" }
    return withDefinition(definition.copy(notNullBackfill = statements.toList()))
}

private fun validateAssignments(table: Table, assignments: List<NotNullBackfillAssignment>) {
    require(assignments.isNotEmpty()) { "Backfill statement requires assignments" }
    require(assignments.map { it.column.name }.distinct().size == assignments.size) {
        "Backfill column is assigned more than once"
    }
    require(assignments.all { it.column.table === table }) { "Backfill assignment belongs to another table" }
}
