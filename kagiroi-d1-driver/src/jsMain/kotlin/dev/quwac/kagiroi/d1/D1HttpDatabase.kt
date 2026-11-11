@file:Suppress("TooGenericExceptionCaught", "TooManyFunctions", "UnsafeCastFromDynamic", "UnusedParameter")

package dev.quwac.kagiroi.d1

import dev.quwac.kagiroi.sqlite.SqlitePhysicalValue
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.Promise

internal actual suspend fun requestD1Http(
    url: String,
    token: String,
    statements: List<D1Statement>,
    batch: Boolean,
): List<D1HttpResult> {
    val body = if (batch) batchBody(statements) else singleBody(statements.single())
    val results = requestJson(url, token, body).await() as Array<dynamic>
    return results.map { result ->
        requireStatementSuccess(result)
        D1HttpResult(metadata(result.meta), rawRows(result))
    }
}

private fun requireStatementSuccess(result: dynamic) {
    if (result.success === false) {
        throw D1DriverException(
            D1FailureKind.EXECUTION,
            result.error?.code?.toString(),
            result.error?.message as? String ?: "D1 statement failed",
        )
    }
}

private fun rawRows(result: dynamic): D1RawRows = rawRowsOrNull(result) ?: D1RawRows(emptyList(), emptyList())

private fun rawRowsOrNull(result: dynamic): D1RawRows? {
    requireStatementSuccess(result)
    val rows = result.results
    if (rows == null || !(js("Array.isArray(rows)") as Boolean) || rows.length == 0) return null
    val names = (js("Object.keys(rows[0])") as Array<String>).toList()
    return D1RawRows(
        names,
        (0 until rows.length).map { index ->
            val row = rows[index]
            names.map { name -> physicalValue(row[name]) }
        },
    )
}

private fun metadata(meta: dynamic): D1ExecuteMetadata {
    if (meta == null) return D1ExecuteMetadata()
    return D1ExecuteMetadata(
        servedBy = meta.served_by as? String ?: meta.served_by_colo as? String,
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
}

private fun physicalValue(value: dynamic): SqlitePhysicalValue = when {
    value == null -> SqlitePhysicalValue.Null

    jsTypeOf(value) == "string" -> SqlitePhysicalValue.Text(value as String)

    jsTypeOf(value) == "number" -> (value as Double).let {
        if (isSafeInteger(it)) SqlitePhysicalValue.Integer(it.toLong().toString()) else SqlitePhysicalValue.Real(it)
    }

    else -> throw D1DriverException(
        D1FailureKind.RESULT_CONVERSION,
        "D1_RESULT_TYPE_ERROR",
        "Unsupported value returned by the D1 HTTP API: ${jsTypeOf(value)}",
    )
}

private fun singleBody(statement: D1Statement): dynamic {
    val body = js("({})")
    body.sql = statement.sql
    if (statement.parameters.isNotEmpty()) body.params = statement.parameters.map(::requestValue).toTypedArray()
    return body
}

private fun batchBody(statements: List<D1Statement>): dynamic {
    val body = js("({})")
    body.batch = statements.map(::singleBody).toTypedArray()
    return body
}

private fun requestValue(value: SqlitePhysicalValue): dynamic = when (value) {
    SqlitePhysicalValue.Null -> null

    is SqlitePhysicalValue.Integer -> value.decimal

    is SqlitePhysicalValue.Real -> value.value

    is SqlitePhysicalValue.Text -> value.value

    is SqlitePhysicalValue.Blob -> throw D1DriverException(
        D1FailureKind.INVALID_BINDING,
        "D1_TYPE_ERROR",
        "D1 HTTP API blob bindings are not supported",
    )
}

private suspend fun <T> Promise<T>.await(): T = suspendCancellableCoroutine { continuation ->
    then { value ->
        if (continuation.isActive) continuation.resume(value)
        value
    }.catch { error ->
        if (continuation.isActive) continuation.resumeWithException(error)
        null
    }
}

@Suppress("UnusedParameter")
private fun requestJson(url: String, token: String, body: dynamic): Promise<dynamic> = js(
    """
    globalThis.fetch(url, {
      method: 'POST',
      headers: {
        'Authorization': 'Bearer ' + token,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(body)
    }).then(response => response.json().then(payload => {
      if (!response.ok || payload.success === false) {
        const error = (payload.errors && payload.errors[0]) || {};
        throw new Error(error.message || ('D1 HTTP API request failed with status ' + response.status));
      }
      return payload.result;
    }))
    """,
)

@Suppress("UnusedParameter")
private fun isSafeInteger(value: Double): Boolean = js("Number.isSafeInteger(value)")
