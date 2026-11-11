@file:Suppress("TooGenericExceptionCaught", "TooManyFunctions", "UnsafeCastFromDynamic")

package dev.quwac.kagiroi.d1

import dev.quwac.kagiroi.sqlite.SqlitePhysicalValue
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.Promise

/** The Cloudflare Workers D1 binding passed through an environment object. */
external interface CloudflareD1Binding

/** Provides the d1 database result. */
fun D1Database(binding: CloudflareD1Binding, configure: D1DatabaseConfiguration.() -> Unit = {}): D1Database =
    D1Database(D1Binding(binding), configure)

@Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
internal actual fun createD1Endpoint(native: Any): D1Endpoint {
    val value = native.asDynamic()
    require(isD1Binding(value)) { "Expected a native D1 binding with prepare and batch operations" }
    return JsD1Endpoint(value)
}

@Suppress("UnusedParameter")
private fun isD1Binding(value: dynamic): Boolean = js(
    "value !== null && typeof value === 'object' && typeof value.prepare === 'function' && typeof value.batch === 'function'",
)

private open class JsD1Endpoint(private val native: dynamic) : D1Endpoint {
    override suspend fun execute(statement: D1Statement): D1ExecuteMetadata = translate(D1FailureKind.EXECUTION) {
        val result = prepared(statement).run().unsafeCast<Promise<dynamic>>().await()
        metadata(requireSuccess(result, D1FailureKind.EXECUTION).meta)
    }

    override suspend fun query(statement: D1Statement): D1RawRows = translate(D1FailureKind.EXECUTION) {
        val rows = prepared(statement).raw(columnNamesOption()).unsafeCast<Promise<Array<Array<dynamic>>>>().await()
        if (rows.isEmpty()) return@translate D1RawRows(emptyList(), emptyList())
        val names = rows.first().map { it as String }
        D1RawRows(names, rows.drop(1).map { row -> row.map(::physicalValue) })
    }

    override suspend fun batch(statements: List<D1Statement>): List<D1BatchOutcome> = translate(D1FailureKind.BATCH) {
        val prepared = statements.map(::prepared).toTypedArray()
        val results = native.batch(prepared).unsafeCast<Promise<Array<dynamic>>>().await()
        results.map { result ->
            val successful = requireSuccess(result, D1FailureKind.BATCH)
            D1BatchOutcome(metadata(successful.meta), batchRows(successful))
        }
    }

    override fun session(start: D1SessionStart): D1SessionEndpoint {
        val constraint = when (start) {
            D1SessionStart.Unconstrained -> "first-unconstrained"
            D1SessionStart.Primary -> "first-primary"
            is D1SessionStart.After -> start.bookmark.value
        }
        return JsD1SessionEndpoint(native.withSession(constraint))
    }

    private fun prepared(statement: D1Statement): dynamic {
        val prepared = native.prepare(statement.sql)
        if (statement.parameters.isEmpty()) return prepared
        return bind(prepared, statement.parameters.map(::bindingValue).toTypedArray())
    }
}

private class JsD1SessionEndpoint(private val session: dynamic) :
    JsD1Endpoint(session),
    D1SessionEndpoint {
    override fun bookmark(): D1Bookmark? = bookmarkValue(session.getBookmark())?.let(::D1Bookmark)
}

private fun requireSuccess(result: dynamic, defaultKind: D1FailureKind): dynamic {
    if (result.success === false) {
        val detail = (result.error?.message as? String) ?: "D1 statement failed"
        throw D1DriverException(defaultKind, result.error?.name as? String, detail)
    }
    return result
}

private fun batchRows(result: dynamic): D1RawRows? {
    val rows = result.results
    if (rows == null || !(js("Array.isArray(rows)") as Boolean) || rows.length == 0) return null
    val first = rows[0]
    val names = (js("Object.keys(first)") as Array<String>).toList()
    val values = (0 until rows.length).map { index ->
        val row = rows[index]
        names.map { name -> physicalValue(row[name]) }
    }
    return D1RawRows(names, values)
}

