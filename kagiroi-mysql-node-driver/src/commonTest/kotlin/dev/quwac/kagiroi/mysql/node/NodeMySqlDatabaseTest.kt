package dev.quwac.kagiroi.mysql.node

import dev.quwac.kagiroi.mysql.MySqlBinding
import dev.quwac.kagiroi.mysql.MySqlResultValue
import dev.quwac.kagiroi.mysql.MySqlScalar
import dev.quwac.kagiroi.mysql.MySqlType
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.sql.ConnectionFailure
import dev.quwac.kagiroi.sql.ConstraintKind
import dev.quwac.kagiroi.sql.ConstraintViolation
import dev.quwac.kagiroi.sql.DatabaseClosed
import dev.quwac.kagiroi.sql.DeadlockFailure
import dev.quwac.kagiroi.sql.InvalidBinding
import dev.quwac.kagiroi.sql.LockTimeoutFailure
import dev.quwac.kagiroi.sql.ResultConversionFailure
import dev.quwac.kagiroi.sql.SqlCapabilities
import dev.quwac.kagiroi.sql.SqlStreamingMode
import dev.quwac.kagiroi.sql.TransactionIsolation
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.transaction
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.where
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

class NodeMySqlDatabaseTest {
    @Test
    fun executesTypedQueriesThroughAutomaticallyReleasedPoolSessions() = runTest {
        val pool = FakeNodeMySqlPool()
        val id = Uuid.parse("123e4567-e89b-12d3-a456-426614174000")
        pool.nextQueryResult = MySqlRows(
            listOf("id", "active", "payload", "nickname"),
            listOf(
                listOf(
                    MySqlResultValue(MySqlType.CHAR_UUID.protocolType, MySqlScalar.Text(id.toString())),
                    MySqlResultValue(MySqlType.TINYINT.protocolType, MySqlScalar.Number("1")),
                    MySqlResultValue(MySqlType.BLOB.protocolType, MySqlScalar.Bytes(byteArrayOf(0, 1, -1))),
                    MySqlResultValue(MySqlType.TEXT.protocolType, null),
                ),
            ),
        )
        val database = NodeMySqlDatabase.create(pool)

        val row = database.select(Users.id, Users.active, Users.payload, Users.nickname)
            .from(Users)
            .where(Users.id eq id)
            .one()

        assertEquals(id, row[Users.id])
        assertTrue(row[Users.active])
        assertContentEquals(byteArrayOf(0, 1, -1), row[Users.payload])
        assertNull(row[Users.nickname])
        assertEquals(1, pool.acquireCount)
        assertEquals(1, pool.sessions.single().releaseCount)
        assertFalse(pool.sessions.single().discarded)
        assertEquals(listOf(MySqlBinding.Text(id.toString())), pool.sessions.single().queries.single().parameters)
    }

    @Test
    fun mapsAffectedRowsAndKeepsRawExecutionParameterized() = runTest {
        val pool = FakeNodeMySqlPool().apply { nextCommandResult = MySqlCommandResult(2) }
        val database = NodeMySqlDatabase.create(pool)

        val result = database.update(Users) { set(Users.active, false) }
            .where(Users.id eq Uuid.parse("123e4567-e89b-12d3-a456-426614174000"))
            .execute()
        database.executeRaw("UPDATE users SET active = ?", listOf(true))

        assertEquals(2, result.affectedRows)
        assertEquals(MySqlBinding.BooleanValue(false), pool.sessions.first().executions.single().parameters.first())
        assertEquals(MySqlBinding.BooleanValue(true), pool.sessions.last().executions.single().parameters.single())
    }

    @Test
    fun leasesOnePhysicalSessionForTheEntireTransaction() = runTest {
        val pool = FakeNodeMySqlPool()
        val database = NodeMySqlDatabase.create(pool)

        database.transaction(isolation = TransactionIsolation.SERIALIZABLE) {
            executeRaw("UPDATE users SET active = ?", listOf(true))
            executeRaw("UPDATE users SET active = ?", listOf(false))
        }

        assertEquals(1, pool.acquireCount)
        assertEquals(
            listOf(
                "SET TRANSACTION ISOLATION LEVEL SERIALIZABLE",
                "START TRANSACTION",
                "UPDATE users SET active = ?",
                "UPDATE users SET active = ?",
                "COMMIT",
            ),
            pool.sessions.single().executions.map(MySqlStatement::sql),
        )
        assertEquals(1, pool.sessions.single().releaseCount)
    }

