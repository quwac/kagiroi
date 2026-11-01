@file:Suppress("TooManyFunctions") // The public typed comparison grammar deliberately exposes infix overloads.

package dev.quwac.kagiroi.schema

/** A typed three-valued SQL Boolean expression reusable by queries and schema CHECK constraints. */
interface BooleanExpression : SqlExpression<Boolean?> {
    override val type: ColumnType<*> get() = BooleanColumnType
    override val storageType: ColumnType<*> get() = BooleanColumnType
    override val isNullable: Boolean get() = true

    override fun toStorageValue(value: Boolean?): Any? = value

    override fun fromStorageValue(value: Any?): Boolean? = value as Boolean?
}

enum class ComparisonOperator(val sql: String) {
    EQUALS("="),
    NOT_EQUALS("!="),
    LESS_THAN("<"),
    LESS_THAN_OR_EQUALS("<="),
    GREATER_THAN(">"),
    GREATER_THAN_OR_EQUALS(">="),
}

/** A typed comparison against a non-null Kotlin value. */
class ValueComparison<T> constructor(val column: Column<T>, val operator: ComparisonOperator, val value: T) :
    BooleanExpression {
    override val referencedColumns: List<Column<*>> = listOf(column)

    @Suppress("UNCHECKED_CAST") // The expression preserves the column/value type relationship.
    fun storageValue(): Any? = (column as Column<Any?>).toStorageValue(value)
}

/** A comparison between an expression and a non-null Kotlin value. */
class ExpressionValueComparison<T> constructor(
    val expression: SqlExpression<T>,
    val operator: ComparisonOperator,
    val value: T,
) : BooleanExpression {
    override val referencedColumns: List<Column<*>> = expression.referencedColumns

    fun storageValue(): Any? = expression.toStorageValue(value)
}

/** A comparison between two expressions with the same logical Kotlin type. */
class ExpressionComparison<T> constructor(
    val left: SqlExpression<T>,
    val operator: ComparisonOperator,
    val right: SqlExpression<T>,
) : BooleanExpression {
    override val referencedColumns: List<Column<*>> = left.referencedColumns + right.referencedColumns
}

enum class LikeOperator(val sql: String) {
    LIKE("LIKE"),
    NOT_LIKE("NOT LIKE"),
    ILIKE("ILIKE"),
    NOT_ILIKE("NOT ILIKE"),
}

/** A parameterized LIKE comparison with a non-null Kotlin String pattern. */
class LikeValueComparison<T : String?> constructor(
    val expression: SqlExpression<T>,
    val operator: LikeOperator,
    val pattern: String,
) : BooleanExpression {
    override val referencedColumns: List<Column<*>> = expression.referencedColumns
}

/** A LIKE comparison whose non-null pattern is another typed String expression. */
class LikeExpressionComparison<T : String?> constructor(
    val expression: SqlExpression<T>,
    val operator: LikeOperator,
    val pattern: SqlExpression<String>,
) : BooleanExpression {
    override val referencedColumns: List<Column<*>> = expression.referencedColumns + pattern.referencedColumns
}

/** A null-safe comparison between two expressions with the same Kotlin type. */
class NullSafeComparison<T> constructor(
    val left: SqlExpression<T>,
    val distinct: Boolean,
    val right: SqlExpression<T>,
) : BooleanExpression {
    override val referencedColumns: List<Column<*>> = left.referencedColumns + right.referencedColumns
}

/** A null-safe comparison between an expression and a non-null Kotlin value. */
class NullSafeValueComparison<T> constructor(
    val expression: SqlExpression<T>,
    val distinct: Boolean,
    val value: T & Any,
) : BooleanExpression {
    override val referencedColumns: List<Column<*>> = expression.referencedColumns

    fun storageValue(): Any? = expression.toStorageValue(value)
}

enum class NullOperator(val sql: String) {
    IS_NULL("IS NULL"),
    IS_NOT_NULL("IS NOT NULL"),
}

class NullComparison constructor(val column: Column<*>, val operator: NullOperator) : BooleanExpression {
    override val referencedColumns: List<Column<*>> = listOf(column)
}

/** A NULL comparison for a nullable function, literal, or arithmetic expression. */
class ExpressionNullComparison constructor(val expression: SqlExpression<*>, val operator: NullOperator) :
    BooleanExpression {
    override val referencedColumns: List<Column<*>> = expression.referencedColumns
}

enum class LogicalOperator(val sql: String) {
    AND("AND"),
    OR("OR"),
}

class LogicalPredicate constructor(
    val left: BooleanExpression,
    val operator: LogicalOperator,
    val right: BooleanExpression,
) : BooleanExpression {
    override val referencedColumns: List<Column<*>> = left.referencedColumns + right.referencedColumns
}

