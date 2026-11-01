package dev.quwac.kagiroi.sqlite.inmemory

import dev.quwac.bignumber.BigDecimal
import dev.quwac.bignumber.BigInteger
import dev.quwac.kagiroi.kotlinx.datetime.localDate
import dev.quwac.kagiroi.kotlinx.datetime.localDateTime
import dev.quwac.kagiroi.kotlinx.datetime.localTime
import dev.quwac.kagiroi.kotlinx.datetime.timeZone
import dev.quwac.kagiroi.kotlinx.datetime.utcOffset
import dev.quwac.kagiroi.quwac.bignumber.bigDecimal
import dev.quwac.kagiroi.quwac.bignumber.bigInteger
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.get
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.optional
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.schema.transform
import dev.quwac.kagiroi.schema.unique
import dev.quwac.kagiroi.sql.ConstraintViolation
import dev.quwac.kagiroi.sql.DatabaseClosed
import dev.quwac.kagiroi.sql.ExecutionFailure
import dev.quwac.kagiroi.sql.ExpectedOneRowButFoundMultiple
import dev.quwac.kagiroi.sql.ExpectedOneRowButFoundZero
import dev.quwac.kagiroi.sql.InvalidValueConversion
import dev.quwac.kagiroi.sql.NestedTransactionUnsupported
import dev.quwac.kagiroi.sql.ReturningCapability
import dev.quwac.kagiroi.sql.SavepointCapability
import dev.quwac.kagiroi.sql.SqlCapabilities
import dev.quwac.kagiroi.sql.SqlExecution
import dev.quwac.kagiroi.sql.SqlTypeCodecRegistry
import dev.quwac.kagiroi.sql.alterTable
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.createIndex
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.crossJoin
import dev.quwac.kagiroi.sql.deleteFrom
import dev.quwac.kagiroi.sql.dropIndex
import dev.quwac.kagiroi.sql.dropTable
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.from
import dev.quwac.kagiroi.sql.fullOuterJoin
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.leftJoin
import dev.quwac.kagiroi.sql.orderBy
import dev.quwac.kagiroi.sql.returning
import dev.quwac.kagiroi.sql.rightJoin
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.testing.castDistinctCaseExpressionContract
import dev.quwac.kagiroi.sql.testing.joinExpressionContract
import dev.quwac.kagiroi.sql.testing.recursiveCteExpressionContract
import dev.quwac.kagiroi.sql.testing.windowExpressionContract
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.values
import dev.quwac.kagiroi.sql.where
import dev.quwac.kagiroi.sqlite.SqlitePhysicalValue
import dev.quwac.kagiroi.sqlite.kotlinx.datetime.SqliteKotlinxDateTime
import dev.quwac.kagiroi.sqlite.quwac.bignumber.SqliteQuwacBigNumber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

class InMemorySqliteDatabaseTest {
    @Test
    fun executesRecursiveCommonTableExpressionsAgainstSqlite() = withDatabase { database ->
        recursiveCteExpressionContract(database)
    }

    @Test
    fun executesWindowFunctionsAgainstSqlite() = withDatabase { database ->
        windowExpressionContract(
            database,
            supportsNavigationDefault = true,
            supportsAdvancedWindowFeatures = true,
        )
    }

    @Test
    fun executesCastDistinctAndCaseExpressionsAgainstSqlite() = withDatabase { database ->
        castDistinctCaseExpressionContract(database)
    }

    @Test
    fun executesTypedDdlThenTypedDmlOnJsAndWasm() = runTest {
        val database = InMemorySqliteDatabase.inMemory()
        try {
            database.execute(createTable(DdlBaseRows))
            database.execute(alterTable(DdlRows) { addColumn(DdlRows.note) })
            database.execute(createIndex("typed_ddl_name_idx", DdlRows) { on(DdlRows.name) })
            database.insertInto(DdlRows).values {
                set(DdlRows.id, 1L)
                set(DdlRows.name, "typed-ddl")
                set(DdlRows.note, null)
            }.execute()

            assertEquals(
                "typed-ddl",
                database.select(DdlRows.name).from(DdlRows).one()[DdlRows.name],
            )

            database.execute(dropIndex("typed_ddl_name_idx", DdlRows))
            database.execute(dropTable(DdlRows))
        } finally {
            database.dispose()
        }
    }

