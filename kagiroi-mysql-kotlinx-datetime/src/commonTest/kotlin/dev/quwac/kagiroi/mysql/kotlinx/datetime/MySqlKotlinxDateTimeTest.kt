package dev.quwac.kagiroi.mysql.kotlinx.datetime

import dev.quwac.kagiroi.kotlinx.datetime.KotlinxLocalDateColumnType
import dev.quwac.kagiroi.kotlinx.datetime.KotlinxLocalDateTimeColumnType
import dev.quwac.kagiroi.kotlinx.datetime.KotlinxLocalTimeColumnType
import dev.quwac.kagiroi.kotlinx.datetime.KotlinxTimeZoneColumnType
import dev.quwac.kagiroi.kotlinx.datetime.KotlinxUtcOffsetColumnType
import dev.quwac.kagiroi.kotlinx.datetime.localDateTime
import dev.quwac.kagiroi.kotlinx.datetime.localTime
import dev.quwac.kagiroi.kotlinx.datetime.timeZone
import dev.quwac.kagiroi.kotlinx.datetime.utcOffset
import dev.quwac.kagiroi.mysql.MySqlBinding
import dev.quwac.kagiroi.mysql.MySqlDialect
import dev.quwac.kagiroi.mysql.MySqlResultValue
import dev.quwac.kagiroi.mysql.MySqlScalar
import dev.quwac.kagiroi.mysql.MySqlType
import dev.quwac.kagiroi.mysql.MySqlTypeRegistry
import dev.quwac.kagiroi.mysql.MySqlTypes
import dev.quwac.kagiroi.mysql.MySqlValueCodec
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

class MySqlKotlinxDateTimeTest {
    @Test
    fun mapsLocalTypesToDistinctMySqlTypes() {
        val registry = MySqlTypeRegistry(listOf(MySqlKotlinxDateTime))

        assertEquals(MySqlType.DATE, registry.typeOf(KotlinxLocalDateColumnType))
        assertEquals(MySqlType.TIME, registry.typeOf(KotlinxLocalTimeColumnType))
        assertEquals(MySqlType.DATETIME, registry.typeOf(KotlinxLocalDateTimeColumnType))
        assertEquals(MySqlType.TEXT, registry.typeOf(KotlinxUtcOffsetColumnType))
        assertEquals(MySqlType.TEXT, registry.typeOf(KotlinxTimeZoneColumnType))
    }

    @Test
    fun roundTripsOffsetAndIanaTimeZoneAsText() {
        val codec = MySqlValueCodec(SqlTypeCodecRegistry(MySqlKotlinxDateTime.typeCodecs))
        val offset = UtcOffset.parse("+09:00")
        val timeZone = TimeZone.of("Asia/Tokyo")

        assertEquals(MySqlBinding.Text("+09:00"), codec.binding(offset, Values.offset))
        assertEquals(MySqlBinding.Text("Asia/Tokyo"), codec.binding(timeZone, Values.timeZone))
        assertEquals(
            offset,
            codec.decode(
                Values.offset,
                MySqlResultValue(MySqlType.TEXT.protocolType, MySqlScalar.Text("+09:00")),
            ),
        )
        assertEquals(
            timeZone,
            codec.decode(
                Values.timeZone,
                MySqlResultValue(MySqlType.TEXT.protocolType, MySqlScalar.Text("Asia/Tokyo")),
            ),
        )
    }

    @Test
    fun rejectsLocalValuesThatWouldLoseNanosecondPrecision() {
        val codec = MySqlValueCodec(SqlTypeCodecRegistry(MySqlKotlinxDateTime.typeCodecs))

        assertFailsWith<IllegalArgumentException> {
            codec.binding(LocalTime.parse("01:02:03.123456789"), Values.time)
        }
        assertFailsWith<IllegalArgumentException> {
            codec.binding(LocalDateTime.parse("2026-10-18T01:02:03.123456789"), Values.dateTime)
        }
    }

    @Test
    fun mapsMySqlPhysicalTemporalColumnsWithTheDeclaredPrecision() {
        val date = LocalDate.parse("2040-01-02")
        val time = LocalTime.parse("03:04:05.123456")
        val dateTime = LocalDateTime.parse("2040-01-02T03:04:05.123456")
        val codec = MySqlValueCodec(SqlTypeCodecRegistry(emptyList()))

        assertEquals(
            "CREATE TABLE `physical_temporal_values` (`date_value` DATE NOT NULL, " +
                "`time_value` TIME(6) NOT NULL, `date_time_value` DATETIME(6) NOT NULL)",
            MySqlDialect.render(createTable(PhysicalTemporalValues)).sql,
        )
        assertEquals(MySqlBinding.Text("2040-01-02"), codec.binding(date, PhysicalTemporalValues.dateValue))
        assertEquals(MySqlBinding.Text("03:04:05.123456"), codec.binding(time, PhysicalTemporalValues.timeValue))
        assertEquals(
            MySqlBinding.Text("2040-01-02 03:04:05.123456"),
            codec.binding(dateTime, PhysicalTemporalValues.dateTimeValue),
        )
        assertEquals(
            date,
            codec.decode(
                PhysicalTemporalValues.dateValue,
                MySqlResultValue(MySqlType.DATE.protocolType, MySqlScalar.Text("2040-01-02")),
            ),
        )
        assertEquals(
            time,
            codec.decode(
                PhysicalTemporalValues.timeValue,
                MySqlResultValue(MySqlType.TIME.protocolType, MySqlScalar.Text("03:04:05.123456")),
            ),
        )
        assertEquals(
            dateTime,
            codec.decode(
                PhysicalTemporalValues.dateTimeValue,
                MySqlResultValue(MySqlType.DATETIME.protocolType, MySqlScalar.Text("2040-01-02 03:04:05.123456")),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            codec.binding(LocalTime.parse("03:04:05.1234567"), PhysicalTemporalValues.timeValue)
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
    val dateValue = column("date_value", MySqlTypes.date(MySqlCompatibleKotlinxDateTimeMappings.localDate))
    val timeValue = column("time_value", MySqlTypes.time(6, MySqlCompatibleKotlinxDateTimeMappings.localTime(6)))
    val dateTimeValue = column(
        "date_time_value",
        MySqlTypes.dateTime(6, MySqlCompatibleKotlinxDateTimeMappings.localDateTime(6)),
    )
}
