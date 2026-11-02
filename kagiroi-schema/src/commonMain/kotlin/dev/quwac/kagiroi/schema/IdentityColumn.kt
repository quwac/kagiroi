package dev.quwac.kagiroi.schema

/**
 * Declarative configuration for a native identity allocation facility.
 *
 * A `null` option means that the database default remains part of the schema contract; it is not
 * the same as explicitly requesting a numeric default. Dialects own the physical syntax and
 * reject an option their native identity facility cannot express.
 */
data class Identity(val startWith: Long? = null, val incrementBy: Long? = null) {
    init {
        require(incrementBy != 0L) { "Identity increment must not be zero" }
    }
}

/** Marks a signed 64-bit column as using its dialect's default native identity behavior. */
fun WritableColumn<Long>.identity(): WritableColumn<Long> = identity(Identity())

/**
 * Marks a signed 64-bit column as using its dialect's native identity facility.
 *
 * [startWith] and [incrementBy] retain only explicit user choices. They are not completed with
 * database defaults in schema metadata, so a future snapshot or diff can distinguish omitted
 * values from an explicit `1` / `1` configuration.
 */
fun WritableColumn<Long>.identity(startWith: Long? = null, incrementBy: Long? = null): WritableColumn<Long> =
    identity(Identity(startWith, incrementBy))

private fun WritableColumn<Long>.identity(identity: Identity): WritableColumn<Long> {
    require(!definition.isComputed && definition.generatedExpression == null) {
        "Computed or generated column cannot also be an identity column: $this"
    }
    require(definition.defaultExpression == null) { "Column with DEFAULT cannot also be an identity column: $this" }
    require(definition.identity == null) { "Column is already an identity column: $this" }
    return withDefinition(definition.copy(identity = identity))
}