    @Test
    fun opensCreatesAndDisposesAnInMemoryDatabase() = runTest {
        val database = InMemorySqliteDatabase.inMemory()

        database.executeRaw("CREATE TABLE health (value TEXT NOT NULL)")
        database.dispose()
        database.dispose()

        withTimeout(1_000) {
            assertFailsWith<DatabaseClosed> { database.transaction { } }
            assertFailsWith<DatabaseClosed> { database.executeRaw("SELECT 1") }
        }
    }

    @Test
    fun executesCrudWithTypedDslRowsAndParameters() = withDatabase { database ->
        val dangerousName = "Robert'); DROP TABLE users; --"
        val firstId = Uuid.parse("123e4567-e89b-12d3-a456-426614174000")
        val secondId = Uuid.parse("123e4567-e89b-12d3-a456-426614174001")
        val blob = byteArrayOf(0, 1, 127, -1)

        val firstInsert = database.insertInto(Users).values {
            set(Users.id, 1L)
            set(Users.name, dangerousName)
            set(Users.nickname, null)
            set(Users.active, true)
            set(Users.payload, blob)
            set(Users.externalId, firstId)
        }.execute()
        val secondInsert = database.insertInto(Users).values {
            set(Users.id, 2L)
            set(Users.name, "Grace")
            set(Users.nickname, "Amazing Grace")
            set(Users.active, false)
            set(Users.payload, byteArrayOf(2, 3))
            set(Users.externalId, secondId)
        }.execute()

        assertEquals(1L, firstInsert.affectedRows)
        assertEquals(1L, secondInsert.affectedRows)
        val users = database
            .select(Users.id, Users.name, Users.nickname, Users.active, Users.payload, Users.externalId)
            .from(Users)
            .stream()
            .map { row ->
                User(
                    id = row[Users.id],
                    name = row[Users.name],
                    nickname = row[Users.nickname],
                    active = row[Users.active],
                    payload = row[Users.payload],
                    externalId = row[Users.externalId],
                )
            }
            .toList()
        assertEquals(listOf(1L, 2L), users.map(User::id))
        assertEquals(dangerousName, users.first().name)
        assertNull(users.first().nickname)
        assertTrue(users.first().active)
        assertContentEquals(blob, users.first().payload)
        assertEquals(firstId, users.first().externalId)

        val updateResult = database.update(Users) { set(Users.name, "Ada") }
            .where(Users.id eq 1L)
            .execute()
        assertEquals(1L, updateResult.affectedRows)
        assertEquals(
            "Ada",
            database.select(Users.name).from(Users).where(Users.id eq 1L).one()[Users.name],
        )

        val deleteResult = database.deleteFrom(Users).where(Users.id eq 2L).execute()
        assertEquals(1L, deleteResult.affectedRows)
        assertEquals(listOf(1L), database.select(Users.id).from(Users).all().map { it[Users.id] })
    }

    @Test
    fun executesTypedOuterAndCrossJoinsWithTheirNativeNullability() = runTest {
        val database = InMemorySqliteDatabase.inMemory()
        try {
            database.insertJoinExecutionData()
            database.assertLeftJoinResult()
            database.assertRightAndFullJoinResults()
            database.assertSelfJoinResult()
            database.assertCrossJoinResult()
        } finally {
            database.dispose()
        }
    }

    @Test
    fun executesNativeUsingJoinsAgainstSqlite() = runTest {
        val database = InMemorySqliteDatabase.inMemory()
        try {
            joinExpressionContract(database)
        } finally {
            database.dispose()
        }
    }

