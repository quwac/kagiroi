package dev.quwac.kagiroi.mysql

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.sql.SqlTypeCodecRegistry
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

class MySqlValuesTest {
    private val codec = MySqlValueCodec(SqlTypeCodecRegistry(MySqlStandardTypes.typeCodecs))

    @Test
    fun bindsBooleanUuidAndBytesUsingMySqlRepresentations() {
        val id = Uuid.parse("123e4567-e89b-12d3-a456-426614174000")

        assertEquals(MySqlBinding.BooleanValue(true), codec.binding(true, Values.active))
        assertEquals(MySqlBinding.Text(id.toString()), codec.binding(id, Values.id))
        val bytes = codec.binding(byteArrayOf(0, 1, -1), Values.payload)
        assertTrue(bytes is MySqlBinding.Bytes)
        assertContentEquals(byteArrayOf(0, 1, -1), bytes.value)
    }

    @Test
    fun mapsSignedAndUnsignedCoreTypes() {
        val registry = MySqlTypeRegistry()

        assertEquals(MySqlType.TINYINT, registry.typeOf(Values.byteValue.storageType))
        assertEquals(MySqlType.TINYINT_UNSIGNED, registry.typeOf(Values.ubyteValue.storageType))
        assertEquals(MySqlType.SMALLINT, registry.typeOf(Values.shortValue.storageType))
        assertEquals(MySqlType.SMALLINT_UNSIGNED, registry.typeOf(Values.ushortValue.storageType))
        assertEquals(MySqlType.INTEGER, registry.typeOf(Values.intValue.storageType))
        assertEquals(MySqlType.INTEGER_UNSIGNED, registry.typeOf(Values.uintValue.storageType))
        assertEquals(MySqlType.BIGINT, registry.typeOf(Values.longValue.storageType))
        assertEquals(MySqlType.BIGINT_UNSIGNED, registry.typeOf(Values.ulongValue.storageType))
        assertEquals(MySqlType.TEXT, registry.typeOf(Values.durationValue.storageType))
    }

    @Test
    fun mapsDurationAsIsoTextWithoutTimeRangeOrPrecisionLoss() {
        val duration = Duration.parseIsoString("-PT900H0.000000001S")

        assertEquals(MySqlBinding.Text(duration.toIsoString()), codec.binding(duration, Values.durationValue))
        assertEquals(
            duration,
            codec.decode(
                Values.durationValue,
                MySqlResultValue(MySqlType.TEXT.protocolType, MySqlScalar.Text(duration.toIsoString())),
            ),
        )
    }

    @Test
    fun decodesTypedValuesAndBinaryUuid() {
        val id = Uuid.parse("123e4567-e89b-12d3-a456-426614174000")
        assertTrue(
            codec.decode(
                Values.active,
                MySqlResultValue(MySqlType.TINYINT.protocolType, MySqlScalar.Number("1")),
            ) as Boolean,
        )
        assertEquals(
            id,
            codec.decode(
                Values.id,
                MySqlResultValue(MySqlType.CHAR_UUID.protocolType, MySqlScalar.Text(id.toString())),
            ),
        )

        val binary = codec.binding(id, Values.binaryId)
        assertTrue(binary is MySqlBinding.Bytes)
        assertEquals(
            id,
            codec.decode(
                Values.binaryId,
                MySqlResultValue(MySqlType.BINARY_UUID.protocolType, MySqlScalar.Bytes(binary.value)),
            ),
        )
    }

    @Test
    fun rejectsInstantsThatWouldLoseNanosecondPrecision() {
        assertFailsWith<IllegalArgumentException> {
            codec.binding(Instant.parse("2026-10-18T01:02:03.123456789Z"), Values.instantValue)
        }
    }

    @Test
    fun bindsAndDecodesDirectMySqlPhysicalTypesThroughTheirStorageCodec() {
        val bits = byteArrayOf(0x2a)
        val id = Uuid.parse("123e4567-e89b-12d3-a456-426614174000")
        val physicalCodec = MySqlValueCodec(SqlTypeCodecRegistry(emptyList()))

        assertEquals(
            MySqlBinding.Text("{\"active\":true}"),
            physicalCodec.binding("{\"active\":true}", PhysicalValues.metadata),
        )
        assertEquals(MySqlBinding.Text(id.toString()), physicalCodec.binding(id, PhysicalValues.charId))
        val boundBinaryId = physicalCodec.binding(id, PhysicalValues.binaryId)
        assertTrue(boundBinaryId is MySqlBinding.Bytes)
        val boundBits = physicalCodec.binding(bits, PhysicalValues.flags)
        assertTrue(boundBits is MySqlBinding.Bytes)
        assertContentEquals(bits, boundBits.value)
        assertEquals(
            "{\"active\":true}",
            physicalCodec.decode(
                PhysicalValues.metadata,
                MySqlResultValue(MySqlType.TEXT.protocolType, MySqlScalar.Text("{\"active\":true}")),
            ),
        )
        assertContentEquals(
            bits,
            physicalCodec.decode(
                PhysicalValues.flags,
                MySqlResultValue(MySqlType.BLOB.protocolType, MySqlScalar.Bytes(bits)),
            ) as ByteArray,
        )
        assertEquals(
            id,
            physicalCodec.decode(
                PhysicalValues.charId,
                MySqlResultValue(MySqlType.CHAR_UUID.protocolType, MySqlScalar.Text(id.toString())),
            ),
        )
        assertEquals(
            id,
            physicalCodec.decode(
                PhysicalValues.binaryId,
                MySqlResultValue(MySqlType.BINARY_UUID.protocolType, MySqlScalar.Bytes(boundBinaryId.value)),
            ),
        )
    }
}

private object Values : Table("values") {
    val id = uuid("id")
    val binaryId = binaryUuid("binary_id")
    val active = boolean("active")
    val payload = bytes("payload")
    val byteValue = byte("byte_value")
    val ubyteValue = ubyte("ubyte_value")
    val shortValue = short("short_value")
    val ushortValue = ushort("ushort_value")
    val intValue = int("int_value")
    val uintValue = uint("uint_value")
    val longValue = long("long_value")
    val ulongValue = ulong("ulong_value")
    val instantValue = instant("instant_value")
    val durationValue = duration("duration_value")
    val nickname = string("nickname").nullable()
}

private object PhysicalValues : Table("physical_values") {
    val metadata = column("metadata", MySqlTypes.json)
    val flags = column("flags", MySqlTypes.bit(8))
    val charId = column("char_id", MySqlTypes.charUuid)
    val binaryId = column("binary_id", MySqlTypes.binaryUuid)
}
