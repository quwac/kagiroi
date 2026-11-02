package dev.quwac.kagiroi.postgres.kotlinx.datetime

import dev.quwac.kagiroi.kotlinx.datetime.KotlinxLocalDateColumnType
import dev.quwac.kagiroi.kotlinx.datetime.KotlinxLocalDateTimeColumnType
import dev.quwac.kagiroi.kotlinx.datetime.KotlinxLocalTimeColumnType
import dev.quwac.kagiroi.kotlinx.datetime.KotlinxTimeZoneColumnType
import dev.quwac.kagiroi.kotlinx.datetime.KotlinxUtcOffsetColumnType
import dev.quwac.kagiroi.kotlinx.datetime.localDateTime
import dev.quwac.kagiroi.kotlinx.datetime.localTime
import dev.quwac.kagiroi.kotlinx.datetime.timeZone
import dev.quwac.kagiroi.kotlinx.datetime.utcOffset
import dev.quwac.kagiroi.postgres.PostgresBinding
import dev.quwac.kagiroi.postgres.PostgresDialect
import dev.quwac.kagiroi.postgres.PostgresResultValue
import dev.quwac.kagiroi.postgres.PostgresType
import dev.quwac.kagiroi.postgres.PostgresTypeRegistry
import dev.quwac.kagiroi.postgres.PostgresTypes
import dev.quwac.kagiroi.postgres.PostgresValueCodec
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.SqlTypeCodecRegistry
import dev.quwac.kagiroi.sql.createTable
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PostgresKotlinxDateTimeTest {
    @Test
    fun mapsLocalTypesToNativePostgresTypes() {
        val registry = PostgresTypeRegistry(listOf(PostgresKotlinxDateTime))

        assertEquals(PostgresType.DATE, registry.typeOf(KotlinxLocalDateColumnType))
        assertEquals(PostgresType.TIME, registry.typeOf(KotlinxLocalTimeColumnType))
        assertEquals(PostgresType.TIMESTAMP, registry.typeOf(KotlinxLocalDateTimeColumnType))
        assertEquals(PostgresType.TEXT, registry.typeOf(KotlinxUtcOffsetColumnType))
        assertEquals(PostgresType.TEXT, registry.typeOf(KotlinxTimeZoneColumnType))
    }

    @Test
    fun roundTripsOffsetAndIanaTimeZoneAsText() {
        val codec = PostgresValueCodec(SqlTypeCodecRegistry(PostgresKotlinxDateTime.typeCodecs))
        val offset = UtcOffset.parse("+09:00")
        val timeZone = TimeZone.of("Asia/Tokyo")

        assertEquals(PostgresBinding.Text("+09:00"), codec.binding(offset, Values.offset))
        assertEquals(PostgresBinding.Text("Asia/Tokyo"), codec.binding(timeZone, Values.timeZone))
        assertEquals(
            offset,
            codec.decode(Values.offset, PostgresResultValue(PostgresType.TEXT.objectId, "+09:00")),
        )
        assertEquals(
            timeZone,
            codec.decode(Values.timeZone, PostgresResultValue(PostgresType.TEXT.objectId, "Asia/Tokyo")),
        )
    }

    @Test
    fun rejectsLocalValuesThatWouldLoseNanosecondPrecision() {
        val codec = PostgresValueCodec(SqlTypeCodecRegistry(PostgresKotlinxDateTime.typeCodecs))

        assertFailsWith<IllegalArgumentException> {
            codec.binding(LocalTime.parse("01:02:03.123456789"), Values.time)
        }
        assertFailsWith<IllegalArgumentException> {
            codec.binding(LocalDateTime.parse("2026-10-18T01:02:03.123456789"), Values.dateTime)
        }
    }

    @Test
    fun mapsPostgresPhysicalLocalTemporalColumnsWithTheDeclaredPrecision() {
        val date = LocalDate.parse("2040-01-02")
        val time = LocalTime.parse("03:04:05.123456")
        val dateTime = LocalDateTime.parse("2040-01-02T03:04:05.123456")
        val codec = PostgresValueCodec(SqlTypeCodecRegistry(emptyList()))

        assertEquals(
            "CREATE TABLE \"physical_temporal_values\" (\"date_value\" DATE NOT NULL, " +
                "\"time_value\" TIME(6) WITHOUT TIME ZONE NOT NULL, " +
                "\"date_time_value\" TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL)",
            PostgresDialect.render(createTable(PhysicalTemporalValues)).sql,
        )
        assertEquals(PostgresBinding.Text("2040-01-02"), codec.binding(date, PhysicalTemporalValues.dateValue))
        assertEquals(PostgresBinding.Text("03:04:05.123456"), codec.binding(time, PhysicalTemporalValues.timeValue))
        assertEquals(
            PostgresBinding.Text("2040-01-02T03:04:05.123456"),
            codec.binding(dateTime, PhysicalTemporalValues.dateTimeValue),
        )
        assertEquals(
            date,
            codec.decode(
                PhysicalTemporalValues.dateValue,
                PostgresResultValue(PostgresType.DATE.objectId, "2040-01-02"),
            ),
        )
        assertEquals(
            time,
            codec.decode(
                PhysicalTemporalValues.timeValue,
                PostgresResultValue(PostgresType.TIME.objectId, "03:04:05.123456"),
            ),
        )
        assertEquals(
            dateTime,
            codec.decode(
                PhysicalTemporalValues.dateTimeValue,
                PostgresResultValue(PostgresType.TIMESTAMP.objectId, "2040-01-02 03:04:05.123456"),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            codec.binding(LocalDateTime.parse("2040-01-02T03:04:05.1234567"), PhysicalTemporalValues.dateTimeValue)
        }
    }
}

private object Values : Table("values") {
    val time = localTime("time")
    val dateTime = localDateTime("date_time")
    val offset = utcOffset("offset")
    val timeZone = timeZone("time_zone")
}

private object PhysicalTemporalValues : Table("physical_temporal_values") {
    val dateValue = column("date_value", PostgresTypes.date(PostgresKotlinxDateTimeMappings.localDate))
    val timeValue = column("time_value", PostgresTypes.time(6, PostgresKotlinxDateTimeMappings.localTime(6)))
    val dateTimeValue = column(
        "date_time_value",
        PostgresTypes.timestamp(6, PostgresKotlinxDateTimeMappings.localDateTime(6)),
    )
}