/** Rejects `OVER` expressions before a CHECK constraint can be stored as schema metadata. */
@Suppress("CyclomaticComplexMethod") // Exhaustive traversal over the closed Boolean expression AST.
internal fun BooleanExpression.containsWindowExpression(): Boolean = when (this) {
    is ValueComparison<*> -> false
    is ExpressionValueComparison<*> -> expression.containsWindowExpression()
    is ExpressionComparison<*> -> left.containsWindowExpression() || right.containsWindowExpression()
    is LikeValueComparison<*> -> expression.containsWindowExpression()
    is LikeExpressionComparison<*> -> expression.containsWindowExpression() || pattern.containsWindowExpression()
    is NullSafeComparison<*> -> left.containsWindowExpression() || right.containsWindowExpression()
    is NullSafeValueComparison<*> -> expression.containsWindowExpression()
    is NullComparison -> false
    is ExpressionNullComparison -> expression.containsWindowExpression()
    is LogicalPredicate -> left.containsWindowExpression() || right.containsWindowExpression()
    else -> false
}

/** Rejects aggregate expressions from row-local schema constraints without inspecting SQL text. */
@Suppress("CyclomaticComplexMethod") // Exhaustive traversal over the closed Boolean expression AST.
internal fun BooleanExpression.containsAggregateForSchemaConstraint(): Boolean = when (this) {
    is ValueComparison<*>, is NullComparison -> false

    is ExpressionValueComparison<*> -> expression.containsAggregateForSchemaConstraint()

    is ExpressionComparison<*> ->
        left.containsAggregateForSchemaConstraint() || right.containsAggregateForSchemaConstraint()

    is LikeValueComparison<*> -> expression.containsAggregateForSchemaConstraint()

    is LikeExpressionComparison<*> ->
        expression.containsAggregateForSchemaConstraint() || pattern.containsAggregateForSchemaConstraint()

    is NullSafeComparison<*> ->
        left.containsAggregateForSchemaConstraint() || right.containsAggregateForSchemaConstraint()

    is NullSafeValueComparison<*> -> expression.containsAggregateForSchemaConstraint()

    is ExpressionNullComparison -> expression.containsAggregateForSchemaConstraint()

    is LogicalPredicate ->
        left.containsAggregateForSchemaConstraint() || right.containsAggregateForSchemaConstraint()

    else -> false
}

@Suppress("CyclomaticComplexMethod") // Exhaustive expression-tree traversal over the closed AST.
private fun SqlExpression<*>.containsWindowExpression(): Boolean = when (this) {
    is WindowedSqlExpression<*> -> true

    is WindowFunctionExpression<*> -> true

    is ValueWindowFunctionExpression<*> -> true

    is SqlExpressionAlias<*> -> expression.containsWindowExpression()

    is SqlFunctionExpression<*> -> arguments.any(SqlExpression<*>::containsWindowExpression)

    is CastExpression<*> -> expression.containsWindowExpression()

    is CaseExpression<*> -> branches.any { branch ->
        branch.condition.containsWindowExpression() || branch.result.containsWindowExpression()
    } || elseResult.containsWindowExpression()

    is GeneratedColumnBinaryExpression<*> ->
        left.containsWindowExpression() || right.containsWindowExpression()

    is NumericBinaryExpression<*> -> left.containsWindowExpression() || right.containsWindowExpression()

    else -> false
}

private fun SqlExpression<*>.containsAggregateForSchemaConstraint(): Boolean = when (this) {
    is AggregateSqlExpression<*> -> true

    is SqlExpressionAlias<*> -> expression.containsAggregateForSchemaConstraint()

    is SqlFunctionExpression<*> -> arguments.any(SqlExpression<*>::containsAggregateForSchemaConstraint)

    is CastExpression<*> -> expression.containsAggregateForSchemaConstraint()

    is CaseExpression<*> -> branches.any { branch ->
        branch.condition.containsAggregateForSchemaConstraint() || branch.result.containsAggregateForSchemaConstraint()
    } || elseResult.containsAggregateForSchemaConstraint()

    is GeneratedColumnBinaryExpression<*> ->
        left.containsAggregateForSchemaConstraint() || right.containsAggregateForSchemaConstraint()

    is NumericBinaryExpression<*> ->
        left.containsAggregateForSchemaConstraint() || right.containsAggregateForSchemaConstraint()

    else -> false
}

/** Receiver that provides compact, typed comparisons within a table CHECK constraint declaration. */
@Suppress("TooManyFunctions") // CHECK accepts the same complete typed comparison grammar as queries.
class CheckConstraintScope internal constructor() {
    infix fun <T> Column<T>.eq(value: T & Any): BooleanExpression = comparison(ComparisonOperator.EQUALS, value)

