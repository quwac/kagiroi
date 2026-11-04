package dev.quwac.kagiroi.schema

/**
 * Immutable schema metadata for a typed SQL expression evaluated when INSERT omits a column.
 *
 * The expression remains an ordinary [SqlExpression]. This wrapper carries its DEFAULT-context
 * validation and gives schema snapshots a structural equality boundary without creating a second
 * SQL expression language.
 *
 * @property expression typed SQL expression used when INSERT omits the column.
 */
class ColumnDefault<T> internal constructor(val expression: SqlExpression<T>) {
    private val structure = DefaultExpressionStructure.of(expression)

    override fun equals(other: Any?): Boolean = other is ColumnDefault<*> && structure == other.structure

    override fun hashCode(): Int = structure.hashCode()

    override fun toString(): String = "ColumnDefault($structure)"
}

/** A typed value-free operand used by [defaultExpression]. */
sealed interface DefaultExpression<out T>

/** A typed literal operand for a composed DEFAULT expression.
 *
 * @property value literal value used by the DEFAULT expression.
 */
@ConsistentCopyVisibility
data class DefaultLiteral<T> internal constructor(val value: T) : DefaultExpression<T>

/** Arithmetic operators in the portable DEFAULT-expression subset.
 *
 * @property sql SQL operator token.
 */
enum class DefaultBinaryOperator(val sql: String) {
    /** Addition. */
    Add("+"),

    /** Subtraction. */
    Subtract("-"),

    /** Multiplication. */
    Multiply("*"),

    /** Division. */
    Divide("/"),
}

/** A same-type arithmetic operand retained by [defaultExpression].
 *
 * @property left left-hand operand.
 * @property operator arithmetic operation applied to the operands.
 * @property right right-hand operand.
 */
@ConsistentCopyVisibility
data class DefaultBinaryExpression<T> internal constructor(
    val left: DefaultExpression<T>,
    val operator: DefaultBinaryOperator,
    val right: DefaultExpression<T>,
) : DefaultExpression<T>

/** Wraps a typed value when it is an operand of a composed DEFAULT expression. */
fun <T> defaultExpression(value: T): DefaultExpression<T> = DefaultLiteral(value)

/** Adds [other] to this DEFAULT expression. */
operator fun <T> DefaultExpression<T>.plus(other: DefaultExpression<T>): DefaultExpression<T> =
    DefaultBinaryExpression(this, DefaultBinaryOperator.Add, other)

/** Subtracts [other] from this DEFAULT expression. */
operator fun <T> DefaultExpression<T>.minus(other: DefaultExpression<T>): DefaultExpression<T> =
    DefaultBinaryExpression(this, DefaultBinaryOperator.Subtract, other)

/** Multiplies this DEFAULT expression by [other]. */
operator fun <T> DefaultExpression<T>.times(other: DefaultExpression<T>): DefaultExpression<T> =
    DefaultBinaryExpression(this, DefaultBinaryOperator.Multiply, other)

/** Divides this DEFAULT expression by [other]. */
operator fun <T> DefaultExpression<T>.div(other: DefaultExpression<T>): DefaultExpression<T> =
    DefaultBinaryExpression(this, DefaultBinaryOperator.Divide, other)

/** Adds [other] as a literal to this DEFAULT expression. */
operator fun <T> DefaultExpression<T>.plus(other: T): DefaultExpression<T> = this + defaultExpression(other)

/** Subtracts [other] as a literal from this DEFAULT expression. */
operator fun <T> DefaultExpression<T>.minus(other: T): DefaultExpression<T> = this - defaultExpression(other)

/** Multiplies this DEFAULT expression by the literal [other]. */
operator fun <T> DefaultExpression<T>.times(other: T): DefaultExpression<T> = this * defaultExpression(other)

/** Divides this DEFAULT expression by the literal [other]. */
operator fun <T> DefaultExpression<T>.div(other: T): DefaultExpression<T> = this / defaultExpression(other)

/** Returns the database's current instant in ordinary SQL and DEFAULT expression contexts. */
fun currentInstant(): SqlFunctionExpression<kotlin.time.Instant> = currentTimestamp()

