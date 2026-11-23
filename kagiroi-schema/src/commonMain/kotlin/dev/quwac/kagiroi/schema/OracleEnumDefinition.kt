package dev.quwac.kagiroi.schema

private const val MAX_ORACLE_ENUM_VALUE_BYTES = 4000

/** Oracle 23.4+ enumeration domain with explicit names and stable text values.
 * @property name domain name.
 * @property schemaName optional containing schema.
 */
class OracleEnumDefinition(val name: String, values: Map<String, String>, val schemaName: String? = null) {
    /** Immutable entry names and their explicitly assigned database values. */
    val values: Map<String, String> = values.toMap()
    init {
        require(name.isNotBlank() && (schemaName == null || schemaName.isNotBlank())) { "Invalid Oracle domain name" }
        require(this.values.isNotEmpty()) { "An enumeration domain needs at least one entry" }
        require(this.values.values.distinct().size == this.values.size) { "Enumeration aliases are not modeled" }
        require(this.values.keys.all { it.isNotBlank() && '\u0000' !in it }) { "Invalid enumeration entry name" }
        require(
            this.values.values.all {
                it.isNotEmpty() && '\u0000' !in it &&
                    it.encodeToByteArray().size <= MAX_ORACLE_ENUM_VALUE_BYTES
            },
        ) {
            "Oracle enumeration text values must be nonempty and at most 4000 UTF-8 bytes"
        }
    }
}

/** Physical column types expose managed Oracle enumeration domains through this boundary. */
interface OracleEnumStorage {
    /** Managed domain declaration. */
    val enumDomain: OracleEnumDefinition
}
