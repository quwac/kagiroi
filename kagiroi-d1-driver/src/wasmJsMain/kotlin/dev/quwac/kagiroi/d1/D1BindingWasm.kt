@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("FunctionNaming", "PropertyName", "TooGenericExceptionCaught", "TooManyFunctions")

package dev.quwac.kagiroi.d1

import dev.quwac.kagiroi.sqlite.SqlitePhysicalValue
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsBoolean
import kotlin.js.JsException
import kotlin.js.JsNumber
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.asJsException
import kotlin.js.toBoolean
import kotlin.js.toDouble
import kotlin.js.toJsArray
import kotlin.js.toJsNumber
import kotlin.js.toJsString
import kotlin.js.unsafeCast

/** The Cloudflare Workers D1 binding passed through an environment object. */
external interface CloudflareD1Binding : JsAny

/** Provides the d1 database result. */
fun D1Database(binding: CloudflareD1Binding, configure: D1DatabaseConfiguration.() -> Unit = {}): D1Database =
    D1Database.create(WasmD1Endpoint(binding.unsafeCast()), configure)

private external interface NativeD1Endpoint : JsAny {
    fun prepare(query: String): NativeD1Statement
    fun batch(statements: JsArray<NativeD1Statement>): Promise<JsArray<NativeD1Result>>
    fun withSession(constraint: String): NativeD1Session
}

private external interface NativeD1Session : NativeD1Endpoint {
    fun getBookmark(): JsString?
}

private external interface NativeD1Statement : JsAny {
    fun run(): Promise<NativeD1Result>
    fun raw(options: JsAny): Promise<JsArray<JsArray<JsAny?>>>
}

private external interface NativeD1Result : JsAny {
    val success: Boolean
    val error: JsAny?
    val meta: NativeD1Metadata
    val results: JsArray<JsAny?>?
}

private external interface NativeD1Metadata : JsAny {
    val served_by: JsAny?
    val served_by_region: JsAny?
    val served_by_primary: JsAny?
    val duration: JsAny?
    val last_row_id: JsAny?
    val changed_db: JsAny?
    val size_after: JsAny?
    val rows_read: JsAny?
    val rows_written: JsAny?
    val total_attempts: JsAny?
    val changes: JsAny?
}

private open class WasmD1Endpoint(private val native: NativeD1Endpoint) : D1Endpoint {
    override suspend fun execute(statement: D1Statement): D1ExecuteMetadata = translate(D1FailureKind.EXECUTION) {
        metadata(prepared(statement).run().await().meta)
    }

    override suspend fun query(statement: D1Statement): D1RawRows = translate(D1FailureKind.EXECUTION) {
        val rows = prepared(statement).raw(columnNamesOption()).await()
        if (rows.length == 0) return@translate D1RawRows(emptyList(), emptyList())
        val names = rows[0]?.toList().orEmpty().map { it!!.unsafeCast<JsString>().toString() }
        val values = (1 until rows.length).map { rowIndex ->
            rows[rowIndex]?.toList().orEmpty().map(::physicalValue)
        }
        D1RawRows(names, values)
    }

    override suspend fun batch(statements: List<D1Statement>): List<D1BatchOutcome> = translate(D1FailureKind.BATCH) {
        native.batch(statements.map(::prepared).toJsArray()).await().toList().filterNotNull().map { result ->
            requireSuccess(result, D1FailureKind.BATCH)
            D1BatchOutcome(metadata(result.meta), batchRows(result))
        }
    }

    override fun session(start: D1SessionStart): D1SessionEndpoint {
        val constraint = when (start) {
            D1SessionStart.Unconstrained -> "first-unconstrained"
            D1SessionStart.Primary -> "first-primary"
            is D1SessionStart.After -> start.bookmark.value
        }
        return WasmD1SessionEndpoint(native.withSession(constraint))
    }

    private fun prepared(statement: D1Statement): NativeD1Statement {
        val prepared = native.prepare(statement.sql)
        if (statement.parameters.isEmpty()) return prepared
        return bind(prepared, statement.parameters.map(::bindingValue).toJsArray())
    }
}

