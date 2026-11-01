package dev.quwac.kagiroi.postgres.kotlinx.datetime

import dev.quwac.kagiroi.kotlinx.datetime.KotlinxLocalDateColumnType
import dev.quwac.kagiroi.kotlinx.datetime.KotlinxLocalDateTimeColumnType
import dev.quwac.kagiroi.kotlinx.datetime.KotlinxLocalTimeColumnType
import dev.quwac.kagiroi.kotlinx.datetime.KotlinxTimeZoneColumnType
import dev.quwac.kagiroi.kotlinx.datetime.KotlinxUtcOffsetColumnType
import dev.quwac.kagiroi.postgres.PostgresExtension
import dev.quwac.kagiroi.postgres.PostgresType
import dev.quwac.kagiroi.postgres.PostgresTypeMapping
import dev.quwac.kagiroi.schema.ColumnMapping
import dev.quwac.kagiroi.schema.ColumnType
import dev.quwac.kagiroi.schema.StringColumnType
import dev.quwac.kagiroi.schema.columnMapping
import dev.quwac.kagiroi.sql.SqlTypeCodec
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset

/**
 * Explicit `kotlinx-datetime` mappings for PostgreSQL local physical temporal columns.
 *
 * The precision argument must match `TIME WITHOUT TIME ZONE(p)` or `TIMESTAMP WITHOUT
 * TIME ZONE(p)`. `TIME WITH TIME ZONE`, `TIMESTAMP WITH TIME ZONE`, and `INTERVAL`
 * remain intentionally absent because they need an offset, instant, or calendar-period
 * policy beyond one local `kotlinx-datetime` value.
 */
object PostgresKotlinxDateTimeMappings {
    val localDate: ColumnMapping<LocalDate, String> = columnMapping(
        fromStorage = LocalDate::parse,
        toStorage = LocalDate::toString,
    )

    fun localTime(fractionalSecondsPrecision: Int): ColumnMapping<LocalTime, String> = columnMapping(
        fromStorage = LocalTime::parse,
        toStorage = { value ->
            value.toString().requireFractionalSecondsPrecision(fractionalSecondsPrecision, "TIME WITHOUT TIME ZONE")
        },
    )

    fun localDateTime(fractionalSecondsPrecision: Int): ColumnMapping<LocalDateTime, String> = columnMapping(
        fromStorage = { LocalDateTime.parse(it.replaceFirst(' ', 'T')) },
        toStorage = { value ->
            value.toString().requireFractionalSecondsPrecision(
                fractionalSecondsPrecision,
                "TIMESTAMP WITHOUT TIME ZONE",
            )
        },
    )
}

/** Native local date/time and lossless offset/zone mappings for kotlinx-datetime types. */
object PostgresKotlinxDateTime : PostgresExtension {
    override val typeMappings: List<PostgresTypeMapping<*>> = listOf(
        PostgresTypeMapping(KotlinxLocalDateColumnType, PostgresType.DATE),
        PostgresTypeMapping(KotlinxLocalTimeColumnType, PostgresType.TIME),
        PostgresTypeMapping(KotlinxLocalDateTimeColumnType, PostgresType.TIMESTAMP),
        PostgresTypeMapping(KotlinxUtcOffsetColumnType, PostgresType.TEXT),
        PostgresTypeMapping(KotlinxTimeZoneColumnType, PostgresType.TEXT),
    )

    override val typeCodecs: List<SqlTypeCodec<*, *>> = initializeTimeZoneSupport().let {
        listOf(
            PostgresKotlinxDateTimeMappings.localDate.textCodec(KotlinxLocalDateColumnType),
            PostgresKotlinxDateTimeMappings.localTime(MICROSECOND_DIGITS).textCodec(KotlinxLocalTimeColumnType),
            PostgresKotlinxDateTimeMappings.localDateTime(MICROSECOND_DIGITS)
                .textCodec(KotlinxLocalDateTimeColumnType),
            SqlTypeCodec(KotlinxUtcOffsetColumnType, StringColumnType, UtcOffset::toString, UtcOffset::parse),
            SqlTypeCodec(KotlinxTimeZoneColumnType, StringColumnType, TimeZone::id, TimeZone::of),
        )
    }
}

internal expect fun initializeTimeZoneSupport()

private fun <T : Any> ColumnMapping<T, String>.textCodec(type: ColumnType<T>): SqlTypeCodec<T, String> = SqlTypeCodec(
    type = type,
    storageType = StringColumnType,
    encode = ::toStorageValue,
    decode = ::fromStorageValue,
)

private fun String.requireFractionalSecondsPrecision(fractionalSecondsPrecision: Int, typeName: String): String {
    require(fractionalSecondsPrecision in 0..MICROSECOND_DIGITS) {
        "$typeName fractional seconds precision must be between 0 and $MICROSECOND_DIGITS: $fractionalSecondsPrecision"
    }
    val fraction = substringAfterLast('.', missingDelimiterValue = "")
    require(fraction.length <= fractionalSecondsPrecision) {
        "$typeName($fractionalSecondsPrecision) cannot represent $fraction fractional seconds without rounding"
    }
    return this
}

private const val MICROSECOND_DIGITS = 6
