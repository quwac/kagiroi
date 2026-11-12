package dev.quwac.kagiroi.schema

/** Physical function reference; functions are provisioned separately from security policies.
 * @property name unqualified function name.
 * @property schema optional function schema.
 * @property packageName optional Oracle package.
 */
data class SecurityFunctionReference(val name: String, val schema: String? = null, val packageName: String? = null) {
    init {
        require(name.isNotBlank()) { "Function name must not be blank" }
        require(schema == null || schema.isNotBlank()) { "Function schema must not be blank" }
        require(packageName == null || packageName.isNotBlank()) { "Function package must not be blank" }
    }
}

/** Native table-local security metadata. Public builders live in their dialect modules. */
sealed interface TableSecurityDefinition {
    /** Owning table. */
    val table: Table

    /** Uniqueness key within the owning table. */
    val registrationKey: String
}

/** PostgreSQL policy command, with native ALL semantics. */
enum class PostgresPolicyCommand {
    /** All commands. */
    All,

    /** SELECT. */
    Select,

    /** INSERT. */
    Insert,

    /** UPDATE. */
    Update,

    /** DELETE. */
    Delete,
}

/** PostgreSQL role selector; special principals remain distinct from quoted role names. */
sealed interface PostgresPolicyRole {
    /** Every role. */
    data object Public : PostgresPolicyRole

    /** Current execution role. */
    data object CurrentUser : PostgresPolicyRole

    /** Session login role. */
    data object SessionUser : PostgresPolicyRole

    /** Explicit quoted role.
     * @property name physical role name.
     */
    data class Named(val name: String) : PostgresPolicyRole {
        init {
            require(name.isNotBlank()) { "Role name must not be blank" }
        }
    }
}

/** Immutable PostgreSQL policy declaration.
 * @property name table-local policy name.
 * @property command command selector.
 * @property restrictive whether policies combine restrictively.
 * @property roles principals to which this policy applies.
 * @property using existing-row visibility predicate.
 * @property withCheck new-row validation predicate.
 */
class PostgresPolicyDefinition(
    val name: String,
    val command: PostgresPolicyCommand = PostgresPolicyCommand.All,
    val restrictive: Boolean = false,
    roles: List<PostgresPolicyRole> = listOf(PostgresPolicyRole.Public),
    val using: BooleanExpression? = null,
    val withCheck: BooleanExpression? = null,
) {
    /** Immutable, unordered role set. */
    val roles: List<PostgresPolicyRole> = roles.distinct().toList()
    init {
        require(name.isNotBlank()) { "Policy name must not be blank" }
        require(this.roles.isNotEmpty()) { "Policy requires at least one role" }
        require(command != PostgresPolicyCommand.Insert || using == null) { "INSERT policy cannot have USING" }
        require(command !in listOf(PostgresPolicyCommand.Select, PostgresPolicyCommand.Delete) || withCheck == null) {
            "SELECT and DELETE policies cannot have WITH CHECK"
        }
    }
}

/** PostgreSQL RLS activation and its table-local policies.
 * @property table owning table.
 * @property force whether to apply RLS to the table owner too.
 */
class PostgresRlsDefinition(override val table: Table, val force: Boolean, policies: List<PostgresPolicyDefinition>) :
    TableSecurityDefinition {
    /** Immutable policy declarations. */
    val policies: List<PostgresPolicyDefinition> = policies.toList()
    override val registrationKey: String = "postgresRls"
    init {
        require(this.policies.map { it.name }.distinct().size == this.policies.size) { "Duplicate PostgreSQL policy" }
        this.policies.forEach { policy ->
            listOfNotNull(policy.using, policy.withCheck).forEach { predicate ->
                predicate.referencedColumns.forEach { column ->
                    require(column.table === table && table.hasColumn(column) && !column.isComputed) {
                        "Policy predicate must reference current physical columns of its table"
                    }
                }
            }
        }
    }
}

/** SQL Server block-predicate operation and timing. */
enum class SqlServerBlockTiming {
    /** Check inserted rows. */
    AfterInsert,

    /** Check updated rows. */
    AfterUpdate,

    /** Check rows before update. */
    BeforeUpdate,

    /** Check rows before delete. */
    BeforeDelete,
}

/** A SQL Server filter or block predicate.
 * @property table target table.
 * @property function separately provisioned inline table-valued function.
 * @property timing null for a FILTER predicate; otherwise BLOCK timing.
 */
class SqlServerSecurityPredicate(
    val table: Table,
    val function: SecurityFunctionReference,
    arguments: List<Column<*>>,
    val timing: SqlServerBlockTiming? = null,
) {
    /** Ordered physical function arguments. */
    val arguments: List<Column<*>> = arguments.toList()
    init {
        require(function.packageName == null) { "SQL Server functions cannot reference Oracle packages" }
        this.arguments.forEach {
            require(it.table === table && table.hasColumn(it) && !it.isComputed) {
                "Predicate arguments must be current physical columns of the target table"
            }
        }
    }
}

/** Table-local contribution to an independent SQL Server security policy.
 * @property table declaring table.
 * @property name policy name.
 * @property schemaName optional policy schema.
 * @property enabled initial policy state.
 */
class SqlServerRlsDefinition(
    override val table: Table,
    val name: String,
    val schemaName: String?,
    val enabled: Boolean,
    predicates: List<SqlServerSecurityPredicate>,
) : TableSecurityDefinition {
    /** Immutable predicate contribution. */
    val predicates: List<SqlServerSecurityPredicate> = predicates.toList()
    override val registrationKey: String = "sqlServerRls:${securityIdentity(schemaName, name)}"
    init {
        require(name.isNotBlank() && (schemaName == null || schemaName.isNotBlank())) { "Invalid policy name" }
        require(this.predicates.isNotEmpty()) { "Security policy requires predicates" }
        require(this.predicates.all { it.table === table }) { "Predicate belongs to another table" }
        require(this.predicates.map { it.timing }.distinct().size == this.predicates.size) {
            "Only one predicate per table and operation is allowed"
        }
    }
}

/** Native Oracle VPD statement types. */
enum class OracleVpdStatement {
    /** SELECT. */
    Select,

    /** INSERT. */
    Insert,

    /** UPDATE. */
    Update,

    /** DELETE. */
    Delete,

    /** INDEX. */
    Index,
}

/** Immutable Oracle DBMS_RLS policy definition.
 * @property table target object.
 * @property name object-local policy name.
 * @property function separately provisioned policy function.
 * @property updateCheck whether to validate post-write rows.
 * @property enabled initial policy state.
 */
class OracleVpdDefinition(
    override val table: Table,
    val name: String,
    val function: SecurityFunctionReference,
    statements: Set<OracleVpdStatement>,
    val updateCheck: Boolean,
    val enabled: Boolean,
) : TableSecurityDefinition {
    /** Immutable unordered native statement selection. */
    val statements: Set<OracleVpdStatement> = statements.toSet()
    override val registrationKey: String = "oracleVpd:$name"
    init {
        require(name.isNotBlank()) { "Policy name must not be blank" }
        require(this.statements.isNotEmpty()) { "VPD requires statement types" }
        require(OracleVpdStatement.Insert !in statements || updateCheck) { "Oracle INSERT VPD requires updateCheck()" }
        require(!updateCheck || statements.any { it == OracleVpdStatement.Insert || it == OracleVpdStatement.Update }) {
            "updateCheck requires INSERT or UPDATE"
        }
    }
}
