package dev.quwac.kagiroi.sqlserver

import dev.quwac.kagiroi.schema.SecurityFunctionReference
import dev.quwac.kagiroi.schema.SqlServerBlockTiming
import dev.quwac.kagiroi.schema.SqlServerSecurityPredicate
import dev.quwac.kagiroi.sql.AddSecurityPredicate
import dev.quwac.kagiroi.sql.CreateSecurityPolicy
import dev.quwac.kagiroi.sql.DropSecurityPolicy
import dev.quwac.kagiroi.sql.DropSecurityPredicate
import dev.quwac.kagiroi.sql.SecurityDdlStatement
import dev.quwac.kagiroi.sql.SetSecurityPolicyEnabled
import dev.quwac.kagiroi.sql.SqlDialect
import dev.quwac.kagiroi.sql.UnsupportedDdlOperation

internal fun renderSqlServerSecurity(dialect: SqlDialect, statement: SecurityDdlStatement): String = when (statement) {
    is CreateSecurityPolicy -> {
        require(statement.predicates.isNotEmpty()) { "Security policy requires predicates" }
        "CREATE SECURITY POLICY ${dialect.quoteQualifiedIdentifier(statement.schemaName, statement.name)} " +
            statement.predicates.sortedBy {
                predicateIdentity(
                    it,
                )
            }.joinToString(", ") { "ADD ${predicate(dialect, it)}" } +
            " WITH (STATE = ${if (statement.enabled) "ON" else "OFF"})"
    }

    is DropSecurityPolicy -> "DROP SECURITY POLICY ${dialect.quoteQualifiedIdentifier(
        statement.schemaName,
        statement.name,
    )}"

    is SetSecurityPolicyEnabled -> "ALTER SECURITY POLICY ${dialect.quoteQualifiedIdentifier(
        statement.schemaName,
        statement.name,
    )} " +
        "WITH (STATE = ${if (statement.enabled) "ON" else "OFF"})"

    is AddSecurityPredicate -> "ALTER SECURITY POLICY ${dialect.quoteQualifiedIdentifier(
        statement.schemaName,
        statement.name,
    )} " +
        "ADD ${predicate(dialect, statement.predicate)}"

    is DropSecurityPredicate -> "ALTER SECURITY POLICY ${dialect.quoteQualifiedIdentifier(
        statement.schemaName,
        statement.name,
    )} " +
        "DROP ${if (statement.predicate.timing == null) "FILTER" else "BLOCK"} PREDICATE " +
        "ON ${dialect.quoteTable(statement.predicate.table)}${timing(statement.predicate.timing)}"

    else -> throw UnsupportedDdlOperation("native security", "SQL Server cannot render this security model")
}

private fun predicate(dialect: SqlDialect, predicate: SqlServerSecurityPredicate): String =
    "${if (predicate.timing == null) "FILTER" else "BLOCK"} PREDICATE " +
        "${function(
            dialect,
            predicate.function,
        )}(${predicate.arguments.joinToString(", ") { dialect.quoteIdentifier(it.name) }}) " +
        "ON ${dialect.quoteTable(predicate.table)}${timing(predicate.timing)}"

private fun function(dialect: SqlDialect, function: SecurityFunctionReference): String {
    require(function.packageName == null) { "SQL Server does not support Oracle packages" }
    return dialect.quoteQualifiedIdentifier(
        requireNotNull(function.schema) { "SQL Server predicate function requires schema" },
        function.name,
    )
}

private fun timing(timing: SqlServerBlockTiming?): String = when (timing) {
    null -> ""
    SqlServerBlockTiming.AfterInsert -> " AFTER INSERT"
    SqlServerBlockTiming.AfterUpdate -> " AFTER UPDATE"
    SqlServerBlockTiming.BeforeUpdate -> " BEFORE UPDATE"
    SqlServerBlockTiming.BeforeDelete -> " BEFORE DELETE"
}

private fun predicateIdentity(predicate: SqlServerSecurityPredicate): String =
    listOf(predicate.table.schemaName.orEmpty(), predicate.table.tableName, predicate.timing?.name.orEmpty()).toString()
