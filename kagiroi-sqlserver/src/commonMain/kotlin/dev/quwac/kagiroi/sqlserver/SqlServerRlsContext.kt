package dev.quwac.kagiroi.sqlserver

import dev.quwac.kagiroi.schema.length
import dev.quwac.kagiroi.sql.RenderedSql
import dev.quwac.kagiroi.sql.RlsContextAdapter
import dev.quwac.kagiroi.sql.SqlExecution
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.readRlsContext
import dev.quwac.kagiroi.sql.rlsContextParameter

/** SQL Server session context, cleared before transaction completion and connection release. */
object SqlServerRlsContext : RlsContextAdapter {
    override val transactionLocal: Boolean = false
    override fun validate(values: List<Pair<String, Any?>>) {
        values.forEach { (key, _) ->
            require(
                key.length <= SQLSERVER_CONTEXT_KEY_CHARACTERS,
            ) { "SESSION_CONTEXT key exceeds 128 UTF-16 bytes" }
        }
    }
    override suspend fun read(execution: SqlExecution, key: String): Any? = execution.readRlsContext(
        "SELECT SESSION_CONTEXT(${execution.dialect.parameterMarker(1)})",
        listOf(key),
    )
    override suspend fun set(execution: SqlExecution, key: String, value: Any?) {
        execution.execute(
            RenderedSql(
                "EXEC sys.sp_set_session_context @key = ${execution.dialect.parameterMarker(1)}, " +
                    "@value = ${execution.dialect.parameterMarker(2)}, @read_only = 0",
                listOf(rlsContextParameter(key), rlsContextParameter(value)),
            ),
        )
    }
}

private const val SQLSERVER_CONTEXT_KEY_CHARACTERS = 64
