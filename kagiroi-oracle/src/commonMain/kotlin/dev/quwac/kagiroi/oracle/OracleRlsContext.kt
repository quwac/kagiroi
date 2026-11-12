package dev.quwac.kagiroi.oracle

import dev.quwac.kagiroi.schema.SecurityFunctionReference
import dev.quwac.kagiroi.sql.RenderedSql
import dev.quwac.kagiroi.sql.RlsContextAdapter
import dev.quwac.kagiroi.sql.SqlExecution
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.readRlsContext
import dev.quwac.kagiroi.sql.rlsContextParameter

/**
 * Trusted Oracle package adapter for an explicitly configured Application Context namespace.
 * The existing setter must accept (attribute, value), set exactly that attribute in [namespace],
 * and clear it when value is NULL. It must not commit, modify other context, or suppress failures.
 * Values use native typed bindings; the package must accept the selected scalar mappings.
 *
 * @property namespace database Application Context namespace used by SYS_CONTEXT.
 * @property setter separately provisioned trusted setter procedure, usually a package member.
 */
class OracleRlsContext(val namespace: String, val setter: SecurityFunctionReference) : RlsContextAdapter {
    init {
        require(namespace.isNotBlank()) { "Oracle context namespace must not be blank" }
    }
    override val transactionLocal: Boolean = false
    override fun validate(values: List<Pair<String, Any?>>) {
        values.forEach { (key, _) ->
            require(
                key.encodeToByteArray().size <= ORACLE_CONTEXT_ATTRIBUTE_BYTES,
            ) { "Oracle context attribute exceeds 30 UTF-8 bytes" }
        }
    }
    override suspend fun read(execution: SqlExecution, key: String): Any? = execution.readRlsContext(
        "SELECT SYS_CONTEXT(${execution.dialect.parameterMarker(
            1,
        )}, ${execution.dialect.parameterMarker(2)}, 4000) FROM DUAL",
        listOf(namespace, key),
    )
    override suspend fun set(execution: SqlExecution, key: String, value: Any?) {
        val name = listOfNotNull(setter.schema, setter.packageName, setter.name)
            .joinToString(".", transform = execution.dialect::quoteIdentifier)
        execution.execute(
            RenderedSql(
                "BEGIN $name(${execution.dialect.parameterMarker(1)}, ${execution.dialect.parameterMarker(2)}); END;",
                listOf(rlsContextParameter(key), rlsContextParameter(value)),
            ),
        )
    }
}

private const val ORACLE_CONTEXT_ATTRIBUTE_BYTES = 30
