package dev.quwac.kagiroi.mysql.hyperdrive

import dev.quwac.bignumber.BigDecimal
import dev.quwac.bignumber.BigInteger
import dev.quwac.kagiroi.kotlinx.datetime.localDate
import dev.quwac.kagiroi.kotlinx.datetime.localDateTime
import dev.quwac.kagiroi.kotlinx.datetime.localTime
import dev.quwac.kagiroi.kotlinx.datetime.timeZone
import dev.quwac.kagiroi.kotlinx.datetime.utcOffset
import dev.quwac.kagiroi.mysql.binaryUuid
import dev.quwac.kagiroi.quwac.bignumber.bigDecimal
import dev.quwac.kagiroi.quwac.bignumber.bigInteger
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.SqlDatabase
import dev.quwac.kagiroi.sql.TransactionIsolation
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.testing.SqlDatabaseContractConfiguration
import dev.quwac.kagiroi.sql.testing.savepointContract
import dev.quwac.kagiroi.sql.testing.sqlDatabaseContract
import dev.quwac.kagiroi.sql.transaction
import dev.quwac.kagiroi.sql.values
import dev.quwac.kagiroi.sql.where
import kotlinx.coroutines.flow.single
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Shared compatibility suite for local MySQL, PlanetScale, and Aurora MySQL endpoints. */
internal suspend fun mySqlDriverContract(database: HyperdriveMySqlDatabase, progress: (String) -> Unit = {}) {
    try {
        sqlDatabaseContract(database, mySqlCommonContract, progress)
        progress("mysql-reset-table")
        database.executeRaw(CONTRACT_TABLE_DROP)
        progress("mysql-create-table")
        database.executeRaw(contractTableDdl)
        progress("mysql-native-types")
        verifyMySqlTypes(database)
        progress("savepoints")
        savepointContract(database)
        progress("complete")
    } finally {
        try {
            progress("mysql-drop-table")
            database.executeRaw(CONTRACT_TABLE_DROP)
        } finally {
            progress("close")
            database.close()
        }
    }
}

/** Read-only acceptance check for provider branches whose credentials cannot execute DDL. */
internal suspend fun mySqlDriverSmoke(database: HyperdriveMySqlDatabase, progress: (String) -> Unit = {}) {
    try {
        progress("smoke-parameterized-query")
        val row = database.queryRaw(
            "SELECT CAST(? AS SIGNED) AS numeric_value, ? AS text_value",
            listOf(SMOKE_NUMBER, SMOKE_TEXT),
        ).single()
        assertEquals(SMOKE_NUMBER.toLong(), row.long("numeric_value"))
        assertEquals(SMOKE_TEXT, row.string("text_value"))

        progress("smoke-transaction")
        database.transaction {
            val transactionRow = queryRaw(
                "SELECT CAST(? AS SIGNED) AS transaction_value",
                listOf(SMOKE_NUMBER),
            ).single()
            assertEquals(SMOKE_NUMBER.toLong(), transactionRow.long("transaction_value"))
        }
        progress("complete")
    } finally {
        progress("close")
        database.close()
    }
}