internal fun <T> DefaultExpression<T>.asSqlExpression(template: SqlExpression<T>): SqlExpression<T> = when (this) {
    is DefaultLiteral<*> -> {
        @Suppress("UNCHECKED_CAST")
        val literal = this as DefaultLiteral<T>
        literal(literal.value, template)
    }

    is DefaultBinaryExpression<*> -> {
        @Suppress("UNCHECKED_CAST")
        val binary = this as DefaultBinaryExpression<T>
        LegacyDefaultBinarySqlExpression(
            binary.left.asSqlExpression(template),
            binary.operator,
            binary.right.asSqlExpression(template),
            template,
        )
    }
}

/** An ordinary-expression view of the value-free [DefaultBinaryExpression] tree.
 *
 * @property left left-hand operand.
 * @property operator arithmetic operation applied to the operands.
 * @property right right-hand operand.
 */
class LegacyDefaultBinarySqlExpression<T> internal constructor(
    val left: SqlExpression<T>,
    val operator: DefaultBinaryOperator,
    val right: SqlExpression<T>,
    private val result: SqlExpression<T>,
) : SqlExpression<T> {
    override val referencedColumns: List<Column<*>> = emptyList()
    override val type: ColumnType<*> get() = result.type
    override val storageType: ColumnType<*> get() = result.storageType
    override val isNullable: Boolean get() = left.isNullable || right.isNullable

    override fun toStorageValue(value: T): Any? = result.toStorageValue(value)

    override fun fromStorageValue(value: Any?): T = result.fromStorageValue(value)
}

private sealed interface DefaultExpressionStructure {
    data class Literal(val value: Any?, val type: String, val storageType: String, val nullable: Boolean) :
        DefaultExpressionStructure

    data class SequenceValue(val sequence: Sequence) : DefaultExpressionStructure

    data class Function(
        val function: SqlFunction,
        val arguments: List<DefaultExpressionStructure>,
        val type: String,
        val storageType: String,
        val nullable: Boolean,
    ) : DefaultExpressionStructure

    data class NumericBinary(
        val operator: NumericBinaryOperator,
        val left: DefaultExpressionStructure,
        val right: DefaultExpressionStructure,
        val type: String,
        val storageType: String,
        val nullable: Boolean,
    ) : DefaultExpressionStructure

    data class LegacyBinary(
        val operator: DefaultBinaryOperator,
        val left: DefaultExpressionStructure,
        val right: DefaultExpressionStructure,
        val type: String,
        val storageType: String,
        val nullable: Boolean,
    ) : DefaultExpressionStructure

    data class Cast(
        val expression: DefaultExpressionStructure,
        val targetType: String,
        val targetStorageType: String,
        val nullable: Boolean,
    ) : DefaultExpressionStructure

    companion object {
        fun of(expression: SqlExpression<*>): DefaultExpressionStructure = when (expression) {
            is SqlLiteral<*> -> Literal(
                canonicalLiteral(expression.value),
                expression.type.name,
                expression.storageType.name,
                expression.isNullable,
            )

            is SequenceNextValue -> SequenceValue(expression.sequence)

            is SqlFunctionExpression<*> -> Function(
                expression.function,
                expression.arguments.map(::of),
                expression.type.name,
                expression.storageType.name,
                expression.isNullable,
            )

            is NumericBinaryExpression<*> -> NumericBinary(
                expression.operator,
                of(expression.left),
                of(expression.right),
                expression.type.name,
                expression.storageType.name,
                expression.isNullable,
            )

            is LegacyDefaultBinarySqlExpression<*> -> LegacyBinary(
                expression.operator,
                of(expression.left),
                of(expression.right),
                expression.type.name,
                expression.storageType.name,
                expression.isNullable,
            )

            is CastExpression<*> -> Cast(
                of(expression.expression),
                expression.target.type.name,
                expression.target.storageType.name,
                expression.isNullable,
            )

            else -> error("DEFAULT expression was not validated: $expression")
        }

        private fun canonicalLiteral(value: Any?): Any? = when (value) {
            is ByteArray -> value.toList()
            else -> value
        }
    }
}
