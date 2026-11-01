package dev.quwac.kagiroi.schema

/** A complete, bidirectional mapping between a Kotlin enum and stable database values. */
class EnumBinding<E : Enum<E>, S : Any> internal constructor(
    val entries: List<E>,
    private val storageByEntry: Map<E, S>,
    private val entryByStorage: Map<S, E>,
) {
    fun toStorageValue(value: E): S = storageByEntry.getValue(value)

    fun fromStorageValue(value: S): E = entryByStorage[value] ?: throw UnknownEnumValueException(value)
}

/** Indicates that a database value has no corresponding enum entry. */
class UnknownEnumValueException(val storageValue: Any) :
    IllegalArgumentException("Unknown stored enum value: $storageValue")

/** Collects explicit enum mappings without using ordinal or declaration position. */
class EnumBindingBuilder<E : Enum<E>, S : Any> internal constructor() {
    internal val mappings = mutableListOf<Pair<E, S>>()

    infix fun E.mapsTo(storageValue: S) {
        require(mappings.none { it.first == this }) { "Enum entry is mapped more than once: $name" }
        require(
            mappings.none { it.second == storageValue },
        ) { "Stored enum value is mapped more than once: $storageValue" }
        mappings += this to storageValue
    }
}

/** Builds a reusable enum binding and verifies that every declared entry is mapped exactly once. */
fun <E : Enum<E>, S : Any> enumBinding(
    entries: Iterable<E>,
    block: EnumBindingBuilder<E, S>.() -> Unit,
): EnumBinding<E, S> {
    val declaredEntries = entries.toList()
    require(declaredEntries.isNotEmpty()) { "Enum binding must declare at least one entry" }
    require(declaredEntries.distinct().size == declaredEntries.size) { "Enum binding contains duplicate entries" }

    val builder = EnumBindingBuilder<E, S>().apply(block)
    val declaredEntrySet = declaredEntries.toSet()
    val mappedEntrySet = builder.mappings.mapTo(mutableSetOf()) { it.first }
    val unknownEntries = mappedEntrySet - declaredEntrySet
    require(unknownEntries.isEmpty()) {
        "Mappings contain undeclared enum entries: ${unknownEntries.joinToString { it.name }}"
    }
    val missingEntries = declaredEntrySet - mappedEntrySet
    require(missingEntries.isEmpty()) {
        "Missing enum mappings: ${missingEntries.joinToString { it.name }}"
    }

    return EnumBinding(
        entries = declaredEntries,
        storageByEntry = builder.mappings.toMap(),
        entryByStorage = builder.mappings.associate { (entry, storage) -> storage to entry },
    )
}

/** Describes an enum column while preserving its underlying database scalar type. */
class EnumColumnType<E : Enum<E>, S : Any> internal constructor(
    val binding: EnumBinding<E, S>,
    val underlyingType: ColumnType<S>,
) : ColumnType<E>("Enum<${underlyingType.name}>")

/** Applies a reusable enum binding to a scalar column. */
@Suppress("UNCHECKED_CAST")
fun <E : Enum<E>, S : Any> WritableColumn<S>.asEnum(binding: EnumBinding<E, S>): WritableColumn<E> {
    require(!definition.hasValueMapping) { "Column already has a value mapping: $this" }
    val underlyingType = type as ColumnType<S>
    return withValueMapping(
        mappedType = EnumColumnType(binding, underlyingType),
        toStorage = binding::toStorageValue,
        fromStorage = binding::fromStorageValue,
    )
}

/** Defines and applies an enum binding directly at the column declaration. */
fun <E : Enum<E>, S : Any> WritableColumn<S>.asEnum(
    entries: Iterable<E>,
    block: EnumBindingBuilder<E, S>.() -> Unit,
): WritableColumn<E> = asEnum(enumBinding(entries, block))

/** Applies a reusable enum binding to a computed scalar column without making it writable. */
@Suppress("UNCHECKED_CAST")
fun <E : Enum<E>, S : Any> ComputedColumn<S>.asEnum(binding: EnumBinding<E, S>): ComputedColumn<E> {
    require(!definition.hasValueMapping) { "Column already has a value mapping: $this" }
    val underlyingType = type as ColumnType<S>
    return withValueMapping(
        mappedType = EnumColumnType(binding, underlyingType),
        toStorage = binding::toStorageValue,
        fromStorage = binding::fromStorageValue,
    )
}

/** Defines and applies an enum binding to a computed scalar column. */
fun <E : Enum<E>, S : Any> ComputedColumn<S>.asEnum(
    entries: Iterable<E>,
    block: EnumBindingBuilder<E, S>.() -> Unit,
): ComputedColumn<E> = asEnum(enumBinding(entries, block))