private fun bookmarkValue(value: dynamic): String? = when {
    value == null -> null
    jsTypeOf(value) == "string" -> value as String
    else -> value.bookmark as? String
}

private fun bindingValue(value: SqlitePhysicalValue): dynamic = when (value) {
    SqlitePhysicalValue.Null -> null
    is SqlitePhysicalValue.Integer -> safeInteger(value.decimal)
    is SqlitePhysicalValue.Real -> value.value
    is SqlitePhysicalValue.Text -> value.value
    is SqlitePhysicalValue.Blob -> value.value.copyOf()
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

private fun physicalValue(value: dynamic): SqlitePhysicalValue = when {
    value == null -> SqlitePhysicalValue.Null

    jsTypeOf(value) == "string" -> SqlitePhysicalValue.Text(value as String)

    jsTypeOf(value) == "number" -> (value as Double).let {
        if (isSafeInteger(it)) SqlitePhysicalValue.Integer(it.toLong().toString()) else SqlitePhysicalValue.Real(it)
    }

    isByteArray(value) -> {
        val length = value.length as Int
        SqlitePhysicalValue.Blob(ByteArray(length) { index -> (value[index] as Number).toByte() })
    }

    js("Array.isArray(value)") as Boolean -> {
        val array = value as Array<Number>
        SqlitePhysicalValue.Blob(ByteArray(array.size) { array[it].toByte() })
    }

    else -> throw D1DriverException(
        D1FailureKind.RESULT_CONVERSION,
        "D1_RESULT_TYPE_ERROR",
        "Unsupported value returned by D1: ${jsTypeOf(value)}",
    )
}

private fun metadata(meta: dynamic): D1ExecuteMetadata = D1ExecuteMetadata(
    servedBy = meta.served_by as? String,
    servedByRegion = meta.served_by_region as? String,
    servedByPrimary = meta.served_by_primary as? Boolean,
    durationMilliseconds = (meta.duration as? Number)?.toDouble(),
    lastRowId = (meta.last_row_id as? Number)?.toLong(),
    changedDatabase = meta.changed_db as? Boolean,
    sizeAfter = (meta.size_after as? Number)?.toLong(),
    rowsRead = (meta.rows_read as? Number)?.toLong(),
    rowsWritten = (meta.rows_written as? Number)?.toLong(),
    totalAttempts = (meta.total_attempts as? Number)?.toInt(),
    changes = (meta.changes as? Number)?.toLong() ?: 0,
)

private suspend inline fun <T> translate(defaultKind: D1FailureKind, crossinline block: suspend () -> T): T = try {
    block()
} catch (error: D1DriverException) {
    throw error
} catch (error: Throwable) {
    val native = error.asDynamic()
    val detail = (native.cause?.message as? String) ?: error.message ?: error.toString()
    val code = (native.name as? String)?.takeUnless { it == "Error" } ?: errorPrefix(detail)
    val kind = when {
        code == "D1_TYPE_ERROR" -> D1FailureKind.INVALID_BINDING
        detail.contains("constraint", ignoreCase = true) -> D1FailureKind.CONSTRAINT
        else -> defaultKind
    }
    throw D1DriverException(kind, code, detail, error)
}

private fun errorPrefix(message: String): String? = message.substringBefore(':').takeIf { it.startsWith("D1_") }

private fun columnNamesOption(): dynamic = js("({columnNames: true})")

@Suppress("UnusedParameter")
private fun bind(statement: dynamic, values: Array<dynamic>): dynamic = js("statement.bind(...values)")

@Suppress("UnusedParameter")
private fun isSafeInteger(value: Double): Boolean = js("Number.isSafeInteger(value)")

@Suppress("UnusedParameter")
private fun isByteArray(value: dynamic): Boolean = js("value instanceof Uint8Array || value instanceof Int8Array")

private suspend fun <T> Promise<T>.await(): T = suspendCancellableCoroutine { continuation ->
    then { value ->
        if (continuation.isActive) continuation.resume(value)
        value
    }.catch { error ->
        if (continuation.isActive) continuation.resumeWithException(error)
        null
    }
}
