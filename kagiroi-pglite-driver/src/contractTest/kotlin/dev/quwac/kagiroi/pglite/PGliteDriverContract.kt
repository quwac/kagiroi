@file:Suppress("MagicNumber", "TooManyFunctions")

package dev.quwac.kagiroi.pglite

import dev.quwac.bignumber.BigDecimal
import dev.quwac.bignumber.BigInteger
import dev.quwac.kagiroi.kotlinx.datetime.localDate
import dev.quwac.kagiroi.kotlinx.datetime.localDateTime
import dev.quwac.kagiroi.kotlinx.datetime.localTime
import dev.quwac.kagiroi.quwac.bignumber.bigDecimal
import dev.quwac.kagiroi.quwac.bignumber.bigInteger
import dev.quwac.kagiroi.schema.ForeignKeyAction
import dev.quwac.kagiroi.schema.GeneratedColumnStorage
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.currentInstant
import dev.quwac.kagiroi.schema.default
import dev.quwac.kagiroi.schema.generated
import dev.quwac.kagiroi.schema.identity
import dev.quwac.kagiroi.schema.length
import dev.quwac.kagiroi.schema.literal
import dev.quwac.kagiroi.schema.lower
import dev.quwac.kagiroi.schema.nextValue
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.plus
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.schema.sequence
import dev.quwac.kagiroi.schema.times
import dev.quwac.kagiroi.sql.ConstraintKind
import dev.quwac.kagiroi.sql.ConstraintViolation
import dev.quwac.kagiroi.sql.DatabaseClosed
import dev.quwac.kagiroi.sql.ExpectedOneRowButFoundMultiple
import dev.quwac.kagiroi.sql.SqlCapabilities
import dev.quwac.kagiroi.sql.SqlExecution
import dev.quwac.kagiroi.sql.SqlStreamingMode
import dev.quwac.kagiroi.sql.TransactionIsolation
import dev.quwac.kagiroi.sql.createIndex
import dev.quwac.kagiroi.sql.createSequence
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.deleteFrom
import dev.quwac.kagiroi.sql.dropSequence
import dev.quwac.kagiroi.sql.dropTable
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.gt
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.nextSequenceValue
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.testing.SqlDatabaseContractConfiguration
import dev.quwac.kagiroi.sql.testing.joinExpressionContract
import dev.quwac.kagiroi.sql.testing.returningContract
import dev.quwac.kagiroi.sql.testing.savepointContract
import dev.quwac.kagiroi.sql.testing.sqlDatabaseContract
import dev.quwac.kagiroi.sql.transaction
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.values
import dev.quwac.kagiroi.sql.where
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Runtime contract shared by the JavaScript and Wasm JavaScript PGlite adapters. */
internal suspend fun pgliteDriverContract(database: PGliteDatabase, progress: (String) -> Unit = {}) {
    sqlDatabaseContract(database, POSTGRES_COMMON_CONTRACT, progress)
    try {
        progress("joins")
        joinExpressionContract(database)
        progress("capabilities")
        verifyCapabilities(database)
        progress("create-pglite-contract-tables")
        createContractTables(database)
        progress("typed-ddl")
        verifyTypedDdl(database)
        progress("generated-values")
        verifyGeneratedValues(database)
        progress("defaults-and-sequences")
        verifyDefaultsAndSequences(database)
        progress("postgres-values")
        verifyPostgresValues(database)
        progress("transactions")
        verifyTransactions(database)
        progress("savepoints")
        savepointContract(database)
        progress("returning")
        returningContract(database)
        progress("constraints")
        verifyConstraints(database)
        progress("concurrency")
        verifyConcurrentQueries(database)
        progress("cardinality")
        verifyCardinality(database)
    } finally {
        progress("drop-pglite-contract-tables")
        dropContractTables(database)
    }
}

