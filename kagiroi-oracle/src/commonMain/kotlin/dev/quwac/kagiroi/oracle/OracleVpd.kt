@file:OptIn(dev.quwac.kagiroi.schema.InternalSchemaDdlApi::class)

package dev.quwac.kagiroi.oracle

import dev.quwac.kagiroi.schema.InternalSchemaDdlApi
import dev.quwac.kagiroi.schema.OracleVpdDefinition
import dev.quwac.kagiroi.schema.OracleVpdStatement
import dev.quwac.kagiroi.schema.SecurityFunctionReference
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.schema

/** SELECT statement type. */
val Select: OracleVpdStatement = OracleVpdStatement.Select

/** INSERT statement type. */
val Insert: OracleVpdStatement = OracleVpdStatement.Insert

/** UPDATE statement type. */
val Update: OracleVpdStatement = OracleVpdStatement.Update

/** DELETE statement type. */
val Delete: OracleVpdStatement = OracleVpdStatement.Delete

/** INDEX statement type. */
val Index: OracleVpdStatement = OracleVpdStatement.Index

/** Stable reference to a separately provisioned policy function or package member. */
fun function(schema: String? = null, packageName: String? = null, name: String): SecurityFunctionReference =
    SecurityFunctionReference(name, schema, packageName)

/** Registers a native DBMS_RLS policy on this table. */
fun Table.oracleVpd(name: String, configure: OracleVpdBuilder.() -> Unit) {
    registerSecurity(OracleVpdBuilder().apply(configure).build(this, name))
}

/** Native Oracle VPD configuration. INSERT automatically enables its mandatory update check. */
class OracleVpdBuilder internal constructor() {
    private var function: SecurityFunctionReference? = null
    private var statements = setOf(Select, Update, Delete)
    private var check = false
    private var enabled = true

    /** References a policy function, optionally inside a package. */
    fun function(schema: String? = null, packageName: String? = null, name: String) {
        function(dev.quwac.kagiroi.oracle.function(schema, packageName, name))
    }

    /** References a separately modeled function without flattening its qualified identity. */
    fun function(reference: SecurityFunctionReference) {
        require(function == null) { "VPD function was already declared" }
        function = reference
    }

    /** Selects an unordered set of native Oracle statement types. */
    fun forStatements(first: OracleVpdStatement, vararg remaining: OracleVpdStatement) {
        statements = (listOf(first) + remaining).toSet()
    }

    /** Checks the post-write row for INSERT/UPDATE. */
    fun updateCheck() {
        check = true
    }

    /** Creates a disabled policy. */
    fun disabled() {
        enabled = false
    }
    internal fun build(table: Table, name: String): OracleVpdDefinition = OracleVpdDefinition(
        table,
        name,
        requireNotNull(function) { "VPD requires a policy function" },
        statements,
        check || Insert in statements,
        enabled,
    )
}
