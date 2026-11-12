package dev.quwac.kagiroi.sqlserver.node

import dev.quwac.kagiroi.sql.DatabaseError
import dev.quwac.kagiroi.sql.ExecutionFailure
import dev.quwac.kagiroi.sql.RlsContextAdapter
import dev.quwac.kagiroi.sql.SqlExecution
import dev.quwac.kagiroi.sql.rlsContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RlsCleanupTest {
    @Test
    fun failedCleanupRejectsCommitAndDiscardsPhysicalConnection() = runTest {
        val transaction = FakeTransaction()
        val database = NodeSqlServerDatabase.create(FakePool(transaction))
        val adapter = object : RlsContextAdapter {
            override val transactionLocal: Boolean = false
            override fun validate(values: List<Pair<String, Any?>>) = Unit
            override suspend fun read(execution: SqlExecution, key: String): Any? = null
            override suspend fun set(execution: SqlExecution, key: String, value: Any?) {
                if (value == null) error("cleanup failed")
            }
        }
        assertFailsWith<DatabaseError> {
            database.transaction {
                assertFailsWith<IllegalStateException> {
                    rlsContext("tenant" to 1, adapter = adapter) { Unit }
                }
                assertFailsWith<ExecutionFailure> { executeRaw("SELECT 1") }
            }
        }
        assertTrue(transaction.discarded)
        assertEquals(0, transaction.commits)
        assertEquals(1, transaction.rollbacks)
    }

    private class FakeTransaction : NodeSqlServerTransaction {
        var discarded = false
        var commits = 0
        var rollbacks = 0
        override suspend fun discard() {
            discarded = true
        }
        override suspend fun commit() {
            commits++
        }
        override suspend fun rollback() {
            rollbacks++
        }
        override suspend fun execute(statement: SqlServerStatement): SqlServerCommandResult = SqlServerCommandResult(0)
        override suspend fun query(statement: SqlServerStatement): SqlServerRows = SqlServerRows(
            emptyList(),
            emptyList(),
        )
    }
    private class FakePool(private val transaction: FakeTransaction) : NodeSqlServerPool {
        override val supportsDedicatedSession: Boolean = false
        override suspend fun begin(isolation: NodeSqlServerIsolation?): NodeSqlServerTransaction = transaction
        override suspend fun <T> dedicatedSession(block: suspend (NodeSqlServerSession) -> T): T = error("unused")
        override suspend fun close() = Unit
        override suspend fun execute(statement: SqlServerStatement): SqlServerCommandResult = transaction.execute(
            statement,
        )
        override suspend fun query(statement: SqlServerStatement): SqlServerRows = transaction.query(statement)
    }
}