    private suspend fun InMemorySqliteDatabase.insertJoinExecutionData() {
        execute(createTable(JoinExecutionUsers))
        execute(createTable(JoinExecutionOrganizations))
        insertInto(JoinExecutionOrganizations).values {
            set(JoinExecutionOrganizations.id, 10L)
            set(JoinExecutionOrganizations.name, "Ada Labs")
        }.execute()
        insertInto(JoinExecutionOrganizations).values {
            set(JoinExecutionOrganizations.id, 20L)
            set(JoinExecutionOrganizations.name, "Unmatched")
        }.execute()
        insertInto(JoinExecutionUsers).values {
            set(JoinExecutionUsers.id, 1L)
            set(JoinExecutionUsers.organizationId, 10L)
            set(JoinExecutionUsers.managerId, null)
            set(JoinExecutionUsers.name, "Ada")
        }.execute()
        insertInto(JoinExecutionUsers).values {
            set(JoinExecutionUsers.id, 2L)
            set(JoinExecutionUsers.organizationId, null)
            set(JoinExecutionUsers.managerId, 1L)
            set(JoinExecutionUsers.name, "Grace")
        }.execute()
    }

    private suspend fun InMemorySqliteDatabase.assertLeftJoinResult() {
        val userId = JoinExecutionUsers[JoinExecutionUsers.id]
        val organizationName = JoinExecutionOrganizations.name.optional
        val rows = select(userId, organizationName)
            .from(JoinExecutionUsers)
            .leftJoin(
                JoinExecutionOrganizations,
                on = JoinExecutionUsers.organizationId eq JoinExecutionOrganizations.id,
            )
            .orderBy(userId.asc())
            .all()

        val firstUserId: Long = rows[0][userId]
        val firstOrganizationName: String? = rows[0][organizationName]
        val secondOrganizationName: String? = rows[1][JoinExecutionOrganizations.name.optional]
        assertEquals(1L, firstUserId)
        assertEquals("Ada Labs", firstOrganizationName)
        assertNull(secondOrganizationName)
    }

    private suspend fun InMemorySqliteDatabase.assertRightAndFullJoinResults() {
        val rightUserId = JoinExecutionUsers.id.optional
        val organizationId = JoinExecutionOrganizations[JoinExecutionOrganizations.id]
        val rightRows = select(rightUserId, organizationId)
            .from(JoinExecutionUsers)
            .rightJoin(
                JoinExecutionOrganizations,
                on = JoinExecutionUsers.organizationId eq JoinExecutionOrganizations.id,
            )
            .all()
        assertTrue(rightRows.any { row -> row[rightUserId] == null && row[organizationId] == 20L })

        val outerUserId = JoinExecutionUsers.id.optional
        val outerOrganizationId = JoinExecutionOrganizations.id.optional
        val fullRows = select(outerUserId, outerOrganizationId)
            .from(JoinExecutionUsers)
            .fullOuterJoin(
                JoinExecutionOrganizations,
                on = JoinExecutionUsers.organizationId eq JoinExecutionOrganizations.id,
            )
            .all()
        assertEquals(3, fullRows.size)
    }

    private suspend fun InMemorySqliteDatabase.assertSelfJoinResult() {
        val employee = JoinExecutionUsers.alias("employee")
        val manager = JoinExecutionUsers.alias("manager")
        val employeeName = employee[JoinExecutionUsers.name]
        val managerName = manager[JoinExecutionUsers.name].optional
        val rows = select(employeeName, managerName)
            .from(employee)
            .leftJoin(manager, on = employee[JoinExecutionUsers.managerId] eq manager[JoinExecutionUsers.id])
            .orderBy(employee[JoinExecutionUsers.id].asc())
            .all()

        assertEquals("Ada", rows[0][employeeName])
        assertNull(rows[0][managerName])
        assertEquals("Grace", rows[1][employeeName])
        assertEquals("Ada", rows[1][managerName])
    }

