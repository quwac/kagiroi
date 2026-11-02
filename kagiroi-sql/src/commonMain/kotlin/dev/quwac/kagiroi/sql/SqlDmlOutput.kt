package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.Column

/** The row image requested by a dialect-specific DML result clause. */
enum class SqlDmlOutputImage {
    BEFORE,
    AFTER,
}

/** A typed reference selected by a dialect-specific DML result clause. */
interface SqlDmlOutputReference<T> {
    val dmlOutputColumn: SqlDmlOutputColumn<T>
}

/** One typed column selected by a dialect-specific DML result clause. */
data class SqlDmlOutputColumn<T>(val image: SqlDmlOutputImage, val column: Column<T>) : SqlDmlOutputReference<T> {
    override val dmlOutputColumn: SqlDmlOutputColumn<T>
        get() = this
}

/** A dialect-specific projection of DML row images. */
class SqlDmlOutput(references: NonEmptyList<SqlDmlOutputReference<*>>) {
    val columns: NonEmptyList<SqlDmlOutputColumn<*>> = nonEmptyListOf(
        references.first.dmlOutputColumn,
        *references.drop(1).map(SqlDmlOutputReference<*>::dmlOutputColumn).toTypedArray(),
    )

    init {
        require(this.columns.distinct().size == this.columns.size) {
            "DML output column appears more than once for the same row image"
        }
    }
}
