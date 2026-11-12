package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.CanonicalSecurityObject
import dev.quwac.kagiroi.schema.OracleVpdDefinition
import dev.quwac.kagiroi.schema.PostgresRlsDefinition
import dev.quwac.kagiroi.schema.SchemaRegistry
import dev.quwac.kagiroi.schema.SqlServerRlsDefinition
import dev.quwac.kagiroi.schema.SqlServerSecurityPredicate
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.TableSecurityDefinition
import dev.quwac.kagiroi.schema.identity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Builds ordered security DDL from the ordinary table registry.
 * Execute after every referenced CREATE TABLE and predicate function. SQL Server contributions are merged.
 */
fun SchemaRegistry.createSecurityObjects(): List<SecurityDdlStatement> = securityEntries().values
    .sortedWith(compareBy({ it.createOrder }, { it.canonical.identity.toString() }))
    .flatMap { it.create }

/**
 * Plans only security changes between ordinary table registries; table and column differences are not emitted.
 * Execute removals before dropping referenced tables, and additions after creating tables and predicate functions.
 * Callers must preserve the returned order and coordinate it with their table migration plan.
 */
fun diffSecurity(before: SchemaRegistry, after: SchemaRegistry): List<SecurityDdlStatement> {
    val old = before.securityEntries()
    val new = after.securityEntries()
    val changes = (old.keys + new.keys).distinct().sorted().mapNotNull { key ->
        val previous = old[key]
        val desired = new[key]
        when {
            previous == null -> SecurityDelta(emptyList(), requireNotNull(desired).create)
            desired == null -> SecurityDelta(previous.drop, emptyList())
            previous.canonical == desired.canonical -> null
            else -> changedSecurity(previous, desired)
        }
    }
    return changes.flatMap { it.drop }.sortedBy {
        if (it is DisableRowLevelSecurity ||
            it is ForceRowLevelSecurity
        ) {
            1
        } else {
            0
        }
    } +
        changes.flatMap { it.create }.sortedBy(
            ::creationOrder,
        )
}

/** Renders the complete plan before executing any statement, rejecting unsupported dialects up front. */
suspend fun SqlExecution.executeSecurity(statements: List<SecurityDdlStatement>) {
    val renderer = dialect as? SqlDdlDialect ?: throw UnsupportedCapability("typed-ddl")
    val plan = statements.flatMap { renderer.renderDdl(it).statements }
    plan.forEach { execute(it) }
}

private data class SecurityDelta(val drop: List<SecurityDdlStatement>, val create: List<SecurityDdlStatement>)
private data class SecurityEntry(
    val canonical: CanonicalSecurityObject,
    val create: List<SecurityDdlStatement>,
    val drop: List<SecurityDdlStatement>,
    val createOrder: Int,
)

private fun SchemaRegistry.securityEntries(): Map<String, SecurityEntry> {
    val canonical = securityObjects
    val definitions = managedTables.flatMap { it.securityDefinitions }
    return canonical.associate { value ->
        val entry = when (value) {
            is CanonicalSecurityObject.PostgreSqlRlsTable -> postgresTableEntry(value, definitions)

            is CanonicalSecurityObject.PostgreSqlPolicy -> {
                val definition = definitions.filterIsInstance<PostgresRlsDefinition>().single {
                    value.matches(
                        it.table,
                    )
                }
                val policy = definition.policies.single { it.name == value.identity.text("policyName") }
                SecurityEntry(
                    value,
                    listOf(CreatePostgresPolicy(definition.table, policy)),
                    listOf(DropPostgresPolicy(definition.table, policy.name)),
                    2,
                )
            }

            is CanonicalSecurityObject.SqlServerSecurityPolicy -> {
                val contributions = definitions.filterIsInstance<SqlServerRlsDefinition>().filter {
                    it.schemaName == value.identity.text("schemaName") && it.name == value.identity.text("name")
                }
                val first = contributions.first()
                SecurityEntry(
                    value,
                    listOf(
                        CreateSecurityPolicy(
                            first.name,
                            first.schemaName,
                            first.enabled,
                            contributions.flatMap { it.predicates },
                        ),
                    ),
                    listOf(DropSecurityPolicy(first.name, first.schemaName)),
                    2,
                )
            }

            is CanonicalSecurityObject.OracleVpdPolicy -> {
                val definition = definitions.filterIsInstance<OracleVpdDefinition>().single {
                    value.matches(it.table) && it.name == value.identity.text("policyName")
                }
                SecurityEntry(
                    value,
                    listOf(CreateVpdPolicy(definition)),
                    listOf(DropVpdPolicy(definition.table, definition.name)),
                    2,
                )
            }
        }
        (value.kind + value.identity.toString()) to entry
    }
}

