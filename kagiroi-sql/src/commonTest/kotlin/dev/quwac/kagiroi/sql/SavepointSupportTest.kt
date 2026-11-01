package dev.quwac.kagiroi.sql

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class SavepointSupportTest {
    @Test
    fun completesNestedSavepointsWithGeneratedNames() = runTest {
        val operations = mutableListOf<String>()
        val support = savepointSupport(operations)

        assertEquals(
            "result",
            support.savepoint(FakeExecution) {
                savepoint { "result" }
            },
        )
        assertEquals(
            listOf(
                "create:kagiroi_sp_1",
                "create:kagiroi_sp_2",
                "release:kagiroi_sp_2",
                "release:kagiroi_sp_1",
            ),
            operations,
        )
    }

    @Test
    fun rollsBackAndRethrowsTheOriginalFailure() = runTest {
        val operations = mutableListOf<String>()
        val support = savepointSupport(operations)
        val original = ExpectedFailure()

        assertSame(
            original,
            assertFailsWith<ExpectedFailure> {
                support.savepoint(FakeExecution) { throw original }
            },
        )
        assertEquals(
            listOf("create:kagiroi_sp_1", "rollback:kagiroi_sp_1", "release:kagiroi_sp_1"),
            operations,
        )
    }

    @Test
    fun rollsBackAndRethrowsCancellation() = runTest {
        val operations = mutableListOf<String>()
        val support = savepointSupport(operations)
        val cancellation = CancellationException("cancelled")

        assertSame(
            cancellation,
            assertFailsWith<CancellationException> {
                support.savepoint(FakeExecution) { throw cancellation }
            },
        )
        assertEquals(
            listOf("create:kagiroi_sp_1", "rollback:kagiroi_sp_1", "release:kagiroi_sp_1"),
            operations,
        )
    }

    @Test
    fun retainsRollbackFailureAndRejectsFurtherTransactionWork() = runTest {
        val rollbackFailure = IllegalStateException("rollback")
        val support = SavepointSupport(
            SavepointController(
                create = {},
                rollbackTo = { throw rollbackFailure },
            ),
        )
        val original = ExpectedFailure()

        val propagated = assertFailsWith<ExpectedFailure> {
            support.savepoint(FakeExecution) { throw original }
        }
        assertSame(original, propagated)
        assertContains(propagated.suppressedExceptions.toList(), rollbackFailure)
        assertSame(
            rollbackFailure,
            assertFailsWith<TransactionFailure> { support.requireUsable() }.cause,
        )
    }

    @Test
    fun propagatesReleaseFailureAndRejectsFurtherTransactionWork() = runTest {
        val releaseFailure = IllegalStateException("release")
        val support = SavepointSupport(
            SavepointController(
                create = {},
                rollbackTo = {},
                release = { throw releaseFailure },
            ),
        )

        assertSame(
            releaseFailure,
            assertFailsWith<TransactionFailure> { support.savepoint(FakeExecution) { Unit } }.cause,
        )
        assertSame(
            releaseFailure,
            assertFailsWith<TransactionFailure> { support.requireUsable() }.cause,
        )
    }

    @Test
    fun deactivatesTheSavepointScopeAfterItsBlockReturns() = runTest {
        val support = savepointSupport(mutableListOf())
        var escaped: SavepointScope? = null

        support.savepoint(FakeExecution) { escaped = this }

        assertFailsWith<ExecutionFailure> { escaped!!.executeRaw("SELECT 1") }
    }

    private fun savepointSupport(operations: MutableList<String>): SavepointSupport = SavepointSupport(
        SavepointController(
            create = { name -> operations += "create:$name" },
            rollbackTo = { name -> operations += "rollback:$name" },
            release = { name -> operations += "release:$name" },
        ),
    )
}

private data object FakeExecution : SqlExecution {
    override val dialect: SqlDialect = DoubleQuotedQuestionMarkDialect

    override suspend fun execute(statement: RenderedSql): ExecuteResult = ExecuteResult(0)

    override fun query(statement: RenderedSql, columns: List<dev.quwac.kagiroi.schema.SqlExpression<*>>): Flow<SqlRow> =
        emptyFlow()

    override suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult = ExecuteResult(0)

    override fun queryRaw(sql: String, parameters: List<Any?>): Flow<RawSqlRow> = emptyFlow()
}

private class ExpectedFailure : RuntimeException()