internal suspend fun verifyPGliteClose(database: PGliteDatabase) {
    database.close()
    database.close()
    assertFailsWith<DatabaseClosed> {
        database.executeRaw("SELECT 1")
    }
}

private fun verifyCapabilities(database: PGliteDatabase) {
    assertNotNull(database.capability(SqlCapabilities.Sql))
    assertNotNull(database.capability(SqlCapabilities.Transactions))
    assertNotNull(database.capability(SqlCapabilities.TransactionIsolation))
    assertEquals(SqlStreamingMode.BUFFERED, database.capability(SqlCapabilities.Streaming)?.mode)
}

private suspend fun createContractTables(database: PGliteDatabase) {
    database.execute(
        createTable(PGliteTypedDdlRows),
    )
    database.execute(
        createIndex("pglite_typed_ddl_name_idx", PGliteTypedDdlRows) {
            on(lower(PGliteTypedDdlRows.name))
            include(PGliteTypedDdlRows.id)
            where(PGliteTypedDdlRows.id gt 0)
        },
    )
    database.execute(createTable(PGliteGeneratedRows))
    database.execute(createSequence(PGliteNumbers))
    database.execute(createTable(PGliteDefaultRows))
    database.executeRaw(contractTableDdl)
    database.execute(createTable(PGliteConstraintParent))
    database.execute(createTable(PGliteConstraintChild))
    database.execute(createTable(PGliteCompositeKeyRows))
    database.execute(createTable(PGliteCompositeUniqueRows))
    database.executeRaw("INSERT INTO kagiroi_pglite_parent (id) VALUES ($1)", listOf(1))
}

private suspend fun dropContractTables(database: PGliteDatabase) {
    database.execute(dropTable(PGliteTypedDdlRows, ifExists = true))
    database.execute(dropTable(PGliteGeneratedRows, ifExists = true))
    database.execute(dropTable(PGliteDefaultRows, ifExists = true))
    database.execute(dropSequence(PGliteNumbers, ifExists = true))
    database.execute(dropTable(PGliteConstraintChild, ifExists = true))
    database.execute(dropTable(PGliteConstraintParent, ifExists = true))
    database.execute(dropTable(PGliteCompositeKeyRows, ifExists = true))
    database.execute(dropTable(PGliteCompositeUniqueRows, ifExists = true))
    database.executeRaw("DROP TABLE IF EXISTS kagiroi_pglite_values")
}

private suspend fun verifyTypedDdl(database: PGliteDatabase) {
    database.insertInto(PGliteTypedDdlRows).values {
        set(PGliteTypedDdlRows.id, 1)
        set(PGliteTypedDdlRows.name, "typed-ddl")
    }.execute()
    assertEquals(
        "typed-ddl",
        database.select(PGliteTypedDdlRows.name).from(PGliteTypedDdlRows).one()[PGliteTypedDdlRows.name],
    )
    assertEquals(
        "pglite_typed_ddl_name_idx",
        database.queryRaw(
            "SELECT indexname FROM pg_indexes WHERE tablename = 'kagiroi_pglite_typed_ddl' " +
                "AND indexname = 'pglite_typed_ddl_name_idx'",
        ).toList().single().string("indexname"),
    )
}

private suspend fun verifyGeneratedValues(database: PGliteDatabase) {
    database.insertInto(PGliteGeneratedRows).values {
        set(PGliteGeneratedRows.price, 6)
        set(PGliteGeneratedRows.quantity, 7)
        set(PGliteGeneratedRows.email, "Ada@Example.com")
    }.execute()

    val row = database.select(
        PGliteGeneratedRows.id,
        PGliteGeneratedRows.total,
        PGliteGeneratedRows.normalizedEmail,
    ).from(PGliteGeneratedRows).one()
    assertEquals(1L, row[PGliteGeneratedRows.id])
    assertEquals(42, row[PGliteGeneratedRows.total])
    assertEquals("ada@example.com", row[PGliteGeneratedRows.normalizedEmail])
}