private class WasmD1SessionEndpoint(private val session: NativeD1Session) :
    WasmD1Endpoint(session),
    D1SessionEndpoint {
    override fun bookmark(): D1Bookmark? = bookmarkValue(session.getBookmark())?.let(::D1Bookmark)
}

private fun requireSuccess(result: NativeD1Result, defaultKind: D1FailureKind): NativeD1Result {
    if (!result.success) {
        val code = result.error?.let(::errorName)
        val detail = result.error?.let(::errorMessage) ?: "D1 statement failed"
        throw D1DriverException(defaultKind, code, detail)
    }
    return result
}

private fun batchRows(result: NativeD1Result): D1RawRows? {
    val rows = result.results ?: return null
    if (rows.length == 0) return null
    val names = objectKeys(rows[0] ?: return null).toList().filterNotNull().map { it.toString() }
    val values = (0 until rows.length).map { index ->
        val row = rows[index] ?: return@map emptyList()
        names.map { name -> physicalValue(objectValue(row, name)) }
    }
    return D1RawRows(names, values)
}

private fun bookmarkValue(value: JsAny?): String? = when {
    value == null -> null

    jsType(value) == "string" -> value.unsafeCast<JsString>().toString()

    else -> objectValue(value, "bookmark")
        ?.takeIf { jsType(it) == "string" }
        ?.unsafeCast<JsString>()
        ?.toString()
}

private fun bindingValue(value: SqlitePhysicalValue): JsAny? = when (value) {
    SqlitePhysicalValue.Null -> null
    is SqlitePhysicalValue.Integer -> safeInteger(value.decimal).toJsNumber()
    is SqlitePhysicalValue.Real -> value.value.toJsNumber()
    is SqlitePhysicalValue.Text -> value.value.toJsString()
    is SqlitePhysicalValue.Blob -> value.value.toUint8Array()
}

private fun safeInteger(decimal: String): Double {
    val value = decimal.toDoubleOrNull()
        ?: throw D1DriverException(D1FailureKind.INVALID_BINDING, "D1_TYPE_ERROR", "Invalid integer binding")
    if (!isSafeInteger(value) || value.toLong().toString() != decimal) {
        throw D1DriverException(
            D1FailureKind.INVALID_BINDING,
            "D1_TYPE_ERROR",
            "D1 cannot bind an integer outside the JavaScript safe integer range",
        )
    }
    return value
}

private fun physicalValue(value: JsAny?): SqlitePhysicalValue {
    if (value == null) return SqlitePhysicalValue.Null
    return when (jsType(value)) {
        "string" -> SqlitePhysicalValue.Text(value.unsafeCast<JsString>().toString())

        "number" -> value.unsafeCast<JsNumber>().toDouble().let {
            if (isSafeInteger(it)) SqlitePhysicalValue.Integer(it.toLong().toString()) else SqlitePhysicalValue.Real(it)
        }

        "object" -> if (isByteArray(value) || isArray(value)) {
            SqlitePhysicalValue.Blob(byteArray(value))
        } else {
            throw IllegalArgumentException("Unsupported object returned by D1")
        }

        else -> throw D1DriverException(
            D1FailureKind.RESULT_CONVERSION,
            "D1_RESULT_TYPE_ERROR",
            "Unsupported value returned by D1: ${jsType(value)}",
        )
    }
}

private fun metadata(meta: NativeD1Metadata): D1ExecuteMetadata = D1ExecuteMetadata(
    servedBy = meta.served_by.stringOrNull(),
    servedByRegion = meta.served_by_region.stringOrNull(),
    servedByPrimary = meta.served_by_primary.booleanOrNull(),
    durationMilliseconds = meta.duration.numberOrNull(),
    lastRowId = meta.last_row_id.numberOrNull()?.toLong(),
    changedDatabase = meta.changed_db.booleanOrNull(),
    sizeAfter = meta.size_after.numberOrNull()?.toLong(),
    rowsRead = meta.rows_read.numberOrNull()?.toLong(),
    rowsWritten = meta.rows_written.numberOrNull()?.toLong(),
    totalAttempts = meta.total_attempts.numberOrNull()?.toInt(),
    changes = meta.changes.numberOrNull()?.toLong() ?: 0,
)

