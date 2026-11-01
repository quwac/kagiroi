package dev.quwac.kagiroi.sqlserver.kotlinx.datetime

import dev.quwac.kagiroi.kotlinx.datetime.KotlinxLocalDateColumnType
import dev.quwac.kagiroi.kotlinx.datetime.KotlinxLocalDateTimeColumnType
import dev.quwac.kagiroi.kotlinx.datetime.KotlinxLocalTimeColumnType
import dev.quwac.kagiroi.kotlinx.datetime.KotlinxTimeZoneColumnType
import dev.quwac.kagiroi.kotlinx.datetime.KotlinxUtcOffsetColumnType
import dev.quwac.kagiroi.kotlinx.datetime.timeZone
import dev.quwac.kagiroi.kotlinx.datetime.utcOffset
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.SqlTypeCodecRegistry
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sqlserver.SqlServerBinding
import dev.quwac.kagiroi.sqlserver.SqlServerDialect
import dev.quwac.kagiroi.sqlserver.SqlServerResultValue
import dev.quwac.kagiroi.sqlserver.SqlServerScalar
import dev.quwac.kagiroi.sqlserver.SqlServerType
import dev.quwac.kagiroi.sqlserver.SqlServerTypeRegistry
import dev.quwac.kagiroi.sqlserver.SqlServerValueCodec
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class SqlServerKotlinxDateTimeTest {
    @Test
    fun mapsLocalAndLosslessTextTypesToSqlServerTypes() {
        val registry = SqlServerTypeRegistry(listOf(SqlServerKotlinxDateTime))

        assertEquals(SqlServerType.DATE, registry.typeOf(KotlinxLocalDateColumnType))
        assertEquals(SqlServerType.TIME, registry.typeOf(KotlinxLocalTimeColumnType))
        assertEquals(SqlServerType.DATETIME2, registry.typeOf(KotlinxLocalDateTimeColumnType))
        assertEquals(SqlServerType.NVARCHAR, registry.typeOf(KotlinxUtcOffsetColumnType))
        assertEquals(SqlServerType.NVARCHAR, registry.typeOf(KotlinxTimeZoneColumnType))
    }

    @Test
    fun rendersOffsetAndTimeZoneAsUnicodeText() {
        assertEquals(
            "CREATE TABLE [values] ([offset] NVARCHAR(MAX) NOT NULL, [time_zone] NVARCHAR(MAX) NOT NULL)",
            SqlServerDialect.configured(listOf(SqlServerKotlinxDateTime)).render(createTable(Values)).sql,
        )
    }

    @Test
    fun roundTripsOffsetAndIanaTimeZoneAsText() {
        val codec = SqlServerValueCodec(SqlTypeCodecRegistry(SqlServerKotlinxDateTime.typeCodecs))
        val offset = UtcOffset.parse("+09:00")
        val timeZone = TimeZone.of("Asia/Tokyo")

        assertEquals(SqlServerBinding.Text("+09:00"), codec.binding(offset, Values.offset))
        assertEquals(SqlServerBinding.Text("Asia/Tokyo"), codec.binding(timeZone, Values.timeZone))
        assertEquals(
            offset,
            codec.decode(Values.offset, SqlServerResultValue(SqlServerScalar.Text("+09:00"))),
        )
        assertEquals(
            timeZone,
            codec.decode(Values.timeZone, SqlServerResultValue(SqlServerScalar.Text("Asia/Tokyo"))),
        )
    }
}

private object Values : Table("values") {
    val offset = utcOffset("offset")
    val timeZone = timeZone("time_zone")
}
