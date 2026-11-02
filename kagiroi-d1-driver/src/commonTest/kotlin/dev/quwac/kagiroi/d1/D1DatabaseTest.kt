package dev.quwac.kagiroi.d1

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.comment
import dev.quwac.kagiroi.schema.identity
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.optional
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.schema.sum
import dev.quwac.kagiroi.sql.ConstraintViolation
import dev.quwac.kagiroi.sql.InvalidBinding
import dev.quwac.kagiroi.sql.InvalidValueConversion
import dev.quwac.kagiroi.sql.ResultConversionFailure
import dev.quwac.kagiroi.sql.ReturningCapability
import dev.quwac.kagiroi.sql.SqlCapabilities
import dev.quwac.kagiroi.sql.SqlStreamingMode
import dev.quwac.kagiroi.sql.UnsupportedCapability
import dev.quwac.kagiroi.sql.UnsupportedDdlOperation
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.crossJoinLateral
import dev.quwac.kagiroi.sql.deleteFrom
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.exceptAll
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.groupBy
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.intersectAll
import dev.quwac.kagiroi.sql.lateral
import dev.quwac.kagiroi.sql.nonEmptyListOf
import dev.quwac.kagiroi.sql.rollup
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.values
import dev.quwac.kagiroi.sql.where
import dev.quwac.kagiroi.sqlite.SqlitePhysicalValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
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
import kotlin.uuid.Uuid

class D1DatabaseTest {
    @Test
    fun rejectsNativeAdvancedGroupingThatD1CannotRender() = runTest {
        val database = D1Database.create(FakeD1Endpoint())

        val failure = assertFailsWith<UnsupportedCapability> {
            database.select(D1AdvancedGroupingRows.country.optional, sum(D1AdvancedGroupingRows.amount))
                .from(D1AdvancedGroupingRows)
                .groupBy(rollup(D1AdvancedGroupingRows.country))
                .all()
        }

        assertEquals("grouping-rollup", failure.capabilityName)
    }

    @Test
    fun rejectsAllSetOperationsBeforeSendingSqlToD1() = runTest {
        val endpoint = FakeD1Endpoint()
        val database = D1Database.create(endpoint)
        val left = database.select(Users.id).from(Users)
        val right = database.select(Users.id).from(Users)

        assertEquals(
            "intersect-all",
            assertFailsWith<UnsupportedCapability> { left.intersectAll(right).all() }.capabilityName,
        )
        assertEquals(
            "except-all",
            assertFailsWith<UnsupportedCapability> { left.exceptAll(right).all() }.capabilityName,
        )
        assertTrue(endpoint.executions.isEmpty())
    }

    @Test
    fun rejectsLateralDerivedRelationsWithTheActualD1SqliteDialect() = runTest {
        val database = D1Database.create(FakeD1Endpoint())
        val latest = lateral("latest") {
            select(D1LateralOrders.id)
                .from(D1LateralOrders)
                .where(D1LateralOrders.userId eq Users.id)
        }

        val failure = assertFailsWith<UnsupportedCapability> {
            database.select(Users.id, latest[D1LateralOrders.id])
                .from(Users)
                .crossJoinLateral(latest)
                .all()
        }

        assertEquals("lateral-cross", failure.capabilityName)
    }

    @Test
    fun executesTypedDdlWithTheSqliteDialect() = runTest {
        val endpoint = FakeD1Endpoint()
        val database = D1Database.create(endpoint)

        database.execute(createTable(Users))

        assertEquals(
            "CREATE TABLE \"users\" (\"id\" INTEGER NOT NULL PRIMARY KEY, \"name\" TEXT NOT NULL, " +
                "\"active\" INTEGER NOT NULL, \"payload\" BLOB NOT NULL, " +
                "\"external_id\" TEXT NOT NULL, \"nickname\" TEXT)",
            endpoint.executions.single().sql,
        )
    }

    @Test
    fun rejectsConfiguredIdentityOptionsWithoutChangingD1RowIdIdentity() = runTest {
        val endpoint = FakeD1Endpoint()
        val database = D1Database.create(endpoint)

        assertFailsWith<UnsupportedDdlOperation> {
            database.execute(createTable(ConfiguredIdentity))
        }
        assertTrue(endpoint.executions.isEmpty())
    }

