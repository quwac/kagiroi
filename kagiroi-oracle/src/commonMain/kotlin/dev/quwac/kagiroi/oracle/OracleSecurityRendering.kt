package dev.quwac.kagiroi.oracle

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.CreateVpdPolicy
import dev.quwac.kagiroi.sql.DropVpdPolicy
import dev.quwac.kagiroi.sql.SecurityDdlStatement
import dev.quwac.kagiroi.sql.SetVpdPolicyEnabled
import dev.quwac.kagiroi.sql.SqlDialect
import dev.quwac.kagiroi.sql.UnsupportedDdlOperation

internal fun renderOracleSecurity(dialect: SqlDialect, statement: SecurityDdlStatement): String = when (statement) {
    is CreateVpdPolicy -> {
        val policy = statement.policy
        val function = policy.function
        val member = listOfNotNull(
            function.packageName,
            function.name,
        ).joinToString(".", transform = dialect::quoteIdentifier)
        "BEGIN DBMS_RLS.ADD_POLICY(${target(policy.table, policy.name)}, " +
            "function_schema => ${sqlText(function.schema)}, policy_function => ${sqlText(member)}, " +
            "statement_types => ${sqlText(
                policy.statements.map { it.name.uppercase() }.sorted().joinToString(","),
            )}, " +
            "update_check => ${boolean(policy.updateCheck)}, enable => ${boolean(policy.enabled)}); END;"
    }

    is DropVpdPolicy -> "BEGIN DBMS_RLS.DROP_POLICY(${target(statement.table, statement.name)}); END;"

    is SetVpdPolicyEnabled -> "BEGIN DBMS_RLS.ENABLE_POLICY(${target(statement.table, statement.name)}, " +
        "enable => ${boolean(statement.enabled)}); END;"

    else -> throw UnsupportedDdlOperation("native security", "Oracle cannot render this security model")
}

private fun target(table: Table, name: String): String = "object_schema => ${sqlText(
    table.schemaName,
)}, object_name => ${sqlText(table.tableName)}, policy_name => ${sqlText(name)}"

private fun sqlText(value: String?): String = value?.let { "'${it.replace("'", "''")}'" } ?: "NULL"
private fun boolean(value: Boolean): String = if (value) "TRUE" else "FALSE"
