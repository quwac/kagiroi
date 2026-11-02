@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("FunctionNaming", "PropertyName", "TooManyFunctions", "VariableNaming")

package dev.quwac.kagiroi.sqlite.inmemory

import dev.quwac.kagiroi.sqlite.SqlitePhysicalValue
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.JsAny
import kotlin.js.JsBigInt
import kotlin.js.JsException
import kotlin.js.JsNumber
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.asJsException
import kotlin.js.definedExternally
import kotlin.js.toDouble
import kotlin.js.toJsBigInt
import kotlin.js.toJsString
import kotlin.js.toLong
import kotlin.js.unsafeCast

@JsModule("@sqlite.org/sqlite-wasm")
private external fun initializeSqlite(): Promise<SqliteStatic>

private external interface SqliteStatic : JsAny {
    val oo1: SqliteObjectOrientedApi
    val capi: SqliteCapi
}

private external interface SqliteCapi : JsAny {
    fun sqlite3_bind_null(statement: JsAny, index: Int): Int
    fun sqlite3_bind_int64(statement: JsAny, index: Int, value: JsBigInt): Int
    fun sqlite3_bind_double(statement: JsAny, index: Int, value: Double): Int
}

private external interface SqliteObjectOrientedApi : JsAny {
    val DB: JsAny
}

private external interface NativeDatabase : JsAny {
    fun prepare(sql: String): NativeStatement
    fun changes(total: Boolean = definedExternally, sixtyFour: Boolean = definedExternally): Int
    fun close()
}

private external interface NativeStatement : JsAny {
    val columnCount: Int
    val pointer: JsAny
    fun bind(index: Int, value: JsAny?): NativeStatement
    fun bindAsBlob(index: Int, value: Uint8Array): NativeStatement
    fun step(): Boolean
    fun get(index: Int): JsAny?
    fun getColumnName(index: Int): String
    fun finalize(): Int?
}

private external class Uint8Array(length: Int) : JsAny {
    val length: Int
    operator fun get(index: Int): Int
    operator fun set(index: Int, value: Int)
}

@Suppress("TooGenericExceptionCaught")
internal actual suspend fun openInMemorySqliteEngine(): SqliteEngine = try {
    val sqlite = initializeSqlite().await()
    WasmSqliteEngine(createDatabase(sqlite.oo1.DB), sqlite.capi)
} catch (error: Throwable) {
    if (error is CancellationException) throw error
    throw error.asSqliteEngineException()
}

private class WasmSqliteEngine(private val database: NativeDatabase, private val capi: SqliteCapi) : SqliteEngine {
    override fun execute(sql: String, parameters: List<SqliteBinding>): Long = translateError {
        val statement = database.prepare(sql)
        try {
            bind(statement, parameters)
            val totalChangesBefore = database.changes(true, false)
            statement.step()
            val totalChangesAfter = database.changes(true, false)
            if (totalChangesAfter == totalChangesBefore) 0 else database.changes(false, false).toLong()
        } finally {
            statement.finalize()
        }
    }

    override fun query(sql: String, parameters: List<SqliteBinding>): List<SqliteEngineRow> = translateError {
        val statement = database.prepare(sql)
        try {
            bind(statement, parameters)
            val columnNames = (0 until statement.columnCount).map(statement::getColumnName)
            buildList {
                while (statement.step()) {
                    val values = (0 until statement.columnCount).map { statement.get(it).toSqliteValue() }
                    add(SqliteEngineRow(columnNames, values))
                }
            }
        } finally {
            statement.finalize()
        }
    }

    override fun close() {
        translateError { database.close() }
    }

    private fun bind(statement: NativeStatement, parameters: List<SqliteBinding>) {
        parameters.forEachIndexed { index, value ->
            val parameterIndex = index + 1
            val statementPointer = statement.pointer
            val resultCode = when (value) {
                SqlitePhysicalValue.Null -> capi.sqlite3_bind_null(statementPointer, parameterIndex)

                is SqlitePhysicalValue.Integer ->
                    capi.sqlite3_bind_int64(statementPointer, parameterIndex, value.decimal.toLong().toJsBigInt())

                is SqlitePhysicalValue.Real -> capi.sqlite3_bind_double(statementPointer, parameterIndex, value.value)

                is SqlitePhysicalValue.Text -> {
                    statement.bind(parameterIndex, value.value.toJsString())
                    SQLITE_OK
                }

                is SqlitePhysicalValue.Blob -> {
                    statement.bindAsBlob(parameterIndex, value.value.toUint8Array())
                    SQLITE_OK
                }
            }
            if (resultCode != SQLITE_OK) {
                throw SqliteEngineException("SQLite parameter binding failed", resultCode)
            }
        }
    }
}

private fun JsAny?.toSqliteValue(): SqliteValue {
    if (this == null) return SqlitePhysicalValue.Null
    return when (jsType(this)) {
        "string" -> SqlitePhysicalValue.Text(unsafeCast<JsString>().toString())

        "bigint" -> SqlitePhysicalValue.Integer(unsafeCast<JsBigInt>().toLong().toString())

        "number" -> unsafeCast<JsNumber>().toDouble().let { number ->
            if (isSafeInteger(number)) {
                SqlitePhysicalValue.Integer(number.toLong().toString())
            } else {
                SqlitePhysicalValue.Real(number)
            }
        }

        "object" -> if (isByteArray(this)) {
            val bytes = unsafeCast<Uint8Array>()
            SqlitePhysicalValue.Blob(ByteArray(bytes.length) { bytes[it].toByte() })
        } else {
            throw IllegalArgumentException("Unsupported object returned by SQLite")
        }

        else -> throw IllegalArgumentException("Unsupported value returned by SQLite: ${jsType(this)}")
    }
}

private fun ByteArray.toUint8Array(): Uint8Array = Uint8Array(size).also { output ->
    forEachIndexed { index, value -> output[index] = value.toInt() and BYTE_MASK }
}

// Native JavaScript may throw values which Kotlin exposes only as Throwable at this boundary.
@Suppress("TooGenericExceptionCaught")
private inline fun <T> translateError(block: () -> T): T = try {
    block()
} catch (error: Throwable) {
    throw error.asSqliteEngineException()
}

private fun Throwable.asSqliteEngineException(): SqliteEngineException {
    if (this is SqliteEngineException) return this
    val thrown = (this as? JsException)?.thrownValue
    return SqliteEngineException(message ?: toString(), thrown?.let(::resultCode), this)
}

private suspend fun <T : JsAny?> Promise<T>.await(): T = suspendCoroutine { continuation ->
    then { value ->
        continuation.resume(value)
        value
    }.catch { error ->
        continuation.resumeWithException(error.asJsException())
        null
    }
}

@Suppress("UnusedParameter")
private fun createDatabase(constructor: JsAny): NativeDatabase = js("new constructor(':memory:')")

@Suppress("UnusedParameter")
private fun jsType(value: JsAny): String = js("typeof value")

@Suppress("UnusedParameter")
private fun isSafeInteger(value: Double): Boolean = js("Number.isSafeInteger(value)")

@Suppress("UnusedParameter")
private fun isByteArray(value: JsAny): Boolean = js("value instanceof Uint8Array || value instanceof Int8Array")

@Suppress("UnusedParameter")
private fun resultCode(value: JsAny): Int? = js("typeof value.resultCode === 'number' ? value.resultCode : null")

private const val BYTE_MASK = 0xff
private const val SQLITE_OK = 0
