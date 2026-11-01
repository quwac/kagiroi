package dev.quwac.kagiroi.schema

/**
 * A table-shaped source that can be introduced into a query FROM tree.
 *
 * Tables are sources themselves. [TableAlias] gives the same schema table a distinct SQL identity
 * for self joins. Derived tables and CTEs implement this interface without changing the expression
 * tree.
 */
interface RelationSource {
    val alias: String?

    /** A stable diagnostic name when this source does not have an explicit SQL alias. */
    val sourceName: String

    /** Whether [expression] is a projection owned by this source. */
    fun contains(expression: SqlExpression<*>): Boolean
}

/**
 * A named output column that can participate in a relation-level SQL construct such as JOIN USING.
 *
 * This marker intentionally excludes arbitrary [SqlExpression] implementations. A USING operand
 * must name one output column of a relation, rather than compute a value from one or more columns.
 * The type parameter deliberately removes schema nullability: SQL equality permits a nullable
 * column on either side, while the query construct validates the logical and storage mappings.
 */
interface NamedRelationColumn<T : Any>

/** A [RelationSource] backed by one physical schema [table]. */
interface TableRelationSource : RelationSource {
    val table: Table

    override val sourceName: String get() = table.tableName

    override fun contains(expression: SqlExpression<*>): Boolean =
        expression is Column<*> && expression.table === table && table.hasColumn(expression)
}

/** A distinct query source for one [table], retaining every column's schema mapping. */
class TableAlias internal constructor(override val table: Table, override val alias: String) : TableRelationSource {
    override fun toString(): String = "$table AS $alias"
}

/** Gives a table a distinct SQL identity for a self join or a repeated reference. */
fun Table.alias(name: String): TableAlias {
    require(name.isNotBlank()) { "Table alias must not be blank" }
    return TableAlias(this, name)
}

/** A column reference qualified by one [RelationSource]. */
class RelationColumn<T> internal constructor(val column: Column<T>, val source: RelationSource) :
    SqlExpression<T>,
    NamedRelationColumn<T & Any> {
    override val referencedColumns: List<Column<*>> = listOf(column)
    override val type: ColumnType<*> get() = column.type
    override val storageType: ColumnType<*> get() = column.storageType
    override val isNullable: Boolean get() = column.isNullable

    override fun toStorageValue(value: T): Any? = column.toStorageValue(value)

    override fun fromStorageValue(value: Any?): T = column.fromStorageValue(value)

    override fun toString(): String = "$source.${column.name}"
}

/** A typed column reference whose schema value may be replaced with SQL NULL by an outer join. */
class NullableRelationColumn<T> internal constructor(val column: Column<T>, val source: RelationSource) :
    SqlExpression<T?> {
    override val referencedColumns: List<Column<*>> = listOf(column)
    override val type: ColumnType<*> get() = column.type
    override val storageType: ColumnType<*> get() = column.storageType
    override val isNullable: Boolean get() = true

    override fun toStorageValue(value: T?): Any? = if (value == null) null else column.toStorageValue(value)

    override fun fromStorageValue(value: Any?): T? = if (value == null) null else column.fromStorageValue(value)

    override fun toString(): String = "$source.${column.name}?"
}

/** Qualifies [column] by this source while preserving its schema nullability. */
operator fun <T> RelationSource.get(column: Column<T>): RelationColumn<T> {
    requireCurrentColumn(this, column)
    return RelationColumn(column, this)
}

/**
 * Marks this query-scoped column reference as nullable because an outer join can synthesize NULL.
 *
 * Kotlin collapses an already nullable schema type, so `Column<String?>` remains
 * `SqlExpression<String?>` while `Column<String>` becomes `SqlExpression<String?>`.
 */
val <T> RelationColumn<T>.optional: NullableRelationColumn<T>
    get() = NullableRelationColumn(column, source)

/**
 * Marks this table column as nullable when its owning table is an outer-join side.
 *
 * This creates a query expression; it never changes the schema nullability of this [Column].
 * Use [RelationColumn.optional] for a table alias.
 */
val <T> Column<T>.optional: NullableRelationColumn<T>
    get() = NullableRelationColumn(this, table)

private fun requireCurrentColumn(source: RelationSource, column: Column<*>) {
    require(source.contains(column)) {
        "Column is not a current column of relation source $source: $column"
    }
}
