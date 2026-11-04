@file:Suppress("Indentation")

package dev.quwac.kagiroi.oracle

import dev.quwac.kagiroi.schema.BooleanColumnType
import dev.quwac.kagiroi.schema.ByteArrayColumnType
import dev.quwac.kagiroi.schema.ByteColumnType
import dev.quwac.kagiroi.schema.ColumnType
import dev.quwac.kagiroi.schema.DoubleColumnType
import dev.quwac.kagiroi.schema.FloatColumnType
import dev.quwac.kagiroi.schema.IntColumnType
import dev.quwac.kagiroi.schema.LongColumnType
import dev.quwac.kagiroi.schema.ShortColumnType
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.StringColumnType
import dev.quwac.kagiroi.schema.UByteColumnType
import dev.quwac.kagiroi.schema.UIntColumnType
import dev.quwac.kagiroi.schema.ULongColumnType
import dev.quwac.kagiroi.schema.UShortColumnType
import dev.quwac.kagiroi.schema.UuidColumnType
import dev.quwac.kagiroi.sql.InvalidValueConversion
import dev.quwac.kagiroi.sql.SqlTypeCodecRegistry
import kotlin.uuid.Uuid

/** The exact scalar boundary between Pure Kotlin and the node-oracledb host adapter. */
sealed interface OracleBinding {
    /** Represents SQL `NULL`. */
    data object Null : OracleBinding

    /** Character value and optional Oracle type information.
     *
     * @property value text to bind.
     * @property type Oracle type selected for the bind, when known.
     */
    data class Text(val value: String, val type: OracleType? = null) : OracleBinding

    /** Decimal number encoded as text to preserve precision.
     *
     * @property value decimal number text to bind.
     */
    data class Decimal(val value: String) : OracleBinding

    /** Boolean value to bind.
     *
     * @property value boolean value.
     */
    data class BooleanValue(val value: Boolean) : OracleBinding

    /** Floating-point number to bind.
     *
     * @property value number value.
     */
    data class Floating(val value: Double) : OracleBinding

    /** Binary value and its Oracle storage type.
     *
     * @property value bytes to bind.
     * @property type Oracle type used to store the bytes.
     */
    data class Bytes(val value: ByteArray, val type: OracleType = OracleType.RAW) : OracleBinding

    /** Temporal value encoded as ISO-8601 text.
     *
     * @property iso8601 temporal value in ISO-8601 form.
     * @property type Oracle temporal type used for the bind.
     */
    data class Temporal(val iso8601: String, val type: OracleType) : OracleBinding
}

/** Host values normalized before Kotlin conversion. Oracle NUMBER is always decimal text. */
sealed interface OracleScalar {
    /** Represents text.
     *
     * @property value Value associated with value.
     */
    data class Text(val value: String) : OracleScalar

    /** Represents decimal.
     *
     * @property value Value associated with value.
     */
    data class Decimal(val value: String) : OracleScalar

    /** Represents boolean value.
     *
     * @property value Value associated with value.
     */
    data class BooleanValue(val value: Boolean) : OracleScalar

    /** Represents floating.
     *
     * @property value Value associated with value.
     */
    data class Floating(val value: Double) : OracleScalar

    /** Represents bytes.
     *
     * @property value Value associated with value.
     */
    data class Bytes(val value: ByteArray) : OracleScalar
}

/** A scalar value returned from Oracle.
 *
 * @property value decoded value, or `null` for SQL `NULL`.
 */
data class OracleResultValue(val value: OracleScalar?)

/** Oracle's character empty-string behavior cannot represent a Kotlin empty String. */
class OracleEmptyStringUnsupported(context: String) :
    IllegalArgumentException(
        "Oracle treats an empty character value as NULL; $context cannot bind an empty String",
    )

/** Lossless Oracle scalar conversion. No Oracle NUMBER is accepted from JavaScript Number. */
class OracleValueCodec(private val codecs: SqlTypeCodecRegistry, private val types: OracleTypeRegistry) {
    /** Provides the binding result. */
    fun binding(value: Any?, expression: SqlExpression<*>? = null): OracleBinding {
        if (value == null) return OracleBinding.Null
        val logicalType = expression?.storageType
        val encoded = logicalType?.let { codecs.encode(it, value) }
        val targetType = logicalType?.let(types::typeOf)
        return bindScalar(encoded?.value ?: value, encoded?.storageType ?: logicalType, targetType)
    }

