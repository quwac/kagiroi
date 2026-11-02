package dev.quwac.kagiroi.postgres.node

import dev.quwac.kagiroi.postgres.PostgresBinding
import dev.quwac.kagiroi.postgres.PostgresResultValue
import dev.quwac.kagiroi.postgres.PostgresType
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.sql.ConnectionFailure
import dev.quwac.kagiroi.sql.ConstraintKind
import dev.quwac.kagiroi.sql.ConstraintViolation
import dev.quwac.kagiroi.sql.DatabaseClosed
import dev.quwac.kagiroi.sql.DeadlockFailure
import dev.quwac.kagiroi.sql.LockTimeoutFailure
import dev.quwac.kagiroi.sql.SerializationFailure
import dev.quwac.kagiroi.sql.SqlCapabilities
import dev.quwac.kagiroi.sql.SqlStreamingMode
import dev.quwac.kagiroi.sql.TransactionIsolation
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.transaction
import dev.quwac.kagiroi.sql.where
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

class NodePostgresDatabaseTest {
    @Test
    fun executesTypedQueriesThroughAutomaticallyReleasedPoolSessions() = runTest {
        val pool = FakeNodePostgresPool()
        val id = Uuid.parse("123e4567-e89b-12d3-a456-426614174000")
        pool.nextQueryResult = PostgresRows(
            listOf("id", "active", "payload", "nickname"),
            listOf(
                listOf(
                    PostgresResultValue(PostgresType.UUID.objectId, id.toString()),
                    PostgresResultValue(PostgresType.BOOLEAN.objectId, "t"),
                    PostgresResultValue(PostgresType.BYTEA.objectId, "\\x0001ff"),
                    PostgresResultValue(PostgresType.TEXT.objectId, null),
                ),
            ),
        )
        val database = NodePostgresDatabase.create(pool)

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
        assertEquals(false, pool.sessions.single().discarded)
        val statement = pool.sessions.single().queries.single()
        assertEquals(listOf(PostgresBinding.UuidValue(id)), statement.parameters)
    }

    @Test
    fun leasesOnePhysicalSessionForTheEntireTransaction() = runTest {
        val pool = FakeNodePostgresPool()
        val database = NodePostgresDatabase.create(pool)

        database.transaction(isolation = TransactionIsolation.SERIALIZABLE) {
            executeRaw("UPDATE users SET active = $1", listOf(true))
            executeRaw("UPDATE users SET active = $1", listOf(false))
        }

        assertEquals(1, pool.acquireCount)
        assertEquals(
            listOf(
                "BEGIN ISOLATION LEVEL SERIALIZABLE",
                "UPDATE users SET active = $1",
                "UPDATE users SET active = $1",
                "COMMIT",
            ),
            pool.sessions.single().executions.map(PostgresStatement::sql),
        )
        assertEquals(1, pool.sessions.single().releaseCount)
    }

    @Test
    fun rollsBackAndReleasesTheSessionBeforePropagatingCancellation() = runTest {
        val pool = FakeNodePostgresPool()
        val database = NodePostgresDatabase.create(pool)
        val transaction = launch {
            database.transaction { awaitCancellation() }
        }
        yield()

        transaction.cancelAndJoin()

        assertEquals(listOf("BEGIN", "ROLLBACK"), pool.sessions.single().executions.map(PostgresStatement::sql))
        assertEquals(1, pool.sessions.single().releaseCount)
    }

    @Test
    fun mapsPoolAndSqlStateFailuresWithoutLeakingNativeErrors() = runTest {
        val unavailable = FakeNodePostgresPool().apply {
            acquireFailure = failure(null, PostgresFailureKind.CONNECTION)
        }
        assertFailsWith<ConnectionFailure> {
            NodePostgresDatabase.create(unavailable).executeRaw("SELECT 1")
        }

        val constrained = FakeNodePostgresPool().apply {
            nextFailure = failure("23505", attributes = mapOf("constraint" to "users_name_key"))
        }
        val violation = assertFailsWith<ConstraintViolation> {
            NodePostgresDatabase.create(constrained).executeRaw("INSERT")
        }
        assertEquals(ConstraintKind.UNIQUE, violation.kind)
        assertEquals("23505", violation.metadata.sqlState)
        assertEquals("users_name_key", violation.metadata.attributes["constraint"])

        assertFailsWith<SerializationFailure> { failingDatabase("40001").executeRaw("UPDATE users") }
        assertFailsWith<DeadlockFailure> { failingDatabase("40P01").executeRaw("UPDATE users") }
        assertFailsWith<LockTimeoutFailure> { failingDatabase("55P03").executeRaw("UPDATE users") }
    }

