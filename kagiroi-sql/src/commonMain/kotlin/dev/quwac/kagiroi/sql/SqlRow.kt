package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.schema.NullableRelationColumn
import dev.quwac.kagiroi.schema.NullableSqlExpression
import dev.quwac.kagiroi.schema.RelationColumn
import dev.quwac.kagiroi.schema.RelationSource
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.SqlExpressionAlias

/** A row produced for one [SelectQuery], decoded through its selected typed expressions. */
class SqlRow internal constructor(
    private val expressions: List<SqlExpression<*>>,
    private val storageValueAt: (Int) -> Any?,
) {
    /** Returns a selected column using its nullability and value mapping. */
    operator fun <T> get(column: Column<T>): T = getExpression(column)

    /** Returns a selected expression alias without losing the expression's Kotlin result type. */
    operator fun <T> get(alias: SqlExpressionAlias<T>): T = getExpression(alias)

    /** Returns a selected relation-qualified column with its schema nullability. */
    operator fun <T> get(column: RelationColumn<T>): T = getExpression(column)

    /** Returns a selected outer-join column. Its type is nullable even if the schema column is not. */
    operator fun <T> get(column: NullableRelationColumn<T>): T? = getExpression(column)

    /** Returns an explicitly nullable expression projection, including an advanced grouping key. */
    operator fun <T> get(expression: NullableSqlExpression<T>): T? = getExpression(expression)

    /** Returns a selected derived-table projection using its original typed projection mapping. */
    operator fun <T> get(column: DerivedColumn<T>): T = getExpression(column)

    /** Returns a selected derived-table projection from an outer join. */
    operator fun <T> get(column: NullableDerivedColumn<T>): T? = getExpression(column)

    private fun <T> getExpression(expression: SqlExpression<T>): T {
        val index = expressions.indexOfFirst { it === expression || sameRelationColumn(it, expression) }
        require(index >= 0) { "Expression was not selected by this query: $expression" }
        return expression.fromStorageValue(storageValueAt(index))
    }

    internal fun storageValueAt(index: Int): Any? = storageValueAt.invoke(index)
}

private fun sameRelationColumn(left: SqlExpression<*>, right: SqlExpression<*>): Boolean {
    if (left.isNullable != right.isNullable) return false
    val leftReference = left.relationColumnReference() ?: return false
    val rightReference = right.relationColumnReference() ?: return false
    return leftReference.source === rightReference.source && leftReference.expression === rightReference.expression
}

private data class RelationColumnReference(val source: RelationSource, val expression: SqlExpression<*>)

private fun SqlExpression<*>.relationColumnReference(): RelationColumnReference? = when (this) {
    is Column<*> -> RelationColumnReference(table, this)
    is RelationColumn<*> -> RelationColumnReference(source, column)
    is NullableRelationColumn<*> -> RelationColumnReference(source, column)
    is DerivedColumn<*> -> RelationColumnReference(source, projection)
    is NullableDerivedColumn<*> -> RelationColumnReference(source, column.projection)
    else -> null
}

/** Creates a typed row from values already converted to each column's storage type. */
fun sqlRow(expressions: List<SqlExpression<*>>, storageValues: List<Any?>): SqlRow {
    require(expressions.size == storageValues.size) {
        "SQL row has ${storageValues.size} values for ${expressions.size} selected expressions"
    }
    return SqlRow(expressions, storageValues::get)
}
