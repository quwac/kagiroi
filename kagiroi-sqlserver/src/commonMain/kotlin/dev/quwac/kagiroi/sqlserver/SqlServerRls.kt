@file:OptIn(dev.quwac.kagiroi.schema.InternalSchemaDdlApi::class)

package dev.quwac.kagiroi.sqlserver

import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.schema.InternalSchemaDdlApi
import dev.quwac.kagiroi.schema.SecurityFunctionReference
import dev.quwac.kagiroi.schema.SqlServerBlockTiming
import dev.quwac.kagiroi.schema.SqlServerRlsDefinition
import dev.quwac.kagiroi.schema.SqlServerSecurityPredicate
import dev.quwac.kagiroi.schema.Table

/** AFTER INSERT block timing. */
val AfterInsert: SqlServerBlockTiming = SqlServerBlockTiming.AfterInsert

/** AFTER UPDATE block timing. */
val AfterUpdate: SqlServerBlockTiming = SqlServerBlockTiming.AfterUpdate

/** BEFORE UPDATE block timing. */
val BeforeUpdate: SqlServerBlockTiming = SqlServerBlockTiming.BeforeUpdate

/** BEFORE DELETE block timing. */
val BeforeDelete: SqlServerBlockTiming = SqlServerBlockTiming.BeforeDelete

/** References a separately provisioned schema-qualified predicate function. */
fun function(schema: String, name: String): SecurityFunctionReference = SecurityFunctionReference(name, schema)

/** Registers this table's contribution to a native SQL Server security policy. */
fun Table.sqlServerRls(name: String, schema: String = schemaName ?: "dbo", configure: SqlServerRlsBuilder.() -> Unit) {
    registerSecurity(SqlServerRlsBuilder(this).apply(configure).build(name, schema))
}

/** Table-local SQL Server predicates; contributions merge by policy schema and name. */
class SqlServerRlsBuilder internal constructor(private val table: Table) {
    private var enabled = true
    private val predicates = mutableListOf<SqlServerSecurityPredicate>()

    /** Creates a disabled policy. All contributions to one policy must agree on state. */
    fun disabled() {
        enabled = false
    }

    /** Adds a filter predicate with ordered, typed column arguments. */
    fun filterPredicate(function: SecurityFunctionReference, vararg arguments: Column<*>) {
        predicates += SqlServerSecurityPredicate(table, function, arguments.toList())
    }

    /** Adds a block predicate with native timing and ordered, typed column arguments. */
    fun blockPredicate(function: SecurityFunctionReference, timing: SqlServerBlockTiming, vararg arguments: Column<*>) {
        predicates += SqlServerSecurityPredicate(table, function, arguments.toList(), timing)
    }
    internal fun build(name: String, schema: String): SqlServerRlsDefinition =
        SqlServerRlsDefinition(table, name, schema, enabled, predicates)
}
