package dev.quwac.kagiroi.kotlinx.datetime

import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.default
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

class DateTimeExpressionTest {
    @Test
    fun currentValuesAndExtractionRetainLocalLogicalTypes() {
        val date: SqlExpression<LocalDate> = currentDate()
        val time: SqlExpression<LocalTime> = currentTime()
        val year: SqlExpression<Int> = year(DateTimeRows.date)
        val month: SqlExpression<Int> = month(DateTimeRows.dateTime)
        val day: SqlExpression<Int> = day(DateTimeRows.dateTime)
        val hour: SqlExpression<Int> = hour(DateTimeRows.time)
        val minute: SqlExpression<Int> = minute(DateTimeRows.dateTime)
        val second: SqlExpression<Int> = second(DateTimeRows.time)

        assertEquals(false, date.isNullable)
        assertEquals(false, time.isNullable)
        assertEquals(false, year.isNullable)
        assertEquals(false, month.isNullable)
        assertEquals(false, day.isNullable)
        assertEquals(false, hour.isNullable)
        assertEquals(false, minute.isNullable)
        assertEquals(false, second.isNullable)
    }

    @Test
    fun currentDateAndTimeAreTypedDefaultExpressions() {
        assertEquals(
            KotlinxLocalDateColumnType,
            DateTimeDefaultRows.date.defaultExpression?.expression?.type,
        )
        assertEquals(
            KotlinxLocalTimeColumnType,
            DateTimeDefaultRows.time.defaultExpression?.expression?.type,
        )
    }
}

private object DateTimeRows : Table("date_time_rows") {
    val date = localDate("date")
    val time = localTime("time")
    val dateTime = localDateTime("date_time")
}

private object DateTimeDefaultRows : Table("date_time_default_rows") {
    val date = localDate("date").default(currentDate())
    val time = localTime("time").default(currentTime())
}