private suspend fun verifyDefaultsAndSequences(database: PGliteDatabase) {
    assertEquals(1L, database.nextSequenceValue(PGliteNumbers))
    database.executeRaw("INSERT INTO kagiroi_pglite_default_rows DEFAULT VALUES")
    val row = database.select(
        PGliteDefaultRows.id,
        PGliteDefaultRows.status,
        PGliteDefaultRows.createdAt,
        PGliteDefaultRows.normalized,
    ).from(PGliteDefaultRows).one()
    assertEquals(12L, row[PGliteDefaultRows.id])
    assertEquals(7, row[PGliteDefaultRows.status])
    assertTrue(row[PGliteDefaultRows.createdAt].epochSeconds > 0)
    assertEquals("default", row[PGliteDefaultRows.normalized])
}

private suspend fun verifyPostgresValues(database: PGliteDatabase) {
    val fixture = PGliteFixture()
    database.insertInto(PGliteValues).values {
        set(PGliteValues.id, fixture.id)
        set(PGliteValues.active, true)
        set(PGliteValues.payload, fixture.payload)
        set(PGliteValues.nullableName, null)
        set(PGliteValues.longValue, fixture.longValue)
        set(PGliteValues.ulongValue, fixture.ulongValue)
        set(PGliteValues.integerValue, fixture.integerValue)
        set(PGliteValues.decimalValue, fixture.decimalValue)
        set(PGliteValues.dateValue, fixture.dateValue)
        set(PGliteValues.timeValue, fixture.timeValue)
        set(PGliteValues.dateTimeValue, fixture.dateTimeValue)
        set(PGliteValues.instantValue, fixture.instantValue)
    }.execute()

    val row = database.select(
        PGliteValues.id,
        PGliteValues.active,
        PGliteValues.payload,
        PGliteValues.nullableName,
        PGliteValues.longValue,
        PGliteValues.ulongValue,
        PGliteValues.integerValue,
        PGliteValues.decimalValue,
        PGliteValues.dateValue,
        PGliteValues.timeValue,
        PGliteValues.dateTimeValue,
        PGliteValues.instantValue,
    ).from(PGliteValues).where(PGliteValues.id eq fixture.id).one()
    assertEquals(fixture.id, row[PGliteValues.id])
    assertTrue(row[PGliteValues.active])
    assertContentEquals(fixture.payload, row[PGliteValues.payload])
    assertNull(row[PGliteValues.nullableName])
    assertEquals(fixture.longValue, row[PGliteValues.longValue])
    assertEquals(fixture.ulongValue, row[PGliteValues.ulongValue])
    assertEquals(fixture.integerValue, row[PGliteValues.integerValue])
    assertEquals(0, fixture.decimalValue.compareTo(row[PGliteValues.decimalValue]))
    assertEquals(fixture.dateValue, row[PGliteValues.dateValue])
    assertEquals(fixture.timeValue, row[PGliteValues.timeValue])
    assertEquals(fixture.dateTimeValue, row[PGliteValues.dateTimeValue])
    assertEquals(fixture.instantValue, row[PGliteValues.instantValue])
}

private suspend fun verifyTransactions(database: PGliteDatabase) {
    database.transaction(isolation = TransactionIsolation.REPEATABLE_READ) {
        insertValueRow(PGliteFixture(COMMITTED_ID))
    }
    database.transaction(isolation = TransactionIsolation.SERIALIZABLE) {
        executeRaw("SELECT $1", listOf(1))
    }
    assertFailsWith<IllegalStateException> {
        database.transaction {
            insertValueRow(PGliteFixture(ROLLED_BACK_ID))
            error("rollback")
        }
    }
    assertNull(
        database.select(PGliteValues.id).from(PGliteValues).where(PGliteValues.id eq ROLLED_BACK_ID).oneOrNull(),
    )
}