    private suspend fun InMemorySqliteDatabase.assertCrossJoinResult() {
        val userId = JoinExecutionUsers[JoinExecutionUsers.id]
        val organizationId = JoinExecutionOrganizations[JoinExecutionOrganizations.id]
        val rows = select(userId, organizationId)
            .from(JoinExecutionUsers)
            .crossJoin(JoinExecutionOrganizations)
            .all()

        assertEquals(4, rows.size)
        assertEquals(
            setOf(1L to 10L, 1L to 20L, 2L to 10L, 2L to 20L),
            rows.map { row -> row[userId] to row[organizationId] }.toSet(),
        )
    }

    @Test
    fun returnsTypedRowsFromInsertUpdateAndDelete() = withDatabase { database ->
        assertTrue(database.capability(SqlCapabilities.Returning) is ReturningCapability)
        val id = Uuid.parse("123e4567-e89b-12d3-a456-426614174099")

        val inserted = database.insertInto(Users).values {
            set(Users.id, 99L)
            set(Users.name, "returning-insert")
            set(Users.nickname, null)
            set(Users.active, false)
            set(Users.payload, byteArrayOf(9))
            set(Users.externalId, id)
        }.returning(Users.id, Users.name, Users.active).one()
        assertEquals(99L, inserted[Users.id])
        assertEquals("returning-insert", inserted[Users.name])
        assertFalse(inserted[Users.active])

        val updated = database.update(Users) { set(Users.name, "returning-update") }
            .where(Users.id eq 99L)
            .returning(Users.id, Users.name)
            .one()
        assertEquals(99L, updated[Users.id])
        assertEquals("returning-update", updated[Users.name])

        val deleted = database.deleteFrom(Users).where(Users.id eq 99L).returning(Users.id, Users.name).one()
        assertEquals(99L, deleted[Users.id])
        assertEquals("returning-update", deleted[Users.name])
    }

    @Test
    fun enforcesExactOneAndOneOrNullCardinality() = withDatabase { database ->
        database.insertUser(1, "Ada")
        database.insertUser(2, "Grace")

        assertEquals(1L, database.select(Users.id).from(Users).where(Users.id eq 1L).one()[Users.id])
        assertNull(database.select(Users.id).from(Users).where(Users.id eq 99L).oneOrNull())
        assertFailsWith<ExpectedOneRowButFoundZero> {
            database.select(Users.id).from(Users).where(Users.id eq 99L).one()
        }
        assertFailsWith<ExpectedOneRowButFoundMultiple> {
            database.select(Users.id).from(Users).one()
        }
        assertFailsWith<ExpectedOneRowButFoundMultiple> {
            database.select(Users.id).from(Users).oneOrNull()
        }
    }

    @Test
    fun commitsAndRollsBackExplicitTransactions() = withDatabase { database ->
        database.transaction {
            insertUser(1, "committed")
            insertUser(2, "also committed")
        }
        assertEquals(2, database.select(Users.id).from(Users).all().size)

        assertFailsWith<IllegalStateException> {
            database.transaction {
                insertUser(3, "rolled back")
                error("stop")
            }
        }
        assertNull(database.select(Users.id).from(Users).where(Users.id eq 3L).oneOrNull())
    }

    @Test
    fun rejectsNestedTransactionsExplicitly() = withDatabase { database ->
        assertFailsWith<NestedTransactionUnsupported> {
            database.transaction {
                database.transaction { insertUser(1, "nested") }
            }
        }

        assertFailsWith<ExecutionFailure> {
            database.transaction {
                database.executeRaw("SELECT 1")
            }
        }
    }

