package dev.quwac.kagiroi.schema

/** Marks this typed column as read-only without declaring a physical generation expression. */
fun <T> WritableColumn<T>.computed(): ComputedColumn<T> {
    require(definition.identity == null) { "Identity column cannot also be computed: $this" }
    val replacement = ComputedColumn<T>(definition.copy(isComputed = true))
    table.replaceColumn(this, replacement)
    return replacement
}

/** Changes the Kotlin value produced by a computed column from [T] to nullable [T]. */
fun <T : Any> ComputedColumn<T>.nullable(): ComputedColumn<T?> {
    require(!definition.isPrimaryKey) { "Primary key column must not be nullable: $this" }
    return withDefinition(definition.copy(isNullable = true))
}

/** Adds a uniqueness constraint to this computed column. */
fun <T> ComputedColumn<T>.unique(): ComputedColumn<T> = withDefinition(definition.copy(isUnique = true))
