package dev.quwac.kagiroi.schema

/** An independent database sequence that allocates signed 64-bit values.
 *
 * @property name sequence name.
 * @property schemaName containing schema name, or `null` when unspecified.
 */
@ConsistentCopyVisibility
data class Sequence internal constructor(val name: String, val schemaName: String?) {
    init {
        require(name.isNotBlank()) { "Sequence name must not be blank" }
        require(schemaName == null || schemaName.isNotBlank()) { "Sequence schema name must not be blank" }
    }
}

/** Declares an independent signed 64-bit sequence. */
fun sequence(name: String, schemaName: String? = null): Sequence = Sequence(name, schemaName)

/** A typed expression that requests the next signed 64-bit value from [sequence].
 *
 * @property sequence sequence whose next value is requested.
 */
@ConsistentCopyVisibility
data class SequenceNextValue internal constructor(val sequence: Sequence) : SqlExpression<Long> {
    private val result = resultExpression(LongColumnType)

    /** Columns referenced by this expression; always empty because a sequence is independent. */
    override val referencedColumns: List<Column<*>> = emptyList()

    /** Kotlin type of the sequence value. */
    override val type: ColumnType<*> get() = result.type

    /** Physical type used to store the sequence value. */
    override val storageType: ColumnType<*> get() = result.storageType

    /** Sequence values are never nullable. */
    override val isNullable: Boolean get() = false

    /** Encodes a sequence value using the signed 64-bit storage mapping. */
    override fun toStorageValue(value: Long): Any? = result.toStorageValue(value)

    /** Decodes a sequence result using the signed 64-bit storage mapping. */
    override fun fromStorageValue(value: Any?): Long = result.fromStorageValue(value)
}

/** Returns a typed expression that requests the next value from this sequence. */
fun Sequence.nextValue(): SequenceNextValue = SequenceNextValue(this)

/**
 * Sequences referenced by physical DEFAULT expressions of this table.
 *
 * An application schema registry validates that every returned sequence is declared by one of its table
 * roots. Current DEFAULT validation permits only the expression shapes traversed here.
 */
fun Table.referencedSequences(): List<Sequence> = columns.flatMap { column ->
    column.defaultExpression?.expression?.referencedSequences().orEmpty()
}

private fun SqlExpression<*>.referencedSequences(): List<Sequence> = when (this) {
    is SequenceNextValue -> listOf(sequence)
    is SqlFunctionExpression<*> -> arguments.flatMap(SqlExpression<*>::referencedSequences)
    is NumericBinaryExpression<*> -> left.referencedSequences() + right.referencedSequences()
    is LegacyDefaultBinarySqlExpression<*> -> left.referencedSequences() + right.referencedSequences()
    is CastExpression<*> -> expression.referencedSequences()
    else -> emptyList()
}