    @Test
    fun rollsBackAndReleasesTheSessionBeforePropagatingCancellation() = runTest {
        val pool = FakeNodeMySqlPool()
        val database = NodeMySqlDatabase.create(pool)
        val transaction = launch { database.transaction { awaitCancellation() } }
        yield()

        transaction.cancelAndJoin()

        assertEquals(
            listOf("START TRANSACTION", "ROLLBACK"),
            pool.sessions.single().executions.map(MySqlStatement::sql),
        )
        assertEquals(1, pool.sessions.single().releaseCount)
    }

    @Test
    fun mapsPoolVendorAndConversionErrorsWithoutRetrying() = runTest {
        val unavailable = FakeNodeMySqlPool().apply {
            acquireFailure = failure("28000", "ER_ACCESS_DENIED_ERROR", 1045, MySqlFailureKind.CONNECTION)
        }
        assertFailsWith<ConnectionFailure> { NodeMySqlDatabase.create(unavailable).executeRaw("SELECT 1") }

        val pool = FakeNodeMySqlPool()
        val database = NodeMySqlDatabase.create(pool)
        pool.nextFailure = failure("23000", "ER_DUP_ENTRY", 1062, attributes = mapOf("sql" to "INSERT"))
        val unique = assertFailsWith<ConstraintViolation> { database.executeRaw("INSERT") }
        assertEquals(ConstraintKind.UNIQUE, unique.kind)
        assertEquals("23000", unique.metadata.sqlState)
        assertEquals("ER_DUP_ENTRY", unique.metadata.vendorCode)
        assertEquals("INSERT", unique.metadata.attributes["sql"])

        pool.nextFailure = failure("40001", "ER_LOCK_DEADLOCK", 1213)
        assertFailsWith<DeadlockFailure> { database.executeRaw("UPDATE") }
        pool.nextFailure = failure("HY000", "ER_LOCK_WAIT_TIMEOUT", 1205)
        assertFailsWith<LockTimeoutFailure> { database.executeRaw("UPDATE") }
        pool.nextFailure = failure("22007", "ER_TRUNCATED_WRONG_VALUE", 1292)
        assertFailsWith<InvalidBinding> { database.executeRaw("INSERT") }
        pool.nextFailure = failure("HY000", "ER_UNKNOWN_ERROR", 1105, MySqlFailureKind.RESULT_CONVERSION)
        assertFailsWith<ResultConversionFailure> { database.executeRaw("SELECT") }
        assertEquals(5, pool.acquireCount)
    }

    @Test
    fun reportsBufferedResultsAndExplicitTransactionsOnly() {
        val database = NodeMySqlDatabase.create(FakeNodeMySqlPool())

        assertNotNull(database.capability(SqlCapabilities.Sql))
        assertNotNull(database.capability(SqlCapabilities.Transactions))
        assertEquals(
            TransactionIsolation.entries.toSet(),
            database.capability(SqlCapabilities.TransactionIsolation)?.supported,
        )
        assertEquals(SqlStreamingMode.BUFFERED, database.capability(SqlCapabilities.Streaming)?.mode)
    }

    @Test
    fun timesOutPoolAcquisitionAsConnectionFailure() = runTest {
        val pool = FakeNodeMySqlPool().apply { suspendAcquire = true }
        val database = NodeMySqlDatabase.create(pool, acquisitionTimeoutMilliseconds = 100)

        val failure = assertFailsWith<ConnectionFailure> { database.executeRaw("SELECT 1") }

        assertEquals("KAGIROI_POOL_ACQUISITION_TIMEOUT", failure.metadata.vendorCode)
        assertEquals("100", failure.metadata.attributes["timeoutMilliseconds"])
    }

    @Test
    fun closeDrainsLeasedSessionsIsIdempotentAndRejectsLaterWork() = runTest {
        val pool = FakeNodeMySqlPool()
        val database = NodeMySqlDatabase.create(pool)
        val acquired = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transaction = launch {
            database.transaction {
                acquired.complete(Unit)
                release.await()
            }
        }
        acquired.await()

        val close = async { database.close() }
        yield()
        assertFalse(close.isCompleted)
        release.complete(Unit)
        transaction.join()
        close.await()
        database.close()

        assertEquals(1, pool.closeCount)
        assertFailsWith<DatabaseClosed> { database.executeRaw("SELECT 1") }
    }

