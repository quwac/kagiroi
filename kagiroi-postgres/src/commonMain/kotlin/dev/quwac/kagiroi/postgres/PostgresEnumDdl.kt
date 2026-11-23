package dev.quwac.kagiroi.postgres

import dev.quwac.kagiroi.sql.AddPostgresEnumValue
import dev.quwac.kagiroi.sql.CreatePostgresEnum
import dev.quwac.kagiroi.sql.DropPostgresEnum
import dev.quwac.kagiroi.sql.PostgresEnumDdlStatement
import dev.quwac.kagiroi.sql.SqlDialect

internal fun renderPostgresEnum(dialect: SqlDialect, statement: PostgresEnumDdlStatement): String {
    val definition = when (statement) {
        is CreatePostgresEnum -> statement.definition
        is DropPostgresEnum -> statement.definition
        is AddPostgresEnumValue -> statement.definition
    }
    val name = dialect.quoteQualifiedIdentifier(definition.schemaName, definition.name)
    fun label(value: String): String = "'${value.replace("'", "''")}'"
    return when (statement) {
        is CreatePostgresEnum -> "CREATE TYPE $name AS ENUM (${definition.labels.joinToString(
            ", ",
            transform = ::label,
        )})"

        is DropPostgresEnum -> "DROP TYPE ${if (statement.ifExists) "IF EXISTS " else ""}$name"

        is AddPostgresEnumValue -> "ALTER TYPE $name ADD VALUE ${label(statement.label)}"
    }
}
