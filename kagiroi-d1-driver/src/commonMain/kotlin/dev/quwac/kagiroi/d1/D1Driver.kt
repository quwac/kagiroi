package dev.quwac.kagiroi.d1

import dev.quwac.kagiroi.sqlite.SqlitePhysicalValue

internal data class D1Statement(val sql: String, val parameters: List<SqlitePhysicalValue>)

internal data class D1RawRows(val columnNames: List<String>, val rows: List<List<SqlitePhysicalValue>>)

internal data class D1BatchOutcome(val metadata: D1ExecuteMetadata, val rows: D1RawRows? = null)

internal enum class D1FailureKind {
    EXECUTION,
    CONSTRAINT,
    INVALID_BINDING,
    RESULT_CONVERSION,
    BATCH,
}

internal class D1DriverException(
    val kind: D1FailureKind,
    val vendorCode: String?,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal interface D1Endpoint {
    suspend fun execute(statement: D1Statement): D1ExecuteMetadata

    suspend fun query(statement: D1Statement): D1RawRows

    suspend fun batch(statements: List<D1Statement>): List<D1BatchOutcome>

    fun session(start: D1SessionStart): D1SessionEndpoint
}

internal interface D1SessionEndpoint : D1Endpoint {
    fun bookmark(): D1Bookmark?
}
