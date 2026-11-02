package dev.quwac.kagiroi.schema

/** One stable, named argument of an extension column type descriptor. */
data class SchemaTypeArgument(val name: String, val value: String) {
    init {
        require(name.isNotBlank()) { "Schema type argument name must not be blank" }
    }
}

/**
 * A stable, dialect-neutral identifier for a storage type used by schema export.
 *
 * The identifier is an extension API contract, never a Kotlin implementation class name. Values
 * in [arguments] are strings so export can preserve precision, scale, and large numeric values
 * without JavaScript number coercion.
 */
data class SchemaTypeDescriptor(val id: String, val arguments: List<SchemaTypeArgument> = emptyList()) {
    init {
        require(id.isNotBlank()) { "Schema type identifier must not be blank" }
        require(arguments.map(SchemaTypeArgument::name).distinct().size == arguments.size) {
            "Schema type argument names must not repeat: $id"
        }
    }
}

/**
 * Implement this on an extension [ColumnType] when its storage representation is not one of
 * Kagiroi's built-in scalar types.
 */
interface StableSchemaColumnType {
    val schemaTypeDescriptor: SchemaTypeDescriptor
}