    @Test
    fun rollsBackTypedCrudToNestedSavepointsAndKeepsTheParentTransactionUsable() = withDatabase { database ->
        assertTrue(database.capability(SqlCapabilities.Savepoints) is SavepointCapability)

        database.transaction {
            insertUser(1, "before")
            insertUser(2, "delete-me")
            assertFailsWith<ExpectedSavepointFailure> {
                savepoint {
                    assertEquals(
                        "before",
                        select(Users.name).from(Users).where(Users.id eq 1L).one()[Users.name],
                    )
                    update(Users) { set(Users.name, "inside-savepoint") }.where(Users.id eq 1L).execute()
                    deleteFrom(Users).where(Users.id eq 2L).execute()
                    insertUser(3, "rolled-back")
                    assertEquals(1L, queryRaw("SELECT 1 AS savepoint_raw").toList().single().long("savepoint_raw"))
                    throw ExpectedSavepointFailure()
                }
            }
            assertEquals("before", select(Users.name).from(Users).where(Users.id eq 1L).one()[Users.name])
            assertNotNull(select(Users.id).from(Users).where(Users.id eq 2L).oneOrNull())
            assertNull(select(Users.id).from(Users).where(Users.id eq 3L).oneOrNull())
            insertUser(4, "continued")
        }

        database.transaction {
            insertUser(10, "parent")
            savepoint {
                insertUser(11, "outer")
                assertFailsWith<ExpectedSavepointFailure> {
                    savepoint {
                        insertUser(12, "inner-rolled-back")
                        throw ExpectedSavepointFailure()
                    }
                }
                insertUser(13, "outer-continued")
            }
        }
        assertNull(database.select(Users.id).from(Users).where(Users.id eq 12L).oneOrNull())
        verifiesOuterSavepointAndTransactionRollback(database)
        assertEquals(
            "continued",
            database.transaction {
                savepoint { select(Users.name).from(Users).where(Users.id eq 4L).one()[Users.name] }
            },
        )
    }

    private suspend fun verifiesOuterSavepointAndTransactionRollback(database: InMemorySqliteDatabase) {
        database.transaction {
            insertUser(30, "outer-parent")
            assertFailsWith<ExpectedSavepointFailure> {
                savepoint {
                    insertUser(31, "outer-rolled-back")
                    savepoint { insertUser(32, "nested-rolled-back") }
                    throw ExpectedSavepointFailure()
                }
            }
            insertUser(33, "parent-continued")
        }
        assertNotNull(database.select(Users.id).from(Users).where(Users.id eq 30L).oneOrNull())
        assertNull(database.select(Users.id).from(Users).where(Users.id eq 31L).oneOrNull())
        assertNull(database.select(Users.id).from(Users).where(Users.id eq 32L).oneOrNull())
        assertNotNull(database.select(Users.id).from(Users).where(Users.id eq 33L).oneOrNull())

        assertFailsWith<ExpectedSavepointFailure> {
            database.transaction {
                insertUser(20, "outer-transaction-before")
                insertUser(21, "deleted-inside-savepoint")
                savepoint {
                    update(Users) { set(Users.name, "updated-inside-savepoint") }.where(Users.id eq 20L).execute()
                    deleteFrom(Users).where(Users.id eq 21L).execute()
                    insertUser(22, "inserted-inside-savepoint")
                }
                throw ExpectedSavepointFailure()
            }
        }
        assertNull(database.select(Users.id).from(Users).where(Users.id eq 20L).oneOrNull())
        assertNull(database.select(Users.id).from(Users).where(Users.id eq 21L).oneOrNull())
        assertNull(database.select(Users.id).from(Users).where(Users.id eq 22L).oneOrNull())
    }

    @Test
    fun serializesIndependentConcurrentTransactions() = withDatabase { database ->
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        coroutineScope {
            val first = async {
                database.transaction {
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                    insertUser(1, "first")
                }
            }
            firstEntered.await()

            val secondAttempted = CompletableDeferred<Unit>()
            val second = async {
                secondAttempted.complete(Unit)
                database.transaction { insertUser(2, "second") }
            }
            secondAttempted.await()
            yield()
            assertFalse(second.isCompleted)

            releaseFirst.complete(Unit)
            first.await()
            second.await()
        }

        assertEquals(2, database.select(Users.id).from(Users).all().size)
    }

