@file:Suppress("TooManyFunctions")

package dev.quwac.kagiroi.mysql

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

/** A parameter value before the target-specific mysql2 bridge. */
sealed interface MySqlBinding {
    data object Null : MySqlBinding
    data class Text(val value: String) : MySqlBinding
    data class BooleanValue(val value: Boolean) : MySqlBinding
    data class Integer(val decimal: String) : MySqlBinding
    data class Real(val value: Double) : MySqlBinding
    data class Bytes(val value: ByteArray) : MySqlBinding
}

/** A mysql2 field value normalized without losing integer precision. */
sealed interface MySqlScalar {
    data class Text(val value: String) : MySqlScalar
    data class Number(val decimal: String) : MySqlScalar
    data class BooleanValue(val value: Boolean) : MySqlScalar
    data class Bytes(val value: ByteArray) : MySqlScalar
}

data class MySqlResultValue(val protocolType: Int, val value: MySqlScalar?)

/** MySQL scalar conversion shared by MySQL execution adapters. */
class MySqlValueCodec(private val codecs: SqlTypeCodecRegistry) {
    fun binding(value: Any?, expression: SqlExpression<*>? = null): MySqlBinding {
        if (value == null) return MySqlBinding.Null
        val columnType = expression?.storageType
        val encoded = columnType?.let { codecs.encode(it, value) }
        return bindScalar(encoded?.value ?: value, encoded?.storageType ?: columnType)
    }

