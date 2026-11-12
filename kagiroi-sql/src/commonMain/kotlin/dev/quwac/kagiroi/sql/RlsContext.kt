package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.BooleanColumnType
import dev.quwac.kagiroi.schema.IntColumnType
import dev.quwac.kagiroi.schema.LongColumnType
import dev.quwac.kagiroi.schema.StringColumnType
import dev.quwac.kagiroi.schema.UuidColumnType
import dev.quwac.kagiroi.schema.resultExpression
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.Uuid

/** Dialect-owned binding of a transaction-local RLS scope to native database context operations. */
interface RlsContextAdapter {
    /** Whether this adapter uses transaction-local state which rolls back with a PostgreSQL savepoint. */
    val transactionLocal: Boolean

    /** Validates every key and value before any context is changed. */
    fun validate(values: List<Pair<String, Any?>>)

    /** Reads the previous native value, using bound parameters for context names. */
    suspend fun read(execution: SqlExecution, key: String): Any?

    /** Sets a native value or clears it with null. Implementations must bind all values. */
    suspend fun set(execution: SqlExecution, key: String, value: Any?)
}

/** Dialect support for selecting a default native context adapter. */
interface SqlRlsContextDialect : SqlDialect {
    /** Native adapter; Oracle requires an explicit trusted-package adapter. */
    val rlsContextAdapter: RlsContextAdapter
}

/**
 * Driver guard for session-local context. A failed cleanup makes the transaction unusable and
 * the physical session must be discarded before it can reenter a pool.
 */
interface RlsContextConnectionGuard {
    /** Marks the transaction unusable and requires physical-session discard. Must not throw. */
    fun invalidateRlsContext(cause: Throwable)

    /** Rejects overlapping context scopes on this physical transaction. */
    fun acquireRlsContext()

    /** Releases the transaction's scope guard. */
    fun releaseRlsContext()
}

/**
 * Sets native context on the transaction's physical session. Complete keys are used verbatim.
 * Nested scopes are rejected. String, Int, Long, Boolean, and UUID values retain typed bindings;
 * PostgreSQL converts those scalar values to the text required by its settings infrastructure.
 * Session-local adapters require initially empty keys, clear them in NonCancellable cleanup, and
 * invalidate the physical connection on cleanup failure. The returned value must not defer queries
 * (for example, an uncollected Flow) beyond this scope.
 */
suspend fun <T> SqlTransaction.rlsContext(
    vararg values: Pair<String, Any?>,
    adapter: RlsContextAdapter = (dialect as? SqlRlsContextDialect)?.rlsContextAdapter
        ?: throw UnsupportedCapability("native-rls-context"),
    block: suspend SqlTransaction.() -> T,
): T {
    check(currentCoroutineContext()[ActiveRlsContext] == null) { "Nested RLS context scopes are not supported" }
    require(values.isNotEmpty()) { "RLS context requires values" }
    require(values.map { it.first }.distinct().size == values.size) { "Duplicate RLS context keys" }
    values.forEach { (key, value) ->
        require(key.isNotBlank()) { "RLS context key must not be blank" }
        rlsContextParameter(value)
    }
    adapter.validate(values.toList())
    val guard = this as? RlsContextConnectionGuard
    check(adapter.transactionLocal || guard != null) { "Driver cannot safely discard a contaminated RLS session" }
    guard?.acquireRlsContext()
    try {
        return withContext(ActiveRlsContext()) {
            if (adapter.transactionLocal) {
                savepoint {
                    val previous = values.associate { it.first to adapter.read(this, it.first) }
                    values.forEach { (key, value) -> adapter.set(this, key, value) }
                    val result = this@rlsContext.block()
                    values.reversed().forEach { (key, _) -> adapter.set(this, key, previous[key]) }
                    result
                }
            } else {
                withSessionContext(adapter, values.toList(), requireNotNull(guard), block)
            }
        }
    } finally {
        guard?.releaseRlsContext()
    }
}

private suspend fun <T> SqlTransaction.withSessionContext(
    adapter: RlsContextAdapter,
    values: List<Pair<String, Any?>>,
    guard: RlsContextConnectionGuard,
    block: suspend SqlTransaction.() -> T,
): T {
    values.forEach { (key, _) ->
        check(adapter.read(this, key) == null) { "RLS context key is already populated: $key" }
    }
    val attempted = mutableListOf<String>()
    val result = runCatching {
        values.forEach { (key, value) ->
            attempted += key
            adapter.set(this, key, value)
        }
        block()
    }
    val cleanupFailures = withContext(NonCancellable) {
        attempted.reversed().mapNotNull { key ->
            runCatching { adapter.set(this@withSessionContext, key, null) }.exceptionOrNull()
        }
    }
    cleanupFailures.firstOrNull()?.let { error ->
        cleanupFailures.drop(1).forEach(error::addSuppressed)
        guard.invalidateRlsContext(error)
        val original = result.exceptionOrNull()
        if (original == null) throw error else original.addSuppressed(error)
    }
    return result.getOrThrow()
}

/** Builds a bound scalar using Kagiroi's existing physical type mappings. */
fun rlsContextParameter(value: Any?): SqlParameter<*> = when (value) {
    null -> SqlParameter(null, null)
    is String -> SqlParameter(value, resultExpression(StringColumnType))
    is Int -> SqlParameter(value, resultExpression(IntColumnType))
    is Long -> SqlParameter(value, resultExpression(LongColumnType))
    is Boolean -> SqlParameter(value, resultExpression(BooleanColumnType))
    is Uuid -> SqlParameter(value, resultExpression(UuidColumnType))
    else -> throw IllegalArgumentException("Unsupported RLS context value; use String, Int, Long, Boolean, or UUID")
}

/** Reads exactly one native context value through the ordinary raw-row driver boundary. */
suspend fun SqlExecution.readRlsContext(sql: String, parameters: List<Any?>): Any? {
    val rows = queryRaw(sql, parameters).toList()
    require(rows.size == 1 && rows.single().size == 1) { "Context getter must return one scalar" }
    return rows.single()[0]
}

private class ActiveRlsContext : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ActiveRlsContext>
}
