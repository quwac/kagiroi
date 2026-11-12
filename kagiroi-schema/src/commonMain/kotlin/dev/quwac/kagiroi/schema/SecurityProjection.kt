package dev.quwac.kagiroi.schema

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal fun canonicalizeSecurity(tables: List<Table>): List<CanonicalSecurityObject> {
    val definitions = tables.flatMap { it.securityDefinitions }
    val result = definitions.flatMap { definition ->
        when (definition) {
            is PostgresRlsDefinition -> definition.projectPostgres()
            is OracleVpdDefinition -> listOf(definition.projectOracle())
            is SqlServerRlsDefinition -> emptyList()
        }
    }.toMutableList()
    definitions.filterIsInstance<SqlServerRlsDefinition>()
        .groupBy { securityIdentity(it.schemaName, it.name) }
        .forEach { (identity, contributions) ->
            require(contributions.map { it.enabled }.distinct().size == 1) {
                "SQL Server policy contributions disagree on enabled state: $identity"
            }
            val predicates = contributions.flatMap { it.predicates }.map { it.projectSecurityPredicate() }
                .sortedBy { it.toString() }
            require(predicates.map { it.getValue("identity") }.distinct().size == predicates.size) {
                "SQL Server policy has duplicate predicate identities: $identity"
            }
            result += CanonicalSecurityObject.SqlServerSecurityPolicy(
                identity,
                buildJsonObject {
                    put("enabled", JsonPrimitive(contributions.first().enabled))
                    put("predicates", JsonArray(predicates))
                },
            )
        }
    return result.sortedWith(compareBy({ it.kind }, { it.identity.toString() }))
}

internal fun securityIdentity(schema: String?, name: String, policy: String? = null): JsonObject = buildJsonObject {
    schema?.let { put("schemaName", JsonPrimitive(it)) }
    put("name", JsonPrimitive(name))
    policy?.let { put("policyName", JsonPrimitive(it)) }
}

internal fun PostgresRlsDefinition.projectPostgres(): List<CanonicalSecurityObject> = buildList {
    add(
        CanonicalSecurityObject.PostgreSqlRlsTable(
            securityIdentity(
                table.schemaName,
                table.tableName,
            ),
            buildJsonObject {
                put("enabled", JsonPrimitive(true))
                put("force", JsonPrimitive(force))
            },
        ),
    )
    policies.forEach { policy ->
        add(
            CanonicalSecurityObject.PostgreSqlPolicy(
                securityIdentity(table.schemaName, table.tableName, policy.name),
                buildJsonObject {
                    put("command", JsonPrimitive(policy.command.name.lowercase()))
                    put("mode", JsonPrimitive(if (policy.restrictive) "restrictive" else "permissive"))
                    put(
                        "roles",
                        JsonArray(
                            policy.roles.map { role ->
                                buildJsonObject {
                                    when (role) {
                                        PostgresPolicyRole.Public -> put("kind", JsonPrimitive("public"))

                                        PostgresPolicyRole.CurrentUser -> put("kind", JsonPrimitive("currentUser"))

                                        PostgresPolicyRole.SessionUser -> put("kind", JsonPrimitive("sessionUser"))

                                        is PostgresPolicyRole.Named -> {
                                            put("kind", JsonPrimitive("named"))
                                            put("name", JsonPrimitive(role.name))
                                        }
                                    }
                                }
                            }.sortedBy { it.toString() },
                        ),
                    )
                    policy.using?.let { put("using", it.predicateJson()) }
                    policy.withCheck?.let { put("withCheck", it.predicateJson()) }
                },
            ),
        )
    }
}

internal fun SqlServerSecurityPredicate.projectSecurityPredicate(): JsonObject = buildJsonObject {
    put(
        "identity",
        buildJsonObject {
            put("table", securityIdentity(table.schemaName, table.tableName))
            put("operation", JsonPrimitive(timing?.name ?: "Filter"))
        },
    )
    put("function", function.securityFunctionJson())
    put("arguments", JsonArray(arguments.map { JsonPrimitive(it.name) }))
}

internal fun OracleVpdDefinition.projectOracle(): CanonicalSecurityObject = CanonicalSecurityObject.OracleVpdPolicy(
    securityIdentity(table.schemaName, table.tableName, name),
    buildJsonObject {
        put("function", function.securityFunctionJson())
        put("statements", JsonArray(statements.map { it.name.lowercase() }.sorted().map(::JsonPrimitive)))
        put("updateCheck", JsonPrimitive(updateCheck))
        put("enabled", JsonPrimitive(enabled))
    },
)

private fun SecurityFunctionReference.securityFunctionJson(): JsonObject = buildJsonObject {
    schema?.let { put("schemaName", JsonPrimitive(it)) }
    packageName?.let { put("packageName", JsonPrimitive(it)) }
    put("name", JsonPrimitive(name))
}
