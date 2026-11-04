package dev.quwac.kagiroi.schema

/** A writable source column whose foreign-key metadata is declared with the column itself. */
class ForeignKeyColumn<T> internal constructor(definition: ColumnDefinition) : WritableColumn<T>(definition) {
    internal lateinit var foreignKey: ForeignKeyBuilder

    /** Sets the action applied to referencing rows when the target row is deleted. */
    fun onDelete(action: ForeignKeyAction): ForeignKeyColumn<T> = apply { foreignKey.setOnDelete(action) }

    /** Sets the action applied to referencing rows when the target key is updated. */
    fun onUpdate(action: ForeignKeyAction): ForeignKeyColumn<T> = apply { foreignKey.setOnUpdate(action) }
}

internal fun <T> ForeignKeyColumn<*>.withDefinition(definition: ColumnDefinition): ForeignKeyColumn<T> {
    val replacement = ForeignKeyColumn<T>(definition)
    replacement.foreignKey = foreignKey
    table.replaceColumn(this, replacement)
    return replacement
}

/** Marks a foreign-key source as a primary key while retaining its constraint metadata. */
fun <T : Any> ForeignKeyColumn<T>.primaryKey(): ForeignKeyColumn<T> {
    require(!definition.isNullable) { "Primary key column must not be nullable: $this" }
    require(table.primaryKey == null) { "Column-level primary key conflicts with a table-level primary key: $this" }
    require(table.columns.none { it !== this && it.isPrimaryKey }) {
        "Table already defines a column-level primary key: $table"
    }
    return withDefinition(definition.copy(isPrimaryKey = true, isUnique = true))
}

/** Changes the foreign-key source column to nullable while retaining its constraint metadata. */
fun <T : Any> ForeignKeyColumn<T>.nullable(): ForeignKeyColumn<T?> {
    require(!definition.isPrimaryKey) { "Primary key column must not be nullable: $this" }
    return withDefinition(definition.copy(isNullable = true))
}

/** Adds a uniqueness constraint while retaining this foreign key's metadata. */
fun <T> ForeignKeyColumn<T>.unique(): ForeignKeyColumn<T> = withDefinition(definition.copy(isUnique = true))

/** Declares a typed literal DEFAULT value while retaining this foreign key's metadata. */
fun <T> ForeignKeyColumn<T>.default(value: T): ForeignKeyColumn<T> = default(literal(value, this))

/** Declares a typed SQL DEFAULT expression while retaining this foreign key's metadata. */
fun <T> ForeignKeyColumn<T>.default(expression: SqlExpression<T>): ForeignKeyColumn<T> = withDefinition(
    defaultDefinition(
        ColumnDefault(
            expression.also(::requireDefaultExpression),
        ),
    ),
)

/** Declares a typed composed DEFAULT expression while retaining this foreign key's metadata. */
fun <T> ForeignKeyColumn<T>.default(expression: DefaultExpression<T>): ForeignKeyColumn<T> =
    default(expression.asSqlExpression(this))

/** Adds comment metadata while retaining this foreign key's metadata. */
fun <T> ForeignKeyColumn<T>.comment(comment: String): ForeignKeyColumn<T> {
    require(comment.isNotBlank()) { "Column comment must not be blank: $this" }
    return withDefinition(definition.copy(comment = comment))
}
