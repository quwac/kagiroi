package dev.quwac.kagiroi.schema

import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Describes the Kotlin value accepted by a column without selecting a database storage type.
 *
 * @property name stable human-readable name of the Kotlin value type.
 */
abstract class ColumnType<T : Any>(val name: String)

/** Column type for Kotlin [String] values. */
object StringColumnType : ColumnType<String>("String"), StableSchemaColumnType {
    override val schemaTypeDescriptor = SchemaTypeDescriptor("kagiroi.string")
}

/** Column type for Kotlin [Byte] values. */
object ByteColumnType : ColumnType<Byte>("Byte"), StableSchemaColumnType {
    override val schemaTypeDescriptor = SchemaTypeDescriptor("kagiroi.int8")
}

/** Column type for Kotlin [Short] values. */
object ShortColumnType : ColumnType<Short>("Short"), StableSchemaColumnType {
    override val schemaTypeDescriptor = SchemaTypeDescriptor("kagiroi.int16")
}

/** Column type for Kotlin [Int] values. */
object IntColumnType : ColumnType<Int>("Int"), StableSchemaColumnType {
    override val schemaTypeDescriptor = SchemaTypeDescriptor("kagiroi.int32")
}

/** Column type for Kotlin [Long] values. */
object LongColumnType : ColumnType<Long>("Long"), StableSchemaColumnType {
    override val schemaTypeDescriptor = SchemaTypeDescriptor("kagiroi.int64")
}

/** Column type for Kotlin [Float] values. */
object FloatColumnType : ColumnType<Float>("Float"), StableSchemaColumnType {
    override val schemaTypeDescriptor = SchemaTypeDescriptor("kagiroi.float32")
}

/** Column type for Kotlin [Double] values. */
object DoubleColumnType : ColumnType<Double>("Double"), StableSchemaColumnType {
    override val schemaTypeDescriptor = SchemaTypeDescriptor("kagiroi.float64")
}

/** Column type for Kotlin [UByte] values. */
object UByteColumnType : ColumnType<UByte>("UByte"), StableSchemaColumnType {
    override val schemaTypeDescriptor = SchemaTypeDescriptor("kagiroi.uint8")
}

/** Column type for Kotlin [UShort] values. */
object UShortColumnType : ColumnType<UShort>("UShort"), StableSchemaColumnType {
    override val schemaTypeDescriptor = SchemaTypeDescriptor("kagiroi.uint16")
}

/** Column type for Kotlin [UInt] values. */
object UIntColumnType : ColumnType<UInt>("UInt"), StableSchemaColumnType {
    override val schemaTypeDescriptor = SchemaTypeDescriptor("kagiroi.uint32")
}

/** Column type for Kotlin [ULong] values. */
object ULongColumnType : ColumnType<ULong>("ULong"), StableSchemaColumnType {
    override val schemaTypeDescriptor = SchemaTypeDescriptor("kagiroi.uint64")
}

/** Column type for Kotlin [Boolean] values. */
object BooleanColumnType : ColumnType<Boolean>("Boolean"), StableSchemaColumnType {
    override val schemaTypeDescriptor = SchemaTypeDescriptor("kagiroi.boolean")
}

/** Column type for Kotlin [ByteArray] values. */
object ByteArrayColumnType : ColumnType<ByteArray>("ByteArray"), StableSchemaColumnType {
    override val schemaTypeDescriptor = SchemaTypeDescriptor("kagiroi.bytes")
}

/** Column type for Kotlin [Instant] values. */
object InstantColumnType : ColumnType<Instant>("Instant"), StableSchemaColumnType {
    override val schemaTypeDescriptor = SchemaTypeDescriptor("kagiroi.instant")
}

/** Column type for Kotlin [Duration] values. */
object DurationColumnType : ColumnType<Duration>("Duration"), StableSchemaColumnType {
    override val schemaTypeDescriptor = SchemaTypeDescriptor("kagiroi.duration")
}

/** Column type for Kotlin [Uuid] values. */
object UuidColumnType : ColumnType<Uuid>("Uuid"), StableSchemaColumnType {
    override val schemaTypeDescriptor = SchemaTypeDescriptor("kagiroi.uuid")
}