private suspend fun verifyConstraints(database: PGliteDatabase) {
    val unique = assertFailsWith<ConstraintViolation> {
        database.insertInto(PGliteValues).values {
            set(PGliteValues.id, PGliteFixture().id)
            set(PGliteValues.active, false)
            set(PGliteValues.payload, byteArrayOf())
            set(PGliteValues.nullableName, null)
            set(PGliteValues.longValue, 0L)
            set(PGliteValues.ulongValue, 0uL)
            set(PGliteValues.integerValue, BigInteger.ZERO)
            set(PGliteValues.decimalValue, BigDecimal.ZERO)
            set(PGliteValues.dateValue, LocalDate.parse("2026-10-25"))
            set(PGliteValues.timeValue, LocalTime.parse("00:00:00"))
            set(PGliteValues.dateTimeValue, LocalDateTime.parse("2026-10-25T00:00:00"))
            set(PGliteValues.instantValue, Instant.parse("2026-10-25T00:00:00Z"))
        }.execute()
    }
    assertEquals(ConstraintKind.UNIQUE, unique.kind)
    assertEquals("23505", unique.metadata.sqlState)

    assertEquals(
        ConstraintKind.FOREIGN_KEY,
        assertFailsWith<ConstraintViolation> {
            database.executeRaw(
                "INSERT INTO kagiroi_pglite_child (id, parent_id, required_value, positive_value) VALUES ($1, $2, $3, $4)",
                listOf(1, 999, "value", 1),
            )
        }.kind,
    )
    assertEquals(
        ConstraintKind.NOT_NULL,
        assertFailsWith<ConstraintViolation> {
            database.executeRaw(
                "INSERT INTO kagiroi_pglite_child (id, parent_id, required_value, positive_value) VALUES ($1, $2, $3, $4)",
                listOf(2, 1, null, 1),
            )
        }.kind,
    )
    assertEquals(
        ConstraintKind.CHECK,
        assertFailsWith<ConstraintViolation> {
            database.executeRaw(
                "INSERT INTO kagiroi_pglite_child (id, parent_id, required_value, positive_value) VALUES ($1, $2, $3, $4)",
                listOf(3, 1, "value", 0),
            )
        }.kind,
    )

    verifyCompositePrimaryKeyConstraint(database)
    verifyCompositeUniqueConstraint(database)
}

private suspend fun verifyCompositePrimaryKeyConstraint(database: PGliteDatabase) {
    database.insertInto(PGliteCompositeKeyRows).values {
        set(PGliteCompositeKeyRows.organizationId, 1)
        set(PGliteCompositeKeyRows.userId, 1)
    }.execute()
    assertEquals(
        ConstraintKind.UNIQUE,
        assertFailsWith<ConstraintViolation> {
            database.insertInto(PGliteCompositeKeyRows).values {
                set(PGliteCompositeKeyRows.organizationId, 1)
                set(PGliteCompositeKeyRows.userId, 1)
            }.execute()
        }.kind,
    )
    assertEquals(
        1,
        database.insertInto(PGliteCompositeKeyRows).values {
            set(PGliteCompositeKeyRows.organizationId, 1)
            set(PGliteCompositeKeyRows.userId, 2)
        }.execute().affectedRows,
    )
}

private suspend fun verifyCompositeUniqueConstraint(database: PGliteDatabase) {
    database.insertInto(PGliteCompositeUniqueRows).values {
        set(PGliteCompositeUniqueRows.organizationId, 1)
        set(PGliteCompositeUniqueRows.email, "ada@example.com")
    }.execute()
    assertEquals(
        ConstraintKind.UNIQUE,
        assertFailsWith<ConstraintViolation> {
            database.insertInto(PGliteCompositeUniqueRows).values {
                set(PGliteCompositeUniqueRows.organizationId, 1)
                set(PGliteCompositeUniqueRows.email, "ada@example.com")
            }.execute()
        }.kind,
    )
    assertEquals(
        1,
        database.insertInto(PGliteCompositeUniqueRows).values {
            set(PGliteCompositeUniqueRows.organizationId, 2)
            set(PGliteCompositeUniqueRows.email, "ada@example.com")
        }.execute().affectedRows,
    )
}

