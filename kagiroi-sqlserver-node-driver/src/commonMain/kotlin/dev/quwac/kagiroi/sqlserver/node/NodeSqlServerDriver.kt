package dev.quwac.kagiroi.sqlserver.node

import dev.quwac.kagiroi.sqlserver.SqlServerBinding
import dev.quwac.kagiroi.sqlserver.SqlServerResultValue

internal data class SqlServerStatement(val sql: String, val parameters: List<SqlServerBinding>)

internal data class SqlServerCommandResult(val affectedRows: Long)

internal data class SqlServerRows(val columnNames: List<String>, val rows: List<List<SqlServerResultValue>>)

internal enum class SqlServerFailureKind {
    CONNECTION,
    EXECUTION,
    RESULT_CONVERSION,
}

internal class SqlServerDriverException(
    val kind: SqlServerFailureKind,
    val errorNumber: Int?,
    val code: String?,
    val attributes: Map<String, String>,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal interface NodeSqlServerSession {
    suspend fun execute(statement: SqlServerStatement): SqlServerCommandResult

    suspend fun query(statement: SqlServerStatement): SqlServerRows
}

internal interface NodeSqlServerTransaction : NodeSqlServerSession {
    suspend fun commit()

    suspend fun rollback()
}

internal interface NodeSqlServerPool : NodeSqlServerSession {
    val supportsDedicatedSession: Boolean

    suspend fun begin(isolation: NodeSqlServerIsolation?): NodeSqlServerTransaction

    /**
     * Runs [block] on one checked-out physical connection without starting a transaction.
     *
     * This is intentionally separate from [begin]: callers that execute a SQL script with its
     * own BEGIN/COMMIT must retain the connection while leaving transaction boundaries in SQL.
     */
    suspend fun <T> dedicatedSession(block: suspend (NodeSqlServerSession) -> T): T

    suspend fun close()
}

internal enum class NodeSqlServerIsolation {
    READ_COMMITTED,
    REPEATABLE_READ,
    SERIALIZABLE,
}

internal expect fun createNodeSqlServerPool(settings: NodeSqlServerSettings): NodeSqlServerPool

internal expect fun createNodeSqlServerPool(connectionString: String): NodeSqlServerPool