/** A typed column owned by one [Table]. */
sealed class Column<T> protected constructor(internal val definition: ColumnDefinition) :
    GeneratedColumnExpression<T>,
    NamedRelationColumn<T & Any> {
    /** Table that owns this column. */
    val table: Table get() = definition.table

    /** Name of this column in [table]. */
    val name: String get() = definition.name

    /** Kotlin value type declared for this column. */
    override val type: ColumnType<*> get() = definition.type

    /** Physical storage type used by the database driver. */
    override val storageType: ColumnType<*> get() = definition.storageType

    /** Whether this column permits SQL `NULL` values. */
    override val isNullable: Boolean get() = definition.isNullable

    /** Whether this column participates in the table's primary key. */
    val isPrimaryKey: Boolean get() = definition.isPrimaryKey

    /** Whether this column has a uniqueness constraint. */
    val isUnique: Boolean get() = definition.isUnique

    /** Whether this column is a logical computed expression. */
    val isComputed: Boolean get() = definition.isComputed

    /** Native identity allocation metadata, or `null` when this is not an identity column. */
    val identity: Identity? get() = definition.identity

    /** Whether this column uses the database's native identity or auto-increment facility. */
    val isIdentity: Boolean get() = identity != null

    /** Optional typed SQL expression evaluated when INSERT omits this column. */
    @Suppress("UNCHECKED_CAST") // The definition is always constructed through a same-typed writable column.
    val defaultExpression: ColumnDefault<T>? get() = definition.defaultExpression as ColumnDefault<T>?

    /** Whether this column declares an explicit database DEFAULT expression. */
    val hasDefault: Boolean get() = defaultExpression != null

    /** The database generation expression, when this is a physical generated column. */
    val generatedExpression: GeneratedColumnExpression<*>? get() = definition.generatedExpression

    /** The requested physical storage mode for [generatedExpression], when present. */
    val generatedColumnStorage: GeneratedColumnStorage? get() = definition.generatedColumnStorage

    /** Whether this is a physical generated column that typed writes cannot assign. */
    val isGenerated: Boolean get() = generatedExpression != null

    /** Optional logical schema metadata for this column. */
    val comment: String? get() = definition.comment

    /** Ordered historical physical names for schema evolution consumers; never part of current DDL. */
    val renameHistory: List<String> get() = definition.renameHistory

    /** Converts an application value to the scalar value consumed by a database driver. */
    override fun toStorageValue(value: T): Any? {
        if (value == null) {
            require(isNullable) { "Non-null column cannot encode null: $this" }
            return null
        }
        return definition.encode(value)
    }

    /** Converts a driver-decoded scalar value to this column's application value. */
    @Suppress("UNCHECKED_CAST")
    override fun fromStorageValue(value: Any?): T {
        if (value == null) {
            require(isNullable) { "Non-null column cannot decode null: $this" }
            return null as T
        }
        return definition.decode(value) as T
    }

    /** Returns this column's qualified table and column name. */
    override fun toString(): String = "${table.tableName}.$name"

    /** The sole relation column referenced by this column expression. */
    override val referencedColumns: List<Column<*>> get() = listOf(this)
}

/** A column that may be assigned by INSERT and UPDATE queries. */
open class WritableColumn<T> internal constructor(definition: ColumnDefinition) : Column<T>(definition)

/** A logical read-only column without a physical generated-column declaration. */
class ComputedColumn<T> internal constructor(definition: ColumnDefinition) : Column<T>(definition)

/** A physical database column whose value is calculated from its generation expression. */
class GeneratedColumn<T> internal constructor(definition: ColumnDefinition) : Column<T>(definition)

