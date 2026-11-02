package dev.quwac.kagiroi.postgres.hyperdrive

import dev.quwac.kagiroi.postgres.PostgresBinding
import dev.quwac.kagiroi.postgres.PostgresResultValue
import dev.quwac.kagiroi.postgres.PostgresType
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.sql.ConstraintKind
import dev.quwac.kagiroi.sql.ConstraintViolation
import dev.quwac.kagiroi.sql.DeadlockFailure
import dev.quwac.kagiroi.sql.SerializationFailure
import dev.quwac.kagiroi.sql.SqlCapabilities
import dev.quwac.kagiroi.sql.SqlStreamingMode
import dev.quwac.kagiroi.sql.TransactionIsolation
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.transaction
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.where
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
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
import kotlin.uuid.Uuid

class HyperdrivePostgresDatabaseTest {
    @Test
    fun executesTypedDdlAsRenderedPostgresSql() = runTest {
        val endpoint = FakePostgresEndpoint()
        val database = HyperdrivePostgresDatabase.create(endpoint)

        database.execute(createTable(Users))

        assertEquals(
            "CREATE TABLE \"users\" (\"id\" UUID NOT NULL, \"active\" BOOLEAN NOT NULL, " +
                "\"payload\" BYTEA NOT NULL, \"nickname\" TEXT)",
            endpoint.executions.single().sql,
        )
    }

