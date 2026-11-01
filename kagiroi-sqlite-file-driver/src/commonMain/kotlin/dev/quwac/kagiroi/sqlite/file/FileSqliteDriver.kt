package dev.quwac.kagiroi.sqlite.file

import dev.quwac.kagiroi.sqlite.SqlitePhysicalValue

internal typealias FileSqliteBinding = SqlitePhysicalValue

internal typealias FileSqliteValue = SqlitePhysicalValue

internal data class FileSqliteEngineRow(val columnNames: List<String>, val values: List<FileSqliteValue>)

internal enum class FileSqliteFailureKind {
    OPEN,
    EXECUTION,
    RESULT_CONVERSION,
    CLOSE,
}

/** Host-adapter failure metadata retained until the common Kagiroi error boundary. */
internal class FileSqliteDriverException(
    val kind: FileSqliteFailureKind,
    val sqliteCode: String?,
    val extendedResultCode: Int?,
    val attributes: Map<String, String>,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Minimal internal surface implemented by the JavaScript host adapter. */
internal interface FileSqliteEngine {
    fun execute(sql: String, parameters: List<FileSqliteBinding>): Long

    fun query(sql: String, parameters: List<FileSqliteBinding>): List<FileSqliteEngineRow>

    fun close()
}

internal data class FileSqliteSettings(
    val path: String,
    val readOnly: Boolean,
    val fileMustExist: Boolean,
    val busyTimeoutMilliseconds: Int,
)

internal expect fun openFileSqliteEngine(settings: FileSqliteSettings): FileSqliteEngine
