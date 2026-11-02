package dev.quwac.kagiroi.postgres

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

class PostgresValuesTest {
    private val codec = PostgresValueCodec(SqlTypeCodecRegistry(PostgresStandardTypes.typeCodecs))

    @Test
    fun bindsNativeBooleanUuidAndByteaWithoutSQLiteRepresentations() {
        val id = Uuid.parse("123e4567-e89b-12d3-a456-426614174000")

        assertEquals(PostgresBinding.BooleanValue(true), codec.binding(true, Values.active))
        assertEquals(PostgresBinding.UuidValue(id), codec.binding(id, Values.id))
        val bytes = codec.binding(byteArrayOf(0, 1, -1), Values.payload)
        assertTrue(bytes is PostgresBinding.Bytes)
        assertContentEquals(byteArrayOf(0, 1, -1), bytes.value)
    }

    @Test
    fun mapsAndBindsCoreScalarTypes() {
        val registry = PostgresTypeRegistry()

        assertEquals(PostgresType.TEXT, registry.typeOf(Values.name.storageType))
        assertEquals(PostgresType.SMALLINT, registry.typeOf(Values.byteValue.storageType))
        assertEquals(PostgresType.SMALLINT, registry.typeOf(Values.shortValue.storageType))
        assertEquals(PostgresType.INTEGER, registry.typeOf(Values.intValue.storageType))
        assertEquals(PostgresType.BIGINT, registry.typeOf(Values.longValue.storageType))
        assertEquals(PostgresType.REAL, registry.typeOf(Values.floatValue.storageType))
        assertEquals(PostgresType.DOUBLE_PRECISION, registry.typeOf(Values.doubleValue.storageType))
        assertEquals(PostgresType.TEXT, registry.typeOf(Values.durationValue.storageType))
        assertEquals(PostgresBinding.Text("text"), codec.binding("text", Values.name))
        assertEquals(PostgresBinding.Integer("1"), codec.binding(1.toByte(), Values.byteValue))
        assertEquals(PostgresBinding.Integer("2"), codec.binding(2.toShort(), Values.shortValue))
        assertEquals(PostgresBinding.Integer("3"), codec.binding(3, Values.intValue))
        assertEquals(PostgresBinding.Integer("4"), codec.binding(4L, Values.longValue))
        assertEquals(PostgresBinding.Real(5.0), codec.binding(5.0F, Values.floatValue))
        assertEquals(PostgresBinding.Real(6.0), codec.binding(6.0, Values.doubleValue))
        assertEquals(PostgresBinding.Null, codec.binding(null, Values.nickname))
    }

    @Test
    fun mapsDurationAsIsoTextWithoutIntervalSemanticsOrPrecisionLoss() {
        val duration = Duration.parseIsoString("-PT900H0.000000001S")

        assertEquals(PostgresBinding.Text(duration.toIsoString()), codec.binding(duration, Values.durationValue))
        assertEquals(
            duration,
            codec.decode(Values.durationValue, PostgresResultValue(PostgresType.TEXT.objectId, duration.toIsoString())),
        )
    }

    @Test
    fun decodesNativeValuesFromPostgresTextFormat() {
        val id = Uuid.parse("123e4567-e89b-12d3-a456-426614174000")

        assertTrue(codec.decode(Values.active, PostgresResultValue(PostgresType.BOOLEAN.objectId, "t")) as Boolean)
        assertEquals(id, codec.decode(Values.id, PostgresResultValue(PostgresType.UUID.objectId, id.toString())))
        assertContentEquals(
            byteArrayOf(0, 1, -1),
            codec.decode(Values.payload, PostgresResultValue(PostgresType.BYTEA.objectId, "\\x0001ff")) as ByteArray,
        )
    }

    @Test
    fun rejectsInstantsThatWouldLoseNanosecondPrecision() {
        assertFailsWith<IllegalArgumentException> {
            codec.binding(Instant.parse("2026-10-18T01:02:03.123456789Z"), Values.instantValue)
        }
    }

    @Test
    fun bindsAndDecodesDirectPostgresPhysicalTypesThroughTheirStorageCodec() {
        val column = PhysicalValues.address

        assertEquals(PostgresBinding.Text("2001:db8::1"), codec.binding("2001:db8::1", column))
        assertEquals(
            "2001:db8::1",
            codec.decode(column, PostgresResultValue(typeObjectId = 869, text = "2001:db8::1")),
        )
        assertEquals("INET", PostgresTypes.inet.sqlDeclaration)
    }
}

private object Values : Table("values") {
    val id = uuid("id")
    val name = string("name")
    val active = boolean("active")
    val payload = bytes("payload")
    val byteValue = byte("byte_value")
    val shortValue = short("short_value")
    val intValue = int("int_value")
    val longValue = long("long_value")
    val floatValue = float("float_value")
    val doubleValue = double("double_value")
    val instantValue = instant("instant_value")
    val durationValue = duration("duration_value")
    val nickname = string("nickname").nullable()
}

private object PhysicalValues : Table("physical_values") {
    val address = column("address", PostgresTypes.inet)
}