    @Test
    fun executesTypedQueriesWithPostgresBindingsAndRows() = runTest {
        val endpoint = FakePostgresEndpoint()
        val database = HyperdrivePostgresDatabase.create(endpoint)
        val id = Uuid.parse("123e4567-e89b-12d3-a456-426614174000")
        endpoint.queryResult = PostgresRows(
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

        val row = database.select(Users.id, Users.active, Users.payload, Users.nickname)
            .from(Users)
            .where(Users.id eq id)
            .one()

        assertEquals(id, row[Users.id])
        assertTrue(row[Users.active])
        assertContentEquals(byteArrayOf(0, 1, -1), row[Users.payload])
        assertNull(row[Users.nickname])
        val statement = endpoint.queries.single()
        assertTrue(statement.sql.endsWith("WHERE \"users\".\"id\" = $1"))
        assertEquals(listOf(PostgresBinding.UuidValue(id)), statement.parameters)
        assertNotNull(statement.preparedName)
    }

    @Test
    fun mapsAffectedRowsAndKeepsRawExecutionParameterized() = runTest {
        val endpoint = FakePostgresEndpoint(commandResult = PostgresCommandResult(2))
        val database = HyperdrivePostgresDatabase.create(endpoint)

        val result = database.update(Users) { set(Users.active, false) }
            .where(Users.id eq Uuid.parse("123e4567-e89b-12d3-a456-426614174000"))
            .execute()
        database.executeRaw("UPDATE users SET active = $1", listOf(true))

        assertEquals(2, result.affectedRows)
        assertEquals(PostgresBinding.BooleanValue(false), endpoint.executions.first().parameters.first())
        assertEquals(PostgresBinding.BooleanValue(true), endpoint.executions.last().parameters.single())
        assertNull(endpoint.executions.last().preparedName)
    }

    @Test
    fun commitsRollsBackAndAppliesExplicitIsolation() = runTest {
        val endpoint = FakePostgresEndpoint()
        val database = HyperdrivePostgresDatabase.create(endpoint)

        database.transaction(isolation = TransactionIsolation.SERIALIZABLE) {
            executeRaw("UPDATE users SET active = $1", listOf(true))
        }
        assertEquals(
            listOf("BEGIN ISOLATION LEVEL SERIALIZABLE", "UPDATE users SET active = $1", "COMMIT"),
            endpoint.executions.map(PostgresStatement::sql),
        )

        endpoint.executions.clear()
        assertFailsWith<IllegalStateException> {
            database.transaction {
                executeRaw("UPDATE users SET active = $1", listOf(false))
                error("stop")
            }
        }
        assertEquals(listOf("BEGIN", "UPDATE users SET active = $1", "ROLLBACK"), endpoint.executions.map { it.sql })
    }

    @Test
    fun rollsBackBeforePropagatingCancellation() = runTest {
        val endpoint = FakePostgresEndpoint()
        val database = HyperdrivePostgresDatabase.create(endpoint)
        val transaction = launch {
            database.transaction { awaitCancellation() }
        }
        yield()

        transaction.cancelAndJoin()

        assertEquals(listOf("BEGIN", "ROLLBACK"), endpoint.executions.map { it.sql })
    }

    @Test
    fun rollsBackAfterCommitFailureWithoutRetrying() = runTest {
        val endpoint = FakePostgresEndpoint()
        val database = HyperdrivePostgresDatabase.create(endpoint)
        endpoint.failureSql = "COMMIT"
        endpoint.failure = failure("40001")

        assertFailsWith<SerializationFailure> {
            database.transaction { executeRaw("UPDATE users SET active = $1", listOf(true)) }
        }

        assertEquals(
            listOf("BEGIN", "UPDATE users SET active = $1", "COMMIT", "ROLLBACK"),
            endpoint.executions.map { it.sql },
        )
        assertEquals(4, endpoint.attempts)
    }

    @Test
    fun reportsOnlyImplementedCapabilities() = runTest {
        val database = HyperdrivePostgresDatabase.create(FakePostgresEndpoint())

        assertNotNull(database.capability(SqlCapabilities.Sql))
        assertNotNull(database.capability(SqlCapabilities.Transactions))
        assertEquals(
            TransactionIsolation.entries.toSet(),
            database.capability(SqlCapabilities.TransactionIsolation)?.supported,
        )
        assertEquals(SqlStreamingMode.BUFFERED, database.capability(SqlCapabilities.Streaming)?.mode)
    }

    @Test
    fun mapsSqlStateWithoutRetrying() = runTest {
        val endpoint = FakePostgresEndpoint()
        val database = HyperdrivePostgresDatabase.create(endpoint)

        endpoint.failure = failure("23505", mapOf("constraint" to "users_name_key"))
        val unique = assertFailsWith<ConstraintViolation> { database.executeRaw("INSERT") }
        assertEquals(ConstraintKind.UNIQUE, unique.kind)
        assertEquals("23505", unique.metadata.sqlState)
        assertEquals("users_name_key", unique.metadata.attributes["constraint"])

        endpoint.failure = failure("40001")
        assertFailsWith<SerializationFailure> { database.executeRaw("UPDATE") }
        endpoint.failure = failure("40P01")
        assertFailsWith<DeadlockFailure> { database.executeRaw("UPDATE") }
        assertEquals(3, endpoint.attempts)
    }

    @Test
    fun keepsFlowColdAtThePublicBoundary() = runTest {
        val endpoint = FakePostgresEndpoint(
            queryResult = PostgresRows(
                listOf("active"),
                listOf(listOf(PostgresResultValue(PostgresType.BOOLEAN.objectId, "t"))),
            ),
        )
        val database = HyperdrivePostgresDatabase.create(endpoint)
        val flow = database.queryRaw("SELECT active FROM users")

        assertEquals(0, endpoint.queryAttempts)
        assertEquals(true, flow.toList().single()["active"])
        assertEquals(1, endpoint.queryAttempts)
    }
}

private fun failure(sqlState: String, attributes: Map<String, String> = emptyMap()) =
    PostgresDriverException(PostgresFailureKind.EXECUTION, sqlState, attributes, "failure")

private class FakePostgresEndpoint(
    var commandResult: PostgresCommandResult = PostgresCommandResult(0),
    var queryResult: PostgresRows = PostgresRows(emptyList(), emptyList()),
) : PostgresEndpoint {
    val executions = mutableListOf<PostgresStatement>()
    val queries = mutableListOf<PostgresStatement>()
    var failure: PostgresDriverException? = null
    var failureSql: String? = null
    var attempts = 0
    var queryAttempts = 0

    override suspend fun execute(statement: PostgresStatement): PostgresCommandResult {
        attempts++
        executions += statement
        failure?.takeIf { failureSql == null || failureSql == statement.sql }?.let {
            failure = null
            failureSql = null
            throw it
        }
        return commandResult
    }

    override suspend fun query(statement: PostgresStatement): PostgresRows {
        queryAttempts++
        queries += statement
        failure?.let {
            failure = null
            throw it
        }
        return queryResult
    }

    override suspend fun close() = Unit
}

private object Users : Table("users") {
    val id = uuid("id")
    val active = boolean("active")
    val payload = bytes("payload")
    val nickname = string("nickname").nullable()
}