private suspend fun verifyConcurrentQueries(database: PGliteDatabase) = coroutineScope {
    val values = (1..CONCURRENT_QUERY_COUNT).map { value ->
        async {
            database.queryRaw("SELECT $1::INTEGER AS value", listOf(value)).toList().single().long("value")
        }
    }.awaitAll()
    assertEquals((1L..CONCURRENT_QUERY_COUNT.toLong()).toList(), values)
}

private suspend fun verifyCardinality(database: PGliteDatabase) {
    assertFailsWith<ExpectedOneRowButFoundMultiple> {
        database.select(PGliteValues.id).from(PGliteValues).one()
    }
    val raw = database.queryRaw(
        "SELECT long_value, payload FROM kagiroi_pglite_values WHERE id = $1",
        listOf(PGliteFixture().id),
    ).toList().single()
    assertEquals(Long.MIN_VALUE, raw.long("long_value"))
    assertContentEquals(PGliteFixture().payload, raw.bytes("payload"))

    val parameterValue = "Robert'); DROP TABLE kagiroi_pglite_values; --"
    val updated = database.update(PGliteValues) { set(PGliteValues.nullableName, parameterValue) }
        .where(PGliteValues.id eq PGliteFixture().id)
        .execute()
    assertEquals(1, updated.affectedRows)
    assertEquals(
        parameterValue,
        database.select(PGliteValues.nullableName).from(PGliteValues).where(PGliteValues.id eq PGliteFixture().id)
            .one()[PGliteValues.nullableName],
    )
    val deleted = database.deleteFrom(PGliteValues).where(PGliteValues.id eq COMMITTED_ID).execute()
    assertEquals(1, deleted.affectedRows)
}

private suspend fun SqlExecution.insertValueRow(fixture: PGliteFixture) {
    insertInto(PGliteValues).values {
        set(PGliteValues.id, fixture.id)
        set(PGliteValues.active, false)
        set(PGliteValues.payload, fixture.payload)
        set(PGliteValues.nullableName, null)
        set(PGliteValues.longValue, fixture.longValue)
        set(PGliteValues.ulongValue, fixture.ulongValue)
        set(PGliteValues.integerValue, fixture.integerValue)
        set(PGliteValues.decimalValue, fixture.decimalValue)
        set(PGliteValues.dateValue, fixture.dateValue)
        set(PGliteValues.timeValue, fixture.timeValue)
        set(PGliteValues.dateTimeValue, fixture.dateTimeValue)
        set(PGliteValues.instantValue, fixture.instantValue)
    }.execute()
}

private data class PGliteFixture(
    val id: Uuid = Uuid.parse("123e4567-e89b-12d3-a456-426614174000"),
    val payload: ByteArray = byteArrayOf(0, 1, Byte.MAX_VALUE, -1),
    val longValue: Long = Long.MIN_VALUE,
    val ulongValue: ULong = ULong.MAX_VALUE,
    val integerValue: BigInteger = BigInteger.parse("123456789012345678901234567890"),
    val decimalValue: BigDecimal = BigDecimal.parse("1234567890.123456789"),
    val dateValue: LocalDate = LocalDate.parse("2026-10-25"),
    val timeValue: LocalTime = LocalTime.parse("01:02:03.123456"),
    val dateTimeValue: LocalDateTime = LocalDateTime.parse("2026-10-25T01:02:03.123456"),
    val instantValue: Instant = Instant.parse("2026-10-25T01:02:03.123456Z"),
)

