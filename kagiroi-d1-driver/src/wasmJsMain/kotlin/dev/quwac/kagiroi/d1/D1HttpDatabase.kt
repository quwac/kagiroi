@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("UnusedParameter", "TooManyFunctions")

package dev.quwac.kagiroi.d1

import dev.quwac.kagiroi.sqlite.SqlitePhysicalValue
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsNumber
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.asJsException
import kotlin.js.toDouble
import kotlin.js.toJsArray
import kotlin.js.toJsNumber
import kotlin.js.toJsString
import kotlin.js.unsafeCast

internal actual suspend fun requestD1Http(
    url: String,
    token: String,
    statements: List<D1Statement>,
    batch: Boolean,
): List<D1HttpResult> {
    val bodies = statements.map { statement ->
        singleBody(statement.sql, statement.parameters.map(::requestValue).toJsArray())
    }
    val body = if (batch) batchBody(bodies.toJsArray()) else bodies.single()
    val results = requestJson(url, token, body).await()
    return results.toList().map { result ->
        val value = requireNotNull(result) { "Expected a D1 HTTP result" }
        if (isFailure(value)) {
            val error = property(value, "error")
            throw D1DriverException(
                D1FailureKind.EXECUTION,
                error?.let { codeProperty(it) },
                error?.let { stringProperty(it, "message") } ?: "D1 statement failed",
            )
        }
        D1HttpResult(metadata(property(value, "meta")), rawRows(property(value, "results")))
    }
}

private fun rawRows(results: JsAny?): D1RawRows {
    if (results == null || !isArray(results) || arraySize(results) == 0) {
        return D1RawRows(emptyList(), emptyList())
    }
    val rows = results.unsafeCast<JsArray<JsAny?>>()
    val names = objectKeys(requireNotNull(rows[0])).toList().filterNotNull().map { it.toString() }
    return D1RawRows(names, rows.toList().map { row -> names.map { physicalValue(property(requireNotNull(row), it)) } })
}

private fun physicalValue(value: JsAny?): SqlitePhysicalValue = when {
    value == null -> SqlitePhysicalValue.Null

    typeOf(value) == "string" -> SqlitePhysicalValue.Text(value.unsafeCast<JsString>().toString())

    typeOf(value) == "number" -> value.unsafeCast<JsNumber>().toDouble().let {
        if (isSafeInteger(it)) SqlitePhysicalValue.Integer(it.toLong().toString()) else SqlitePhysicalValue.Real(it)
    }

    else -> throw D1DriverException(
        D1FailureKind.RESULT_CONVERSION,
        "D1_RESULT_TYPE_ERROR",
        "Unsupported value returned by the D1 HTTP API: ${typeOf(value)}",
    )
}

private fun metadata(meta: JsAny?): D1ExecuteMetadata {
    if (meta == null) return D1ExecuteMetadata()
    return D1ExecuteMetadata(
        servedBy = stringProperty(meta, "served_by") ?: stringProperty(meta, "served_by_colo"),
        servedByRegion = stringProperty(meta, "served_by_region"),
        servedByPrimary = booleanProperty(meta, "served_by_primary"),
        durationMilliseconds = numberProperty(meta, "duration"),
        lastRowId = numberProperty(meta, "last_row_id")?.toLong(),
        changedDatabase = booleanProperty(meta, "changed_db"),
        sizeAfter = numberProperty(meta, "size_after")?.toLong(),
        rowsRead = numberProperty(meta, "rows_read")?.toLong(),
        rowsWritten = numberProperty(meta, "rows_written")?.toLong(),
        totalAttempts = numberProperty(meta, "total_attempts")?.toInt(),
        changes = numberProperty(meta, "changes")?.toLong() ?: 0,
    )
}

private fun requestValue(value: SqlitePhysicalValue): JsAny? = when (value) {
    SqlitePhysicalValue.Null -> null

    is SqlitePhysicalValue.Integer -> value.decimal.toJsString()

    is SqlitePhysicalValue.Real -> value.value.toJsNumber()

    is SqlitePhysicalValue.Text -> value.value.toJsString()

    is SqlitePhysicalValue.Blob -> throw D1DriverException(
        D1FailureKind.INVALID_BINDING,
        "D1_TYPE_ERROR",
        "D1 HTTP API blob bindings are not supported",
    )
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

private fun requestJson(url: String, token: String, body: JsAny): Promise<JsArray<JsAny?>> = js(
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

private fun singleBody(sql: String, params: JsArray<JsAny?>): JsAny = js("params.length ? { sql, params } : { sql }")
private fun batchBody(batch: JsArray<JsAny>): JsAny = js("({ batch })")
private fun isArray(value: JsAny): Boolean = js("Array.isArray(value)")
private fun arraySize(value: JsAny): Int = js("value.length")
private fun objectKeys(value: JsAny): JsArray<JsString?> = js("Object.keys(value)")
private fun property(value: JsAny, name: String): JsAny? = js("value[name] ?? null")
private fun isFailure(value: JsAny): Boolean = js("value.success === false")
private fun typeOf(value: JsAny): String = js("typeof value")
private fun isSafeInteger(value: Double): Boolean = js("Number.isSafeInteger(value)")
private fun stringProperty(value: JsAny, name: String): String? =
    js("typeof value[name] === 'string' ? value[name] : null")
private fun numberProperty(value: JsAny, name: String): Double? =
    js("typeof value[name] === 'number' ? value[name] : null")
private fun booleanProperty(value: JsAny, name: String): Boolean? =
    js("typeof value[name] === 'boolean' ? value[name] : null")

private fun codeProperty(value: JsAny): String? = js("value.code == null ? null : String(value.code)")
