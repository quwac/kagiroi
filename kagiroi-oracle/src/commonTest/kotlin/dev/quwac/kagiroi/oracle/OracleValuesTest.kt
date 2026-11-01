@file:Suppress("MaximumLineLength")

package dev.quwac.kagiroi.oracle

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.sql.InvalidValueConversion
import dev.quwac.kagiroi.sql.SqlTypeCodecRegistry
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class OracleValuesTest {
    private val values = OracleValueCodec(SqlTypeCodecRegistry(OracleStandardTypes.typeCodecs), OracleTypeRegistry())

    @Test
    fun rejectsEmptyStringsInsteadOfPretendingTheyRoundTrip() {
        val error = assertFailsWith<OracleEmptyStringUnsupported> {
            values.binding("", OracleValuesTable.name)
        }
        assertEquals(
            "Oracle treats an empty character value as NULL; a character parameter cannot bind an empty String",
            error.message,
        )
        assertEquals(OracleBinding.Null, values.binding(null, OracleValuesTable.name))
    }

    @Test
    fun transportsIntegralNumbersAsTextWithoutJavaScriptNumberPrecisionLoss() {
        assertEquals(
            OracleBinding.Decimal("9223372036854775807"),
            values.binding(Long.MAX_VALUE, OracleValuesTable.longValue),
        )
        assertEquals(
            OracleBinding.Decimal("18446744073709551615"),
            values.binding(ULong.MAX_VALUE, OracleValuesTable.unsignedValue),
        )
        assertEquals(
            Long.MAX_VALUE,
            values.decode(OracleValuesTable.longValue, OracleResultValue(OracleScalar.Decimal("9223372036854775807"))),
        )
        assertEquals(
            ULong.MAX_VALUE,
            values.decode(
                OracleValuesTable.unsignedValue,
                OracleResultValue(OracleScalar.Decimal("18446744073709551615")),
            ),
        )
        assertFailsWith<InvalidValueConversion> {
            values.decode(OracleValuesTable.longValue, OracleResultValue(OracleScalar.Decimal("9223372036854775808")))
        }
    }

    @Test
    fun mapsUuidToRawAndNullableValuesToNull() {
        val uuid = Uuid.parse("123e4567-e89b-12d3-a456-426614174000")
        val binding = values.binding(uuid, OracleValuesTable.identifier) as OracleBinding.Bytes
        assertEquals(OracleType.RAW_UUID, binding.type)
        assertContentEquals(
            byteArrayOf(0x12, 0x3e, 0x45, 0x67, -0x18, -0x65, 0x12, -0x2d, -0x5c, 0x56, 0x42, 0x66, 0x14, 0x17, 0x40, 0x00),
            binding.value,
        )
        assertEquals(
            uuid,
            values.decode(OracleValuesTable.identifier, OracleResultValue(OracleScalar.Bytes(binding.value))),
        )
        assertNull(values.decode(OracleValuesTable.optionalName, OracleResultValue(null)))
    }

    @Test
    fun usesExplicitTextTransportForInstantAndDuration() {
        val instant = Instant.parse("2040-01-02T03:04:05.123456789Z")
        assertEquals(
            OracleBinding.Temporal(instant.toString(), OracleType.TIMESTAMP_WITH_TIME_ZONE),
            values.binding(instant, OracleValuesTable.createdAt),
        )
        assertEquals(
            instant,
            values.decode(OracleValuesTable.createdAt, OracleResultValue(OracleScalar.Text(instant.toString()))),
        )
        assertEquals(
            OracleBinding.Text("PT42S", OracleType.VARCHAR2),
            values.binding(42.seconds, OracleValuesTable.timeout),
        )
    }
}

private object OracleValuesTable : Table("oracle_values") {
    val name = string("name")
    val optionalName = string("optional_name").nullable()
    val longValue = long("long_value")
    val unsignedValue = ulong("unsigned_value")
    val identifier = uuid("identifier")
    val createdAt = instant("created_at")
    val timeout = duration("timeout")
}