internal data class ColumnDefinition(
    val table: Table,
    val name: String,
    val type: ColumnType<*>,
    val storageType: ColumnType<*> = type,
    val encode: (Any) -> Any = { it },
    val decode: (Any) -> Any = { it },
    val hasValueMapping: Boolean = false,
    val isNullable: Boolean = false,
    val isPrimaryKey: Boolean = false,
    val isUnique: Boolean = false,
    val isComputed: Boolean = false,
    val identity: Identity? = null,
    val defaultExpression: ColumnDefault<*>? = null,
    val generatedExpression: GeneratedColumnExpression<*>? = null,
    val generatedColumnStorage: GeneratedColumnStorage? = null,
    val comment: String? = null,
    val renameHistory: List<String> = emptyList(),
)

internal fun <T> WritableColumn<*>.withDefinition(definition: ColumnDefinition): WritableColumn<T> {
    val replacement = WritableColumn<T>(definition)
    table.replaceColumn(this, replacement)
    return replacement
}

internal fun <T> ComputedColumn<*>.withDefinition(definition: ColumnDefinition): ComputedColumn<T> {
    val replacement = ComputedColumn<T>(definition)
    table.replaceColumn(this, replacement)
    return replacement
}

internal fun <T> GeneratedColumn<*>.withDefinition(definition: ColumnDefinition): GeneratedColumn<T> {
    val replacement = GeneratedColumn<T>(definition)
    table.replaceColumn(this, replacement)
    return replacement
}

@Suppress("UNCHECKED_CAST") // ColumnDefinition stores codecs behind an erased driver boundary.
internal fun <S : Any, T : Any> WritableColumn<S>.withValueMapping(
    mappedType: ColumnType<T>,
    toStorage: (T) -> S,
    fromStorage: (S) -> T,
): WritableColumn<T> {
    require(!definition.hasValueMapping) { "Column already has a value mapping: $this" }
    require(definition.defaultExpression == null) { "Value mapping must be declared before DEFAULT: $this" }
    return withDefinition(
        definition.copy(
            type = mappedType,
            encode = { toStorage(it as T) },
            decode = { fromStorage(it as S) },
            hasValueMapping = true,
        ),
    )
}

@Suppress("UNCHECKED_CAST") // ColumnDefinition stores codecs behind an erased driver boundary.
internal fun <S : Any, T : Any> ComputedColumn<S>.withValueMapping(
    mappedType: ColumnType<T>,
    toStorage: (T) -> S,
    fromStorage: (S) -> T,
): ComputedColumn<T> {
    require(!definition.hasValueMapping) { "Column already has a value mapping: $this" }
    return withDefinition(
        definition.copy(
            type = mappedType,
            encode = { toStorage(it as T) },
            decode = { fromStorage(it as S) },
            hasValueMapping = true,
        ),
    )
}

/** Marks a non-null column as its table's primary key. */
fun <T : Any> WritableColumn<T>.primaryKey(): WritableColumn<T> {
    require(!definition.isNullable) { "Primary key column must not be nullable: $this" }
    require(table.primaryKey == null) { "Column-level primary key conflicts with a table-level primary key: $this" }
    require(table.columns.none { it !== this && it.isPrimaryKey }) {
        "Table already defines a column-level primary key: $table"
    }
    return withDefinition(definition.copy(isPrimaryKey = true, isUnique = true))
}

/** Changes the Kotlin value accepted by a column from [T] to nullable [T]. */
fun <T : Any> WritableColumn<T>.nullable(): WritableColumn<T?> {
    require(!definition.isPrimaryKey) { "Primary key column must not be nullable: $this" }
    return withDefinition(definition.copy(isNullable = true))
}

/** Adds a uniqueness constraint to this column. */
fun <T> WritableColumn<T>.unique(): WritableColumn<T> = withDefinition(definition.copy(isUnique = true))

/** Adds logical schema metadata that schema tooling and supported DDL dialects can retain. */
fun <T> WritableColumn<T>.comment(comment: String): WritableColumn<T> {
    require(comment.isNotBlank()) { "Column comment must not be blank: $this" }
    return withDefinition(definition.copy(comment = comment))
}

/** Adds logical schema metadata to a computed column without making it writable. */
fun <T> ComputedColumn<T>.comment(comment: String): ComputedColumn<T> {
    require(comment.isNotBlank()) { "Column comment must not be blank: $this" }
    return withDefinition(definition.copy(comment = comment))
}
