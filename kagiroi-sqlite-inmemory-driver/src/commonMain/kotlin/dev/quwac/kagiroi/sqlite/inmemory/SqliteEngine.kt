package dev.quwac.kagiroi.sqlite.inmemory

import dev.quwac.kagiroi.sqlite.SqlitePhysicalValue

internal typealias SqliteValue = SqlitePhysicalValue

internal typealias SqliteBinding = SqlitePhysicalValue

internal data class SqliteEngineRow(val columnNames: List<String>, val values: List<SqliteValue>)

internal class SqliteEngineException(message: String, val resultCode: Int?, cause: Throwable? = null) :
    Exception(message, cause)

internal interface SqliteEngine {
    fun execute(sql: String, parameters: List<SqliteBinding>): Long

    fun query(sql: String, parameters: List<SqliteBinding>): List<SqliteEngineRow>

    fun close()
}

internal expect suspend fun openInMemorySqliteEngine(): SqliteEngine