    @Test
    fun retainsSchemaCommentsWithoutClaimingD1Persistence() = runTest {
        val endpoint = FakeD1Endpoint()
        val database = D1Database.create(endpoint)

        database.execute(createTable(CommentedUsers))

        assertEquals("Application users", CommentedUsers.comment)
        assertEquals("Primary email address", CommentedUsers.email.comment)
        assertEquals(
            "CREATE TABLE \"commented_users\" (\"email\" TEXT NOT NULL)",
            endpoint.executions.single().sql,
        )
    }

    @Test
    fun executesTypedQueriesWithSQLiteRenderingAndSharedValueConversion() = runTest {
        val endpoint = FakeD1Endpoint()
        val database = D1Database.create(endpoint)
        val id = Uuid.parse("123e4567-e89b-12d3-a456-426614174000")
        val payload = byteArrayOf(1, 2, -1)
        endpoint.queryResult = D1RawRows(
            listOf("id", "name", "active", "payload", "external_id", "nickname"),
            listOf(
                listOf(
                    SqlitePhysicalValue.Integer("7"),
                    SqlitePhysicalValue.Text("Ada"),
                    SqlitePhysicalValue.Integer("1"),
                    SqlitePhysicalValue.Blob(payload),
                    SqlitePhysicalValue.Text(id.toString()),
                    SqlitePhysicalValue.Null,
                ),
            ),
        )

        val row = database
            .select(Users.id, Users.name, Users.active, Users.payload, Users.externalId, Users.nickname)
            .from(Users)
            .where(Users.externalId eq id)
            .one()

        assertEquals(7L, row[Users.id])
        assertEquals("Ada", row[Users.name])
        assertTrue(row[Users.active])
        assertContentEquals(payload, row[Users.payload])
        assertEquals(id, row[Users.externalId])
        assertNull(row[Users.nickname])
        assertEquals(
            "SELECT \"users\".\"id\", \"users\".\"name\", \"users\".\"active\", " +
                "\"users\".\"payload\", \"users\".\"external_id\", \"users\".\"nickname\" " +
                "FROM \"users\" WHERE \"users\".\"external_id\" = ?",
            endpoint.queries.single().sql,
        )
        assertEquals(listOf(SqlitePhysicalValue.Text(id.toString())), endpoint.queries.single().parameters)
    }

    @Test
    fun mapsWritesRawSqlAndAffectedRows() = runTest {
        val endpoint = FakeD1Endpoint(executeMetadata = D1ExecuteMetadata(changes = 3))
        val database = D1Database.create(endpoint)

        val result = database.update(Users) { set(Users.active, false) }
            .where(Users.id eq 7L)
            .execute()

        assertEquals(3, result.affectedRows)
        assertEquals(
            listOf(SqlitePhysicalValue.Integer("0"), SqlitePhysicalValue.Integer("7")),
            endpoint.executions.single().parameters,
        )

        endpoint.queryResult = D1RawRows(
            listOf("name", "score", "payload"),
            listOf(
                listOf(
                    SqlitePhysicalValue.Text("Ada"),
                    SqlitePhysicalValue.Real(1.5),
                    SqlitePhysicalValue.Blob(byteArrayOf(4)),
                ),
            ),
        )
        val raw = database.queryRaw("SELECT name, score, payload FROM users WHERE active = ?", listOf(true))
            .toList()
            .single()
        assertEquals("Ada", raw.string("name"))
        assertEquals(1.5, raw.double("score"))
        assertContentEquals(byteArrayOf(4), raw.bytes("payload"))
        assertEquals(SqlitePhysicalValue.Integer("1"), endpoint.queries.last().parameters.single())
    }

