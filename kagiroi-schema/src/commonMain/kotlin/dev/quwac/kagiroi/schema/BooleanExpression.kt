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

/** SQL comparison operators used by typed Boolean expressions.
 *
 * @property sql SQL operator token.
 */
enum class ComparisonOperator(val sql: String) {
    /** Equality comparison. */
    EQUALS("="),

    /** Inequality comparison. */
    NOT_EQUALS("!="),

    /** Strict less-than comparison. */
    LESS_THAN("<"),

    /** Less-than-or-equal comparison. */
    LESS_THAN_OR_EQUALS("<="),

    /** Strict greater-than comparison. */
    GREATER_THAN(">"),

    /** Greater-than-or-equal comparison. */
    GREATER_THAN_OR_EQUALS(">="),
}

/** A typed comparison against a non-null Kotlin value.
 *
 * @property column column compared to [value].
 * @property operator comparison applied to [column] and [value].
 * @property value non-null value bound to the comparison.
 */
class ValueComparison<T> constructor(val column: Column<T>, val operator: ComparisonOperator, val value: T) :
    BooleanExpression {
    override val referencedColumns: List<Column<*>> = listOf(column)

    /** Encodes the comparison value using the column's storage mapping. */
    @Suppress("UNCHECKED_CAST") // The expression preserves the column/value type relationship.
    fun storageValue(): Any? = (column as Column<Any?>).toStorageValue(value)
}

/** A comparison between an expression and a non-null Kotlin value.
 *
 * @property expression expression being compared.
 * @property operator comparison applied to [expression] and [value].
 * @property value non-null value bound to the comparison.
 */
class ExpressionValueComparison<T> constructor(
    val expression: SqlExpression<T>,
    val operator: ComparisonOperator,
    val value: T,
) : BooleanExpression {
    override val referencedColumns: List<Column<*>> = expression.referencedColumns

    /** Encodes [value] using the expression's storage mapping. */
    fun storageValue(): Any? = expression.toStorageValue(value)
}

/** A comparison between two expressions with the same logical Kotlin type.
 *
 * @property left left-hand expression.
 * @property operator comparison applied to both expressions.
 * @property right right-hand expression.
 */
class ExpressionComparison<T> constructor(
    val left: SqlExpression<T>,
    val operator: ComparisonOperator,
    val right: SqlExpression<T>,
) : BooleanExpression {
    override val referencedColumns: List<Column<*>> = left.referencedColumns + right.referencedColumns
}

/** SQL pattern-matching operators for string expressions.
 *
 * @property sql SQL operator token.
 */
enum class LikeOperator(val sql: String) {
    /** Case-sensitive pattern match. */
    LIKE("LIKE"),

    /** Negated case-sensitive pattern match. */
    NOT_LIKE("NOT LIKE"),

    /** Case-insensitive pattern match where supported by the dialect. */
    ILIKE("ILIKE"),

    /** Negated case-insensitive pattern match where supported by the dialect. */
    NOT_ILIKE("NOT ILIKE"),
}

/** A parameterized LIKE comparison with a non-null Kotlin String pattern.
 *
 * @property expression string expression being matched.
 * @property operator pattern-matching operation.
 * @property pattern non-null pattern bound as a SQL parameter.
 */
class LikeValueComparison<T : String?> constructor(
    val expression: SqlExpression<T>,
    val operator: LikeOperator,
    val pattern: String,
) : BooleanExpression {
    override val referencedColumns: List<Column<*>> = expression.referencedColumns
}

/** A LIKE comparison whose non-null pattern is another typed String expression.
 *
 * @property expression string expression being matched.
 * @property operator pattern-matching operation.
 * @property pattern typed expression that supplies the pattern.
 */
class LikeExpressionComparison<T : String?> constructor(
    val expression: SqlExpression<T>,
    val operator: LikeOperator,
    val pattern: SqlExpression<String>,
) : BooleanExpression {
    override val referencedColumns: List<Column<*>> = expression.referencedColumns + pattern.referencedColumns
}

/** A null-safe comparison between two expressions with the same Kotlin type.
 *
 * @property left left-hand expression.
 * @property distinct whether to test for distinctness rather than equality.
 * @property right right-hand expression.
 */
class NullSafeComparison<T> constructor(
    val left: SqlExpression<T>,
    val distinct: Boolean,
    val right: SqlExpression<T>,
) : BooleanExpression {
    override val referencedColumns: List<Column<*>> = left.referencedColumns + right.referencedColumns
}

/** A null-safe comparison between an expression and a non-null Kotlin value.
 *
 * @property expression expression being compared.
 * @property distinct whether to test for distinctness rather than equality.
 * @property value non-null value bound to the comparison.
 */
class NullSafeValueComparison<T> constructor(
    val expression: SqlExpression<T>,
    val distinct: Boolean,
    val value: T & Any,
) : BooleanExpression {
    override val referencedColumns: List<Column<*>> = expression.referencedColumns

    /** Encodes [value] using the expression's storage mapping. */
    fun storageValue(): Any? = expression.toStorageValue(value)
}

/** SQL null tests supported by nullable columns and expressions.
 *
 * @property sql SQL operator token.
 */
enum class NullOperator(val sql: String) {
    /** Tests whether a value is SQL `NULL`. */
    IS_NULL("IS NULL"),

    /** Tests whether a value is not SQL `NULL`. */
    IS_NOT_NULL("IS NOT NULL"),
}

/** A SQL null test on a column.
 *
 * @property column column whose nullness is tested.
 * @property operator null test applied to the column.
 */
