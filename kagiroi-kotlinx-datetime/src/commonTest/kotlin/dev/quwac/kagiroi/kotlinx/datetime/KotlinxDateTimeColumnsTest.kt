package dev.quwac.kagiroi.kotlinx.datetime

import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.computed
import dev.quwac.kagiroi.schema.nullable
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KotlinxDateTimeColumnsTest {
    @Test
    fun definesTypedKotlinxDateTimeColumns() {
        val date: Column<LocalDate> = Schedule.date
        val time: Column<LocalTime> = Schedule.time
        val dateTime: Column<LocalDateTime?> = Schedule.dateTime
        val offset: Column<UtcOffset> = Schedule.offset
        val timeZone: Column<TimeZone> = Schedule.timeZone

        assertSame(KotlinxLocalDateColumnType, date.type)
        assertSame(KotlinxLocalTimeColumnType, time.type)
        assertSame(KotlinxLocalDateTimeColumnType, dateTime.type)
        assertSame(KotlinxUtcOffsetColumnType, offset.type)
        assertSame(KotlinxTimeZoneColumnType, timeZone.type)
        assertTrue(dateTime.isNullable)
        assertTrue(timeZone.isComputed)
        assertEquals(listOf("date", "time", "date_time", "offset", "time_zone"), Schedule.columns.map { it.name })
    }
}

private object Schedule : Table("schedule") {
    val date = localDate("date")
    val time = localTime("time")
    val dateTime = localDateTime("date_time").nullable()
    val offset = utcOffset("offset")
    val timeZone = timeZone("time_zone").computed()
}