    @Test
    fun streamsRowsAndReleasesResourcesAfterConsumerCancellation() = withDatabase { database ->
        repeat(5) { database.insertUser(it + 1, "user-$it") }

        val firstTwo = database.select(Users.id).from(Users).stream().take(2).toList()

        withTimeout(1_000) {
            database.select(Users.id).from(Users).stream().take(1).collect {
                database.update(Users) { set(Users.nickname, "collected") }
                    .where(Users.id eq it[Users.id])
                    .execute()
            }
        }

        assertEquals(listOf(1L, 2L), firstTwo.map { it[Users.id] })
        assertEquals(5, database.select(Users.id).from(Users).all().size)
        assertEquals(
            "collected",
            database.select(Users.nickname).from(Users).where(Users.id eq 1L).one()[Users.nickname],
        )
    }

    @Test
    fun mapsConstraintAndInvalidValueFailures() = withDatabase { database ->
        database.insertUser(1, "Ada")

        assertFailsWith<ConstraintViolation> { database.insertUser(2, "Ada") }
        database.executeRaw("CREATE TABLE invalid_values (value TEXT NOT NULL)")
        database.executeRaw("INSERT INTO invalid_values (value) VALUES (?)", listOf("not an integer"))
        assertFailsWith<InvalidValueConversion> {
            database.select(InvalidValues.value).from(InvalidValues).one()
        }
    }

    @Test
    fun supportsRawSqlWithSeparateParametersAndTypedHelpers() = withDatabase { database ->
        val dangerous = "'); DELETE FROM users; --"
        database.executeRaw(
            "INSERT INTO users (id, name, nickname, active, payload, external_id) VALUES (?, ?, ?, ?, ?, ?)",
            listOf(10L, dangerous, null, true, byteArrayOf(4, 5), "123e4567-e89b-12d3-a456-426614174010"),
        )

        val row = database.queryRaw("SELECT id, name, nickname FROM users WHERE id = ?", listOf(10L)).toList().single()
        assertEquals(10L, row.long("id"))
        assertEquals(dangerous, row.string("name"))
        assertNull(row.string("nickname"))

        assertEquals(0L, database.executeRaw("CREATE TABLE raw_ddl (value TEXT)").affectedRows)
        assertEquals(
            0L,
            database.executeRaw("UPDATE users SET name = ? WHERE id = ?", listOf("nobody", -1L)).affectedRows,
        )
    }

    @Test
    fun bindsRawFloatingPointValuesAsRealWithoutIntegerTruncation() = withDatabase { database ->
        assertTrue(binding(1.0e20, codecs = SqlTypeCodecRegistry(emptyList())) is SqlitePhysicalValue.Real)
        database.executeRaw("CREATE TABLE raw_numbers (value)")
        database.executeRaw("INSERT INTO raw_numbers (value) VALUES (?)", listOf(1.0e20))
        database.executeRaw("INSERT INTO raw_numbers (value) VALUES (?)", listOf(42))

        val rows = database
            .queryRaw("SELECT value, typeof(value) AS storage_class FROM raw_numbers ORDER BY rowid")
            .toList()
        assertEquals("real", rows[0].string("storage_class"))
        assertEquals(1.0e20, rows[0]["value"])
        assertEquals("integer", rows[1].string("storage_class"))
    }

