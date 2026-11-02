package dev.quwac.kagiroi.sqlite.kotlinx.datetime

import dev.quwac.kagiroi.kotlinx.datetime.KotlinxLocalDateColumnType
import dev.quwac.kagiroi.kotlinx.datetime.KotlinxLocalDateTimeColumnType
import dev.quwac.kagiroi.kotlinx.datetime.KotlinxLocalTimeColumnType
import dev.quwac.kagiroi.kotlinx.datetime.KotlinxTimeZoneColumnType
import dev.quwac.kagiroi.kotlinx.datetime.KotlinxUtcOffsetColumnType
import dev.quwac.kagiroi.schema.StringColumnType
import dev.quwac.kagiroi.sql.SqlTypeCodec
import dev.quwac.kagiroi.sqlite.SqliteExtension
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset

/** Lossless ISO and identifier TEXT mappings for SQLite. */
object SqliteKotlinxDateTime : SqliteExtension {
    override val typeCodecs: List<SqlTypeCodec<*, *>> = initializeTimeZoneSupport().let {
        listOf(
            SqlTypeCodec(KotlinxLocalDateColumnType, StringColumnType, LocalDate::toString, LocalDate::parse),
            SqlTypeCodec(KotlinxLocalTimeColumnType, StringColumnType, LocalTime::toString, LocalTime::parse),
            SqlTypeCodec(
                KotlinxLocalDateTimeColumnType,
                StringColumnType,
                LocalDateTime::toString,
                LocalDateTime::parse,
            ),
            SqlTypeCodec(KotlinxUtcOffsetColumnType, StringColumnType, UtcOffset::toString, UtcOffset::parse),
            SqlTypeCodec(KotlinxTimeZoneColumnType, StringColumnType, TimeZone::id, TimeZone::of),
        )
    }
}

internal expect fun initializeTimeZoneSupport()
