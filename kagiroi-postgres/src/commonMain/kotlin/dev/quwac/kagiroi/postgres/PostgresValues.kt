package dev.quwac.kagiroi.postgres

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

/** A parameter value before the target-specific node-postgres bridge. */
sealed interface PostgresBinding {
    data object Null : PostgresBinding
    data class Text(val value: String) : PostgresBinding
    data class BooleanValue(val value: Boolean) : PostgresBinding
    data class Integer(val decimal: String) : PostgresBinding
    data class Real(val value: Double) : PostgresBinding
    data class Bytes(val value: ByteArray) : PostgresBinding
    data class UuidValue(val value: Uuid) : PostgresBinding
}

/** One PostgreSQL text-format field paired with the server's type OID. */
data class PostgresResultValue(val typeObjectId: Int, val text: String?)

/** PostgreSQL scalar conversion shared by Hyperdrive target adapters. */
class PostgresValueCodec(private val codecs: SqlTypeCodecRegistry) {
    fun binding(value: Any?, expression: SqlExpression<*>? = null): PostgresBinding {
        if (value == null) return PostgresBinding.Null
        val columnType = expression?.storageType
        val encoded = columnType?.let { codecs.encode(it, value) }
        return bindScalar(encoded?.value ?: value, encoded?.storageType ?: columnType)
    }

    @Suppress("CyclomaticComplexMethod", "ThrowsCount", "TooGenericExceptionCaught")
    fun decode(column: SqlExpression<*>, value: PostgresResultValue): Any? {
        val text = value.text
        if (text == null) {
            if (column.isNullable) return null
            throw invalid(column, null, column.storageType.name)
        }

        val physicalType = codecs.storageType(column.storageType) ?: column.storageType
        val converted = try {
            when (physicalType) {
                StringColumnType -> text
                BooleanColumnType -> text.postgresBoolean()
                ByteColumnType -> text.toLongOrNull()?.takeIf { it in Byte.MIN_VALUE..Byte.MAX_VALUE }?.toByte()
                ShortColumnType -> text.toLongOrNull()?.takeIf { it in Short.MIN_VALUE..Short.MAX_VALUE }?.toShort()
                IntColumnType -> text.toLongOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
                LongColumnType -> text.toLongOrNull()
                UByteColumnType -> text.toULongOrNull()?.takeIf { it <= UByte.MAX_VALUE.toULong() }?.toUByte()
                UShortColumnType -> text.toULongOrNull()?.takeIf { it <= UShort.MAX_VALUE.toULong() }?.toUShort()
                UIntColumnType -> text.toULongOrNull()?.takeIf { it <= UInt.MAX_VALUE.toULong() }?.toUInt()
                ULongColumnType -> text.toULongOrNull()
                FloatColumnType -> text.toDoubleOrNull()?.toFloat()
                DoubleColumnType -> text.toDoubleOrNull()
                ByteArrayColumnType -> decodeBytea(text)
                UuidColumnType -> Uuid.parse(text)
                else -> null
            }
        } catch (error: Throwable) {
            throw invalid(column, text, column.storageType.name, error)
        } ?: throw invalid(column, text, column.storageType.name)

        return try {
            codecs.decode(column.storageType, converted)?.value ?: converted
        } catch (error: Throwable) {
            throw invalid(column, text, column.storageType.name, error)
        }
    }

    fun rawValue(value: PostgresResultValue): Any? {
        val text = value.text ?: return null
        return when (value.typeObjectId) {
            PostgresType.BOOLEAN.objectId -> text.postgresBoolean()

            PostgresType.SMALLINT.objectId,
            PostgresType.INTEGER.objectId,
            PostgresType.BIGINT.objectId,
            -> text.toLongOrNull() ?: text

            PostgresType.REAL.objectId, PostgresType.DOUBLE_PRECISION.objectId -> text.toDoubleOrNull() ?: text

            PostgresType.BYTEA.objectId -> decodeBytea(text)

            else -> text
        }
    }

