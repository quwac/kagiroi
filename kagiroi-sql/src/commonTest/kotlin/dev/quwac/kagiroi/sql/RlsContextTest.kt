package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.SqlExpression
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class RlsContextTest {
    @Test
    fun clearsEveryKeyAfterSuccessAndPreservesTypedValues() = runTest {
        val tx = FakeTransaction()
        val adapter = FakeAdapter()
        val uuid = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val result = tx.rlsContext(
            "str" to "a'",
            "int" to 3,
            "long" to Long.MAX_VALUE,
            "bool" to true,
            "uuid" to uuid,
            adapter = adapter,
        ) {
            assertEquals(listOf("a'", 3, Long.MAX_VALUE, true, uuid), adapter.values.values.toList())
            "done"
        }
        assertEquals("done", result)
        assertTrue(adapter.values.values.all { it == null })
        assertEquals(listOf("uuid", "bool", "long", "int", "str"), adapter.cleared)
        assertFalse(tx.guard.requiresDiscard)
        assertEquals(Long.MAX_VALUE, rlsContextParameter(Long.MAX_VALUE).value)
        assertEquals(uuid, rlsContextParameter(uuid).value)
    }

    @Test
    fun clearsPartialSetupAndPreservesOriginalFailure() = runTest {
        val tx = FakeTransaction()
        val adapter = FakeAdapter(failSet = "second")
        val failure = assertFailsWith<IllegalStateException> {
            tx.rlsContext("first" to 1, "second" to 2, adapter = adapter) { error("must not run") }
        }
        assertEquals("setup failed", failure.message)
        assertEquals(listOf("second", "first"), adapter.cleared)
        assertTrue(adapter.values.values.all { it == null })
    }

    @Test
    fun cancellationCleansInNonCancellableAndCleanupFailurePoisonsTransaction() = runTest {
        val tx = FakeTransaction()
        val adapter = FakeAdapter(failClear = true)
        val original = CancellationException("cancelled")
        val thrown = assertFailsWith<CancellationException> {
            tx.rlsContext("tenant" to "a", adapter = adapter) { throw original }
        }
        assertSame(original, thrown)
        assertEquals("cleanup failed", thrown.suppressedExceptions.single().message)
        assertTrue(adapter.cleanupActive)
        assertTrue(tx.guard.requiresDiscard)
        assertFailsWith<ExecutionFailure> { tx.guard.requireUsable() }
    }

    @Test
    fun cleanupFailureCannotBeCaughtAndCommitted() = runTest {
        val tx = FakeTransaction()
        assertFailsWith<IllegalStateException> {
            tx.rlsContext("tenant" to "a", adapter = FakeAdapter(failClear = true)) { 1 }
        }
        assertFailsWith<ExecutionFailure> { tx.executeRaw("SELECT 1") }
    }

    @Test
    fun rejectsNestedPreexistingDuplicateAndUnsupportedValuesBeforeMutation() = runTest {
        val tx = FakeTransaction()
        val adapter = FakeAdapter()
        tx.rlsContext("tenant" to "a", adapter = adapter) {
            assertFailsWith<IllegalStateException> { rlsContext("tenant" to "b", adapter = adapter) {} }
            assertEquals("a", adapter.values["tenant"])
        }
        adapter.values["tenant"] = "existing"
        assertFailsWith<IllegalStateException> { tx.rlsContext("tenant" to "a", adapter = adapter) {} }
        assertEquals("existing", adapter.values["tenant"])
        assertFailsWith<IllegalArgumentException> { tx.rlsContext("a" to 1, "a" to 2, adapter = adapter) {} }
        assertFailsWith<IllegalArgumentException> { tx.rlsContext("a" to listOf(1), adapter = adapter) {} }
    }

    private class FakeAdapter(private val failSet: String? = null, private val failClear: Boolean = false) :
        RlsContextAdapter {
        val values = linkedMapOf<String, Any?>()
        val cleared = mutableListOf<String>()
        var cleanupActive = false
        override val transactionLocal: Boolean = false
        override fun validate(values: List<Pair<String, Any?>>) = Unit
        override suspend fun read(execution: SqlExecution, key: String): Any? = values[key]
        override suspend fun set(execution: SqlExecution, key: String, value: Any?) {
            values[key] = value
            if (value == null) {
                cleanupActive = currentCoroutineContext().isActive
                cleared += key
                if (failClear) error("cleanup failed")
            } else if (key == failSet) {
                error("setup failed")
            }
        }
    }
    private class FakeTransaction(val guard: RlsContextGuard = RlsContextGuard()) :
        SqlTransaction,
        RlsContextConnectionGuard by guard {
        override val dialect: SqlDialect = object : SqlDialect {
            override fun quoteIdentifier(identifier: String): String = identifier
            override fun parameterMarker(position: Int): String = "?"
        }
        override suspend fun execute(statement: RenderedSql): ExecuteResult {
            guard.requireUsable()
            return ExecuteResult(
                0,
            )
        }
        override suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult {
            guard.requireUsable()
            return ExecuteResult(
                0,
            )
        }
        override fun query(statement: RenderedSql, columns: List<SqlExpression<*>>): Flow<SqlRow> = emptyFlow()
        override fun queryRaw(sql: String, parameters: List<Any?>): Flow<RawSqlRow> = emptyFlow()
        override suspend fun <T> savepoint(block: suspend SavepointScope.() -> T): T = block()
    }
}
