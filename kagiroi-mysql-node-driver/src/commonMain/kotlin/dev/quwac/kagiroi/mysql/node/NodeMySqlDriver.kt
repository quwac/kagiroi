package dev.quwac.kagiroi.mysql.node

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

internal interface NodeMySqlPool {
    suspend fun acquire(timeoutMilliseconds: Int): NodeMySqlSession

    suspend fun close()
}

internal interface NodeMySqlSession {
    suspend fun execute(statement: MySqlStatement): MySqlCommandResult

    suspend fun query(statement: MySqlStatement): MySqlRows

    fun release(discard: Boolean)
}

internal expect fun createNodeMySqlPool(settings: NodeMySqlSettings): NodeMySqlPool

internal const val POOL_ACQUISITION_TIMEOUT_CODE = "KAGIROI_POOL_ACQUISITION_TIMEOUT"
