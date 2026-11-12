package dev.quwac.kagiroi.postgres

import dev.quwac.kagiroi.schema.BooleanExpression
import dev.quwac.kagiroi.schema.PostgresPolicyCommand
import dev.quwac.kagiroi.schema.PostgresPolicyRole
import dev.quwac.kagiroi.sql.CreatePostgresPolicy
import dev.quwac.kagiroi.sql.DisableRowLevelSecurity
import dev.quwac.kagiroi.sql.DropPostgresPolicy
import dev.quwac.kagiroi.sql.EnableRowLevelSecurity
import dev.quwac.kagiroi.sql.ForceRowLevelSecurity
import dev.quwac.kagiroi.sql.SecurityDdlStatement
import dev.quwac.kagiroi.sql.SqlDialect
import dev.quwac.kagiroi.sql.UnsupportedDdlOperation
import dev.quwac.kagiroi.sql.renderCheckConstraintExpression

internal fun renderPostgresSecurity(dialect: SqlDialect, statement: SecurityDdlStatement): String = when (statement) {
    is EnableRowLevelSecurity -> "ALTER TABLE ${dialect.quoteTable(statement.table)} ENABLE ROW LEVEL SECURITY"

    is DisableRowLevelSecurity -> "ALTER TABLE ${dialect.quoteTable(statement.table)} DISABLE ROW LEVEL SECURITY"

    is ForceRowLevelSecurity -> "ALTER TABLE ${dialect.quoteTable(statement.table)} " +
        "${if (statement.force) "FORCE" else "NO FORCE"} ROW LEVEL SECURITY"

    is DropPostgresPolicy -> "DROP POLICY ${dialect.quoteIdentifier(
        statement.name,
    )} ON ${dialect.quoteTable(statement.table)}"

    is CreatePostgresPolicy -> renderPolicy(dialect, statement)

    else -> throw UnsupportedDdlOperation("native security", "PostgreSQL cannot render this security model")
}

private fun renderPolicy(dialect: SqlDialect, statement: CreatePostgresPolicy): String = buildString {
    val policy = statement.policy
    append("CREATE POLICY ${dialect.quoteIdentifier(policy.name)} ON ${dialect.quoteTable(statement.table)}")
    if (policy.restrictive) append(" AS RESTRICTIVE")
    if (policy.command != PostgresPolicyCommand.All) append(" FOR ${policy.command.name.uppercase()}")
    if (policy.roles != listOf(PostgresPolicyRole.Public)) {
        append(" TO ")
        append(policy.roles.map { renderRole(dialect, it) }.sorted().joinToString(", "))
    }
    fun predicate(expression: dev.quwac.kagiroi.schema.BooleanExpression): String = renderCheckConstraintExpression(
        expression,
        dialect::quoteIdentifier,
        { if (it) "TRUE" else "FALSE" },
        { "'${it.replace("'", "''")}'" },
        dialect::renderFunction,
        dialect::renderCastTarget,
    )
    policy.using?.let { append(" USING (${predicate(it)})") }
    policy.withCheck?.let { append(" WITH CHECK (${predicate(it)})") }
}

private fun renderRole(dialect: SqlDialect, role: PostgresPolicyRole): String = when (role) {
    PostgresPolicyRole.Public -> "PUBLIC"
    PostgresPolicyRole.CurrentUser -> "CURRENT_USER"
    PostgresPolicyRole.SessionUser -> "SESSION_USER"
    is PostgresPolicyRole.Named -> dialect.quoteIdentifier(role.name)
}