    @Test
    fun convertsSupportedNumericColumnTypes() = withDatabase { database ->
        database.executeRaw(
            """
            CREATE TABLE numeric_values (
                byte_value INTEGER NOT NULL,
                short_value INTEGER NOT NULL,
                int_value INTEGER NOT NULL,
                long_value INTEGER NOT NULL,
                ubyte_value INTEGER NOT NULL,
                ushort_value INTEGER NOT NULL,
                uint_value INTEGER NOT NULL,
                ulong_value INTEGER NOT NULL,
                float_value REAL NOT NULL,
                double_value REAL NOT NULL
            )
            """.trimIndent(),
        )
        database.insertInto(NumericValues).values {
            set(NumericValues.byteValue, (-8).toByte())
            set(NumericValues.shortValue, (-300).toShort())
            set(NumericValues.intValue, 2_000_000_000)
            set(NumericValues.longValue, 5_000_000_000L)
            set(NumericValues.ubyteValue, 250.toUByte())
            set(NumericValues.ushortValue, 60_000.toUShort())
            set(NumericValues.uintValue, 4_000_000_000U)
            set(NumericValues.ulongValue, 8_000_000_000UL)
            set(NumericValues.floatValue, 1.25F)
            set(NumericValues.doubleValue, 2.5)
        }.execute()

        val row = database
            .select(
                NumericValues.byteValue,
                NumericValues.shortValue,
                NumericValues.intValue,
                NumericValues.longValue,
                NumericValues.ubyteValue,
                NumericValues.ushortValue,
                NumericValues.uintValue,
                NumericValues.ulongValue,
                NumericValues.floatValue,
                NumericValues.doubleValue,
            )
            .from(NumericValues)
            .one()
        assertEquals((-8).toByte(), row[NumericValues.byteValue])
        assertEquals((-300).toShort(), row[NumericValues.shortValue])
        assertEquals(2_000_000_000, row[NumericValues.intValue])
        assertEquals(5_000_000_000L, row[NumericValues.longValue])
        assertEquals(250.toUByte(), row[NumericValues.ubyteValue])
        assertEquals(60_000.toUShort(), row[NumericValues.ushortValue])
        assertEquals(4_000_000_000U, row[NumericValues.uintValue])
        assertEquals(8_000_000_000UL, row[NumericValues.ulongValue])
        assertEquals(1.25F, row[NumericValues.floatValue])
        assertEquals(2.5, row[NumericValues.doubleValue])
    }

    @Test
    fun appliesColumnMappingsExactlyOnce() = withDatabase { database ->
        database.executeRaw("CREATE TABLE mapped_values (code TEXT NOT NULL)")
        database.insertInto(MappedValues).values { set(MappedValues.code, UserCode("user-1")) }.execute()

        val row = database.select(MappedValues.code).from(MappedValues).one()

        assertEquals(UserCode("user-1"), row[MappedValues.code])
    }

    @Test
    fun roundTripsTimeAndArbitraryPrecisionTypesWithExplicitCodecs() = runTest {
        val database = InMemorySqliteDatabase.inMemory {
            install(SqliteKotlinxDateTime)
            install(SqliteQuwacBigNumber)
        }
        try {
            database.execute(createTable(ExtendedValues))
            val instant = Instant.parse("2026-10-17T12:34:56.123456789Z")
            val duration = Duration.parseIsoString("PT1H2M3.004S")
            val date = LocalDate.parse("2026-10-17")
            val time = LocalTime.parse("12:34:56.123456789")
            val dateTime = LocalDateTime.parse("2026-10-17T12:34:56.123456789")
            val offset = UtcOffset.parse("+09:00")
            val timeZone = TimeZone.of("Asia/Tokyo")
            val integer = BigInteger.parse("1234567890123456789012345678901234567890")
            val decimal = BigDecimal.parse("-12345678901234567890.1234567890123456789")

            database.insertInto(ExtendedValues).values {
                set(ExtendedValues.instantValue, instant)
                set(ExtendedValues.durationValue, duration)
                set(ExtendedValues.dateValue, date)
                set(ExtendedValues.timeValue, time)
                set(ExtendedValues.dateTimeValue, dateTime)
                set(ExtendedValues.offsetValue, offset)
                set(ExtendedValues.timeZoneValue, timeZone)
                set(ExtendedValues.integerValue, integer)
                set(ExtendedValues.decimalValue, decimal)
            }.execute()

            val row = database.select(
                ExtendedValues.instantValue,
                ExtendedValues.durationValue,
                ExtendedValues.dateValue,
                ExtendedValues.timeValue,
                ExtendedValues.dateTimeValue,
                ExtendedValues.offsetValue,
                ExtendedValues.timeZoneValue,
                ExtendedValues.integerValue,
                ExtendedValues.decimalValue,
            ).from(ExtendedValues).where(ExtendedValues.integerValue eq integer).one()

            assertEquals(instant, row[ExtendedValues.instantValue])
            assertEquals(duration, row[ExtendedValues.durationValue])
            assertEquals(date, row[ExtendedValues.dateValue])
            assertEquals(time, row[ExtendedValues.timeValue])
            assertEquals(dateTime, row[ExtendedValues.dateTimeValue])
            assertEquals(offset, row[ExtendedValues.offsetValue])
            assertEquals(timeZone, row[ExtendedValues.timeZoneValue])
            assertEquals(integer, row[ExtendedValues.integerValue])
            assertEquals(decimal, row[ExtendedValues.decimalValue])
        } finally {
            database.dispose()
        }
    }