    @Test
    fun executesD1BatchWithoutPresentingItAsInteractiveTransaction() = runTest {
        val endpoint = FakeD1Endpoint(
            batchMetadata = listOf(
                D1BatchOutcome(D1ExecuteMetadata(changes = 1)),
                D1BatchOutcome(D1ExecuteMetadata(changes = 2)),
            ),
        )
        val database = D1Database.create(endpoint)

        val results = database.batch {
            add(
                insertInto(Users).values {
                    set(Users.id, 1L)
                    set(Users.name, "Ada")
                    set(Users.active, true)
                    set(Users.payload, byteArrayOf())
                    set(Users.externalId, Uuid.parse("123e4567-e89b-12d3-a456-426614174000"))
                    set(Users.nickname, null)
                },
            )
            add(deleteFrom(Users).where(Users.id eq 2L))
        }

        assertEquals(listOf(1L, 2L), results.map { it.result.affectedRows })
        assertEquals(2, endpoint.batches.single().size)
        assertTrue(endpoint.batches.single()[0].sql.startsWith("INSERT INTO"))
        assertTrue(endpoint.batches.single()[1].sql.startsWith("DELETE FROM"))
        assertFailsWith<UnsupportedCapability> { database.transaction { } }
    }

    @Test
    fun executesD1BatchFromNonEmptyStatements() = runTest {
        val endpoint = FakeD1Endpoint(
            batchMetadata = listOf(D1BatchOutcome(D1ExecuteMetadata(changes = 1))),
        )
        val database = D1Database.create(endpoint)
        val statement = d1RawBatchStatement("DELETE FROM users WHERE id = ?", listOf(1L))

        assertEquals(1L, database.batch(statement).single().result.affectedRows)
        assertEquals(1L, database.batch(nonEmptyListOf(statement)).single().result.affectedRows)
        assertEquals(
            listOf("DELETE FROM users WHERE id = ?", "DELETE FROM users WHERE id = ?"),
            endpoint.batches.map { it.single().sql },
        )
    }

    @Test
    fun preservesRawRowsReturnedByBatchQueries() = runTest {
        val endpoint = FakeD1Endpoint(
            batchMetadata = listOf(
                D1BatchOutcome(
                    D1ExecuteMetadata(),
                    D1RawRows(listOf("name"), listOf(listOf(SqlitePhysicalValue.Text("Ada")))),
                ),
            ),
        )

        val result = D1Database.create(endpoint).batch {
            raw("SELECT name FROM users")
        }.single()

        assertEquals("Ada", result.rows.single().string("name"))
    }

    @Test
    fun reportsCapabilitiesAndBufferedStreamingTruthfully() = runTest {
        val database = D1Database.create(FakeD1Endpoint())

        assertNotNull(database.capability(SqlCapabilities.Sql))
        assertTrue(database.capability(SqlCapabilities.Returning) is ReturningCapability)
        assertNull(database.capability(SqlCapabilities.Transactions))
        assertNull(database.capability(SqlCapabilities.Savepoints))
        assertEquals(SqlStreamingMode.BUFFERED, database.capability(SqlCapabilities.Streaming)?.mode)
        assertNotNull(database.capability(D1Capabilities.Batch))
        assertNotNull(database.capability(D1Capabilities.Sessions))
    }

    @Test
    fun createsSessionsWithTypedStartsAndCarriesBookmarks() = runTest {
        val endpoint = FakeD1Endpoint()
        val database = D1Database.create(endpoint)
        val previous = D1Bookmark("bookmark-1")

        val primary = database.session(D1SessionStart.Primary)
        val resumed = database.session(after = previous)

        assertEquals(listOf(D1SessionStart.Primary, D1SessionStart.After(previous)), endpoint.sessionStarts)
        assertNull(primary.bookmark())
        endpoint.sessionEndpoint.currentBookmark = D1Bookmark("bookmark-2")
        assertEquals(D1Bookmark("bookmark-2"), resumed.bookmark())
    }

