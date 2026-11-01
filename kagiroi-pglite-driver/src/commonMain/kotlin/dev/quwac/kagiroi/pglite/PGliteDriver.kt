package dev.quwac.kagiroi.pglite

import dev.quwac.kagiroi.postgres.PostgresBinding
import dev.quwac.kagiroi.postgres.PostgresResultValue

internal data class PGliteSettings(val dataDirectory: String, val relaxedDurability: Boolean)

internal data class PGliteStatement(val sql: String, val parameters: List<PostgresBinding>)

internal data class PGliteCommandResult(val affectedRows: Long)

internal data class PGliteRows(val columnNames: List<String>, val rows: List<List<PostgresResultValue>>)

internal enum class PGliteFailureKind {
    INITIALIZATION,
    EXECUTION,
    RESULT_CONVERSION,
}

internal class PGliteDriverException(
    val kind: PGliteFailureKind,
    val sqlState: String?,
    val attributes: Map<String, String>,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Internal bridge to the PGlite JavaScript API; no PGlite values cross the public API boundary. */
internal interface PGliteEndpoint {
    suspend fun execute(statement: PGliteStatement): PGliteCommandResult

    suspend fun query(statement: PGliteStatement): PGliteRows

    suspend fun close()
}

internal expect suspend fun createPGliteEndpoint(settings: PGliteSettings): PGliteEndpoint
