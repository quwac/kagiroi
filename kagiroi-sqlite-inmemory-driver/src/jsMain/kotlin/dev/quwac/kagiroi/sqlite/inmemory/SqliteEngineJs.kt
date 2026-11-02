@file:Suppress("UnsafeCastFromDynamic")

package dev.quwac.kagiroi.sqlite.inmemory

import dev.quwac.kagiroi.sqlite.SqlitePhysicalValue
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Promise

@JsModule("@sqlite.org/sqlite-wasm")
@JsNonModule
private external object SqliteWasmModule {
    @JsName("default")
    fun initialize(): Promise<dynamic>
}

@Suppress("TooGenericExceptionCaught")
internal actual suspend fun openInMemorySqliteEngine(): SqliteEngine = try {
    val sqlite = SqliteWasmModule.initialize().await()
    val database = js("new sqlite.oo1.DB(':memory:')")
    JsSqliteEngine(database, sqlite.capi)
} catch (error: Throwable) {
    if (error is CancellationException) throw error
    throw error.asSqliteEngineException()
}

private class JsSqliteEngine(private val database: dynamic, private val capi: dynamic) : SqliteEngine {
    override fun execute(sql: String, parameters: List<SqliteBinding>): Long = translateError {
        val statement = database.prepare(sql)
        try {
            bind(statement, parameters)
            val totalChangesBefore = (database.changes(true, false) as Number).toLong()
            statement.step()
            val totalChangesAfter = (database.changes(true, false) as Number).toLong()
            if (totalChangesAfter == totalChangesBefore) 0 else (database.changes(false, false) as Number).toLong()
        } finally {
            statement.finalize()
        }
    }

    override fun query(sql: String, parameters: List<SqliteBinding>): List<SqliteEngineRow> = translateError {
        val statement = database.prepare(sql)
        try {
            bind(statement, parameters)
            val columnCount = statement.columnCount as Int
            val columnNames = (0 until columnCount).map { statement.getColumnName(it) as String }
            buildList {
                while (statement.step() as Boolean) {
                    val values = (0 until columnCount).map { toSqliteValue(statement.get(it)) }
                    add(SqliteEngineRow(columnNames, values))
                }
            }
        } finally {
            statement.finalize()
        }
    }

    override fun close() {
        translateError<Unit> { database.close() }
    }

    private fun bind(statement: dynamic, parameters: List<SqliteBinding>) {
        parameters.forEachIndexed { index, value ->
            val parameterIndex = index + 1
            val statementPointer = statement.pointer
            val result = when (value) {
                SqlitePhysicalValue.Null -> {
                    capi.sqlite3_bind_null(statementPointer, parameterIndex)
                    SQLITE_OK
                }

                is SqlitePhysicalValue.Integer ->
                    capi.sqlite3_bind_int64(statementPointer, parameterIndex, bigInt(value.decimal))

                is SqlitePhysicalValue.Real -> capi.sqlite3_bind_double(statementPointer, parameterIndex, value.value)

                is SqlitePhysicalValue.Text -> {
                    statement.bind(parameterIndex, value.value)
                    SQLITE_OK
                }

                is SqlitePhysicalValue.Blob -> {
                    statement.bindAsBlob(parameterIndex, value.value)
                    SQLITE_OK
                }
            }
            val resultCode = (result as Number).toInt()
            if (resultCode != SQLITE_OK) {
                throw SqliteEngineException("SQLite parameter binding failed", resultCode)
            }
        }
    }
}

private fun toSqliteValue(value: dynamic): SqliteValue = when {
    value == null -> SqlitePhysicalValue.Null

    jsTypeOf(value) == "string" -> SqlitePhysicalValue.Text(value as String)

    jsTypeOf(value) == "bigint" -> SqlitePhysicalValue.Integer(value.toString())

    jsTypeOf(value) == "number" -> {
        val number = value as Double
        if (isSafeInteger(number)) {
            SqlitePhysicalValue.Integer(number.toLong().toString())
        } else {
            SqlitePhysicalValue.Real(number)
        }
    }

    isByteArray(value) -> {
        val length = value.length as Int
        SqlitePhysicalValue.Blob(ByteArray(length) { index -> (value[index] as Number).toByte() })
    }

    else -> throw IllegalArgumentException("Unsupported value returned by SQLite: ${jsTypeOf(value)}")
}

@Suppress("UnusedParameter")
private fun bigInt(value: String): dynamic = js("BigInt(value)")

@Suppress("UnusedParameter")
private fun isSafeInteger(value: Double): Boolean = js("Number.isSafeInteger(value)")

@Suppress("UnusedParameter")
private fun isByteArray(value: dynamic): Boolean = js("value instanceof Uint8Array || value instanceof Int8Array")

// Native JavaScript may throw values which Kotlin exposes only as Throwable at this boundary.
@Suppress("TooGenericExceptionCaught")
private inline fun <T> translateError(block: () -> T): T = try {
    block()
} catch (error: Throwable) {
    throw error.asSqliteEngineException()
}

private fun Throwable.asSqliteEngineException(): SqliteEngineException {
    if (this is SqliteEngineException) return this
    val native = asDynamic()
    val code = (native.resultCode as? Number)?.toInt()
    return SqliteEngineException(message ?: toString(), code, this)
}

private suspend fun <T> Promise<T>.await(): T = suspendCoroutine { continuation ->
    then { value ->
        continuation.resume(value)
        value
    }.catch { error ->
        continuation.resumeWithException(error)
        null
    }
}

private const val SQLITE_OK = 0
