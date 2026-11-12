@file:Suppress("Indentation", "TrailingCommaOnCallSite")

package dev.quwac.kagiroi.oracle.node

import dev.quwac.kagiroi.oracle.OracleBinding
import dev.quwac.kagiroi.oracle.OracleResultValue
import dev.quwac.kagiroi.oracle.OracleScalar
import dev.quwac.kagiroi.oracle.returningInto
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.sql.ConnectionFailure
import dev.quwac.kagiroi.sql.ConstraintKind
import dev.quwac.kagiroi.sql.ConstraintViolation
import dev.quwac.kagiroi.sql.DatabaseClosed
import dev.quwac.kagiroi.sql.DeadlockFailure
import dev.quwac.kagiroi.sql.ReturningIntoCapability
import dev.quwac.kagiroi.sql.SerializationFailure
import dev.quwac.kagiroi.sql.SqlCapabilities
import dev.quwac.kagiroi.sql.SqlStreamingMode
import dev.quwac.kagiroi.sql.TransactionIsolation
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.rlsContext
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.transaction
import dev.quwac.kagiroi.sql.values
import dev.quwac.kagiroi.sql.where
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class NodeOracleDatabaseTest {
    @Test
    fun rlsCleanupKeepsOriginalFailureWhenSessionDiscardAlsoFails() = runTest {
        val pool = FakeNodeOraclePool().apply { releaseFailure = IllegalStateException("discard failed") }
        val database = NodeOracleDatabase.create(pool)
        val adapter = object : dev.quwac.kagiroi.sql.RlsContextAdapter {
            override val transactionLocal: Boolean = false
            override fun validate(values: List<Pair<String, Any?>>) = Unit
            override suspend fun read(execution: dev.quwac.kagiroi.sql.SqlExecution, key: String): Any? = null
            override suspend fun set(execution: dev.quwac.kagiroi.sql.SqlExecution, key: String, value: Any?) {
                if (value == null) error("cleanup failed")
            }
        }
        val original = IllegalStateException("query failed")
        val thrown = assertFailsWith<IllegalStateException> {
            database.transaction { rlsContext("TENANT" to "a", adapter = adapter) { throw original } }
        }
        assertSame(original, thrown)
        assertEquals(listOf("cleanup failed", "discard failed"), thrown.suppressedExceptions.map { it.message })
        assertTrue(pool.sessions.single().discarded)
    }

    @Test
    fun failedRlsCleanupRejectsCommitAndDiscardsTheSession() = runTest {
        val pool = FakeNodeOraclePool()
        val database = NodeOracleDatabase.create(pool)
        val adapter = object : dev.quwac.kagiroi.sql.RlsContextAdapter {
            override val transactionLocal: Boolean = false
            override fun validate(values: List<Pair<String, Any?>>) = Unit
            override suspend fun read(execution: dev.quwac.kagiroi.sql.SqlExecution, key: String): Any? = null
            override suspend fun set(execution: dev.quwac.kagiroi.sql.SqlExecution, key: String, value: Any?) {
                if (value == null) error("cleanup failed")
            }
        }
        assertFailsWith<dev.quwac.kagiroi.sql.DatabaseError> {
            database.transaction {
                assertFailsWith<IllegalStateException> {
                    rlsContext("TENANT" to "a", adapter = adapter) { Unit }
                }
                assertFailsWith<dev.quwac.kagiroi.sql.ExecutionFailure> { executeRaw("SELECT 1 FROM DUAL") }
            }
        }
        assertTrue(pool.sessions.single().discarded)
        assertEquals("ROLLBACK", pool.sessions.single().executions.last().sql)
    }

    @Test
    fun mapsTypedRowsAndKeepsLargeBindingsAsDecimalText() = runTest {
        val pool = FakeNodeOraclePool()
        val id = Uuid.parse("123e4567-e89b-12d3-a456-426614174000")
        pool.nextQueryResult = OracleRows(
            listOf("ID", "ACTIVE", "PAYLOAD", "NICKNAME"),
            listOf(
                listOf(
                OracleResultValue(OracleScalar.Bytes(rawUuid(id))),
                OracleResultValue(OracleScalar.BooleanValue(true)),
                OracleResultValue(OracleScalar.Bytes(byteArrayOf(0, 1, -1))),
                OracleResultValue(null),
            )
            ),
        )
        val database = NodeOracleDatabase.create(pool)

        val row = database.select(
            OracleDriverUsers.id,
            OracleDriverUsers.active,
            OracleDriverUsers.payload,
            OracleDriverUsers.nickname,
        )
            .from(OracleDriverUsers)
            .where(OracleDriverUsers.id eq id)
            .one()

        assertEquals(id, row[OracleDriverUsers.id])
        assertTrue(row[OracleDriverUsers.active])
        assertContentEquals(byteArrayOf(0, 1, -1), row[OracleDriverUsers.payload])
        assertNull(row[OracleDriverUsers.nickname])
        val boundId = pool.sessions.single().queries.single().parameters.single() as OracleBinding.Bytes
        assertEquals(dev.quwac.kagiroi.oracle.OracleType.RAW_UUID, boundId.type)
        assertContentEquals(rawUuid(id), boundId.value)
    }

    @Test
    fun usesOneConnectionAndOracleIsolationSemanticsForTransactions() = runTest {
        val pool = FakeNodeOraclePool()
        val database = NodeOracleDatabase.create(pool)

        database.transaction(TransactionIsolation.SERIALIZABLE) {
            executeRaw("UPDATE users SET active = :p1", listOf(true))
            executeRaw("UPDATE users SET active = :p1", listOf(false))
        }

        assertEquals(1, pool.acquireCount)
        assertEquals(
            listOf(
                "SET TRANSACTION ISOLATION LEVEL SERIALIZABLE",
                "UPDATE users SET active = :p1",
                "UPDATE users SET active = :p1",
                "COMMIT",
            ),
            pool.sessions.single().executions.map(OracleStatement::sql),
        )
        assertEquals(1, pool.sessions.single().releaseCount)
    }

    @Test
    fun mapsOracleStructuredErrorNumbersAndReportsBufferedStreaming() = runTest {
        val unique = assertFailsWith<ConstraintViolation> { failingDatabase(1).executeRaw("INSERT") }
        assertEquals(ConstraintKind.UNIQUE, unique.kind)
        assertEquals("ORA-00001", unique.metadata.vendorCode)
        assertFailsWith<SerializationFailure> { failingDatabase(8177).executeRaw("UPDATE") }
        assertFailsWith<DeadlockFailure> { failingDatabase(60).executeRaw("UPDATE") }
        assertFailsWith<ConnectionFailure> {
            NodeOracleDatabase.create(
                FakeNodeOraclePool().apply {
                acquireFailure = driverFailure(12541, OracleFailureKind.CONNECTION)
            }
            ).executeRaw("SELECT 1")
        }
        val database = NodeOracleDatabase.create(FakeNodeOraclePool())
        assertNotNull(database.capability(SqlCapabilities.Sql))
        assertEquals(
            setOf(TransactionIsolation.READ_COMMITTED, TransactionIsolation.SERIALIZABLE),
            database.capability(SqlCapabilities.TransactionIsolation)?.supported,
        )
        assertEquals(SqlStreamingMode.BUFFERED, database.capability(SqlCapabilities.Streaming)?.mode)
        assertTrue(database.capability(SqlCapabilities.ReturningInto) is ReturningIntoCapability)
    }

    @Test
    fun returnsTypedDmlValuesThroughGeneratedOracleOutBinds() = runTest {
        val pool = FakeNodeOraclePool()
        val id = Uuid.parse("123e4567-e89b-12d3-a456-426614174001")
        pool.nextCommandResult = OracleCommandResult(
            affectedRows = 1,
            returningValues = listOf(
                listOf(OracleResultValue(OracleScalar.Bytes(rawUuid(id)))),
                listOf(OracleResultValue(OracleScalar.BooleanValue(true))),
            ),
        )
        val database = NodeOracleDatabase.create(pool)

        val row = database.insertInto(OracleDriverUsers).values {
            set(OracleDriverUsers.id, id)
            set(OracleDriverUsers.active, true)
            set(OracleDriverUsers.payload, byteArrayOf(1))
            set(OracleDriverUsers.nickname, null)
        }.returningInto(OracleDriverUsers.id, OracleDriverUsers.active).one()

        assertEquals(id, row[OracleDriverUsers.id])
        assertTrue(row[OracleDriverUsers.active])
        assertEquals(
            listOf(
                OracleReturningBinding("kagiroi_ret_1", OracleReturningKind.BYTES, 32_767),
                OracleReturningBinding("kagiroi_ret_2", OracleReturningKind.BOOLEAN),
            ),
            pool.sessions.single().executions.single().returning,
        )
    }

    @Test
    fun closesThePrivatePoolOnceAndRejectsSubsequentWork() = runTest {
        val pool = FakeNodeOraclePool()
        val database = NodeOracleDatabase.create(pool)
        database.close()
        database.close()
        assertEquals(1, pool.closeCount)
        assertFailsWith<DatabaseClosed> { database.executeRaw("SELECT 1") }
    }
}

