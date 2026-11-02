package dev.quwac.kagiroi.mysql.hyperdrive

import dev.quwac.kagiroi.mariadb.MariaDbDialect
import dev.quwac.kagiroi.mysql.MySqlBinding
import dev.quwac.kagiroi.mysql.MySqlResultValue
import dev.quwac.kagiroi.mysql.MySqlScalar
import dev.quwac.kagiroi.mysql.MySqlType
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.sql.ConnectionFailure
import dev.quwac.kagiroi.sql.ConstraintKind
import dev.quwac.kagiroi.sql.ConstraintViolation
import dev.quwac.kagiroi.sql.DeadlockFailure
import dev.quwac.kagiroi.sql.InvalidBinding
import dev.quwac.kagiroi.sql.LockTimeoutFailure
import dev.quwac.kagiroi.sql.ResultConversionFailure
import dev.quwac.kagiroi.sql.SqlCapabilities
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.SqlStreamingMode
import dev.quwac.kagiroi.sql.TransactionIsolation
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.limit
import dev.quwac.kagiroi.sql.scalar
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

class HyperdriveMySqlDatabaseTest {
    @Test
    fun rendersNestedPaginationAsValidatedLiteralsForTheHyperdriveTransport() {
        val database = HyperdriveMySqlDatabase.create(FakeMySqlEndpoint())
        val latestId = select(Users.id).from(Users).limit(1).scalar()

        val rendered = SqlRenderer(database.dialect).render(select(Users.id, latestId).from(Users))

        assertEquals(
            "SELECT `users`.`id`, (SELECT `users`.`id` FROM `users` LIMIT 1) FROM `users`",
            rendered.sql,
        )
        assertTrue(rendered.parameters.isEmpty())
    }

    @Test
    fun rendersNativeMariaDbUuidThroughTheSharedHyperdriveAdapter() = runTest {
        val endpoint = FakeMySqlEndpoint()
        val database = HyperdriveMySqlDatabase.create(endpoint) {
            dialect(MariaDbDialect)
        }

        database.execute(createTable(Users))

        assertEquals(
            "CREATE TABLE `users` (`id` UUID NOT NULL, `active` BOOLEAN NOT NULL, " +
                "`payload` BLOB NOT NULL, `nickname` TEXT)",
            endpoint.executions.single().sql,
        )
    }

    @Test
    fun executesTypedDdlAsRenderedMySqlSql() = runTest {
        val endpoint = FakeMySqlEndpoint()
        val database = HyperdriveMySqlDatabase.create(endpoint)

        database.execute(createTable(Users))

        assertEquals(
            "CREATE TABLE `users` (`id` CHAR(36) NOT NULL, `active` BOOLEAN NOT NULL, " +
                "`payload` BLOB NOT NULL, `nickname` TEXT)",
            endpoint.executions.single().sql,
        )
    }

