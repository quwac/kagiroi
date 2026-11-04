package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.ColumnType
import dev.quwac.kagiroi.schema.StorageMappedColumnType

/** Explicitly maps one SQL column type to a scalar type understood by a database driver.
 *
 * @property type logical column type recognized by this codec.
 * @property storageType driver-supported scalar type used for transport.
 */
class SqlTypeCodec<T : Any, S : Any>(
    val type: ColumnType<T>,
    val storageType: ColumnType<S>,
    private val encode: (T) -> S,
    private val decode: (S) -> T,
) {
    @Suppress("UNCHECKED_CAST") // Erasure is contained at the registry boundary after identity lookup.
    internal fun encodeValue(value: Any): Any = encode(value as T)

    @Suppress("UNCHECKED_CAST") // Erasure is contained at the registry boundary after identity lookup.
    internal fun decodeValue(value: Any): Any = decode(value as S)
}

/** Encoded scalar and its physical column type.
 *
 * @property storageType type used by the driver to bind [value].
 * @property value encoded scalar value.
 */
data class EncodedSqlValue(val storageType: ColumnType<*>, val value: Any)

/** A decoded application value produced from a driver scalar.
 *
 * @property value decoded value.
 */
data class DecodedSqlValue(val value: Any)

/** Immutable identity-based codec lookup used by SQL database drivers. */
class SqlTypeCodecRegistry(codecs: Iterable<SqlTypeCodec<*, *>>) {
    private val entries = codecs.toList()

    init {
        entries.forEachIndexed { index, codec ->
            require(entries.take(index).none { it.type === codec.type }) {
                "SQL type codec is registered more than once: ${codec.type.name}"
            }
            require(codec.type !== codec.storageType) {
                "SQL type codec must map to a different storage type: ${codec.type.name}"
            }
        }
    }

    /** Returns the scalar storage type used for [type], if a codec or mapping is registered. */
    fun storageType(type: ColumnType<*>): ColumnType<*>? = find(type)?.storageType
        ?: (type as? StorageMappedColumnType)?.storageType

    /** Encodes [value] according to the registered codec for [type], when one exists. */
    fun encode(type: ColumnType<*>, value: Any): EncodedSqlValue? = find(type)?.let { codec ->
        EncodedSqlValue(codec.storageType, codec.encodeValue(value))
    } ?: (type as? StorageMappedColumnType)?.let { mapped ->
        EncodedSqlValue(mapped.storageType, mapped.encodeStorageValue(value))
    }

    /** Decodes a driver [value] according to the registered codec for [type], when one exists. */
    fun decode(type: ColumnType<*>, value: Any): DecodedSqlValue? = find(type)?.let { codec ->
        DecodedSqlValue(codec.decodeValue(value))
    } ?: (type as? StorageMappedColumnType)?.let { mapped ->
        DecodedSqlValue(mapped.decodeStorageValue(value))
    }

    private fun find(type: ColumnType<*>): SqlTypeCodec<*, *>? = entries.firstOrNull { it.type === type }
}
