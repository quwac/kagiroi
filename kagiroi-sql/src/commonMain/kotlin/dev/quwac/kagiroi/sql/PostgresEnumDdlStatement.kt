package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.PostgresEnumDefinition

/** Native PostgreSQL ENUM operations; other dialects reject these statements. */
sealed interface PostgresEnumDdlStatement : DdlStatement

/** Creates an ENUM type.
 * @property definition immutable type declaration.
 */
class CreatePostgresEnum(val definition: PostgresEnumDefinition) : PostgresEnumDdlStatement

/** Drops an ENUM type without cascading to dependent columns.
 * @property definition type to drop.
 * @property ifExists whether to tolerate a missing type.
 */
class DropPostgresEnum(val definition: PostgresEnumDefinition, val ifExists: Boolean = false) : PostgresEnumDdlStatement

/** Appends a new label. Commit before using the new value on PostgreSQL.
 * @property definition current type declaration.
 * @property label new label to append.
 */
class AddPostgresEnumValue(val definition: PostgresEnumDefinition, val label: String) : PostgresEnumDdlStatement {
    init {
        PostgresEnumDefinition(definition.name, definition.labels + label, definition.schemaName)
    }
}
