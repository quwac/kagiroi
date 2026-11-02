package dev.quwac.kagiroi.postgres.hyperdrive

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
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.sql.ConstraintKind
import dev.quwac.kagiroi.sql.ConstraintViolation
import dev.quwac.kagiroi.sql.ExpectedOneRowButFoundMultiple
import dev.quwac.kagiroi.sql.SqlExecution
import dev.quwac.kagiroi.sql.TransactionIsolation
import dev.quwac.kagiroi.sql.deleteFrom
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.testing.SqlDatabaseContractConfiguration
import dev.quwac.kagiroi.sql.testing.returningContract
import dev.quwac.kagiroi.sql.testing.savepointContract
import dev.quwac.kagiroi.sql.testing.sqlDatabaseContract
import dev.quwac.kagiroi.sql.transaction
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.values
import dev.quwac.kagiroi.sql.where
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Shared compatibility suite for local PostgreSQL, Neon, Supabase, and Aurora endpoints. */
internal suspend fun postgresDriverContract(database: HyperdrivePostgresDatabase, progress: (String) -> Unit = {}) {
    try {
        sqlDatabaseContract(database, postgresCommonContract, progress)
        progress("reset-table")
        database.executeRaw(CONTRACT_TABLE_DROP)
        progress("create-table")
        database.executeRaw(contractTableDdl)
        val fixture = ContractFixture()
        progress("typed-values")
        verifyTypedValues(database, fixture)
        progress("update")
        verifyUpdate(database, fixture.firstId)
        progress("transactions")
        verifyTransactions(database, fixture.secondId)
        progress("savepoints")
        savepointContract(database)
        progress("returning")
        returningContract(database)
        progress("constraints-and-queries")
        verifyConstraintAndQueryContracts(database, fixture)
        progress("complete")
    } finally {
        try {
            progress("drop-table")
            database.executeRaw(CONTRACT_TABLE_DROP)
        } finally {
            progress("close")
            database.close()
        }
    }
}

private suspend fun verifyTypedValues(database: HyperdrivePostgresDatabase, fixture: ContractFixture) {
    val insert = database.insertInto(ContractRows).values {
        set(ContractRows.id, fixture.firstId)
        set(ContractRows.name, "Ada")
        set(ContractRows.active, true)
        set(ContractRows.payload, fixture.payload)
        set(ContractRows.nickname, null)
        set(ContractRows.instantValue, fixture.instant)
        set(ContractRows.durationValue, fixture.duration)
        set(ContractRows.dateValue, fixture.date)
        set(ContractRows.timeValue, fixture.time)
        set(ContractRows.dateTimeValue, fixture.dateTime)
        set(ContractRows.offsetValue, fixture.offset)
        set(ContractRows.timeZoneValue, fixture.timeZone)
        set(ContractRows.integerValue, fixture.integer)
        set(ContractRows.decimalValue, fixture.decimal)
    }.execute()
    assertEquals(1, insert.affectedRows)

    val row = database.select(
        ContractRows.id,
        ContractRows.active,
        ContractRows.payload,
        ContractRows.nickname,
        ContractRows.instantValue,
        ContractRows.durationValue,
        ContractRows.dateValue,
        ContractRows.timeValue,
        ContractRows.dateTimeValue,
        ContractRows.offsetValue,
        ContractRows.timeZoneValue,
        ContractRows.integerValue,
        ContractRows.decimalValue,
    ).from(ContractRows).where(ContractRows.id eq fixture.firstId).one()
    assertEquals(fixture.firstId, row[ContractRows.id])
    assertTrue(row[ContractRows.active])
    assertContentEquals(fixture.payload, row[ContractRows.payload])
    assertNull(row[ContractRows.nickname])
    assertEquals(fixture.instant, row[ContractRows.instantValue])
    assertEquals(fixture.duration, row[ContractRows.durationValue])
    assertEquals(fixture.date, row[ContractRows.dateValue])
    assertEquals(fixture.time, row[ContractRows.timeValue])
    assertEquals(fixture.dateTime, row[ContractRows.dateTimeValue])
    assertEquals(fixture.offset, row[ContractRows.offsetValue])
    assertEquals(fixture.timeZone, row[ContractRows.timeZoneValue])
    assertEquals(fixture.integer, row[ContractRows.integerValue])
    // NUMERIC(40, 18) preserves numeric precision but materializes the column's scale.
    assertEquals(0, fixture.decimal.compareTo(row[ContractRows.decimalValue]))
}

private suspend fun verifyUpdate(database: HyperdrivePostgresDatabase, firstId: Uuid) {
    val update = database.update(ContractRows) { set(ContractRows.nickname, "updated") }
        .where(ContractRows.id eq firstId)
        .execute()
    assertEquals(1, update.affectedRows)
    assertEquals(
        "updated",
        database.select(ContractRows.nickname).from(ContractRows).where(ContractRows.id eq firstId)
            .one()[ContractRows.nickname],
    )
}