    /** Decodes the decode result. */
    @Suppress("CyclomaticComplexMethod", "ThrowsCount", "TooGenericExceptionCaught")
fun decode(column: SqlExpression<*>, result: OracleResultValue): Any? {
        val scalar = result.value
        if (scalar == null) {
            if (column.isNullable) return null
            throw invalid(column, null, column.storageType.name)
        }
        val physicalType = codecs.storageType(column.storageType) ?: column.storageType
        val converted = try {
            when (physicalType) {
                StringColumnType -> if (types.typeOf(column.storageType) == OracleType.NUMBER) {
                    scalar.decimalOrNull()
                } else {
                    scalar.textOrNull()
                }

                BooleanColumnType -> scalar.booleanOrNull()

                ByteColumnType -> scalar.decimalOrNull()?.toLongOrNull()?.takeIf {
                    it in Byte.MIN_VALUE..Byte.MAX_VALUE
                }?.toByte()

                ShortColumnType -> scalar.decimalOrNull()?.toLongOrNull()?.takeIf {
                    it in
                        Short.MIN_VALUE..Short.MAX_VALUE
                }?.toShort()

                IntColumnType -> scalar.decimalOrNull()?.toLongOrNull()?.takeIf {
                    it in Int.MIN_VALUE..Int.MAX_VALUE
                }?.toInt()

                LongColumnType -> scalar.decimalOrNull()?.toLongOrNull()

                UByteColumnType -> scalar.decimalOrNull()?.toULongOrNull()?.takeIf {
                    it <= UByte.MAX_VALUE.toULong()
                }?.toUByte()

                UShortColumnType -> scalar.decimalOrNull()?.toULongOrNull()?.takeIf {
                    it <= UShort.MAX_VALUE.toULong()
                }?.toUShort()

                UIntColumnType -> scalar.decimalOrNull()?.toULongOrNull()?.takeIf {
                    it <= UInt.MAX_VALUE.toULong()
                }?.toUInt()

                ULongColumnType -> scalar.decimalOrNull()?.toULongOrNull()

                FloatColumnType -> scalar.decimalOrNull()?.toDoubleOrNull()?.toFloat()

                DoubleColumnType -> scalar.decimalOrNull()?.toDoubleOrNull()

                ByteArrayColumnType -> (scalar as? OracleScalar.Bytes)?.value?.copyOf()

                UuidColumnType -> (scalar as? OracleScalar.Bytes)?.value?.toOracleUuid()

                else -> null
            }
        } catch (error: Throwable) {
            throw invalid(column, scalar, column.storageType.name, error)
        } ?: throw invalid(column, scalar, column.storageType.name)
        return try {
            codecs.decode(column.storageType, converted)?.value ?: converted
        } catch (error: Throwable) {
            throw invalid(column, scalar, column.storageType.name, error)
        }
    }

    /** Provides the raw value result. */
    fun rawValue(result: OracleResultValue): Any? = when (val scalar = result.value) {
        null -> null
        is OracleScalar.BooleanValue -> scalar.value
        is OracleScalar.Bytes -> scalar.value.copyOf()
        is OracleScalar.Decimal -> scalar.value.toLongOrNull() ?: scalar.value
        is OracleScalar.Floating -> scalar.value
        is OracleScalar.Text -> scalar.value
    }

    private fun bindScalar(value: Any, storageType: ColumnType<*>?, targetType: OracleType?): OracleBinding = when {
        targetType == OracleType.NUMBER || storageType in numericTypes -> OracleBinding.Decimal(value.toString())

        storageType === StringColumnType && targetType?.isTemporal() == true ->
            OracleBinding.Temporal(value as String, requireNotNull(targetType))

        storageType === StringColumnType -> textBinding(value as String, targetType)

        storageType === BooleanColumnType -> OracleBinding.BooleanValue(value as Boolean)

        storageType === FloatColumnType || storageType === DoubleColumnType -> OracleBinding.Floating(
            (value as Number).toDouble(),
        )

        storageType === ByteArrayColumnType -> OracleBinding.Bytes(
            (value as ByteArray).copyOf(),
            targetType ?: OracleType.RAW,
        )

        storageType === UuidColumnType -> OracleBinding.Bytes((value as Uuid).toOracleRaw(), OracleType.RAW_UUID)

        storageType == null -> inferRawBinding(value)

        else -> throw IllegalArgumentException("Unsupported Oracle column type: ${storageType.name}")
    }

    private fun textBinding(value: String, targetType: OracleType?): OracleBinding {
        if (value.isEmpty()) throw OracleEmptyStringUnsupported("a character parameter")
        return OracleBinding.Text(value, targetType)
    }
}

private fun inferRawBinding(value: Any): OracleBinding = when (value) {
    is String -> if (value.isEmpty()) {
        throw OracleEmptyStringUnsupported(
        "a raw character parameter",
    )
    } else {
        OracleBinding.Text(value)
    }

    is Boolean -> OracleBinding.BooleanValue(value)

    is Byte, is Short, is Int, is Long, is UByte, is UShort, is UInt, is ULong -> OracleBinding.Decimal(
        value.toString(),
    )

    is Float -> OracleBinding.Floating(value.toDouble())

    is Double -> OracleBinding.Floating(value)

    is ByteArray -> OracleBinding.Bytes(value.copyOf())

    is Uuid -> OracleBinding.Bytes(value.toOracleRaw(), OracleType.RAW_UUID)

    else -> throw IllegalArgumentException("Unsupported Oracle raw parameter type")
}

private fun OracleScalar.textOrNull(): String? = when (this) {
    is OracleScalar.Text -> value
    else -> null
}

private fun OracleScalar.booleanOrNull(): Boolean? = when (this) {
    is OracleScalar.BooleanValue -> value

    is OracleScalar.Text -> when (value.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }

    else -> null
}

private fun OracleScalar.decimalOrNull(): String? = when (this) {
    is OracleScalar.Decimal -> value
    is OracleScalar.Floating -> value.toString()
    is OracleScalar.Text -> value
    else -> null
}

private fun OracleType?.isTemporal(): Boolean = this in setOf(
    OracleType.DATE,
    OracleType.TIMESTAMP,
    OracleType.TIMESTAMP_WITH_TIME_ZONE,
    OracleType.TIMESTAMP_WITH_LOCAL_TIME_ZONE,
)

private fun invalid(
    column: SqlExpression<*>,
    value: Any?,
    expected: String,
    cause: Throwable? = null,
): InvalidValueConversion = InvalidValueConversion(column.toString(), value, expected, cause)

private val numericTypes: Set<ColumnType<*>> = setOf(
    ByteColumnType,
    ShortColumnType,
    IntColumnType,
    LongColumnType,
    UByteColumnType,
    UShortColumnType,
    UIntColumnType,
    ULongColumnType,
)
