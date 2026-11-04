package dev.quwac.kagiroi.sqlserver

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

/** A value and an explicit mssql parameter type before it crosses the JS host boundary. */
sealed interface SqlServerBinding {
    /** Represents null. */
    data object Null : SqlServerBinding

    /** Represents text.
     *
     * @property value Value associated with value.
     */
    data class Text(val value: String) : SqlServerBinding

    /** Represents boolean value.
     *
     * @property value Value associated with value.
     */
    data class BooleanValue(val value: Boolean) : SqlServerBinding

    /** Represents tiny integer.
     *
     * @property decimal Value associated with decimal.
     */
    data class TinyInteger(val decimal: String) : SqlServerBinding

    /** Represents small integer.
     *
     * @property decimal Value associated with decimal.
     */
    data class SmallInteger(val decimal: String) : SqlServerBinding

    /** Represents integer.
     *
     * @property decimal Value associated with decimal.
     */
    data class Integer(val decimal: String) : SqlServerBinding

    /** Represents big integer.
     *
     * @property decimal Value associated with decimal.
     */
    data class BigInteger(val decimal: String) : SqlServerBinding

    /** Represents real.
     *
     * @property value Value associated with value.
     */
    data class Real(val value: Double) : SqlServerBinding

    /** Represents float value.
     *
     * @property value Value associated with value.
     */
    data class FloatValue(val value: Double) : SqlServerBinding

    /** Represents bytes.
     *
     * @property value Value associated with value.
     */
    data class Bytes(val value: ByteArray) : SqlServerBinding

    /** Represents uuid value.
     *
     * @property value Value associated with value.
     */
    data class UuidValue(val value: Uuid) : SqlServerBinding
}

/** Host values normalized before Kotlin type conversion; exact numerics remain decimal text. */
sealed interface SqlServerScalar {
    /** Represents text.
     *
     * @property value Value associated with value.
     */
    data class Text(val value: String) : SqlServerScalar

    /** Represents boolean value.
     *
     * @property value Value associated with value.
     */
    data class BooleanValue(val value: Boolean) : SqlServerScalar

    /** Represents integer.
     *
     * @property decimal Value associated with decimal.
     */
    data class Integer(val decimal: String) : SqlServerScalar

    /** Represents real.
     *
     * @property value Value associated with value.
     */
    data class Real(val value: Double) : SqlServerScalar

    /** Represents bytes.
     *
     * @property value Value associated with value.
     */
    data class Bytes(val value: ByteArray) : SqlServerScalar
}

/** A scalar value returned by SQL Server.
 *
 * @property value decoded value, or `null` for SQL `NULL`.
 */
data class SqlServerResultValue(val value: SqlServerScalar?)

/** SQL Server conversion that keeps mssql and JavaScript values outside the public API. */
class SqlServerValueCodec(private val codecs: SqlTypeCodecRegistry) {
    /** Provides the binding result. */
    fun binding(value: Any?, expression: SqlExpression<*>? = null): SqlServerBinding {
        if (value == null) return SqlServerBinding.Null
        val columnType = expression?.storageType
        val encoded = columnType?.let { codecs.encode(it, value) }
        return bindScalar(encoded?.value ?: value, encoded?.storageType ?: columnType)
    }

