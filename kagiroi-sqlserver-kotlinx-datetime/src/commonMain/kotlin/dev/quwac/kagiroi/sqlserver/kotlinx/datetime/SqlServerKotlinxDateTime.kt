package dev.quwac.kagiroi.sqlserver.kotlinx.datetime

import dev.quwac.kagiroi.kotlinx.datetime.KotlinxLocalDateColumnType
import dev.quwac.kagiroi.kotlinx.datetime.KotlinxLocalDateTimeColumnType
import dev.quwac.kagiroi.kotlinx.datetime.KotlinxLocalTimeColumnType
import dev.quwac.kagiroi.kotlinx.datetime.KotlinxTimeZoneColumnType
import dev.quwac.kagiroi.kotlinx.datetime.KotlinxUtcOffsetColumnType
import dev.quwac.kagiroi.schema.ColumnMapping
import dev.quwac.kagiroi.schema.ColumnType
import dev.quwac.kagiroi.schema.StringColumnType
import dev.quwac.kagiroi.schema.columnMapping
import dev.quwac.kagiroi.sql.SqlTypeCodec
import dev.quwac.kagiroi.sqlserver.SqlServerExtension
import dev.quwac.kagiroi.sqlserver.SqlServerType
import dev.quwac.kagiroi.sqlserver.SqlServerTypeMapping
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset

/** Explicit local temporal mappings for SQL Server DATE, TIME, and DATETIME2. */
object SqlServerKotlinxDateTimeMappings {
    /** Value associated with local date. */
    val localDate: ColumnMapping<LocalDate, String> = columnMapping(LocalDate::parse, LocalDate::toString)

    /** Provides the local time result. */
    fun localTime(fractionalSecondsPrecision: Int = DEFAULT_PRECISION): ColumnMapping<LocalTime, String> =
        columnMapping(
            LocalTime::parse,
            { value -> value.toString().requirePrecision(fractionalSecondsPrecision, "TIME") },
        )

    /** Provides the local date time result. */
    fun localDateTime(fractionalSecondsPrecision: Int = DEFAULT_PRECISION): ColumnMapping<LocalDateTime, String> =
        columnMapping(
            { LocalDateTime.parse(it.replaceFirst(' ', 'T')) },
            { value -> value.toString().requirePrecision(fractionalSecondsPrecision, "DATETIME2") },
        )
}

/** Native local date/time and lossless offset/zone mappings for kotlinx-datetime types. */
object SqlServerKotlinxDateTime : SqlServerExtension {
    override val typeMappings = listOf(
        SqlServerTypeMapping(KotlinxLocalDateColumnType, SqlServerType.DATE),
        SqlServerTypeMapping(KotlinxLocalTimeColumnType, SqlServerType.TIME),
        SqlServerTypeMapping(KotlinxLocalDateTimeColumnType, SqlServerType.DATETIME2),
        SqlServerTypeMapping(KotlinxUtcOffsetColumnType, SqlServerType.NVARCHAR),
        SqlServerTypeMapping(KotlinxTimeZoneColumnType, SqlServerType.NVARCHAR),
    )

    override val typeCodecs = initializeTimeZoneSupport().let {
        listOf(
            SqlServerKotlinxDateTimeMappings.localDate.textCodec(KotlinxLocalDateColumnType),
            SqlServerKotlinxDateTimeMappings.localTime().textCodec(KotlinxLocalTimeColumnType),
            SqlServerKotlinxDateTimeMappings.localDateTime().textCodec(KotlinxLocalDateTimeColumnType),
            SqlTypeCodec(KotlinxUtcOffsetColumnType, StringColumnType, UtcOffset::toString, UtcOffset::parse),
            SqlTypeCodec(KotlinxTimeZoneColumnType, StringColumnType, TimeZone::id, TimeZone::of),
        )
    }
}

internal expect fun initializeTimeZoneSupport()

private fun <T : Any> ColumnMapping<T, String>.textCodec(type: ColumnType<T>): SqlTypeCodec<T, String> = SqlTypeCodec(
    type,
    StringColumnType,
    ::toStorageValue,
    ::fromStorageValue,
)

private fun String.requirePrecision(precision: Int, type: String): String {
    require(precision in 0..MAX_FRACTIONAL_SECONDS_PRECISION) {
        "$type fractional seconds precision must be 0..$MAX_FRACTIONAL_SECONDS_PRECISION: $precision"
    }
    val fraction = substringAfterLast('.', missingDelimiterValue = "").takeWhile(Char::isDigit)
    require(fraction.length <= precision) {
        "$type($precision) cannot represent fractional seconds without rounding"
    }
    return this
}

private const val DEFAULT_PRECISION = 7
private const val MAX_FRACTIONAL_SECONDS_PRECISION = 7
