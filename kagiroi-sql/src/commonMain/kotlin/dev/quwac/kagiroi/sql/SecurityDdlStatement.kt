package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.OracleVpdDefinition
import dev.quwac.kagiroi.schema.PostgresPolicyDefinition
import dev.quwac.kagiroi.schema.PostgresRlsDefinition
import dev.quwac.kagiroi.schema.SqlServerSecurityPredicate
import dev.quwac.kagiroi.schema.Table

/** Semantic native-security DDL, kept separate from ALTER TABLE operations. */
sealed interface SecurityDdlStatement : DdlStatement

/** Enables native PostgreSQL RLS.
 * @property table native operation table.
 */
class EnableRowLevelSecurity(val table: Table) : SecurityDdlStatement

/** Disables native PostgreSQL RLS.
 * @property table native operation table.
 */
class DisableRowLevelSecurity(val table: Table) : SecurityDdlStatement

/** Changes PostgreSQL owner enforcement.
 * @property table native operation table.
 * @property force native operation force.
 */
class ForceRowLevelSecurity(val table: Table, val force: Boolean = true) : SecurityDdlStatement

/** Creates a native PostgreSQL policy.
 * @property table native operation table.
 * @property policy native operation policy.
 */
class CreatePostgresPolicy(val table: Table, val policy: PostgresPolicyDefinition) : SecurityDdlStatement {
    init {
        // Standalone DDL receives the same column-ownership validation as table registration.
        PostgresRlsDefinition(table, force = false, policies = listOf(policy))
    }
}

/** Drops a native PostgreSQL policy.
 * @property table native operation table.
 * @property name native operation name.
 */
class DropPostgresPolicy(val table: Table, val name: String) : SecurityDdlStatement

/** Creates an independent SQL Server policy with merged table predicates.
 * @property name native operation name.
 * @property schemaName native operation schemaName.
 * @property enabled native operation enabled.
 * @property predicates native operation predicates.
 */
class CreateSecurityPolicy(
    val name: String,
    val schemaName: String?,
    val enabled: Boolean,
    predicates: List<SqlServerSecurityPredicate>,
) : SecurityDdlStatement {
    /** Immutable merged predicate snapshot. */
    val predicates: List<SqlServerSecurityPredicate> = predicates.toList()
}

/** Drops an independent SQL Server security policy.
 * @property name native operation name.
 * @property schemaName native operation schemaName.
 */
class DropSecurityPolicy(val name: String, val schemaName: String?) : SecurityDdlStatement

/** Changes SQL Server policy state.
 * @property name native operation name.
 * @property schemaName native operation schemaName.
 * @property enabled native operation enabled.
 */
class SetSecurityPolicyEnabled(val name: String, val schemaName: String?, val enabled: Boolean) : SecurityDdlStatement

/** Adds a SQL Server filter or block predicate.
 * @property name native operation name.
 * @property schemaName native operation schemaName.
 * @property predicate native operation predicate.
 */
class AddSecurityPredicate(val name: String, val schemaName: String?, val predicate: SqlServerSecurityPredicate) :
    SecurityDdlStatement

/** Drops a SQL Server predicate by table and operation.
 * @property name native operation name.
 * @property schemaName native operation schemaName.
 * @property predicate native operation predicate.
 */
class DropSecurityPredicate(val name: String, val schemaName: String?, val predicate: SqlServerSecurityPredicate) :
    SecurityDdlStatement

/** Creates a native Oracle VPD policy.
 * @property policy native operation policy.
 */
class CreateVpdPolicy(val policy: OracleVpdDefinition) : SecurityDdlStatement

/** Drops a native Oracle VPD policy.
 * @property table native operation table.
 * @property name native operation name.
 */
class DropVpdPolicy(val table: Table, val name: String) : SecurityDdlStatement

/** Changes Oracle VPD policy state.
 * @property table native operation table.
 * @property name native operation name.
 * @property enabled native operation enabled.
 */
class SetVpdPolicyEnabled(val table: Table, val name: String, val enabled: Boolean) : SecurityDdlStatement
