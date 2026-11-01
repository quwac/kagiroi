package dev.quwac.kagiroi.sqlite

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

/** A scalar value at the shared SQLite storage boundary. */
sealed interface SqlitePhysicalValue {
    data object Null : SqlitePhysicalValue

    data class Integer(val decimal: String) : SqlitePhysicalValue

    data class Real(val value: Double) : SqlitePhysicalValue

    data class Text(val value: String) : SqlitePhysicalValue

    data class Blob(val value: ByteArray) : SqlitePhysicalValue
}

/** Shared SQLite scalar conversion used by local and remote execution drivers. */
class SqliteValueCodec(private val codecs: SqlTypeCodecRegistry) {
    fun binding(value: Any?, expression: SqlExpression<*>? = null): SqlitePhysicalValue {
        if (value == null) return SqlitePhysicalValue.Null
        val columnType = expression?.storageType
        val encoded = columnType?.let { codecs.encode(it, value) }
        return bindScalar(encoded?.value ?: value, encoded?.storageType ?: columnType)
    }

    // This is the single exhaustive physical-value conversion boundary for every built-in column type.
    @Suppress("CyclomaticComplexMethod", "ThrowsCount", "TooGenericExceptionCaught")
    fun decode(column: SqlExpression<*>, value: SqlitePhysicalValue): Any? {
        if (value === SqlitePhysicalValue.Null) {
            if (column.isNullable) return null
            throw invalid(column, null, column.storageType.name)
        }

        val physicalType = codecs.storageType(column.storageType) ?: column.storageType
        val converted = try {
            when (physicalType) {
                StringColumnType -> (value as? SqlitePhysicalValue.Text)?.value

                BooleanColumnType -> value.integer(column).let {
                    when (it) {
                        0L -> false
                        1L -> true
                        else -> null
                    }
                }

                ByteColumnType -> value.integer(column).takeIf { it in Byte.MIN_VALUE..Byte.MAX_VALUE }?.toByte()

                ShortColumnType -> value.integer(column).takeIf { it in Short.MIN_VALUE..Short.MAX_VALUE }?.toShort()

                IntColumnType -> value.integer(column).takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

                LongColumnType -> value.integer(column)

                UByteColumnType -> value.integer(column).takeIf { it in 0..UByte.MAX_VALUE.toLong() }?.toUByte()

                UShortColumnType -> value.integer(column).takeIf { it in 0..UShort.MAX_VALUE.toLong() }?.toUShort()

                UIntColumnType -> value.integer(column).takeIf { it in 0..UInt.MAX_VALUE.toLong() }?.toUInt()

                ULongColumnType -> value.integer(column).takeIf { it >= 0 }?.toULong()

                FloatColumnType -> value.real(column).toFloat()

                DoubleColumnType -> value.real(column)

                ByteArrayColumnType -> (value as? SqlitePhysicalValue.Blob)?.value?.copyOf()

                UuidColumnType -> (value as? SqlitePhysicalValue.Text)?.value?.let(Uuid::parse)

                else -> null
            }
        } catch (error: Throwable) {
            throw invalid(column, value, column.storageType.name, error)
        } ?: throw invalid(column, value, column.storageType.name)

        return try {
            codecs.decode(column.storageType, converted)?.value ?: converted
        } catch (error: Throwable) {
            throw invalid(column, value, column.storageType.name, error)
        }
    }

    fun rawValue(value: SqlitePhysicalValue): Any? = when (value) {
        SqlitePhysicalValue.Null -> null
        is SqlitePhysicalValue.Integer -> value.decimal.toLong()
        is SqlitePhysicalValue.Real -> value.value
        is SqlitePhysicalValue.Text -> value.value
        is SqlitePhysicalValue.Blob -> value.value.copyOf()
    }

    private fun bindScalar(value: Any, type: ColumnType<*>?): SqlitePhysicalValue = when (type) {
        StringColumnType -> SqlitePhysicalValue.Text(value as String)

        BooleanColumnType -> SqlitePhysicalValue.Integer(if (value as Boolean) "1" else "0")

        ByteColumnType,
        ShortColumnType,
        IntColumnType,
        LongColumnType,
        UByteColumnType,
        UShortColumnType,
        UIntColumnType,
        ULongColumnType,
        -> SqlitePhysicalValue.Integer(integerDecimal(value))

        FloatColumnType -> SqlitePhysicalValue.Real((value as Float).toDouble())

        DoubleColumnType -> SqlitePhysicalValue.Real(value as Double)

        ByteArrayColumnType -> SqlitePhysicalValue.Blob((value as ByteArray).copyOf())

        UuidColumnType -> SqlitePhysicalValue.Text((value as Uuid).toString())

        null -> inferRawBinding(value)

        else -> throw IllegalArgumentException("Unsupported SQLite column type: ${type.name}")
    }
}

private fun inferRawBinding(value: Any): SqlitePhysicalValue = when (value) {
    is String -> SqlitePhysicalValue.Text(value)

    is Boolean -> SqlitePhysicalValue.Integer(if (value) "1" else "0")

    is UByte, is UShort, is UInt, is ULong ->
        SqlitePhysicalValue.Integer(integerDecimal(value))

    is Number -> rawSignedNumberBinding(value)

    is ByteArray -> SqlitePhysicalValue.Blob(value.copyOf())

    is Uuid -> SqlitePhysicalValue.Text(value.toString())

    else -> throw IllegalArgumentException("Unsupported SQLite parameter type")
}

internal fun numericBinding(value: Double): SqlitePhysicalValue {
    val isIntegral = value.isFinite() && value % 1.0 == 0.0
    val fitsLong = value >= LONG_MIN_AS_DOUBLE && value < LONG_MAX_EXCLUSIVE_AS_DOUBLE
    return if (isIntegral && fitsLong) {
        SqlitePhysicalValue.Integer(value.toLong().toString())
    } else {
        SqlitePhysicalValue.Real(value)
    }
}

internal expect fun rawSignedNumberBinding(value: Number): SqlitePhysicalValue

private fun integerDecimal(value: Any): String = when (value) {
    is Byte, is Short, is Int, is Long, is UByte, is UShort, is UInt -> value.toString()

    is ULong -> {
        require(value <= Long.MAX_VALUE.toULong()) { "SQLite INTEGER cannot represent ULong value: $value" }
        value.toString()
    }

    else -> throw IllegalArgumentException("Unsupported SQLite INTEGER value")
}

private fun SqlitePhysicalValue.integer(column: SqlExpression<*>): Long =
    (this as? SqlitePhysicalValue.Integer)?.decimal?.toLongOrNull()
        ?: throw invalid(column, this, column.storageType.name)

private fun SqlitePhysicalValue.real(column: SqlExpression<*>): Double = when (this) {
    is SqlitePhysicalValue.Integer -> decimal.toDouble()
    is SqlitePhysicalValue.Real -> value
    else -> throw invalid(column, this, column.storageType.name)
}

private fun invalid(
    column: SqlExpression<*>,
    value: Any?,
    expected: String,
    cause: Throwable? = null,
): InvalidValueConversion = InvalidValueConversion(column.toString(), value, expected, cause)

private const val LONG_MIN_AS_DOUBLE = -9_223_372_036_854_775_808.0
private const val LONG_MAX_EXCLUSIVE_AS_DOUBLE = 9_223_372_036_854_775_808.0