    @Test
    fun reportsBufferedResultsAndExplicitTransactionsOnly() = runTest {
        val database = NodePostgresDatabase.create(FakeNodePostgresPool())

        assertNotNull(database.capability(SqlCapabilities.Sql))
        assertNotNull(database.capability(SqlCapabilities.Transactions))
        assertEquals(
            TransactionIsolation.entries.toSet(),
            database.capability(SqlCapabilities.TransactionIsolation)?.supported,
        )
        assertEquals(SqlStreamingMode.BUFFERED, database.capability(SqlCapabilities.Streaming)?.mode)
    }

    @Test
    fun closesThePoolOnceAndRejectsLaterWork() = runTest {
        val pool = FakeNodePostgresPool()
        val database = NodePostgresDatabase.create(pool)

        database.close()
        database.close()

        assertEquals(1, pool.closeCount)
        assertFailsWith<DatabaseClosed> { database.executeRaw("SELECT 1") }
    }

    @Test
    fun validatesPortableConnectionPoolAndTlsConfiguration() {
        val settings = NodePostgresDatabaseConfiguration(null).apply {
            host = "localhost"
            database = "app"
            user = "app"
            pool {
                minConnections = 1
                maxConnections = 4
                connectionTimeout = 250.milliseconds
                idleTimeout = 500.milliseconds
            }
            tls { mode = NodePostgresTlsMode.REQUIRE }
        }.snapshot()

        assertEquals(1, settings.pool.minConnections)
        assertEquals(4, settings.pool.maxConnections)
        assertEquals(250, settings.pool.connectionTimeoutMilliseconds)
        assertEquals(NodePostgresTlsMode.REQUIRE, settings.tls?.mode)

        val urlSettings = NodePostgresDatabaseConfiguration("postgresql://localhost:6543/app").snapshot()
        assertNull(urlSettings.host)
        assertNull(urlSettings.port)

        assertFailsWith<IllegalArgumentException> {
            NodePostgresDatabaseConfiguration("postgresql://localhost/app?sslmode=require").apply {
                tls()
            }.snapshot()
        }
        assertFailsWith<IllegalArgumentException> {
            NodePostgresDatabaseConfiguration(null).apply {
                host = "localhost"
                database = "app"
                user = "app"
                pool {
                    minConnections = 2
                    maxConnections = 1
                }
            }.snapshot()
        }
    }
}

private fun failure(
    sqlState: String?,
    kind: PostgresFailureKind = PostgresFailureKind.EXECUTION,
    attributes: Map<String, String> = emptyMap(),
) = PostgresDriverException(kind, sqlState, attributes, "failure")

private fun failingDatabase(sqlState: String): NodePostgresDatabase = NodePostgresDatabase.create(
    FakeNodePostgresPool().apply { nextFailure = failure(sqlState) },
)

private class FakeNodePostgresPool : NodePostgresPool {
    val sessions = mutableListOf<FakeNodePostgresSession>()
    var nextQueryResult = PostgresRows(emptyList(), emptyList())
    var nextCommandResult = PostgresCommandResult(0)
    var nextFailure: PostgresDriverException? = null
    var acquireFailure: PostgresDriverException? = null
    var acquireCount = 0
    var closeCount = 0

    override suspend fun acquire(): NodePostgresSession {
        acquireCount++
        acquireFailure?.let { throw it }
        return FakeNodePostgresSession(this).also(sessions::add)
    }

    override suspend fun close() {
        closeCount++
    }
}

private class FakeNodePostgresSession(private val pool: FakeNodePostgresPool) : NodePostgresSession {
    val executions = mutableListOf<PostgresStatement>()
    val queries = mutableListOf<PostgresStatement>()
    var releaseCount = 0
    var discarded = false

    override suspend fun execute(statement: PostgresStatement): PostgresCommandResult {
        executions += statement
        pool.nextFailure?.let {
            pool.nextFailure = null
            throw it
        }
        return pool.nextCommandResult
    }

    override suspend fun query(statement: PostgresStatement): PostgresRows {
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
