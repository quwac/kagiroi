package dev.quwac.kagiroi.postgres.node

import dev.quwac.bignumber.BigDecimal
import dev.quwac.bignumber.BigInteger
import dev.quwac.kagiroi.postgres.PostgresIntervalFields
import dev.quwac.kagiroi.postgres.PostgresTypes
import dev.quwac.kagiroi.postgres.kotlinx.datetime.PostgresKotlinxDateTimeMappings
import dev.quwac.kagiroi.quwac.bignumber.BigNumberTextMappings
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.values
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.assertEquals

internal suspend fun verifyPostgresPhysicalTypes(database: NodePostgresDatabase) {
    database.execute(createTable(PostgresPhysicalTypes))
    database.insertInto(PostgresPhysicalTypes).values {
        set(PostgresPhysicalTypes.address, "2001:db8::1")
        set(PostgresPhysicalTypes.metadata, "{\"id\":1}")
        set(PostgresPhysicalTypes.flags, "1010")
        set(PostgresPhysicalTypes.tags, "{alpha,beta}")
        set(PostgresPhysicalTypes.span, "[1,10)")
        set(PostgresPhysicalTypes.location, "(1,2)")
        set(PostgresPhysicalTypes.amount, "123.45")
        set(PostgresPhysicalTypes.exactInteger, PostgresPhysicalValues.exactInteger)
        set(PostgresPhysicalTypes.exactAmount, PostgresPhysicalValues.exactAmount)
        set(PostgresPhysicalTypes.objectId, PostgresPhysicalValues.OBJECT_ID)
        set(PostgresPhysicalTypes.duration, "2 days 03:04:05.123456")
        set(PostgresPhysicalTypes.localDate, PostgresPhysicalValues.localDate)
        set(PostgresPhysicalTypes.localTime, PostgresPhysicalValues.localTime)
        set(PostgresPhysicalTypes.localDateTime, PostgresPhysicalValues.localDateTime)
    }.execute()

    val row = database.select(
        PostgresPhysicalTypes.address,
        PostgresPhysicalTypes.metadata,
        PostgresPhysicalTypes.flags,
        PostgresPhysicalTypes.tags,
        PostgresPhysicalTypes.span,
        PostgresPhysicalTypes.location,
        PostgresPhysicalTypes.amount,
        PostgresPhysicalTypes.exactInteger,
        PostgresPhysicalTypes.exactAmount,
        PostgresPhysicalTypes.objectId,
        PostgresPhysicalTypes.duration,
        PostgresPhysicalTypes.localDate,
        PostgresPhysicalTypes.localTime,
        PostgresPhysicalTypes.localDateTime,
    ).from(PostgresPhysicalTypes).one()
    assertEquals("2001:db8::1", row[PostgresPhysicalTypes.address])
    assertEquals("{\"id\": 1}", row[PostgresPhysicalTypes.metadata])
    assertEquals("1010", row[PostgresPhysicalTypes.flags])
    assertEquals("{alpha,beta}", row[PostgresPhysicalTypes.tags])
    assertEquals("[1,10)", row[PostgresPhysicalTypes.span])
    assertEquals("(1,2)", row[PostgresPhysicalTypes.location])
    assertEquals("123.45", row[PostgresPhysicalTypes.amount])
    assertEquals(PostgresPhysicalValues.exactInteger, row[PostgresPhysicalTypes.exactInteger])
    assertEquals(PostgresPhysicalValues.exactAmount, row[PostgresPhysicalTypes.exactAmount])
    assertEquals(PostgresPhysicalValues.OBJECT_ID, row[PostgresPhysicalTypes.objectId])
    assertEquals("2 days 03:04:05.123456", row[PostgresPhysicalTypes.duration])
    assertEquals(PostgresPhysicalValues.localDate, row[PostgresPhysicalTypes.localDate])
    assertEquals(PostgresPhysicalValues.localTime, row[PostgresPhysicalTypes.localTime])
    assertEquals(PostgresPhysicalValues.localDateTime, row[PostgresPhysicalTypes.localDateTime])
}

internal object PostgresPhysicalTypes : Table("kagiroi_node_postgres_physical_types") {
    val address = column("address", PostgresTypes.inet)
    val metadata = column("metadata", PostgresTypes.jsonb)
    val flags = column("flags", PostgresTypes.bit(4))
    val tags = column("tags", PostgresTypes.array(PostgresTypes.text))
    val span = column("span", PostgresTypes.int4Range)
    val location = column("location", PostgresTypes.point)
    val amount = column("amount", PostgresTypes.numeric(10, 2))
    val exactInteger = column("exact_integer", PostgresTypes.numeric(40, 0, BigNumberTextMappings.integer))
    val exactAmount = column("exact_amount", PostgresTypes.numeric(30, 18, BigNumberTextMappings.decimal))
    val objectId = column("object_id", PostgresTypes.oid)
    val duration = column("duration", PostgresTypes.interval(PostgresIntervalFields.DAY_TO_SECOND, 6))
    val localDate = column("local_date", PostgresTypes.date(PostgresKotlinxDateTimeMappings.localDate))
    val localTime = column("local_time", PostgresTypes.time(6, PostgresKotlinxDateTimeMappings.localTime(6)))
    val localDateTime = column(
        "local_date_time",
        PostgresTypes.timestamp(6, PostgresKotlinxDateTimeMappings.localDateTime(6)),
    )
}

private object PostgresPhysicalValues {
    const val OBJECT_ID: UInt = 42u
    val exactInteger: BigInteger = BigInteger.parse("9999999999999999999999999999999999999999")
    val exactAmount: BigDecimal = BigDecimal.parse("123456789012.123456789012345678")
    val localDate: LocalDate = LocalDate.parse("2040-01-02")
    val localTime: LocalTime = LocalTime.parse("03:04:05.123456")
    val localDateTime: LocalDateTime = LocalDateTime.parse("2040-01-02T03:04:05.123456")
}
