package dev.quwac.kagiroi.schema

/**
 * A column type whose application value is encoded through one scalar storage type.
 *
 * Database dialect modules use this for native types whose transport representation is
 * still a standard Kagiroi scalar, such as a bounded character string transported as
 * [String]. The physical SQL declaration remains a dialect concern.
 */
interface StorageMappedColumnType {
    /** Scalar column type used to encode and decode this mapped value. */
    val storageType: ColumnType<*>

    /** Encodes an application value into the scalar representation. */
    fun encodeStorageValue(value: Any): Any

    /** Decodes a scalar representation into the application value. */
    fun decodeStorageValue(value: Any): Any
}

/**
 * Defines an explicit application-to-storage mapping without changing the logical
 * [ColumnType] contract in kagiroi-schema.
 */
open class ScalarMappedColumnType<T : Any, S : Any>(
    name: String,
    final override val storageType: ColumnType<S>,
    private val mapping: ColumnMapping<T, S>,
) : ColumnType<T>(name),
    StorageMappedColumnType {
    @Suppress("UNCHECKED_CAST") // The database codec boundary performs the corresponding inverse cast.
    final override fun encodeStorageValue(value: Any): Any = mapping.toStorageValue(value as T)

    @Suppress("UNCHECKED_CAST") // The database codec boundary provides the declared storage scalar.
    final override fun decodeStorageValue(value: Any): Any = mapping.fromStorageValue(value as S)
}

/** Creates a reusable [ScalarMappedColumnType] from an explicit scalar mapping. */
fun <T : Any, S : Any> scalarMappedColumnType(
    name: String,
    storageType: ColumnType<S>,
    mapping: ColumnMapping<T, S>,
): ScalarMappedColumnType<T, S> = ScalarMappedColumnType(name, storageType, mapping)