    private fun bindScalar(value: Any, type: ColumnType<*>?): PostgresBinding = when (type) {
        StringColumnType -> PostgresBinding.Text(value as String)

        BooleanColumnType -> PostgresBinding.BooleanValue(value as Boolean)

        ByteColumnType,
        ShortColumnType,
        IntColumnType,
        LongColumnType,
        UByteColumnType,
        UShortColumnType,
        UIntColumnType,
        ULongColumnType,
        -> PostgresBinding.Integer(value.toString())

        FloatColumnType -> PostgresBinding.Real((value as Float).toDouble())

        DoubleColumnType -> PostgresBinding.Real(value as Double)

        ByteArrayColumnType -> PostgresBinding.Bytes((value as ByteArray).copyOf())

        UuidColumnType -> PostgresBinding.UuidValue(value as Uuid)

        null -> inferRawBinding(value)

        else -> throw IllegalArgumentException("Unsupported PostgreSQL column type: ${type.name}")
    }
}

private fun inferRawBinding(value: Any): PostgresBinding = when (value) {
    is String -> PostgresBinding.Text(value)

    is Boolean -> PostgresBinding.BooleanValue(value)

    is Byte, is Short, is Int, is Long, is UByte, is UShort, is UInt, is ULong ->
        PostgresBinding.Integer(value.toString())

    is Float -> PostgresBinding.Real(value.toDouble())

    is Double -> PostgresBinding.Real(value)

    is ByteArray -> PostgresBinding.Bytes(value.copyOf())

    is Uuid -> PostgresBinding.UuidValue(value)

    else -> throw IllegalArgumentException("Unsupported PostgreSQL parameter type")
}

private fun String.postgresBoolean(): Boolean? = when (this) {
    "t", "true" -> true
    "f", "false" -> false
    else -> null
}

private fun decodeBytea(value: String): ByteArray = if (value.startsWith("\\x")) {
    val hexadecimal = value.drop(HEXADECIMAL_PREFIX_LENGTH)
    require(hexadecimal.length % HEXADECIMAL_BYTE_WIDTH == 0) { "Invalid hexadecimal BYTEA length" }
    ByteArray(hexadecimal.length / HEXADECIMAL_BYTE_WIDTH) { index ->
        val start = index * HEXADECIMAL_BYTE_WIDTH
        hexadecimal.substring(start, start + HEXADECIMAL_BYTE_WIDTH).toInt(HEXADECIMAL_RADIX).toByte()
    }
} else {
    decodeEscapedBytea(value)
}

private fun decodeEscapedBytea(value: String): ByteArray {
    val bytes = mutableListOf<Byte>()
    var index = 0
    while (index < value.length) {
        val character = value[index]
        if (character != '\\') {
            require(character.code <= UByte.MAX_VALUE.toInt()) { "Invalid BYTEA character" }
            bytes += character.code.toByte()
            index++
        } else if (value.getOrNull(index + NEXT_CHARACTER_OFFSET) == '\\') {
            bytes += '\\'.code.toByte()
            index += ESCAPED_SLASH_WIDTH
        } else {
            val octal = value.substring(
                index + NEXT_CHARACTER_OFFSET,
                (index + ESCAPED_OCTAL_WIDTH).coerceAtMost(value.length),
            )
            require(octal.length == OCTAL_DIGIT_COUNT && octal.all { it in '0'..'7' }) {
                "Invalid escaped BYTEA value"
            }
            bytes += octal.toInt(OCTAL_RADIX).toByte()
            index += ESCAPED_OCTAL_WIDTH
        }
    }
    return bytes.toByteArray()
}

private fun invalid(
    column: SqlExpression<*>,
    value: Any?,
    expected: String,
    cause: Throwable? = null,
): InvalidValueConversion = InvalidValueConversion(column.toString(), value, expected, cause)

private const val HEXADECIMAL_PREFIX_LENGTH = 2
private const val HEXADECIMAL_BYTE_WIDTH = 2
private const val HEXADECIMAL_RADIX = 16
private const val NEXT_CHARACTER_OFFSET = 1
private const val ESCAPED_SLASH_WIDTH = 2
private const val ESCAPED_OCTAL_WIDTH = 4
private const val OCTAL_DIGIT_COUNT = 3
private const val OCTAL_RADIX = 8