private fun driverFailure(number: Int, kind: OracleFailureKind = OracleFailureKind.EXECUTION) =
    OracleDriverException(kind, number, "ORA-${number.toString().padStart(5, '0')}", emptyMap(), "failure")

private fun failingDatabase(number: Int): NodeOracleDatabase = NodeOracleDatabase.create(
    FakeNodeOraclePool().apply { nextFailure = driverFailure(number) },
)

private class FakeNodeOraclePool : NodeOraclePool {
    val sessions = mutableListOf<FakeNodeOracleSession>()
    var nextQueryResult = OracleRows(emptyList(), emptyList())
    var nextCommandResult = OracleCommandResult(0)
    var nextFailure: OracleDriverException? = null
    var acquireFailure: OracleDriverException? = null
    var acquireCount = 0
    var closeCount = 0
    var releaseFailure: Throwable? = null

    override suspend fun acquire(): NodeOracleSession {
        acquireCount++
        acquireFailure?.let { throw it }
        return FakeNodeOracleSession(this).also(sessions::add)
    }

    override suspend fun close() {
        closeCount++
    }
}

private class FakeNodeOracleSession(private val pool: FakeNodeOraclePool) : NodeOracleSession {
    val executions = mutableListOf<OracleStatement>()
    val queries = mutableListOf<OracleStatement>()
    var releaseCount = 0
    var discarded = false

    override suspend fun execute(statement: OracleStatement): OracleCommandResult {
        executions += statement
        pool.nextFailure?.let { failure ->
            pool.nextFailure = null
            throw failure
        }
        return pool.nextCommandResult
    }

    override suspend fun query(statement: OracleStatement): OracleRows {
        queries += statement
        pool.nextFailure?.let { failure ->
            pool.nextFailure = null
            throw failure
        }
        return pool.nextQueryResult
    }

    override suspend fun commit() = Unit

    override suspend fun release(discard: Boolean) {
        discarded = discard
        releaseCount++
        pool.releaseFailure?.let { throw it }
    }
}

private fun rawUuid(value: Uuid): ByteArray {
    val hexadecimal = value.toString().filterNot { it == '-' }
    return ByteArray(16) { index -> hexadecimal.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}

private object OracleDriverUsers : Table("users") {
    val id = uuid("id")
    val active = boolean("active")
    val payload = bytes("payload")
    val nickname = string("nickname").nullable()
}
