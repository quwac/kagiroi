package dev.quwac.kagiroi.mysql.hyperdrive

import dev.quwac.kagiroi.mysql.MySqlBinding
import dev.quwac.kagiroi.mysql.MySqlResultValue

internal data class MySqlStatement(val sql: String, val parameters: List<MySqlBinding>)

internal data class MySqlCommandResult(val affectedRows: Long)

internal data class MySqlRows(val columnNames: List<String>, val rows: List<List<MySqlResultValue>>)

internal enum class MySqlFailureKind {
    CONNECTION,
    EXECUTION,
    RESULT_CONVERSION,
}

internal class MySqlDriverException(
    val kind: MySqlFailureKind,
    val sqlState: String?,
    val vendorCode: String?,
    val errorNumber: Int?,
    val attributes: Map<String, String>,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal interface MySqlEndpoint {
    suspend fun execute(statement: MySqlStatement): MySqlCommandResult

    suspend fun query(statement: MySqlStatement): MySqlRows

    suspend fun close()
}
