package dev.quwac.kagiroi.schema

/** Adds ordered historical physical names to a writable column without changing SQL rendering. */
fun <T> WritableColumn<T>.renamedFrom(first: String, vararg remaining: String): WritableColumn<T> =
    withDefinition(definition.withRenameHistory(first, *remaining))

/** Adds ordered historical physical names to a generated column without changing SQL rendering. */
fun <T> GeneratedColumn<T>.renamedFrom(first: String, vararg remaining: String): GeneratedColumn<T> =
    withDefinition(definition.withRenameHistory(first, *remaining))

/** Adds ordered historical physical names to a logical computed column without changing SQL rendering. */
fun <T> ComputedColumn<T>.renamedFrom(first: String, vararg remaining: String): ComputedColumn<T> =
    withDefinition(definition.withRenameHistory(first, *remaining))

private fun ColumnDefinition.withRenameHistory(first: String, vararg remaining: String): ColumnDefinition {
    val added = listOf(first) + remaining
    added.forEach { previous ->
        require(previous.isNotBlank()) { "Previous column name must not be blank: ${table.tableName}.$name" }
        require(previous != name) { "Previous column name must differ from the current name: ${table.tableName}.$name" }
    }
    require((renameHistory + added).distinct().size == renameHistory.size + added.size) {
        "Previous column names must not repeat: ${table.tableName}.$name"
    }
    return copy(renameHistory = renameHistory + added)
}
