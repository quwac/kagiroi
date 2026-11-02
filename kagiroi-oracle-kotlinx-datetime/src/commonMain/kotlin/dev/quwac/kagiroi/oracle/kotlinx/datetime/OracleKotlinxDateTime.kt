package dev.quwac.kagiroi.oracle.kotlinx.datetime

import dev.quwac.kagiroi.kotlinx.datetime.KotlinxLocalDateColumnType
import dev.quwac.kagiroi.kotlinx.datetime.KotlinxLocalDateTimeColumnType
import dev.quwac.kagiroi.oracle.OracleExtension
import dev.quwac.kagiroi.oracle.OracleType
import dev.quwac.kagiroi.oracle.OracleTypeMapping
import dev.quwac.kagiroi.schema.StringColumnType
import dev.quwac.kagiroi.sql.SqlTypeCodec
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/**
 * Explicit Oracle mappings for calendar values and instants.
 *
 * Oracle DATE carries a time of day, so [LocalDate] writes midnight and reads only the
 * date component. Applications that need the time component use [LocalDateTime].
 */
object OracleKotlinxDateTime : OracleExtension {
    override val typeMappings: List<OracleTypeMapping<*>> = listOf(
        OracleTypeMapping(KotlinxLocalDateColumnType, OracleType.DATE),
        OracleTypeMapping(KotlinxLocalDateTimeColumnType, OracleType.TIMESTAMP),
    )

    override val typeCodecs: List<SqlTypeCodec<*, *>> = listOf(
        SqlTypeCodec(
            KotlinxLocalDateColumnType,
            StringColumnType,
            LocalDate::toString,
            { value -> LocalDate.parse(value.substringBefore(' ')) },
        ),
        SqlTypeCodec(
            KotlinxLocalDateTimeColumnType,
            StringColumnType,
            { value -> value.toString().requireNanosecondPrecision("TIMESTAMP(9)") },
            LocalDateTime::parse,
        ),
    )
}

private fun String.requireNanosecondPrecision(typeName: String): String {
    val fraction = substringAfterLast('.', missingDelimiterValue = "").substringBefore('T').substringBefore('Z')
    require(fraction.length <= NANOSECOND_DIGITS) {
        "$typeName cannot represent fractional seconds beyond nanoseconds"
    }
    return this
}

private const val NANOSECOND_DIGITS = 9