    @Test
    fun validatesConnectionPoolAndTlsConfiguration() {
        val settings = NodeMySqlDatabaseConfiguration(null).apply {
            host = "localhost"
            database = "app"
            user = "app"
            pool {
                maxConnections = 4
                maxIdleConnections = 2
                connectionTimeout = 250.milliseconds
                acquisitionTimeout = 300.milliseconds
                idleTimeout = 500.milliseconds
            }
            tls { mode = NodeMySqlTlsMode.REQUIRE }
        }.snapshot()

        assertEquals(4, settings.pool.maxConnections)
        assertEquals(2, settings.pool.maxIdleConnections)
        assertEquals(250, settings.pool.connectionTimeoutMilliseconds)
        assertEquals(300, settings.pool.acquisitionTimeoutMilliseconds)
        assertEquals(NodeMySqlTlsMode.REQUIRE, settings.tls?.mode)

        val urlSettings = NodeMySqlDatabaseConfiguration("mysql://localhost:3307/app").snapshot()
        assertNull(urlSettings.host)
        assertNull(urlSettings.port)

        assertFailsWith<IllegalArgumentException> {
            NodeMySqlDatabaseConfiguration("mysql://localhost/app?ssl=true").apply { tls() }.snapshot()
        }
        assertFailsWith<IllegalArgumentException> {
            NodeMySqlDatabaseConfiguration(null).apply {
                host = "localhost"
                database = "app"
                user = "app"
                pool {
                    maxConnections = 1
                    maxIdleConnections = 2
                }
            }.snapshot()
        }
    }
}

private fun failure(
    sqlState: String,
    vendorCode: String,
    errorNumber: Int,
    kind: MySqlFailureKind = MySqlFailureKind.EXECUTION,
    attributes: Map<String, String> = emptyMap(),
) = MySqlDriverException(kind, sqlState, vendorCode, errorNumber, attributes, "failure")

private class FakeNodeMySqlPool : NodeMySqlPool {
    val sessions = mutableListOf<FakeNodeMySqlSession>()
    var nextQueryResult = MySqlRows(emptyList(), emptyList())
    var nextCommandResult = MySqlCommandResult(0)
    var nextFailure: MySqlDriverException? = null
    var acquireFailure: MySqlDriverException? = null
    var suspendAcquire = false
    var acquireCount = 0
    var closeCount = 0

    override suspend fun acquire(timeoutMilliseconds: Int): NodeMySqlSession {
        acquireCount++
        acquireFailure?.let { throw it }
        if (suspendAcquire) {
            throw MySqlDriverException(
                MySqlFailureKind.CONNECTION,
                null,
                POOL_ACQUISITION_TIMEOUT_CODE,
                null,
                mapOf("timeoutMilliseconds" to timeoutMilliseconds.toString()),
                "Timed out waiting for a MySQL pool connection",
            )
        }
        return FakeNodeMySqlSession(this).also(sessions::add)
    }

    override suspend fun close() {
        closeCount++
    }
}

private class FakeNodeMySqlSession(private val pool: FakeNodeMySqlPool) : NodeMySqlSession {
    val executions = mutableListOf<MySqlStatement>()
    val queries = mutableListOf<MySqlStatement>()
    var releaseCount = 0
    var discarded = false

    override suspend fun execute(statement: MySqlStatement): MySqlCommandResult {
        executions += statement
        pool.nextFailure?.let {
            pool.nextFailure = null
            throw it
        }
        return pool.nextCommandResult
    }

    override suspend fun query(statement: MySqlStatement): MySqlRows {
        queries += statement
        pool.nextFailure?.let {
            pool.nextFailure = null
            throw it
        }
        return pool.nextQueryResult
    }

    override fun release(discard: Boolean) {
        releaseCount++
        discarded = discard
    }
}

private object Users : Table("users") {
    val id = uuid("id")
    val active = boolean("active")
    val payload = bytes("payload")
    val nickname = string("nickname").nullable()
}