private suspend fun verifyTransactions(database: HyperdrivePostgresDatabase, secondId: Uuid) {
    database.transaction(isolation = TransactionIsolation.REPEATABLE_READ) {
        insertContractRow(secondId, "committed")
    }
    assertFailsWith<IllegalStateException> {
        database.transaction {
            insertContractRow(Uuid.parse("123e4567-e89b-12d3-a456-426614174002"), "rolled-back")
            error("rollback")
        }
    }
    assertNull(
        database.select(ContractRows.id).from(ContractRows)
            .where(ContractRows.name eq "rolled-back")
            .oneOrNull(),
    )
}

private suspend fun verifyConstraintAndQueryContracts(database: HyperdrivePostgresDatabase, fixture: ContractFixture) {
    val violation = assertFailsWith<ConstraintViolation> {
        database.transaction {
            insertContractRow(Uuid.parse("123e4567-e89b-12d3-a456-426614174003"), "Ada")
        }
    }
    assertEquals(ConstraintKind.UNIQUE, violation.kind)
    assertEquals("23505", violation.metadata.sqlState)

    assertEquals(2, database.select(ContractRows.id).from(ContractRows).stream().toList().size)
    assertFailsWith<ExpectedOneRowButFoundMultiple> {
        database.select(ContractRows.id).from(ContractRows).one()
    }
    val raw = database.queryRaw(
        "SELECT name, active, payload FROM kagiroi_postgres_contract WHERE id = $1",
        listOf(fixture.firstId),
    ).toList().single()
    assertEquals("Ada", raw.string("name"))
    assertEquals(true, raw["active"])
    assertContentEquals(fixture.payload, raw.bytes("payload"))

    val delete = database.deleteFrom(ContractRows).where(ContractRows.id eq fixture.secondId).execute()
    assertEquals(1, delete.affectedRows)
}

private data class ContractFixture(
    val firstId: Uuid = Uuid.parse("123e4567-e89b-12d3-a456-426614174000"),
    val secondId: Uuid = Uuid.parse("123e4567-e89b-12d3-a456-426614174001"),
    val payload: ByteArray = byteArrayOf(0, 1, Byte.MAX_VALUE, -1),
    val instant: Instant = Instant.parse("2026-10-18T01:02:03.123456Z"),
    val duration: Duration = Duration.parseIsoString("-PT900H0.000000001S"),
    val date: LocalDate = LocalDate.parse("2026-10-18"),
    val time: LocalTime = LocalTime.parse("01:02:03.123456"),
    val dateTime: LocalDateTime = LocalDateTime.parse("2026-10-18T01:02:03.123456"),
    val offset: UtcOffset = UtcOffset.parse("+09:00"),
    val timeZone: TimeZone = TimeZone.of("Asia/Tokyo"),
    val integer: BigInteger = BigInteger.parse("123456789012345678901234567890"),
    val decimal: BigDecimal = BigDecimal.parse("1234567890.123456789"),
)

private suspend fun SqlExecution.insertContractRow(id: Uuid, name: String) {
    insertInto(ContractRows).values {
        set(ContractRows.id, id)
        set(ContractRows.name, name)
        set(ContractRows.active, false)
        set(ContractRows.payload, byteArrayOf())
        set(ContractRows.nickname, null)
        set(ContractRows.instantValue, Instant.parse("2026-10-18T00:00:00Z"))
        set(ContractRows.durationValue, Duration.ZERO)
        set(ContractRows.dateValue, LocalDate.parse("2026-10-18"))
        set(ContractRows.timeValue, LocalTime.parse("00:00:00"))
        set(ContractRows.dateTimeValue, LocalDateTime.parse("2026-10-18T00:00:00"))
        set(ContractRows.offsetValue, UtcOffset.ZERO)
        set(ContractRows.timeZoneValue, TimeZone.UTC)
        set(ContractRows.integerValue, BigInteger.ONE)
        set(ContractRows.decimalValue, BigDecimal.ONE)
    }.execute()
}

private object ContractRows : Table("kagiroi_postgres_contract") {
    val id = uuid("id")
    val name = string("name")
    val active = boolean("active")
    val payload = bytes("payload")
    val nickname = string("nickname").nullable()
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

private val contractTableDdl =
    """
    CREATE TEMP TABLE kagiroi_postgres_contract (
        id UUID PRIMARY KEY,
        name TEXT NOT NULL UNIQUE,
        active BOOLEAN NOT NULL,
        payload BYTEA NOT NULL,
        nickname TEXT,
        instant_value TIMESTAMP WITH TIME ZONE NOT NULL,
        duration_value TEXT NOT NULL,
        date_value DATE NOT NULL,
        time_value TIME NOT NULL,
        date_time_value TIMESTAMP NOT NULL,
        offset_value TEXT NOT NULL,
        time_zone_value TEXT NOT NULL,
        integer_value NUMERIC(40, 0) NOT NULL,
        decimal_value NUMERIC(40, 18) NOT NULL
    )
    """.trimIndent()

private const val CONTRACT_TABLE_DROP = "DROP TABLE IF EXISTS kagiroi_postgres_contract"

private val postgresCommonContract = SqlDatabaseContractConfiguration(
    rawSelectByIdSql = "SELECT name, payload FROM kagiroi_sql_contract WHERE id = $1",
    uniqueViolationSqlState = "23505",
)