    infix fun <T> Column<T>.neq(value: T & Any): BooleanExpression = comparison(ComparisonOperator.NOT_EQUALS, value)

    infix fun <T> Column<T>.lt(value: T & Any): BooleanExpression = comparison(ComparisonOperator.LESS_THAN, value)

    infix fun <T> Column<T>.lte(value: T & Any): BooleanExpression =
        comparison(ComparisonOperator.LESS_THAN_OR_EQUALS, value)

    infix fun <T> Column<T>.gt(value: T & Any): BooleanExpression = comparison(ComparisonOperator.GREATER_THAN, value)

    infix fun <T> Column<T>.gte(value: T & Any): BooleanExpression =
        comparison(ComparisonOperator.GREATER_THAN_OR_EQUALS, value)

    infix fun <T> SqlExpression<T>.eq(value: T & Any): BooleanExpression =
        ExpressionValueComparison(this, ComparisonOperator.EQUALS, value)

    infix fun <T> SqlExpression<T>.neq(value: T & Any): BooleanExpression =
        ExpressionValueComparison(this, ComparisonOperator.NOT_EQUALS, value)

    infix fun <T> SqlExpression<T>.lt(value: T & Any): BooleanExpression =
        ExpressionValueComparison(this, ComparisonOperator.LESS_THAN, value)

    infix fun <T> SqlExpression<T>.lte(value: T & Any): BooleanExpression =
        ExpressionValueComparison(this, ComparisonOperator.LESS_THAN_OR_EQUALS, value)

    infix fun <T> SqlExpression<T>.gt(value: T & Any): BooleanExpression =
        ExpressionValueComparison(this, ComparisonOperator.GREATER_THAN, value)

    infix fun <T> SqlExpression<T>.gte(value: T & Any): BooleanExpression =
        ExpressionValueComparison(this, ComparisonOperator.GREATER_THAN_OR_EQUALS, value)

    infix fun <T> SqlExpression<T>.eq(other: SqlExpression<T>): BooleanExpression =
        ExpressionComparison(this, ComparisonOperator.EQUALS, other)

    infix fun <T : String?> SqlExpression<T>.like(pattern: String): BooleanExpression =
        LikeValueComparison(this, LikeOperator.LIKE, pattern)

    infix fun <T : String?> SqlExpression<T>.notLike(pattern: String): BooleanExpression =
        LikeValueComparison(this, LikeOperator.NOT_LIKE, pattern)
}

fun <T : Any> Column<T?>.isNull(): BooleanExpression = NullComparison(this, NullOperator.IS_NULL)

fun <T : Any> Column<T?>.isNotNull(): BooleanExpression = NullComparison(this, NullOperator.IS_NOT_NULL)

fun <T : Any> SqlExpression<T?>.isNull(): BooleanExpression = ExpressionNullComparison(this, NullOperator.IS_NULL)

fun <T : Any> SqlExpression<T?>.isNotNull(): BooleanExpression =
    ExpressionNullComparison(this, NullOperator.IS_NOT_NULL)

infix fun BooleanExpression.and(other: BooleanExpression): BooleanExpression =
    LogicalPredicate(this, LogicalOperator.AND, other)

infix fun BooleanExpression.or(other: BooleanExpression): BooleanExpression =
    LogicalPredicate(this, LogicalOperator.OR, other)

/** Compares this string expression to a bound SQL LIKE pattern. */
infix fun <T : String?> SqlExpression<T>.like(pattern: String): BooleanExpression =
    LikeValueComparison(this, LikeOperator.LIKE, pattern)

/** Compares this string expression to a bound SQL NOT LIKE pattern. */
infix fun <T : String?> SqlExpression<T>.notLike(pattern: String): BooleanExpression =
    LikeValueComparison(this, LikeOperator.NOT_LIKE, pattern)

/** Compares this string expression to a typed non-null SQL LIKE pattern expression. */
infix fun <T : String?> SqlExpression<T>.like(pattern: SqlExpression<String>): BooleanExpression =
    LikeExpressionComparison(this, LikeOperator.LIKE, pattern)

/** Compares this string expression to a typed non-null SQL NOT LIKE pattern expression. */
infix fun <T : String?> SqlExpression<T>.notLike(pattern: SqlExpression<String>): BooleanExpression =
    LikeExpressionComparison(this, LikeOperator.NOT_LIKE, pattern)

private fun <T> Column<T>.comparison(operator: ComparisonOperator, value: T & Any): BooleanExpression =
    ValueComparison(this, operator, value)
