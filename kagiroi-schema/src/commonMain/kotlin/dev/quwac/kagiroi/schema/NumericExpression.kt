package dev.quwac.kagiroi.schema

/** The portable arithmetic operators whose operands share one Kotlin numeric type.
 *
 * @property sql SQL operator token.
 */
enum class NumericBinaryOperator(val sql: String) {
    /** Addition. */
    Add("+"),

    /** Subtraction. */
    Subtract("-"),

    /** Multiplication. */
    Multiply("*"),

    /** Division. */
    Divide("/"),

    /** Remainder after division. */
    Modulo("%"),
}

/** A typed ordinary-query arithmetic expression. Generated columns retain their value-free subtype.
 *
 * @property left left-hand operand.
 * @property operator arithmetic operation applied to the operands.
 * @property right right-hand operand.
 */
class NumericBinaryExpression<T : Number?> internal constructor(
    val left: SqlExpression<T>,
    val operator: NumericBinaryOperator,
    val right: SqlExpression<T>,
) : SqlExpression<T> {
    override val referencedColumns: List<Column<*>> = left.referencedColumns + right.referencedColumns
    override val type: ColumnType<*> get() = left.type
    override val storageType: ColumnType<*> get() = left.storageType
    override val isNullable: Boolean get() = left.isNullable || right.isNullable

    override fun toStorageValue(value: T): Any? = left.toStorageValue(value)

    override fun fromStorageValue(value: Any?): T = left.fromStorageValue(value)
}

/** Adds two expressions with the same Kotlin numeric type. */
operator fun <T : Number?> SqlExpression<T>.plus(other: SqlExpression<T>): NumericBinaryExpression<T> =
    NumericBinaryExpression(this, NumericBinaryOperator.Add, other)

/** Subtracts two expressions with the same Kotlin numeric type. */
operator fun <T : Number?> SqlExpression<T>.minus(other: SqlExpression<T>): NumericBinaryExpression<T> =
    NumericBinaryExpression(this, NumericBinaryOperator.Subtract, other)

/** Multiplies two expressions with the same Kotlin numeric type. */
operator fun <T : Number?> SqlExpression<T>.times(other: SqlExpression<T>): NumericBinaryExpression<T> =
    NumericBinaryExpression(this, NumericBinaryOperator.Multiply, other)

/** Divides two expressions with the same Kotlin numeric type. */
operator fun <T : Number?> SqlExpression<T>.div(other: SqlExpression<T>): NumericBinaryExpression<T> =
    NumericBinaryExpression(this, NumericBinaryOperator.Divide, other)

/** Computes the remainder of two expressions with the same Kotlin numeric type. */
operator fun <T : Number?> SqlExpression<T>.rem(other: SqlExpression<T>): NumericBinaryExpression<T> =
    NumericBinaryExpression(this, NumericBinaryOperator.Modulo, other)

/** Adds a bound numeric value to this expression. */
operator fun <T : Number?> SqlExpression<T>.plus(other: T & Any): NumericBinaryExpression<T> = this + literal(
    other,
    this,
)

/** Subtracts a bound numeric value from this expression. */
operator fun <T : Number?> SqlExpression<T>.minus(other: T & Any): NumericBinaryExpression<T> = this - literal(
    other,
    this,
)

/** Multiplies this expression by a bound numeric value. */
operator fun <T : Number?> SqlExpression<T>.times(other: T & Any): NumericBinaryExpression<T> = this * literal(
    other,
    this,
)

/** Divides this expression by a bound numeric value. */
operator fun <T : Number?> SqlExpression<T>.div(other: T & Any): NumericBinaryExpression<T> = this / literal(
    other,
    this,
)

/** Computes this expression's remainder with a bound numeric value. */
operator fun <T : Number?> SqlExpression<T>.rem(other: T & Any): NumericBinaryExpression<T> = this % literal(
    other,
    this,
)