    private fun withDatabase(block: suspend (InMemorySqliteDatabase) -> Unit) = runTest {
        val database = InMemorySqliteDatabase.inMemory()
        try {
            database.executeRaw(
                """
                CREATE TABLE users (
                    id INTEGER PRIMARY KEY,
                    name TEXT NOT NULL UNIQUE,
                    nickname TEXT,
                    active INTEGER NOT NULL,
                    payload BLOB NOT NULL,
                    external_id TEXT NOT NULL
                )
                """.trimIndent(),
            )
            block(database)
        } finally {
            database.dispose()
        }
    }

    private suspend fun SqlExecution.insertUser(id: Int, name: String) {
        insertInto(Users).values {
            set(Users.id, id.toLong())
            set(Users.name, name)
            set(Users.nickname, null)
            set(Users.active, true)
            set(Users.payload, byteArrayOf())
            set(Users.externalId, Uuid.parse("123e4567-e89b-12d3-a456-${id.toString().padStart(12, '0')}"))
        }.execute()
    }
}

private data class User(
    val id: Long,
    val name: String,
    val nickname: String?,
    val active: Boolean,
    val payload: ByteArray,
    val externalId: Uuid,
)

private data class UserCode(val value: String)

private class ExpectedSavepointFailure : RuntimeException()

private object Users : Table("users") {
    val id = long("id").primaryKey()
    val name = string("name").unique()
    val nickname = string("nickname").nullable()
    val active = boolean("active")
    val payload = bytes("payload")
    val externalId = uuid("external_id")
}

private object InvalidValues : Table("invalid_values") {
    val value = int("value")
}

private object JoinExecutionUsers : Table("join_execution_users") {
    val id = long("id").primaryKey()
    val organizationId = long("organization_id").nullable()
    val managerId = long("manager_id").nullable()
    val name = string("name")
}

private object JoinExecutionOrganizations : Table("join_execution_organizations") {
    val id = long("id").primaryKey()
    val name = string("name")
}

private object NumericValues : Table("numeric_values") {
    val byteValue = byte("byte_value")
    val shortValue = short("short_value")
    val intValue = int("int_value")
    val longValue = long("long_value")
    val ubyteValue = ubyte("ubyte_value")
    val ushortValue = ushort("ushort_value")
    val uintValue = uint("uint_value")
    val ulongValue = ulong("ulong_value")
    val floatValue = float("float_value")
    val doubleValue = double("double_value")
}

private object MappedValues : Table("mapped_values") {
    val code = string("code").transform(::UserCode, UserCode::value)
}

private object ExtendedValues : Table("extended_values") {
    val instantValue = instant("instant_value")
    val durationValue = duration("duration_value")
    val dateValue = localDate("date_value")
    val timeValue = localTime("time_value")
    val dateTimeValue = localDateTime("date_time_value")
    val offsetValue = utcOffset("offset_value")
    val timeZoneValue = timeZone("time_zone_value")
    val integerValue = bigInteger("integer_value")
    val decimalValue = bigDecimal("decimal_value")
}

private object DdlBaseRows : Table("typed_ddl_rows") {
    val id = long("id").primaryKey()
    val name = string("name").unique()
}

private object DdlRows : Table("typed_ddl_rows") {
    val id = long("id").primaryKey()
    val name = string("name").unique()
    val note = string("note").nullable()
}