class NullComparison constructor(val column: Column<*>, val operator: NullOperator) : BooleanExpression {
    override val referencedColumns: List<Column<*>> = listOf(column)
}

/** A NULL comparison for a nullable function, literal, or arithmetic expression.
 *
 * @property expression expression whose nullness is tested.
 * @property operator null test applied to the expression.
 */
class ExpressionNullComparison constructor(val expression: SqlExpression<*>, val operator: NullOperator) :
    BooleanExpression {
    override val referencedColumns: List<Column<*>> = expression.referencedColumns
}

/** Logical operators that combine two SQL Boolean expressions.
 *
 * @property sql SQL operator token.
 */
enum class LogicalOperator(val sql: String) {
    /** SQL conjunction. */
    AND("AND"),

    /** SQL disjunction. */
    OR("OR"),
}

/** A logical combination of two Boolean expressions.
 *
 * @property left first Boolean expression.
 * @property operator logical operation applied to the expressions.
 * @property right second Boolean expression.
 */
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
    /** Tests whether this column equals [value]. */
    infix fun <T> Column<T>.eq(value: T & Any): BooleanExpression = comparison(ComparisonOperator.EQUALS, value)

    /** Tests whether this column differs from [value]. */
    infix fun <T> Column<T>.neq(value: T & Any): BooleanExpression = comparison(ComparisonOperator.NOT_EQUALS, value)

    /** Tests whether this column is less than [value]. */
    infix fun <T> Column<T>.lt(value: T & Any): BooleanExpression = comparison(ComparisonOperator.LESS_THAN, value)

    /** Tests whether this column is less than or equal to [value]. */
    infix fun <T> Column<T>.lte(value: T & Any): BooleanExpression =
        comparison(ComparisonOperator.LESS_THAN_OR_EQUALS, value)

    /** Tests whether this column is greater than [value]. */
    infix fun <T> Column<T>.gt(value: T & Any): BooleanExpression = comparison(ComparisonOperator.GREATER_THAN, value)

    /** Tests whether this column is greater than or equal to [value]. */
    infix fun <T> Column<T>.gte(value: T & Any): BooleanExpression =
        comparison(ComparisonOperator.GREATER_THAN_OR_EQUALS, value)

    /** Tests whether this expression equals [value]. */
    infix fun <T> SqlExpression<T>.eq(value: T & Any): BooleanExpression =
        ExpressionValueComparison(this, ComparisonOperator.EQUALS, value)

    /** Tests whether this expression differs from [value]. */
    infix fun <T> SqlExpression<T>.neq(value: T & Any): BooleanExpression =
        ExpressionValueComparison(this, ComparisonOperator.NOT_EQUALS, value)

    /** Tests whether this expression is less than [value]. */
    infix fun <T> SqlExpression<T>.lt(value: T & Any): BooleanExpression =
        ExpressionValueComparison(this, ComparisonOperator.LESS_THAN, value)

    /** Tests whether this expression is less than or equal to [value]. */
    infix fun <T> SqlExpression<T>.lte(value: T & Any): BooleanExpression =
        ExpressionValueComparison(this, ComparisonOperator.LESS_THAN_OR_EQUALS, value)

    /** Tests whether this expression is greater than [value]. */
    infix fun <T> SqlExpression<T>.gt(value: T & Any): BooleanExpression =
        ExpressionValueComparison(this, ComparisonOperator.GREATER_THAN, value)

    /** Tests whether this expression is greater than or equal to [value]. */
    infix fun <T> SqlExpression<T>.gte(value: T & Any): BooleanExpression =
        ExpressionValueComparison(this, ComparisonOperator.GREATER_THAN_OR_EQUALS, value)

    /** Tests this expression against [other] using SQL equality. */
    infix fun <T> SqlExpression<T>.eq(other: SqlExpression<T>): BooleanExpression =
        ExpressionComparison(this, ComparisonOperator.EQUALS, other)

    /** Applies SQL `LIKE` with a bound string pattern. */
    infix fun <T : String?> SqlExpression<T>.like(pattern: String): BooleanExpression =
        LikeValueComparison(this, LikeOperator.LIKE, pattern)

    /** Applies SQL `NOT LIKE` with a bound string pattern. */
    infix fun <T : String?> SqlExpression<T>.notLike(pattern: String): BooleanExpression =
        LikeValueComparison(this, LikeOperator.NOT_LIKE, pattern)
}

/** Tests whether this nullable column contains SQL `NULL`. */
fun <T : Any> Column<T?>.isNull(): BooleanExpression = NullComparison(this, NullOperator.IS_NULL)

/** Tests whether this nullable column contains a non-null value. */
fun <T : Any> Column<T?>.isNotNull(): BooleanExpression = NullComparison(this, NullOperator.IS_NOT_NULL)

/** Tests whether this nullable expression evaluates to SQL `NULL`. */
fun <T : Any> SqlExpression<T?>.isNull(): BooleanExpression = ExpressionNullComparison(this, NullOperator.IS_NULL)

/** Tests whether this nullable expression evaluates to a non-null value. */
fun <T : Any> SqlExpression<T?>.isNotNull(): BooleanExpression =
    ExpressionNullComparison(this, NullOperator.IS_NOT_NULL)

/** Combines this Boolean expression with [other] using SQL `AND`. */
infix fun BooleanExpression.and(other: BooleanExpression): BooleanExpression =
    LogicalPredicate(this, LogicalOperator.AND, other)

/** Combines this Boolean expression with [other] using SQL `OR`. */
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
