@file:Suppress("TooManyFunctions") // The public date/time expression vocabulary needs overloads per logical type.

package dev.quwac.kagiroi.kotlinx.datetime

import dev.quwac.kagiroi.schema.ColumnType
import dev.quwac.kagiroi.schema.IntColumnType
import dev.quwac.kagiroi.schema.SchemaTypeDescriptor
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.SqlFunction
import dev.quwac.kagiroi.schema.SqlFunctionExpression
import dev.quwac.kagiroi.schema.StableSchemaColumnType
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.WritableColumn
import dev.quwac.kagiroi.schema.resultExpression
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlin.js.JsName

/** Represents kotlinx local date column type. */
object KotlinxLocalDateColumnType : ColumnType<LocalDate>("LocalDate"), StableSchemaColumnType {
    override val schemaTypeDescriptor = SchemaTypeDescriptor("kagiroi.kotlinx.localDate")
}

/** Represents kotlinx local time column type. */
object KotlinxLocalTimeColumnType : ColumnType<LocalTime>("LocalTime"), StableSchemaColumnType {
    override val schemaTypeDescriptor = SchemaTypeDescriptor("kagiroi.kotlinx.localTime")
}

/** Represents kotlinx local date time column type. */
object KotlinxLocalDateTimeColumnType : ColumnType<LocalDateTime>("LocalDateTime"), StableSchemaColumnType {
    override val schemaTypeDescriptor = SchemaTypeDescriptor("kagiroi.kotlinx.localDateTime")
}

/** Represents kotlinx utc offset column type. */
object KotlinxUtcOffsetColumnType : ColumnType<UtcOffset>("UtcOffset"), StableSchemaColumnType {
    override val schemaTypeDescriptor = SchemaTypeDescriptor("kagiroi.kotlinx.utcOffset")
}

/** Represents kotlinx time zone column type. */
object KotlinxTimeZoneColumnType : ColumnType<TimeZone>("TimeZone"), StableSchemaColumnType {
    override val schemaTypeDescriptor = SchemaTypeDescriptor("kagiroi.kotlinx.timeZone")
}

/** Provides the local date result. */
fun Table.localDate(name: String): WritableColumn<LocalDate> = column(name, KotlinxLocalDateColumnType)

/** Provides the local time result. */
fun Table.localTime(name: String): WritableColumn<LocalTime> = column(name, KotlinxLocalTimeColumnType)

/** Provides the local date time result. */
fun Table.localDateTime(name: String): WritableColumn<LocalDateTime> = column(name, KotlinxLocalDateTimeColumnType)

/** Provides the utc offset result. */
fun Table.utcOffset(name: String): WritableColumn<UtcOffset> = column(name, KotlinxUtcOffsetColumnType)

/** Provides the time zone result. */
fun Table.timeZone(name: String): WritableColumn<TimeZone> = column(name, KotlinxTimeZoneColumnType)

/**
 * Returns the database's current calendar date in its current session time zone.
 *
 * SQLite and D1 have no session time zone and define this value in UTC. The result has no offset
 * or zone attached; callers that require an instant should use `currentTimestamp()` instead.
 */
fun currentDate(): SqlFunctionExpression<LocalDate> = temporalFunction(
    SqlFunction.CurrentDate,
    KotlinxLocalDateColumnType,
)

/**
 * Returns the database's current clock time in its current session time zone.
 *
 * SQLite and D1 define this value in UTC. The result is a `LocalTime`, so it intentionally does
 * not imply an offset or a globally comparable instant.
 */
fun currentTime(): SqlFunctionExpression<LocalTime> = temporalFunction(
    SqlFunction.CurrentTime,
    KotlinxLocalTimeColumnType,
)

/** Extracts the calendar year from a local date or local date-time. */
@JsName("yearLocalDate")
fun year(expression: SqlExpression<LocalDate>): SqlFunctionExpression<Int> = extraction(SqlFunction.Year, expression)

/** Extracts the calendar year from a local date-time. */
@JsName("yearLocalDateTime")
fun year(expression: SqlExpression<LocalDateTime>): SqlFunctionExpression<Int> = extraction(
    SqlFunction.Year,
    expression,
)

/** Extracts the calendar month number from a local date or local date-time. */
@JsName("monthLocalDate")
fun month(expression: SqlExpression<LocalDate>): SqlFunctionExpression<Int> = extraction(SqlFunction.Month, expression)

/** Extracts the calendar month number from a local date-time. */
@JsName("monthLocalDateTime")
fun month(expression: SqlExpression<LocalDateTime>): SqlFunctionExpression<Int> = extraction(
    SqlFunction.Month,
    expression,
)

/** Extracts the day-of-month from a local date or local date-time. */
@JsName("dayLocalDate")
fun day(expression: SqlExpression<LocalDate>): SqlFunctionExpression<Int> = extraction(SqlFunction.Day, expression)

/** Extracts the day-of-month from a local date-time. */
@JsName("dayLocalDateTime")
fun day(expression: SqlExpression<LocalDateTime>): SqlFunctionExpression<Int> = extraction(SqlFunction.Day, expression)

/** Extracts the hour from a local time or local date-time. */
@JsName("hourLocalTime")
fun hour(expression: SqlExpression<LocalTime>): SqlFunctionExpression<Int> = extraction(SqlFunction.Hour, expression)

/** Extracts the hour from a local date-time. */
@JsName("hourLocalDateTime")
fun hour(expression: SqlExpression<LocalDateTime>): SqlFunctionExpression<Int> = extraction(
    SqlFunction.Hour,
    expression,
)

/** Extracts the minute from a local time or local date-time. */
@JsName("minuteLocalTime")
fun minute(expression: SqlExpression<LocalTime>): SqlFunctionExpression<Int> = extraction(
    SqlFunction.Minute,
    expression,
)

/** Extracts the minute from a local date-time. */
@JsName("minuteLocalDateTime")
fun minute(expression: SqlExpression<LocalDateTime>): SqlFunctionExpression<Int> = extraction(
    SqlFunction.Minute,
    expression,
)

/** Extracts integral seconds from a local time or local date-time. */
@JsName("secondLocalTime")
fun second(expression: SqlExpression<LocalTime>): SqlFunctionExpression<Int> = extraction(
    SqlFunction.Second,
    expression,
)

/** Extracts integral seconds from a local date-time. */
@JsName("secondLocalDateTime")
fun second(expression: SqlExpression<LocalDateTime>): SqlFunctionExpression<Int> = extraction(
    SqlFunction.Second,
    expression,
)

private fun <T : Any> temporalFunction(function: SqlFunction, type: ColumnType<T>): SqlFunctionExpression<T> =
    SqlFunctionExpression(function, emptyList(), resultExpression(type), isNullable = false)

private fun extraction(function: SqlFunction, expression: SqlExpression<*>): SqlFunctionExpression<Int> =
    SqlFunctionExpression(function, listOf(expression), resultExpression(IntColumnType), isNullable = false)
