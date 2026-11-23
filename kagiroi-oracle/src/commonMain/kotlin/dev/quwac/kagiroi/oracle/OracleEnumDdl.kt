package dev.quwac.kagiroi.oracle

import dev.quwac.kagiroi.sql.CreateOracleEnumDomain
import dev.quwac.kagiroi.sql.DropOracleEnumDomain
import dev.quwac.kagiroi.sql.OracleEnumDdlStatement
import dev.quwac.kagiroi.sql.SqlDialect

internal fun renderOracleEnum(dialect: SqlDialect, statement: OracleEnumDdlStatement): String {
    val definition = when (statement) {
        is CreateOracleEnumDomain -> statement.definition
        is DropOracleEnumDomain -> statement.definition
    }
    val name = dialect.quoteQualifiedIdentifier(definition.schemaName, definition.name)
    return when (statement) {
        is DropOracleEnumDomain -> "DROP DOMAIN $name"

        is CreateOracleEnumDomain -> "CREATE DOMAIN $name AS ENUM (" + definition.values.entries.joinToString(", ") {
            "${dialect.quoteIdentifier(it.key)} = '${it.value.replace("'", "''")}'"
        } + ")"
    }
}