    @Test
    fun mapsDriverAndConversionFailures() = runTest {
        val endpoint = FakeD1Endpoint()
        val database = D1Database.create(endpoint)
        endpoint.failure = D1DriverException(D1FailureKind.CONSTRAINT, "D1_ERROR", "UNIQUE constraint failed")
        assertFailsWith<ConstraintViolation> { database.executeRaw("INSERT INTO users DEFAULT VALUES") }

        endpoint.failure = D1DriverException(D1FailureKind.INVALID_BINDING, "D1_TYPE_ERROR", "invalid value")
        assertFailsWith<InvalidBinding> { database.executeRaw("SELECT ?", listOf(1)) }

        endpoint.failure = D1DriverException(D1FailureKind.RESULT_CONVERSION, "D1_RESULT_TYPE_ERROR", "invalid result")
        assertFailsWith<ResultConversionFailure> { database.queryRaw("SELECT invalid").toList() }

        endpoint.failure = null
        endpoint.queryResult = D1RawRows(
            listOf("active"),
            listOf(listOf(SqlitePhysicalValue.Integer("2"))),
        )
        assertFailsWith<InvalidValueConversion> { database.select(Users.active).from(Users).one() }
    }

    @Test
    fun rejectsUnsafeAndUnsupportedBindingsBeforeCallingD1() = runTest {
        val endpoint = FakeD1Endpoint()
        val database = D1Database.create(endpoint)

        assertFailsWith<InvalidBinding> { database.executeRaw("SELECT ?", listOf(Any())) }
        assertFalse(endpoint.executions.isNotEmpty())
    }

    @Test
    fun cancellationStopsConsumptionWithoutClaimingNativeAbort() = runTest {
        val endpoint = FakeD1Endpoint()
        endpoint.queryGate = CompletableDeferred()
        val database = D1Database.create(endpoint)

        val job = launch { database.queryRaw("SELECT 1").toList() }
        yield()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
    }
}

private class FakeD1Endpoint(
    var executeMetadata: D1ExecuteMetadata = D1ExecuteMetadata(),
    var batchMetadata: List<D1BatchOutcome> = emptyList(),
) : D1Endpoint {
    val executions = mutableListOf<D1Statement>()
    val queries = mutableListOf<D1Statement>()
    val batches = mutableListOf<List<D1Statement>>()
    val sessionStarts = mutableListOf<D1SessionStart>()
    val sessionEndpoint = FakeD1SessionEndpoint(this)
    var queryResult = D1RawRows(emptyList(), emptyList())
    var queryGate: CompletableDeferred<Unit>? = null
    var failure: D1DriverException? = null

    override suspend fun execute(statement: D1Statement): D1ExecuteMetadata {
        failure?.let { throw it }
        executions += statement
        return executeMetadata
    }

    override suspend fun query(statement: D1Statement): D1RawRows {
        failure?.let { throw it }
        queryGate?.await()
        queries += statement
        return queryResult
    }

    override suspend fun batch(statements: List<D1Statement>): List<D1BatchOutcome> {
        failure?.let { throw it }
        batches += statements
        return batchMetadata
    }

    override fun session(start: D1SessionStart): D1SessionEndpoint {
        sessionStarts += start
        return sessionEndpoint
    }
}

private class FakeD1SessionEndpoint(private val delegate: FakeD1Endpoint) : D1SessionEndpoint {
    var currentBookmark: D1Bookmark? = null

    override suspend fun execute(statement: D1Statement) = delegate.execute(statement)

    override suspend fun query(statement: D1Statement) = delegate.query(statement)

    override suspend fun batch(statements: List<D1Statement>) = delegate.batch(statements)

    override fun session(start: D1SessionStart): D1SessionEndpoint = delegate.session(start)

    override fun bookmark(): D1Bookmark? = currentBookmark
}

private object Users : Table("users") {
    val id = long("id").primaryKey()
    val name = string("name")
    val active = boolean("active")
    val payload = bytes("payload")
    val externalId = uuid("external_id")
    val nickname = string("nickname").nullable()
}

private object D1AdvancedGroupingRows : Table("d1_advanced_grouping_rows") {
    val country = string("country")
    val amount = int("amount")
}

private object D1LateralOrders : Table("d1_lateral_orders") {
    val id = long("id")
    val userId = long("user_id")
}

private object CommentedUsers : Table("commented_users", comment = "Application users") {
    val email = string("email").comment("Primary email address")
}

private object ConfiguredIdentity : Table("configured_identity") {
    val id = long("id").identity(startWith = 1000L, incrementBy = 10L).primaryKey()
}
