package dev.quwac.kagiroi.postgres

import dev.quwac.kagiroi.schema.PostgresEnumDefinition
import dev.quwac.kagiroi.schema.StringColumnType
import dev.quwac.kagiroi.schema.columnMapping

/** A managed PostgreSQL ENUM column with text transport and an explicit type declaration.
 * @property definition immutable database type declaration.
 */
class PostgresEnumColumnType internal constructor(val definition: PostgresEnumDefinition) :
    PostgresColumnType<String, String>(
        name = "PostgreSQL ENUM ${definition.name}",
        storageType = StringColumnType,
        mapping = columnMapping({ it }, { it }),
        sqlDeclaration = listOfNotNull(
            definition.schemaName,
            definition.name,
        ).joinToString(".") { "\"${it.replace("\"", "\"\"")}\"" },
        enumDefinition = definition,
    )