private suspend inline fun <T> translate(defaultKind: D1FailureKind, crossinline block: suspend () -> T): T = try {
    block()
} catch (error: D1DriverException) {
    throw error
} catch (error: Throwable) {
    val thrown = (error as? JsException)?.thrownValue
    val detail = thrown?.let(::errorMessage) ?: error.message ?: error.toString()
    val code = thrown?.let(::errorName)?.takeUnless { it == "Error" } ?: errorPrefix(detail)
    val kind = when {
        code == "D1_TYPE_ERROR" -> D1FailureKind.INVALID_BINDING
        containsConstraint(detail) -> D1FailureKind.CONSTRAINT
        else -> defaultKind
    }
    throw D1DriverException(kind, code, detail, error)
}

private fun errorPrefix(message: String): String? = message.substringBefore(':').takeIf { it.startsWith("D1_") }

private fun JsAny?.stringOrNull(): String? =
    takeIf { it != null && jsType(it) == "string" }?.unsafeCast<JsString>()?.toString()

private fun JsAny?.numberOrNull(): Double? =
    takeIf { it != null && jsType(it) == "number" }?.unsafeCast<JsNumber>()?.toDouble()

private fun JsAny?.booleanOrNull(): Boolean? =
    takeIf { it != null && jsType(it) == "boolean" }?.unsafeCast<JsBoolean>()?.toBoolean()

private fun ByteArray.toUint8Array(): Uint8Array = Uint8Array(size).also { output ->
    forEachIndexed { index, value -> output[index] = value.toInt() and BYTE_MASK }
}

private external class Uint8Array(length: Int) : JsAny {
    val length: Int
    operator fun get(index: Int): Int
    operator fun set(index: Int, value: Int)
}

private fun byteArray(value: JsAny): ByteArray {
    val length = arrayLength(value)
    return ByteArray(length) { index -> arrayByte(value, index).toByte() }
}

private suspend fun <T : JsAny?> Promise<T>.await(): T = suspendCancellableCoroutine { continuation ->
    then { value ->
        if (continuation.isActive) continuation.resume(value)
        value
    }.catch { error ->
        if (continuation.isActive) continuation.resumeWithException(error.asJsException())
        null
    }
}

@Suppress("UnusedParameter")
private fun bind(statement: NativeD1Statement, values: JsArray<JsAny?>): NativeD1Statement =
    js("statement.bind(...values)")

private fun columnNamesOption(): JsAny = js("({columnNames: true})")

@Suppress("UnusedParameter")
private fun jsType(value: JsAny): String = js("typeof value")

@Suppress("UnusedParameter")
private fun isSafeInteger(value: Double): Boolean = js("Number.isSafeInteger(value)")

@Suppress("UnusedParameter")
private fun isByteArray(value: JsAny): Boolean = js("value instanceof Uint8Array || value instanceof Int8Array")

@Suppress("UnusedParameter")
private fun isArray(value: JsAny): Boolean = js("Array.isArray(value)")

@Suppress("UnusedParameter")
private fun objectKeys(value: JsAny): JsArray<JsString> = js("Object.keys(value)")

@Suppress("UnusedParameter")
private fun objectValue(value: JsAny, key: String): JsAny? = js("value[key]")

@Suppress("UnusedParameter")
private fun errorMessage(value: JsAny): String = js("value && (value.message || String(value))")

@Suppress("UnusedParameter")
private fun errorName(value: JsAny): String? = js("value && value.name")

@Suppress("UnusedParameter")
private fun arrayLength(value: JsAny): Int = js("value.length")

@Suppress("UnusedParameter")
private fun arrayByte(value: JsAny, index: Int): Int = js("value[index]")

@Suppress("UnusedParameter")
private fun containsConstraint(value: String): Boolean = js("value.toLowerCase().includes('constraint')")

private const val BYTE_MASK = 0xff
