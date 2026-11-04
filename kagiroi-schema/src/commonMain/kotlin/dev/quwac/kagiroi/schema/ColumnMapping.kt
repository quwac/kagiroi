package dev.quwac.kagiroi.schema

/** Converts between an application value and the single scalar value stored by a column. */
class ColumnMapping<T : Any, S : Any> internal constructor(
    private val fromStorage: (S) -> T,
    private val toStorage: (T) -> S,
) {
    /** Decodes a stored scalar into its application value. */
    fun fromStorageValue(value: S): T = fromStorage(value)

    /** Encodes an application value into its stored scalar. */
    fun toStorageValue(value: T): S = toStorage(value)
}

/** Creates a reusable mapping between an application value and a database scalar value. */
fun <T : Any, S : Any> columnMapping(fromStorage: (S) -> T, toStorage: (T) -> S): ColumnMapping<T, S> =
    ColumnMapping(fromStorage, toStorage)

/** Describes a transformed column while retaining its underlying database scalar type.
 *
 * @property mapping conversion between the application value and the stored scalar.
 * @property underlyingType column type used for the stored scalar.
 */
class MappedColumnType<T : Any, S : Any> internal constructor(
    val mapping: ColumnMapping<T, S>,
    val underlyingType: ColumnType<S>,
) : ColumnType<T>("Mapped<${underlyingType.name}>")

/** Transforms a scalar column with a reusable bidirectional mapping. */
@Suppress("UNCHECKED_CAST")
fun <S : Any, T : Any> WritableColumn<S>.transform(mapping: ColumnMapping<T, S>): WritableColumn<T> {
    require(!definition.hasValueMapping) { "Column already has a value mapping: $this" }
    val underlyingType = type as ColumnType<S>
    return withValueMapping(
        mappedType = MappedColumnType(mapping, underlyingType),
        toStorage = mapping::toStorageValue,
        fromStorage = mapping::fromStorageValue,
    )
}

/** Defines and applies a bidirectional mapping directly at the column declaration. */
fun <S : Any, T : Any> WritableColumn<S>.transform(fromStorage: (S) -> T, toStorage: (T) -> S): WritableColumn<T> =
    transform(columnMapping(fromStorage, toStorage))

/** Transforms a computed scalar column while keeping it non-writable. */
@Suppress("UNCHECKED_CAST")
fun <S : Any, T : Any> ComputedColumn<S>.transform(mapping: ColumnMapping<T, S>): ComputedColumn<T> {
    require(!definition.hasValueMapping) { "Column already has a value mapping: $this" }
    val underlyingType = type as ColumnType<S>
    return withValueMapping(
        mappedType = MappedColumnType(mapping, underlyingType),
        toStorage = mapping::toStorageValue,
        fromStorage = mapping::fromStorageValue,
    )
}

/** Defines and applies a bidirectional mapping to a computed column. */
fun <S : Any, T : Any> ComputedColumn<S>.transform(fromStorage: (S) -> T, toStorage: (T) -> S): ComputedColumn<T> =
    transform(columnMapping(fromStorage, toStorage))