private object PGliteValues : Table("kagiroi_pglite_values") {
    val id = uuid("id").primaryKey()
    val active = boolean("active")
    val payload = bytes("payload")
    val nullableName = string("nullable_name").nullable()
    val longValue = long("long_value")
    val ulongValue = ulong("ulong_value")
    val integerValue = bigInteger("integer_value")
    val decimalValue = bigDecimal("decimal_value")
    val dateValue = localDate("date_value")
    val timeValue = localTime("time_value")
    val dateTimeValue = localDateTime("date_time_value")
    val instantValue = instant("instant_value")
}

private object PGliteTypedDdlRows : Table("kagiroi_pglite_typed_ddl") {
    val id = int("id").primaryKey()
    val name = string("name")
}

private object PGliteGeneratedRows : Table("kagiroi_pglite_generated_rows") {
    val id = long("id").primaryKey().identity()
    val price = int("price")
    val quantity = int("quantity")
    val email = string("email")
    val total = int("total").generated(price * quantity, GeneratedColumnStorage.Stored)
    val normalizedEmail = string("normalized_email").generated(lower(email), GeneratedColumnStorage.Stored)

    init {
        checkConstraint("email_not_empty") { length(email) gt 0 }
    }
}

private val PGliteNumbers = sequence("kagiroi_pglite_numbers")

private object PGliteDefaultRows : Table("kagiroi_pglite_default_rows") {
    val id = long("id").default(PGliteNumbers.nextValue() + 10L)
    val status = int("status").default(7)
    val createdAt = instant("created_at").default(currentInstant())
    val normalized = string("normalized").default(lower(literal("DEFAULT")))
}

private object PGliteConstraintParent : Table("kagiroi_pglite_parent") {
    val id = int("id").primaryKey()
}

private object PGliteConstraintChild : Table("kagiroi_pglite_child") {
    val id = int("id").primaryKey()
    val parentId = foreignKey("parent_id", PGliteConstraintParent.id).onDelete(ForeignKeyAction.NoAction)
    val requiredValue = string("required_value")
    val positiveValue = int("positive_value")

    init {
        checkConstraint { positiveValue gt 0 }
    }
}

private object PGliteCompositeKeyRows : Table("kagiroi_pglite_composite_key_rows") {
    val organizationId = int("organization_id")
    val userId = int("user_id")

    init {
        primaryKey(organizationId, userId)
    }
}

private object PGliteCompositeUniqueRows : Table("kagiroi_pglite_composite_unique_rows") {
    val organizationId = int("organization_id")
    val email = string("email")

    init {
        unique(organizationId, email, name = "uq_pglite_organization_email")
    }
}

private val contractTableDdl =
    """
    CREATE TABLE kagiroi_pglite_values (
        id UUID PRIMARY KEY,
        active BOOLEAN NOT NULL,
        payload BYTEA NOT NULL,
        nullable_name TEXT,
        long_value BIGINT NOT NULL,
        ulong_value NUMERIC(20, 0) NOT NULL,
        integer_value NUMERIC(40, 0) NOT NULL,
        decimal_value NUMERIC(40, 18) NOT NULL,
        date_value DATE NOT NULL,
        time_value TIME NOT NULL,
        date_time_value TIMESTAMP NOT NULL,
        instant_value TIMESTAMP WITH TIME ZONE NOT NULL
    )
    """.trimIndent()

private val POSTGRES_COMMON_CONTRACT = SqlDatabaseContractConfiguration(
    rawSelectByIdSql = "SELECT name, payload FROM kagiroi_sql_contract WHERE id = $1",
    uniqueViolationSqlState = "23505",
    supportsWindowNavigationDefault = true,
    supportsAdvancedWindowFeatures = true,
)
private val COMMITTED_ID = Uuid.parse("123e4567-e89b-12d3-a456-426614174001")
private val ROLLED_BACK_ID = Uuid.parse("123e4567-e89b-12d3-a456-426614174002")
private const val CONCURRENT_QUERY_COUNT = 8
