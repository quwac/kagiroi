package dev.quwac.kagiroi.postgres.hyperdrive

import dev.quwac.kagiroi.postgres.PostgresBinding
import dev.quwac.kagiroi.postgres.PostgresResultValue

internal data class PostgresStatement(
    val sql: String,
    val parameters: List<PostgresBinding>,
    val preparedName: String? = null,
)

internal data class PostgresCommandResult(val affectedRows: Long)

internal data class PostgresRows(val columnNames: List<String>, val rows: List<List<PostgresResultValue>>)

internal enum class PostgresFailureKind {
    CONNECTION,
    EXECUTION,
    RESULT_CONVERSION,
}

internal class PostgresDriverException(
    val kind: PostgresFailureKind,
    val sqlState: String?,
    val attributes: Map<String, String>,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal interface PostgresEndpoint {
    suspend fun execute(statement: PostgresStatement): PostgresCommandResult

    suspend fun query(statement: PostgresStatement): PostgresRows

    suspend fun close()
}
