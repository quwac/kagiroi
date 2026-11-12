package dev.quwac.kagiroi.schema

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** A physical security object containing only stable JSON values, independent of runtime builders. */
sealed interface CanonicalSecurityObject {
    /** Stable, dialect-specific object discriminator. */
    val kind: String

    /** Qualified physical identity, excluding mutable policy semantics. */
    val identity: JsonObject

    /** Normalized native semantics, including explicit default values. */
    val definition: JsonObject

    /** Encodes the public physical-object projection. */
    fun project(): JsonObject = buildJsonObject {
        put("kind", JsonPrimitive(kind))
        put("identity", identity)
        put("definition", definition)
    }

    /** PostgreSQL table RLS state, separate from its policies.
     * @property identity target table identity.
     * @property definition enabled and force state.
     */
    data class PostgreSqlRlsTable(override val identity: JsonObject, override val definition: JsonObject) :
        CanonicalSecurityObject {
        override val kind: String = "postgresRlsTable"
    }

    /** PostgreSQL policy, keyed by table and policy name.
     * @property identity table and policy identity.
     * @property definition normalized command, roles, mode, and expression ASTs.
     */
    data class PostgreSqlPolicy(override val identity: JsonObject, override val definition: JsonObject) :
        CanonicalSecurityObject {
        override val kind: String = "postgresPolicy"
    }

    /** Independent SQL Server security policy, merged across table contributions.
     * @property identity qualified policy identity.
     * @property definition state and ordered canonical predicate set.
     */
    data class SqlServerSecurityPolicy(override val identity: JsonObject, override val definition: JsonObject) :
        CanonicalSecurityObject {
        override val kind: String = "sqlServerSecurityPolicy"
    }

    /** Oracle object-local VPD policy.
     * @property identity target object and policy identity.
     * @property definition function, statement types, update check, and state.
     */
    data class OracleVpdPolicy(override val identity: JsonObject, override val definition: JsonObject) :
        CanonicalSecurityObject {
        override val kind: String = "oracleVpdPolicy"
    }
}