    @Suppress("CyclomaticComplexMethod", "ThrowsCount", "TooGenericExceptionCaught")
    fun decode(column: SqlExpression<*>, value: MySqlResultValue): Any? {
        val scalar = value.value
        if (scalar == null) {
            if (column.isNullable) return null
            throw invalid(column, null, column.storageType.name)
        }

        val physicalType = codecs.storageType(column.storageType) ?: column.storageType
        val converted = try {
            when (physicalType) {
                StringColumnType -> scalar.textOrNull()
                BooleanColumnType -> scalar.booleanOrNull()
                ByteColumnType -> scalar.byteOrNull()
                ShortColumnType -> scalar.shortOrNull()
                IntColumnType -> scalar.intOrNull()
                LongColumnType -> scalar.signedLongOrNull()
                UByteColumnType -> scalar.ubyteOrNull()
                UShortColumnType -> scalar.ushortOrNull()
                UIntColumnType -> scalar.uintOrNull()
                ULongColumnType -> scalar.unsignedLongOrNull()
                FloatColumnType -> scalar.decimalOrNull()?.toDoubleOrNull()?.toFloat()
                DoubleColumnType -> scalar.decimalOrNull()?.toDoubleOrNull()
                ByteArrayColumnType -> (scalar as? MySqlScalar.Bytes)?.value?.copyOf()
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

    fun rawValue(value: MySqlResultValue): Any? = when (val scalar = value.value) {
        null -> null
        is MySqlScalar.Bytes -> scalar.value.copyOf()
        is MySqlScalar.BooleanValue -> scalar.value
        is MySqlScalar.Number -> rawNumber(value.protocolType, scalar.decimal)
        is MySqlScalar.Text -> rawText(value.protocolType, scalar.value)
    }

    private fun bindScalar(value: Any, type: ColumnType<*>?): MySqlBinding = when (type) {
        StringColumnType -> MySqlBinding.Text(value as String)

        BooleanColumnType -> MySqlBinding.BooleanValue(value as Boolean)

        ByteColumnType,
        ShortColumnType,
        IntColumnType,
        LongColumnType,
        UByteColumnType,
        UShortColumnType,
        UIntColumnType,
        ULongColumnType,
        -> MySqlBinding.Integer(value.toString())

        FloatColumnType -> MySqlBinding.Real((value as Float).toDouble())

        DoubleColumnType -> MySqlBinding.Real(value as Double)

        ByteArrayColumnType -> MySqlBinding.Bytes((value as ByteArray).copyOf())

        UuidColumnType -> MySqlBinding.Text((value as Uuid).toString())

        null -> inferRawBinding(value)

        else -> throw IllegalArgumentException("Unsupported MySQL column type: ${type.name}")
    }
}

private fun inferRawBinding(value: Any): MySqlBinding = when (value) {
    is String -> MySqlBinding.Text(value)

    is Boolean -> MySqlBinding.BooleanValue(value)

    is Byte, is Short, is Int, is Long, is UByte, is UShort, is UInt, is ULong ->
        MySqlBinding.Integer(value.toString())

    is Float -> MySqlBinding.Real(value.toDouble())

    is Double -> MySqlBinding.Real(value)

    is ByteArray -> MySqlBinding.Bytes(value.copyOf())

    is Uuid -> MySqlBinding.Text(value.toString())

    else -> throw IllegalArgumentException("Unsupported MySQL parameter type")
}

private fun MySqlScalar.textOrNull(): String? = when (this) {
    is MySqlScalar.Text -> value
    else -> null
}

private fun MySqlScalar.decimalOrNull(): String? = when (this) {
    is MySqlScalar.Number -> decimal
    is MySqlScalar.Text -> value
    else -> null
}

private fun MySqlScalar.signedLongOrNull(): Long? = decimalOrNull()?.toLongOrNull()

private fun MySqlScalar.unsignedLongOrNull(): ULong? = decimalOrNull()?.toULongOrNull()

private fun MySqlScalar.byteOrNull(): Byte? =
    signedLongOrNull()?.takeIf { it in Byte.MIN_VALUE..Byte.MAX_VALUE }?.toByte()

private fun MySqlScalar.shortOrNull(): Short? =
    signedLongOrNull()?.takeIf { it in Short.MIN_VALUE..Short.MAX_VALUE }?.toShort()

private fun MySqlScalar.intOrNull(): Int? = signedLongOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

private fun MySqlScalar.ubyteOrNull(): UByte? =
    unsignedLongOrNull()?.takeIf { it <= UByte.MAX_VALUE.toULong() }?.toUByte()

private fun MySqlScalar.ushortOrNull(): UShort? =
    unsignedLongOrNull()?.takeIf { it <= UShort.MAX_VALUE.toULong() }?.toUShort()

private fun MySqlScalar.uintOrNull(): UInt? = unsignedLongOrNull()?.takeIf { it <= UInt.MAX_VALUE.toULong() }?.toUInt()

private fun MySqlScalar.booleanOrNull(): Boolean? = when (this) {
    is MySqlScalar.BooleanValue -> value
    is MySqlScalar.Number -> decimal.toBooleanScalar()
    is MySqlScalar.Text -> value.toBooleanScalar()
    else -> null
}

private fun String.toBooleanScalar(): Boolean? = when (lowercase()) {
    "1", "true" -> true
    "0", "false" -> false
    else -> null
}

private fun rawNumber(protocolType: Int, decimal: String): Any = when (protocolType) {
    MySqlType.FLOAT.protocolType, MySqlType.DOUBLE.protocolType -> decimal.toDoubleOrNull() ?: decimal
    else -> decimal.toLongOrNull() ?: decimal
}

private fun rawText(protocolType: Int, text: String): Any = when (protocolType) {
    MySqlType.TINYINT.protocolType,
    MySqlType.SMALLINT.protocolType,
    MySqlType.INTEGER.protocolType,
    MySqlType.BIGINT.protocolType,
    -> text.toLongOrNull() ?: text

    MySqlType.FLOAT.protocolType, MySqlType.DOUBLE.protocolType -> text.toDoubleOrNull() ?: text

    else -> text
}

private fun invalid(
    column: SqlExpression<*>,
    value: Any?,
    expected: String,
    cause: Throwable? = null,
): InvalidValueConversion = InvalidValueConversion(column.toString(), value, expected, cause)