private fun changedSecurity(old: SecurityEntry, new: SecurityEntry): SecurityDelta = when (new.canonical) {
    is CanonicalSecurityObject.PostgreSqlRlsTable -> {
        val table = (new.create.first() as EnableRowLevelSecurity).table
        SecurityDelta(
            emptyList(),
            listOf(
                ForceRowLevelSecurity(
                    table,
                    new.canonical.definition.getValue("force").jsonPrimitive.content == "true",
                ),
            ),
        )
    }

    is CanonicalSecurityObject.SqlServerSecurityPolicy -> changedSqlServer(old, new)

    is CanonicalSecurityObject.OracleVpdPolicy -> {
        val previous = old.canonical.definition.filterKeys { it != "enabled" }
        val desired = new.canonical.definition.filterKeys { it != "enabled" }
        if (previous == desired) {
            val policy = (new.create.single() as CreateVpdPolicy).policy
            SecurityDelta(emptyList(), listOf(SetVpdPolicyEnabled(policy.table, policy.name, policy.enabled)))
        } else {
            SecurityDelta(old.drop, new.create)
        }
    }

    is CanonicalSecurityObject.PostgreSqlPolicy -> SecurityDelta(old.drop, new.create)
}

private fun changedSqlServer(old: SecurityEntry, new: SecurityEntry): SecurityDelta {
    val before = old.create.single() as CreateSecurityPolicy
    val after = new.create.single() as CreateSecurityPolicy
    val oldPredicates = old.canonical.definition.getValue(
        "predicates",
    ).jsonArray.associateBy { it.jsonObject.getValue("identity") }
    val newPredicates = new.canonical.definition.getValue(
        "predicates",
    ).jsonArray.associateBy { it.jsonObject.getValue("identity") }
    fun runtime(predicates: List<SqlServerSecurityPredicate>, identity: JsonObject): SqlServerSecurityPredicate =
        predicates.single {
            val table = identity.getValue("table").jsonObject
            it.table.schemaName == table.text("schemaName") && it.table.tableName == table.text("name") &&
                (it.timing?.name ?: "Filter") == identity.text("operation")
        }
    val drop = oldPredicates.filter { (id, predicate) -> newPredicates[id] != predicate }.keys.map {
        DropSecurityPredicate(before.name, before.schemaName, runtime(before.predicates, it.jsonObject))
    }
    val add = newPredicates.filter { (id, predicate) -> oldPredicates[id] != predicate }.keys.map {
        AddSecurityPredicate(after.name, after.schemaName, runtime(after.predicates, it.jsonObject))
    }.toMutableList<SecurityDdlStatement>()
    if (before.enabled != after.enabled) add += SetSecurityPolicyEnabled(after.name, after.schemaName, after.enabled)
    return SecurityDelta(drop, add)
}

private fun CanonicalSecurityObject.matches(table: Table): Boolean =
    identity.text("schemaName") == table.schemaName && identity.text("name") == table.tableName
private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.content
private fun creationOrder(statement: SecurityDdlStatement): Int = when (statement) {
    is EnableRowLevelSecurity -> 0
    is ForceRowLevelSecurity -> 1
    else -> 2
}

private fun postgresTableEntry(
    value: CanonicalSecurityObject,
    definitions: List<TableSecurityDefinition>,
): SecurityEntry {
    val definition = definitions.filterIsInstance<PostgresRlsDefinition>().single { value.matches(it.table) }
    return SecurityEntry(
        value,
        buildList {
            add(EnableRowLevelSecurity(definition.table))
            if (definition.force) add(ForceRowLevelSecurity(definition.table))
        },
        buildList {
            if (definition.force) add(ForceRowLevelSecurity(definition.table, false))
            add(DisableRowLevelSecurity(definition.table))
        },
        0,
    )
}
