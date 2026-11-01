package dev.quwac.kagiroi.schema

/** Changes the Kotlin value produced by a generated column from [T] to nullable [T]. */
fun <T : Any> GeneratedColumn<T>.nullable(): GeneratedColumn<T?> = withDefinition(definition.copy(isNullable = true))

/** Adds a uniqueness constraint to this generated column. */
fun <T> GeneratedColumn<T>.unique(): GeneratedColumn<T> = withDefinition(definition.copy(isUnique = true))

/** Adds logical schema metadata to a generated column without making it writable. */
fun <T> GeneratedColumn<T>.comment(comment: String): GeneratedColumn<T> {
    require(comment.isNotBlank()) { "Column comment must not be blank: $this" }
    return withDefinition(definition.copy(comment = comment))
}
