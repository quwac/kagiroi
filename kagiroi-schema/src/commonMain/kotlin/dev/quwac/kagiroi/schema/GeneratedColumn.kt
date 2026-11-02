package dev.quwac.kagiroi.schema

/** Whether a database-generated expression is evaluated on read or stored at write time. */
enum class GeneratedColumnStorage {
    Virtual,
    Stored,
}

/** A typed, value-free SQL expression accepted by a generated database column. */
interface GeneratedColumnExpression<T> : SqlExpression<T>

/** An arithmetic operator supported by the portable generated-column expression subset. */
enum class GeneratedColumnBinaryOperator(val sql: String) {
    Add("+"),
    Subtract("-"),
    Multiply("*"),
    Divide("/"),
}

/** A typed arithmetic expression whose result type is shared by both operands. */
class GeneratedColumnBinaryExpression<T> internal constructor(
    val left: GeneratedColumnExpression<T>,
    val operator: GeneratedColumnBinaryOperator,
    val right: GeneratedColumnExpression<T>,
) : GeneratedColumnExpression<T> {
    override val referencedColumns: List<Column<*>> = left.referencedColumns + right.referencedColumns
    override val type: ColumnType<*> get() = left.type
    override val storageType: ColumnType<*> get() = left.storageType
    override val isNullable: Boolean get() = left.isNullable || right.isNullable

    override fun toStorageValue(value: T): Any? = left.toStorageValue(value)

    override fun fromStorageValue(value: Any?): T = left.fromStorageValue(value)
}

/** Turns a typed column into a generated-column expression. */
fun <T> Column<T>.asGeneratedColumnExpression(): GeneratedColumnExpression<T> = this

operator fun <T : Number?> Column<T>.plus(other: Column<T>): GeneratedColumnExpression<T> =
    asGeneratedColumnExpression() + other.asGeneratedColumnExpression()

operator fun <T : Number?> Column<T>.minus(other: Column<T>): GeneratedColumnExpression<T> =
    asGeneratedColumnExpression() - other.asGeneratedColumnExpression()

operator fun <T : Number?> Column<T>.times(other: Column<T>): GeneratedColumnExpression<T> =
    asGeneratedColumnExpression() * other.asGeneratedColumnExpression()

operator fun <T : Number?> Column<T>.div(other: Column<T>): GeneratedColumnExpression<T> =
    asGeneratedColumnExpression() / other.asGeneratedColumnExpression()

operator fun <T : Number?> GeneratedColumnExpression<T>.plus(
    other: GeneratedColumnExpression<T>,
): GeneratedColumnExpression<T> = GeneratedColumnBinaryExpression(this, GeneratedColumnBinaryOperator.Add, other)

operator fun <T : Number?> GeneratedColumnExpression<T>.minus(
    other: GeneratedColumnExpression<T>,
): GeneratedColumnExpression<T> = GeneratedColumnBinaryExpression(this, GeneratedColumnBinaryOperator.Subtract, other)

operator fun <T : Number?> GeneratedColumnExpression<T>.times(
    other: GeneratedColumnExpression<T>,
): GeneratedColumnExpression<T> = GeneratedColumnBinaryExpression(this, GeneratedColumnBinaryOperator.Multiply, other)

operator fun <T : Number?> GeneratedColumnExpression<T>.div(
    other: GeneratedColumnExpression<T>,
): GeneratedColumnExpression<T> = GeneratedColumnBinaryExpression(this, GeneratedColumnBinaryOperator.Divide, other)

/**
 * Makes this column a physical generated column. The expression is schema data, never raw SQL,
 * and its result type must match the Kotlin type of this column.
 */
fun <T> WritableColumn<T>.generated(
    expression: GeneratedColumnExpression<T>,
    storage: GeneratedColumnStorage,
): GeneratedColumn<T> {
    require(definition.identity == null) { "Identity column cannot also be a generated column: $this" }
    require(definition.defaultExpression == null) { "DEFAULT column cannot also be a generated column: $this" }
    require(expression.referencedColumns.isNotEmpty()) { "Generated column expression must reference a column: $this" }
    expression.referencedColumns.forEach { referenced ->
        require(referenced.table === table && table.hasColumn(referenced)) {
            "Generated column expression must reference current columns of $table: $referenced"
        }
    }
    val replacement = GeneratedColumn<T>(
        definition.copy(generatedExpression = expression, generatedColumnStorage = storage),
    )
    table.replaceColumn(this, replacement)
    return replacement
}
