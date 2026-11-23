package dev.quwac.kagiroi.schema

private const val MAX_ENUM_LABEL_BYTES = 63

/** A managed PostgreSQL ENUM type with explicitly ordered, stable database labels.
 * @property name physical type name.
 * @property schemaName optional containing schema.
 */
class PostgresEnumDefinition(val name: String, labels: List<String>, val schemaName: String? = null) {
    /** Immutable labels in PostgreSQL comparison order. */
    val labels: List<String> = labels.toList()

    init {
        require(name.isNotBlank()) { "ENUM type name must not be blank" }
        require(schemaName == null || schemaName.isNotBlank()) { "ENUM schema name must not be blank" }
        require(this.labels.isNotEmpty()) { "ENUM must declare at least one label" }
        require(this.labels.distinct().size == this.labels.size) { "ENUM labels must be unique" }
        require(this.labels.all { '\u0000' !in it && it.encodeToByteArray().size <= MAX_ENUM_LABEL_BYTES }) {
            "PostgreSQL ENUM labels must contain no NUL and at most 63 UTF-8 bytes"
        }
    }
}

/** Physical column types expose their managed ENUM definition through this export boundary. */
interface PostgresEnumStorage {
    /** Managed ENUM declaration, or null for a non-ENUM column type. */
    val enumDefinition: PostgresEnumDefinition?
}
