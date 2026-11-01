package dev.quwac.kagiroi.mysql.kotlinx.datetime

import dev.quwac.kagiroi.kotlinx.datetime.KotlinxLocalDateColumnType
import dev.quwac.kagiroi.kotlinx.datetime.KotlinxLocalDateTimeColumnType
import dev.quwac.kagiroi.kotlinx.datetime.KotlinxLocalTimeColumnType
import dev.quwac.kagiroi.kotlinx.datetime.KotlinxTimeZoneColumnType
import dev.quwac.kagiroi.kotlinx.datetime.KotlinxUtcOffsetColumnType
import dev.quwac.kagiroi.mysql.MySqlExtension
import dev.quwac.kagiroi.mysql.MySqlType
import dev.quwac.kagiroi.mysql.MySqlTypeMapping
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
 * Explicit `kotlinx-datetime` mappings for MySQL-compatible physical temporal columns.
 *
 * These mappings work with both [dev.quwac.kagiroi.mysql.MySqlTypes] and
 * `MariaDbTypes` because `DATE`, `TIME`, and `DATETIME` retain the same textual
 * transport. Their precision argument must match the physical declaration. `TIMESTAMP`
 * is intentionally absent: its session-time-zone conversion is not a local civil
 * date-time mapping.
 */
object MySqlCompatibleKotlinxDateTimeMappings {
    val localDate: ColumnMapping<LocalDate, String> = columnMapping(
        fromStorage = LocalDate::parse,
        toStorage = LocalDate::toString,
    )

    fun localTime(fractionalSecondsPrecision: Int): ColumnMapping<LocalTime, String> = columnMapping(
        fromStorage = LocalTime::parse,
        toStorage = { value ->
            value.toString().requireFractionalSecondsPrecision(fractionalSecondsPrecision, "TIME")
        },
    )

    fun localDateTime(fractionalSecondsPrecision: Int): ColumnMapping<LocalDateTime, String> = columnMapping(
        fromStorage = { LocalDateTime.parse(it.replaceFirst(' ', 'T')) },
        toStorage = { value ->
            value.toString()
                .requireFractionalSecondsPrecision(fractionalSecondsPrecision, "DATETIME")
                .replaceFirst('T', ' ')
        },
    )
}

/** Native MySQL local date/time and lossless offset/zone mappings for kotlinx-datetime types. */
object MySqlKotlinxDateTime : MySqlExtension {
    override val typeMappings: List<MySqlTypeMapping<*>> = listOf(
        MySqlTypeMapping(KotlinxLocalDateColumnType, MySqlType.DATE),
        MySqlTypeMapping(KotlinxLocalTimeColumnType, MySqlType.TIME),
        MySqlTypeMapping(KotlinxLocalDateTimeColumnType, MySqlType.DATETIME),
        MySqlTypeMapping(KotlinxUtcOffsetColumnType, MySqlType.TEXT),
        MySqlTypeMapping(KotlinxTimeZoneColumnType, MySqlType.TEXT),
    )

    override val typeCodecs: List<SqlTypeCodec<*, *>> = initializeTimeZoneSupport().let {
        listOf(
            MySqlCompatibleKotlinxDateTimeMappings.localDate.textCodec(KotlinxLocalDateColumnType),
            MySqlCompatibleKotlinxDateTimeMappings.localTime(MICROSECOND_DIGITS).textCodec(KotlinxLocalTimeColumnType),
            MySqlCompatibleKotlinxDateTimeMappings.localDateTime(MICROSECOND_DIGITS)
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