    /** Decodes the decode result. */
    @Suppress("CyclomaticComplexMethod", "ThrowsCount", "TooGenericExceptionCaught")
    fun decode(column: SqlExpression<*>, result: SqlServerResultValue): Any? {
        val scalar = result.value
        if (scalar == null) {
            if (column.isNullable) return null
            throw invalid(column, null, column.storageType.name)
        }
        val physicalType = codecs.storageType(column.storageType) ?: column.storageType
        val converted = try {
            when (physicalType) {
                StringColumnType -> scalar.textOrNull()

                BooleanColumnType -> (scalar as? SqlServerScalar.BooleanValue)?.value

                ByteColumnType -> scalar.signedLongOrNull()?.takeIf { it in Byte.MIN_VALUE..Byte.MAX_VALUE }?.toByte()

                ShortColumnType -> scalar.signedLongOrNull()?.takeIf {
                    it in Short.MIN_VALUE..Short.MAX_VALUE
                }?.toShort()

                IntColumnType -> scalar.signedLongOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

                LongColumnType -> scalar.signedLongOrNull()

                UByteColumnType -> scalar.unsignedLongOrNull()?.takeIf { it <= UByte.MAX_VALUE.toULong() }?.toUByte()

                UShortColumnType -> scalar.unsignedLongOrNull()?.takeIf { it <= UShort.MAX_VALUE.toULong() }?.toUShort()

                UIntColumnType -> scalar.unsignedLongOrNull()?.takeIf { it <= UInt.MAX_VALUE.toULong() }?.toUInt()

                ULongColumnType -> scalar.unsignedLongOrNull()

                FloatColumnType -> scalar.decimalOrNull()?.toDoubleOrNull()?.toFloat()

                DoubleColumnType -> scalar.decimalOrNull()?.toDoubleOrNull()

                ByteArrayColumnType -> (scalar as? SqlServerScalar.Bytes)?.value?.copyOf()

                UuidColumnType -> scalar.textOrNull()?.let(Uuid::parse)

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
    fun rawValue(result: SqlServerResultValue): Any? = when (val scalar = result.value) {
        null -> null
        is SqlServerScalar.BooleanValue -> scalar.value
        is SqlServerScalar.Bytes -> scalar.value.copyOf()
        is SqlServerScalar.Integer -> scalar.decimal.toLongOrNull() ?: scalar.decimal
        is SqlServerScalar.Real -> scalar.value
        is SqlServerScalar.Text -> scalar.value
    }

    private fun bindScalar(value: Any, type: ColumnType<*>?): SqlServerBinding = when (type) {
        StringColumnType -> SqlServerBinding.Text(value as String)
        BooleanColumnType -> SqlServerBinding.BooleanValue(value as Boolean)
        ByteColumnType, ShortColumnType -> SqlServerBinding.SmallInteger(value.toString())
        UByteColumnType -> SqlServerBinding.TinyInteger(value.toString())
        IntColumnType, UShortColumnType -> SqlServerBinding.Integer(value.toString())
        LongColumnType, UIntColumnType -> SqlServerBinding.BigInteger(value.toString())
        ULongColumnType -> SqlServerBinding.Text(value.toString())
        FloatColumnType -> SqlServerBinding.Real((value as Float).toDouble())
        DoubleColumnType -> SqlServerBinding.FloatValue(value as Double)
        ByteArrayColumnType -> SqlServerBinding.Bytes((value as ByteArray).copyOf())
        UuidColumnType -> SqlServerBinding.UuidValue(value as Uuid)
        null -> inferRawBinding(value)
        else -> throw IllegalArgumentException("Unsupported SQL Server column type: ${type.name}")
    }
}

private fun inferRawBinding(value: Any): SqlServerBinding = when (value) {
    is String -> SqlServerBinding.Text(value)
    is Boolean -> SqlServerBinding.BooleanValue(value)
    is Byte, is Short -> SqlServerBinding.SmallInteger(value.toString())
    is UByte -> SqlServerBinding.TinyInteger(value.toString())
    is Int, is UShort -> SqlServerBinding.Integer(value.toString())
    is Long, is UInt -> SqlServerBinding.BigInteger(value.toString())
    is ULong -> SqlServerBinding.Text(value.toString())
    is Float -> SqlServerBinding.Real(value.toDouble())
    is Double -> SqlServerBinding.FloatValue(value)
    is ByteArray -> SqlServerBinding.Bytes(value.copyOf())
    is Uuid -> SqlServerBinding.UuidValue(value)
    else -> throw IllegalArgumentException("Unsupported SQL Server parameter type")
}

private fun SqlServerScalar.textOrNull(): String? = when (this) {
    is SqlServerScalar.Text -> value
    else -> null
}

private fun SqlServerScalar.decimalOrNull(): String? = when (this) {
    is SqlServerScalar.Integer -> decimal
    is SqlServerScalar.Real -> value.toString()
    is SqlServerScalar.Text -> value
    else -> null
}

private fun SqlServerScalar.signedLongOrNull(): Long? = decimalOrNull()?.toLongOrNull()

private fun SqlServerScalar.unsignedLongOrNull(): ULong? = decimalOrNull()?.toULongOrNull()

private fun invalid(
    column: SqlExpression<*>,
    value: Any?,
    expected: String,
    cause: Throwable? = null,
): InvalidValueConversion = InvalidValueConversion(column.toString(), value, expected, cause)