    @Test
    fun executesTypedQueriesWithMySqlBindingsAndRows() = runTest {
        val endpoint = FakeMySqlEndpoint()
        val database = HyperdriveMySqlDatabase.create(endpoint)
        val id = Uuid.parse("123e4567-e89b-12d3-a456-426614174000")
        endpoint.queryResult = MySqlRows(
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

        val row = database.select(Users.id, Users.active, Users.payload, Users.nickname)
            .from(Users)
            .where(Users.id eq id)
            .one()

        assertEquals(id, row[Users.id])
        assertTrue(row[Users.active])
        assertContentEquals(byteArrayOf(0, 1, -1), row[Users.payload])
        assertNull(row[Users.nickname])
        val statement = endpoint.queries.single()
        assertTrue(statement.sql.endsWith("WHERE `users`.`id` = ?"))
        assertEquals(listOf(MySqlBinding.Text(id.toString())), statement.parameters)
    }

    @Test
    fun mapsAffectedRowsAndKeepsRawExecutionParameterized() = runTest {
        val endpoint = FakeMySqlEndpoint(commandResult = MySqlCommandResult(2))
        val database = HyperdriveMySqlDatabase.create(endpoint)

        val result = database.update(Users) { set(Users.active, false) }
            .where(Users.id eq Uuid.parse("123e4567-e89b-12d3-a456-426614174000"))
            .execute()
        database.executeRaw("UPDATE users SET active = ?", listOf(true))

        assertEquals(2, result.affectedRows)
        assertEquals(MySqlBinding.BooleanValue(false), endpoint.executions.first().parameters.first())
        assertEquals(MySqlBinding.BooleanValue(true), endpoint.executions.last().parameters.single())
    }

    @Test
    fun commitsRollsBackAndAppliesExplicitIsolation() = runTest {
        val endpoint = FakeMySqlEndpoint()
        val database = HyperdriveMySqlDatabase.create(endpoint)

        database.transaction(isolation = TransactionIsolation.SERIALIZABLE) {
            executeRaw("UPDATE users SET active = ?", listOf(true))
        }
        assertEquals(
            listOf(
                "SET TRANSACTION ISOLATION LEVEL SERIALIZABLE",
                "START TRANSACTION",
                "UPDATE users SET active = ?",
                "COMMIT",
            ),
            endpoint.executions.map(MySqlStatement::sql),
        )
        assertEquals(
            listOf(emptyList(), emptyList(), listOf(MySqlBinding.BooleanValue(true)), emptyList()),
            endpoint.executions.map(MySqlStatement::parameters),
        )

        endpoint.executions.clear()
        assertFailsWith<IllegalStateException> {
            database.transaction {
                executeRaw("UPDATE users SET active = ?", listOf(false))
                error("stop")
            }
        }
        assertEquals(
            listOf("START TRANSACTION", "UPDATE users SET active = ?", "ROLLBACK"),
            endpoint.executions.map(MySqlStatement::sql),
        )
    }

    @Test
    fun rollsBackBeforePropagatingCancellation() = runTest {
        val endpoint = FakeMySqlEndpoint()
        val database = HyperdriveMySqlDatabase.create(endpoint)
        val transaction = launch { database.transaction { awaitCancellation() } }
        yield()

        transaction.cancelAndJoin()

        assertEquals(listOf("START TRANSACTION", "ROLLBACK"), endpoint.executions.map(MySqlStatement::sql))
    }

    @Test
    fun mapsVendorErrorsWithoutRetrying() = runTest {
        val endpoint = FakeMySqlEndpoint()
        val database = HyperdriveMySqlDatabase.create(endpoint)

        endpoint.failure = failure("23000", "ER_DUP_ENTRY", 1062)
        val unique = assertFailsWith<ConstraintViolation> { database.executeRaw("INSERT") }
        assertEquals(ConstraintKind.UNIQUE, unique.kind)
        assertEquals("23000", unique.metadata.sqlState)
        assertEquals("ER_DUP_ENTRY", unique.metadata.vendorCode)

        endpoint.failure = failure("40001", "ER_LOCK_DEADLOCK", 1213)
        assertFailsWith<DeadlockFailure> { database.executeRaw("UPDATE") }
        endpoint.failure = failure("HY000", "ER_LOCK_WAIT_TIMEOUT", 1205)
        assertFailsWith<LockTimeoutFailure> { database.executeRaw("UPDATE") }
        endpoint.failure = failure("23000", "ER_NO_REFERENCED_ROW_2", 1452)
        assertEquals(
            ConstraintKind.FOREIGN_KEY,
            assertFailsWith<ConstraintViolation> { database.executeRaw("INSERT") }.kind,
        )
        endpoint.failure = failure("23000", "ER_BAD_NULL_ERROR", 1048)
        assertEquals(
            ConstraintKind.NOT_NULL,
            assertFailsWith<ConstraintViolation> { database.executeRaw("INSERT") }.kind,
        )
        endpoint.failure = failure("HY000", "ER_CHECK_CONSTRAINT_VIOLATED", 3819)
        assertEquals(
            ConstraintKind.CHECK,
            assertFailsWith<ConstraintViolation> { database.executeRaw("INSERT") }.kind,
        )
        endpoint.failure = failure("22007", "ER_TRUNCATED_WRONG_VALUE", 1292)
        assertFailsWith<InvalidBinding> { database.executeRaw("INSERT") }
        endpoint.failure = failure("HY000", "ECONNREFUSED", 2003, MySqlFailureKind.CONNECTION)
        assertFailsWith<ConnectionFailure> { database.executeRaw("SELECT") }
        endpoint.failure = failure("HY000", "ER_UNKNOWN_ERROR", 1105, MySqlFailureKind.RESULT_CONVERSION)
        assertFailsWith<ResultConversionFailure> { database.executeRaw("SELECT") }
        assertEquals(9, endpoint.attempts)
    }

    @Test
    fun reportsOnlyImplementedCapabilities() {
        val database = HyperdriveMySqlDatabase.create(FakeMySqlEndpoint())

        assertNotNull(database.capability(SqlCapabilities.Sql))
        assertNotNull(database.capability(SqlCapabilities.Transactions))
        assertEquals(
            TransactionIsolation.entries.toSet(),
            database.capability(SqlCapabilities.TransactionIsolation)?.supported,
        )
        assertEquals(SqlStreamingMode.BUFFERED, database.capability(SqlCapabilities.Streaming)?.mode)
    }

    @Test
    fun keepsFlowColdAtThePublicBoundary() = runTest {
        val endpoint = FakeMySqlEndpoint(
            queryResult = MySqlRows(
                listOf("active"),
                listOf(listOf(MySqlResultValue(MySqlType.TINYINT.protocolType, MySqlScalar.Number("1")))),
            ),
        )
        val database = HyperdriveMySqlDatabase.create(endpoint)
        val flow = database.queryRaw("SELECT active FROM users")

        assertEquals(0, endpoint.queryAttempts)
        assertEquals(1L, flow.toList().single().long("active"))
        assertEquals(1, endpoint.queryAttempts)
    }
}

private fun failure(
    sqlState: String,
    vendorCode: String,
    errorNumber: Int,
    kind: MySqlFailureKind = MySqlFailureKind.EXECUTION,
) = MySqlDriverException(
    kind,
    sqlState,
    vendorCode,
    errorNumber,
    emptyMap(),
    "failure",
)

private class FakeMySqlEndpoint(
    var commandResult: MySqlCommandResult = MySqlCommandResult(0),
    var queryResult: MySqlRows = MySqlRows(emptyList(), emptyList()),
) : MySqlEndpoint {
    val executions = mutableListOf<MySqlStatement>()
    val queries = mutableListOf<MySqlStatement>()
    var failure: MySqlDriverException? = null
    var failureSql: String? = null
    var attempts = 0
    var queryAttempts = 0

    override suspend fun execute(statement: MySqlStatement): MySqlCommandResult {
        attempts++
        executions += statement
        failure?.takeIf { failureSql == null || failureSql == statement.sql }?.let {
            failure = null
            failureSql = null
            throw it
        }
        return commandResult
    }

    override suspend fun query(statement: MySqlStatement): MySqlRows {
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
