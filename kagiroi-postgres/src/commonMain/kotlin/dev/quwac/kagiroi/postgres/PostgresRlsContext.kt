package dev.quwac.kagiroi.postgres

import dev.quwac.kagiroi.sql.RlsContextAdapter
import dev.quwac.kagiroi.sql.SqlExecution
import dev.quwac.kagiroi.sql.readRlsContext

/** PostgreSQL transaction-local custom settings; keys are complete names such as app.tenant_id. */
object PostgresRlsContext : RlsContextAdapter {
    override val transactionLocal: Boolean = true
    override fun validate(values: List<Pair<String, Any?>>) {
        values.forEach { (key, _) ->
            require(Regex("[A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z_][A-Za-z0-9_]*").matches(key)) {
                "PostgreSQL RLS context requires a complete custom setting name, such as app.tenant_id"
            }
        }
    }
    override suspend fun read(execution: SqlExecution, key: String): Any? = execution.readRlsContext(
        "SELECT current_setting(${execution.dialect.parameterMarker(1)}, true)",
        listOf(key),
    )
    override suspend fun set(execution: SqlExecution, key: String, value: Any?) {
        execution.executeRaw(
            "SELECT set_config(${execution.dialect.parameterMarker(1)}, ${execution.dialect.parameterMarker(2)}, true)",
            listOf(key, value?.toString().orEmpty()),
        )
    }
}
