package dev.quwac.kagiroi.oracle.node

import dev.quwac.kagiroi.oracle.OracleBinding
import dev.quwac.kagiroi.oracle.OracleResultValue

internal data class OracleStatement(
    val sql: String,
    val parameters: List<OracleBinding>,
    val returning: List<OracleReturningBinding> = emptyList(),
)

internal data class OracleCommandResult(
    val affectedRows: Long,
    val returningValues: List<List<OracleResultValue>> = emptyList(),
)

internal data class OracleRows(val columnNames: List<String>, val rows: List<List<OracleResultValue>>)

internal enum class OracleReturningKind {
    TEXT,
    DECIMAL,
    BOOLEAN,
    BYTES,
}

internal data class OracleReturningBinding(val name: String, val kind: OracleReturningKind, val maxSize: Int? = null)

internal enum class OracleFailureKind {
    CONNECTION,
    AUTHENTICATION,
    EXECUTION,
    RESULT_CONVERSION,
    TIMEOUT,
}

internal class OracleDriverException(
    val kind: OracleFailureKind,
    val errorNumber: Int?,
    val sqlState: String?,
    val attributes: Map<String, String>,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal interface NodeOraclePool {
    suspend fun acquire(): NodeOracleSession

    suspend fun close()
}

internal interface NodeOracleSession {
    suspend fun execute(statement: OracleStatement): OracleCommandResult

    suspend fun query(statement: OracleStatement): OracleRows

    suspend fun commit()

    suspend fun release(discard: Boolean)
}

internal expect fun createNodeOraclePool(settings: NodeOracleSettings): NodeOraclePool
