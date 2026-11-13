package dev.quwac.kagiroi.schema

/**
 * A one-time conversion used when an existing non-null column changes its physical type.
 * @property from previous column type, checked against migration history.
 * @property using typed expression evaluated over the previous row.
 */
class ColumnTypeConversion(val from: ColumnType<*>, val using: SqlExpression<*>)

/**
 * Declares PostgreSQL's ALTER COLUMN TYPE USING expression without running a query.
 * [previousType] describes the non-null source column. [convert] must produce this column's
 * final Kotlin type. New columns and already-migrated columns do not run the conversion.
 * The hint is excluded from the physical schema hash. Other dialects must reject USING.
 */
fun <S : Any, T : Any> WritableColumn<T>.typeUsing(
    previousType: ColumnType<S>,
    convert: (SqlExpression<S>) -> SqlExpression<T>,
): WritableColumn<T> {
    require(!isNullable && !isIdentity) { "typeUsing requires a non-null, non-identity writable column: $this" }
    require(definition.typeConversion == null) { "Column already declares typeUsing: $this" }
    val previous = WritableColumn<S>(ColumnDefinition(table, name, previousType))
    val expression = convert(previous)
    require(type === expression.type && storageType === expression.storageType && !expression.isNullable) {
        "typeUsing must return the final non-null column type: $this"
    }
    require(expression.referencedColumns.all { it.table === table && !it.isComputed && !it.isGenerated }) {
        "typeUsing can reference only physical writable columns in its target table"
    }
    return withDefinition(definition.copy(typeConversion = ColumnTypeConversion(previousType, expression)))
}