private suspend fun verifyMySqlTypes(database: SqlDatabase) {
    val fixture = ContractFixture()
    database.transaction(isolation = TransactionIsolation.REPEATABLE_READ) {
        insertInto(ContractRows).values {
            set(ContractRows.id, fixture.id)
            set(ContractRows.binaryId, fixture.id)
            set(ContractRows.active, true)
            set(ContractRows.payload, fixture.payload)
            set(ContractRows.instantValue, fixture.instant)
            set(ContractRows.durationValue, fixture.duration)
            set(ContractRows.dateValue, fixture.date)
            set(ContractRows.timeValue, fixture.time)
            set(ContractRows.dateTimeValue, fixture.dateTime)
            set(ContractRows.offsetValue, fixture.offset)
            set(ContractRows.timeZoneValue, fixture.timeZone)
            set(ContractRows.ubyteValue, UByte.MAX_VALUE)
            set(ContractRows.ushortValue, UShort.MAX_VALUE)
            set(ContractRows.uintValue, UInt.MAX_VALUE)
            set(ContractRows.ulongValue, ULong.MAX_VALUE)
            set(ContractRows.integerValue, fixture.integer)
            set(ContractRows.decimalValue, fixture.decimal)
        }.execute()
    }

    val row = database.select(
        ContractRows.id,
        ContractRows.binaryId,
        ContractRows.active,
        ContractRows.payload,
        ContractRows.instantValue,
        ContractRows.durationValue,
        ContractRows.dateValue,
        ContractRows.timeValue,
        ContractRows.dateTimeValue,
        ContractRows.offsetValue,
        ContractRows.timeZoneValue,
        ContractRows.ubyteValue,
        ContractRows.ushortValue,
        ContractRows.uintValue,
        ContractRows.ulongValue,
        ContractRows.integerValue,
        ContractRows.decimalValue,
    ).from(ContractRows).where(ContractRows.id eq fixture.id).one()
    assertEquals(fixture.id, row[ContractRows.id])
    assertEquals(fixture.id, row[ContractRows.binaryId])
    assertEquals(true, row[ContractRows.active])
    assertContentEquals(fixture.payload, row[ContractRows.payload])
    assertEquals(fixture.instant, row[ContractRows.instantValue])
    assertEquals(fixture.duration, row[ContractRows.durationValue])
    assertEquals(fixture.date, row[ContractRows.dateValue])
    assertEquals(fixture.time, row[ContractRows.timeValue])
    assertEquals(fixture.dateTime, row[ContractRows.dateTimeValue])
    assertEquals(fixture.offset, row[ContractRows.offsetValue])
    assertEquals(fixture.timeZone, row[ContractRows.timeZoneValue])
    assertEquals(UByte.MAX_VALUE, row[ContractRows.ubyteValue])
    assertEquals(UShort.MAX_VALUE, row[ContractRows.ushortValue])
    assertEquals(UInt.MAX_VALUE, row[ContractRows.uintValue])
    assertEquals(ULong.MAX_VALUE, row[ContractRows.ulongValue])
    assertEquals(fixture.integer, row[ContractRows.integerValue])
    assertEquals(0, fixture.decimal.compareTo(row[ContractRows.decimalValue]))
}

private data class ContractFixture(
    val id: Uuid = Uuid.parse("123e4567-e89b-12d3-a456-426614174000"),
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

private object ContractRows : Table("kagiroi_mysql_contract") {
    val id = uuid("id")
    val binaryId = binaryUuid("binary_id")
    val active = boolean("active")
    val payload = bytes("payload")
    val instantValue = instant("instant_value")
    val durationValue = duration("duration_value")
    val dateValue = localDate("date_value")
    val timeValue = localTime("time_value")
    val dateTimeValue = localDateTime("date_time_value")
    val offsetValue = utcOffset("offset_value")
    val timeZoneValue = timeZone("time_zone_value")
    val ubyteValue = ubyte("ubyte_value")
    val ushortValue = ushort("ushort_value")
    val uintValue = uint("uint_value")
    val ulongValue = ulong("ulong_value")
    val integerValue = bigInteger("integer_value")
    val decimalValue = bigDecimal("decimal_value")
}

private val mySqlCommonContract = SqlDatabaseContractConfiguration(
    rawSelectByIdSql = "SELECT name, payload FROM kagiroi_sql_contract WHERE id = ?",
    uniqueViolationSqlState = "23000",
)

private val contractTableDdl =
    """
    CREATE TABLE kagiroi_mysql_contract (
        id CHAR(36) PRIMARY KEY,
        binary_id BINARY(16) NOT NULL,
        active BOOLEAN NOT NULL,
        payload BLOB NOT NULL,
        instant_value TIMESTAMP(6) NOT NULL,
        duration_value TEXT NOT NULL,
        date_value DATE NOT NULL,
        time_value TIME(6) NOT NULL,
        date_time_value DATETIME(6) NOT NULL,
        offset_value TEXT NOT NULL,
        time_zone_value TEXT NOT NULL,
        ubyte_value TINYINT UNSIGNED NOT NULL,
        ushort_value SMALLINT UNSIGNED NOT NULL,
        uint_value INT UNSIGNED NOT NULL,
        ulong_value BIGINT UNSIGNED NOT NULL,
        integer_value DECIMAL(40, 0) NOT NULL,
        decimal_value DECIMAL(40, 18) NOT NULL
    )
    """.trimIndent()

private const val CONTRACT_TABLE_DROP = "DROP TABLE IF EXISTS kagiroi_mysql_contract"
private const val SMOKE_NUMBER = 42
private const val SMOKE_TEXT = "kagiroi"
