@file:OptIn(dev.quwac.kagiroi.schema.InternalSchemaDdlApi::class)

package dev.quwac.kagiroi.postgres

import dev.quwac.kagiroi.schema.BooleanExpression
import dev.quwac.kagiroi.schema.InternalSchemaDdlApi
import dev.quwac.kagiroi.schema.PostgresPolicyCommand
import dev.quwac.kagiroi.schema.PostgresPolicyDefinition
import dev.quwac.kagiroi.schema.PostgresPolicyRole
import dev.quwac.kagiroi.schema.PostgresRlsDefinition
import dev.quwac.kagiroi.schema.SqlFunction
import dev.quwac.kagiroi.schema.SqlFunctionExpression
import dev.quwac.kagiroi.schema.StringColumnType
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.literal
import dev.quwac.kagiroi.schema.optional
import dev.quwac.kagiroi.schema.resultExpression
import dev.quwac.kagiroi.sql.optional

/** PUBLIC policy principal; also the implicit default. */
val Public: PostgresPolicyRole = PostgresPolicyRole.Public

/** Current execution role. */
val CurrentUser: PostgresPolicyRole = PostgresPolicyRole.CurrentUser

/** Session login role. */
val SessionUser: PostgresPolicyRole = PostgresPolicyRole.SessionUser

/** Creates an explicitly named, quoted PostgreSQL role selector. */
fun role(name: String): PostgresPolicyRole = PostgresPolicyRole.Named(name)

/** Registers native PostgreSQL RLS on this table, implicitly enabling RLS. */
fun Table.postgresRls(configure: PostgresRlsBuilder.() -> Unit) {
    registerSecurity(PostgresRlsBuilder(this).apply(configure).build())
}

/** PostgreSQL table RLS configuration; use from a table initializer. */
class PostgresRlsBuilder internal constructor(private val table: Table) {
    private var forced = false
    private val policies = mutableListOf<PostgresPolicyDefinition>()

    /** Applies RLS to the table owner, subject to PostgreSQL's BYPASSRLS rules. */
    fun force() {
        forced = true
    }

    /** Declares a policy with ALL, PUBLIC, and PERMISSIVE defaults. */
    fun policy(name: String, configure: PostgresPolicyBuilder.() -> Unit) {
        policies += PostgresPolicyBuilder().apply(configure).build(name)
    }
    internal fun build(): PostgresRlsDefinition = PostgresRlsDefinition(table, forced, policies)
}

/** Native PostgreSQL policy clauses. Invalid command/clause pairs fail at registration. */
@Suppress("TooManyFunctions")
class PostgresPolicyBuilder internal constructor() {
    private var command = PostgresPolicyCommand.All
    private var isRestrictive = false
    private var roles: List<PostgresPolicyRole> = listOf(Public)
    private var using: BooleanExpression? = null
    private var check: BooleanExpression? = null

    /** Selects all commands (the default). */
    fun forAll() {
        command = PostgresPolicyCommand.All
    }

    /** Selects SELECT. */
    fun forSelect() {
        command = PostgresPolicyCommand.Select
    }

    /** Selects INSERT. */
    fun forInsert() {
        command = PostgresPolicyCommand.Insert
    }

    /** Selects UPDATE. */
    fun forUpdate() {
        command = PostgresPolicyCommand.Update
    }

    /** Selects DELETE. */
    fun forDelete() {
        command = PostgresPolicyCommand.Delete
    }

    /** Uses restrictive policy composition. */
    fun restrictive() {
        isRestrictive = true
    }

    /** Uses permissive policy composition (the default). */
    fun permissive() {
        isRestrictive = false
    }

    /** Sets the unordered target principal set. */
    fun to(first: PostgresPolicyRole, vararg remaining: PostgresPolicyRole) {
        roles = listOf(first) + remaining
    }

    /** Sets the typed existing-row predicate. */
    fun using(predicate: () -> BooleanExpression) {
        require(using == null) { "USING was already declared" }
        using = predicate()
    }

    /** Sets the typed new-row predicate. */
    fun withCheck(predicate: () -> BooleanExpression) {
        require(check == null) { "WITH CHECK was already declared" }
        check = predicate()
    }
    internal fun build(name: String): PostgresPolicyDefinition =
        PostgresPolicyDefinition(name, command, isRestrictive, roles, using, check)
}

/** Reads a PostgreSQL setting using its complete name; missing settings raise a database error. */
fun currentSetting(name: String): SqlFunctionExpression<String> {
    require(name.isNotBlank()) { "Setting name must not be blank" }
    return SqlFunctionExpression(
        SqlFunction.PostgresCurrentSetting,
        listOf(literal(name)),
        resultExpression(StringColumnType),
    )
}

/** Reads a PostgreSQL setting; with missingOk=true a missing setting produces SQL NULL. */
fun currentSetting(name: String, missingOk: Boolean): SqlFunctionExpression<String?> {
    require(name.isNotBlank()) { "Setting name must not be blank" }
    return SqlFunctionExpression(
        SqlFunction.PostgresCurrentSetting,
        listOf(literal(name), literal(missingOk)),
        resultExpression(StringColumnType).optional,
    )
}
