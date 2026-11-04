@file:Suppress("TooManyFunctions") // The public typed scalar and aggregate catalog needs Kotlin overloads.

package dev.quwac.kagiroi.schema

import kotlin.js.JsName

/** One item accepted by a typed SQL `GROUP BY` clause. */
interface GroupByElement

/** One expression or composite expression item within a grouping construct such as `ROLLUP`. */
interface GroupingItem : GroupByElement

/**
 * A typed SQL value expression.
 *
 * Expressions retain the physical result mapping needed by database drivers, so a projection can
 * be decoded without turning its alias or function name into an untyped string lookup.
 */
interface SqlExpression<T> : GroupingItem {
    /** Columns referenced by this expression, in expression-tree order. */
    val referencedColumns: List<Column<*>>

    /** Logical Kotlin type exposed by the expression. */
    val type: ColumnType<*>

    /** Physical value type consumed and produced by a database driver. */
    val storageType: ColumnType<*>

    /** Whether SQL can produce NULL for this expression. */
    val isNullable: Boolean

    /** Converts a Kotlin value used as a comparison parameter to its driver value. */
    fun toStorageValue(value: T): Any?

    /** Converts a driver value selected for this expression to its Kotlin value. */
    fun fromStorageValue(value: Any?): T
}

/**
 * A function call that may be completed with an SQL `OVER` clause.
 *
 * This deliberately marks only ordinary aggregate calls and functions whose syntax requires
 * `OVER`. Scalar expressions remain outside this capability, so calls such as `lower(name).over`
 * are rejected by the Kotlin type system.
 */
sealed interface WindowCapableExpression<T> : SqlExpression<T>

/** A typed expression whose SQL rendering contains an `OVER (...)` clause. */
interface WindowedSqlExpression<T> : SqlExpression<T>

/** A typed aggregate result that is not valid in row-local schema expression contexts. */
interface AggregateSqlExpression<T> : SqlExpression<T>

/**
 * A logical result mapping that can be selected as the target of a SQL `CAST` expression.
 *
 * This is deliberately a value mapping, not a DDL declaration. A dialect chooses the physical
 * SQL CAST target from [type] and [storageType], while the mapping retains the decoder used when
 * the cast result is projected. Use [castTarget] for an application-specific mapped column type.
 *
 * @property type logical Kotlin value type returned by the cast.
 * @property storageType physical storage mapping used to decode the result.
 */
class SqlCastTarget<T> internal constructor(internal val result: SqlExpression<T>) {
    val type: ColumnType<*> get() = result.type

    val storageType: ColumnType<*> get() = result.storageType
}

/** A typed SQL `CAST` expression. The target SQL spelling belongs to the selected dialect.
 *
 * @property expression expression converted by the cast.
 * @property target logical result mapping that selects the dialect's SQL target.
 */
class CastExpression<T> internal constructor(
    val expression: SqlExpression<*>,
    val target: SqlCastTarget<*>,
    private val result: SqlExpression<T>,
    override val isNullable: Boolean,
) : SqlExpression<T>,
    GeneratedColumnExpression<T> {
    override val referencedColumns: List<Column<*>> = expression.referencedColumns
    override val type: ColumnType<*> get() = result.type
    override val storageType: ColumnType<*> get() = result.storageType

    override fun toStorageValue(value: T): Any? = result.toStorageValue(value)

    override fun fromStorageValue(value: Any?): T = result.fromStorageValue(value)
}

/** One searched-CASE branch. Its result mapping is validated by the enclosing builder.
 *
 * @property condition Boolean condition that selects this branch.
 * @property result expression returned when [condition] is true.
 */
class CaseWhen internal constructor(val condition: BooleanExpression, val result: SqlExpression<*>)

/** A typed searched SQL `CASE` expression.
 *
 * @property branches ordered `WHEN` branches.
 * @property elseResult expression returned when no branch condition is true.
 */
class CaseExpression<T> internal constructor(
    val branches: List<CaseWhen>,
    val elseResult: SqlExpression<*>,
    private val result: SqlExpression<T>,
) : SqlExpression<T>,
    GeneratedColumnExpression<T> {
    /**
     * Reuses the CASE result mapping when a surrounding SQL construct must make the physical
     * type explicit (for example an aggregate argument with otherwise-untyped parameters).
     */
    val target: SqlCastTarget<T> = SqlCastTarget(result)

    override val referencedColumns: List<Column<*>> =
        branches.flatMap { it.condition.referencedColumns + it.result.referencedColumns } + elseResult.referencedColumns
    override val type: ColumnType<*> get() = result.type
    override val storageType: ColumnType<*> get() = result.storageType
    override val isNullable: Boolean get() = branches.any { it.result.isNullable } || elseResult.isNullable

    override fun toStorageValue(value: T): Any? = if (value == null) null else result.toStorageValue(value)

    @Suppress("UNCHECKED_CAST") // SQL CASE can produce NULL only for a nullable Kotlin result type.
    override fun fromStorageValue(value: Any?): T = if (value == null) {
        require(isNullable) { "Non-null CASE expression cannot decode null" }
        null as T
    } else {
        result.fromStorageValue(value)
    }
}

/** Mutable only while a [case] expression is being constructed; the resulting AST is immutable. */
class SearchedCaseBuilder<T> internal constructor() {
    private val branches = mutableListOf<CaseWhen>()
    private var mapping: SqlExpression<T>? = null
    private var otherwise: SqlExpression<*>? = null

    /** Adds a Boolean searched-CASE condition and a same-mapped result expression. */
    fun whenThen(condition: BooleanExpression, result: SqlExpression<T>) {
        register(result)
        branches += CaseWhen(condition, result)
    }

    /** Supplies the required ELSE result with the same logical and storage mapping as every THEN. */
    fun elseValue(result: SqlExpression<T>) {
        check(otherwise == null) { "CASE already has an ELSE result" }
        register(result)
        otherwise = result
    }

    internal fun whenThenCompatible(condition: BooleanExpression, result: SqlExpression<*>) {
        registerCompatible(result)
        branches += CaseWhen(condition, result)
    }

    internal fun elseValueCompatible(result: SqlExpression<*>) {
        check(otherwise == null) { "CASE already has an ELSE result" }
        registerCompatible(result)
        otherwise = result
    }

    internal fun build(): CaseExpression<T> {
        require(branches.isNotEmpty()) { "CASE requires at least one WHEN branch" }
        val elseResult = requireNotNull(otherwise) { "CASE requires an ELSE result" }
        return CaseExpression(branches.toList(), elseResult, requireNotNull(mapping))
    }

    private fun register(expression: SqlExpression<T>) {
        registerCompatible(expression)
        if (mapping == null) mapping = expression
    }

    private fun registerCompatible(expression: SqlExpression<*>) {
        val existing = mapping
        if (existing == null) {
            @Suppress("UNCHECKED_CAST")
            val mapped = expression as SqlExpression<T>
            // Nullable CASE lifts only the Kotlin nullability of an otherwise identical mapping.
            mapping = mapped
            return
        }
        require(existing.type === expression.type && existing.storageType === expression.storageType) {
            "CASE results must use one logical and storage mapping"
        }
    }
}

/** Builds a typed searched SQL CASE expression. ELSE is mandatory so the Kotlin nullability is precise. */
fun <T> case(build: SearchedCaseBuilder<T>.() -> Unit): CaseExpression<T> =
    SearchedCaseBuilder<T>().apply(build).build()

/**
 * A typed SQL literal. Renderers bind it as a parameter in DML and queries; DDL renderers may
 * render the value with their dialect-owned literal syntax.
 *
 * @property value literal value represented by this expression.
 */
class SqlLiteral<T> internal constructor(val value: T, private val result: SqlExpression<T>) : SqlExpression<T> {
    override val referencedColumns: List<Column<*>> = emptyList()
    override val type: ColumnType<*> get() = result.type
    override val storageType: ColumnType<*> get() = result.storageType
    override val isNullable: Boolean get() = value == null

    override fun toStorageValue(value: T): Any? = result.toStorageValue(value)

    override fun fromStorageValue(value: Any?): T = result.fromStorageValue(value)
}

/** Builds a literal using an existing expression solely as its type and driver-mapping template. */
fun <T> literal(value: T, result: SqlExpression<T>): SqlLiteral<T> = SqlLiteral(value, result)

/** Builds a String literal expression. */
fun literal(value: String): SqlLiteral<String> = SqlLiteral(value, stringExpression(nullable = false))

/** Builds a Boolean literal expression. */
fun literal(value: Boolean): SqlLiteral<Boolean> = SqlLiteral(value, booleanExpression(nullable = false))

/** Builds a Byte literal expression. */
fun literal(value: Byte): SqlLiteral<Byte> = SqlLiteral(value, byteExpression(nullable = false))

/** Builds a Short literal expression. */
fun literal(value: Short): SqlLiteral<Short> = SqlLiteral(value, shortExpression(nullable = false))

/** Builds an Int literal expression. */
fun literal(value: Int): SqlLiteral<Int> = SqlLiteral(value, intExpression(nullable = false))

/** Builds a Long literal expression. */
fun literal(value: Long): SqlLiteral<Long> = SqlLiteral(value, longExpression(nullable = false))

/** Builds a Float literal expression. */
fun literal(value: Float): SqlLiteral<Float> = SqlLiteral(value, floatExpression(nullable = false))

/** Builds a Double literal expression. */
fun literal(value: Double): SqlLiteral<Double> = SqlLiteral(value, doubleExpression(nullable = false))

/** Built-in function kinds. Function names remain dialect rendering decisions. */
sealed interface SqlFunction {
    /** Converts a string expression to lowercase. */
    data object Lower : SqlFunction

    /** Converts a string expression to uppercase. */
    data object Upper : SqlFunction

    /** Returns the length of a string expression. */
    data object Length : SqlFunction

    /** Removes leading and trailing whitespace from a string expression. */
    data object Trim : SqlFunction

    /** Removes leading whitespace from a string expression. */
    data object LeftTrim : SqlFunction

    /** Removes trailing whitespace from a string expression. */
    data object RightTrim : SqlFunction

    /** Returns a substring using the arguments defined by the dialect. */
    data object Substring : SqlFunction

    /** Replaces matching text in a string expression. */
    data object Replace : SqlFunction

    /** Concatenates string expressions. */
    data object Concat : SqlFunction

    /** Returns the first non-null expression. */
    data object Coalesce : SqlFunction

    /** Returns `NULL` when its two arguments are equal. */
    data object NullIf : SqlFunction

    /** Returns the absolute value of a numeric expression. */
    data object Abs : SqlFunction

    /** Rounds a numeric expression. */
    data object Round : SqlFunction

    /** Returns the floor of a numeric expression. */
    data object Floor : SqlFunction

    /** Returns the ceiling of a numeric expression. */
    data object Ceiling : SqlFunction

    /** Returns the square root of a numeric expression. */
    data object SquareRoot : SqlFunction

    /** Raises one numeric expression to the power of another. */
    data object Power : SqlFunction

    /** Returns the current SQL date. */
    data object CurrentDate : SqlFunction

    /** Returns the current SQL time. */
    data object CurrentTime : SqlFunction

    /** Returns the current SQL timestamp. */
    data object CurrentTimestamp : SqlFunction

    /** Extracts the year field from a temporal expression. */
    data object Year : SqlFunction

    /** Extracts the month field from a temporal expression. */
    data object Month : SqlFunction

    /** Extracts the day field from a temporal expression. */
    data object Day : SqlFunction

    /** Extracts the hour field from a temporal expression. */
    data object Hour : SqlFunction

    /** Extracts the minute field from a temporal expression. */
    data object Minute : SqlFunction

    /** Extracts the second field from a temporal expression. */
    data object Second : SqlFunction

    /** Counts rows or non-null argument values. */
    data object Count : SqlFunction

    /** Returns the sum of non-null argument values. */
    data object Sum : SqlFunction

    /** Returns the average of non-null argument values. */
    data object Average : SqlFunction

    /** Returns the minimum argument value. */
    data object Minimum : SqlFunction

    /** Returns the maximum argument value. */
    data object Maximum : SqlFunction

    /** Distinguishes a source NULL from a NULL synthesized by a grouping extension. */
    data object Grouping : SqlFunction

    /** Returns a row's number in the ordered partition. */
    data object RowNumber : SqlFunction

    /** Returns the rank of a row in the ordered partition. */
    data object Rank : SqlFunction

    /** Returns the rank without gaps between peer groups. */
    data object DenseRank : SqlFunction

    /** Returns the relative rank of a row as a fraction. */
    data object PercentRank : SqlFunction

    /** Returns the cumulative distribution of a row in the partition. */
    data object CumeDist : SqlFunction

    /** Returns an argument value from a preceding row. */
    data object Lag : SqlFunction

    /** Returns an argument value from a following row. */
    data object Lead : SqlFunction

    /** Returns the first value in the current window frame. */
    data object FirstValue : SqlFunction

    /** Returns the last value in the current window frame. */
    data object LastValue : SqlFunction

    /** Assigns the current row to one of a positive number of ordered partition buckets. */
    data object Ntile : SqlFunction

    /** Returns a value at one-based position in the current window frame. */
    data object NthValue : SqlFunction

    /** A custom SQL function name validated as a simple identifier.
     *
     * @property name identifier used for the function call.
     */
    class Unsafe internal constructor(val name: String) : SqlFunction
}

/**
 * Explicit SQL-standard NULL handling for one value or navigation window function.
 *
 * A missing value keeps the database default semantics distinct from an explicit
 * [RespectNulls]. Dialects decide which function-and-option pairs they support; this model never
 * emulates either option by rewriting a query.
 *
 * @property sql SQL null-treatment clause.
 */
enum class WindowNullTreatment(val sql: String) {
    /** Use the database's treatment of null values in this function. */
    RespectNulls("RESPECT NULLS"),

    /** Ignore null values when evaluating this function. */
    IgnoreNulls("IGNORE NULLS"),
}

/** A typed function-call AST node. Its arguments are expressions, never SQL fragments.
 *
 * @property function function kind represented by the node.
 */
open class SqlFunctionExpression<T> constructor(
    val function: SqlFunction,
    arguments: List<SqlExpression<*>>,
    private val result: SqlExpression<T>,
    override val isNullable: Boolean = result.isNullable,
) : SqlExpression<T> {
    /** Immutable function arguments retained by this expression AST node. */
    val arguments: List<SqlExpression<*>> = arguments.toList()

    override val referencedColumns: List<Column<*>> = arguments.flatMap(SqlExpression<*>::referencedColumns)
    override val type: ColumnType<*> get() = result.type
    override val storageType: ColumnType<*> get() = result.storageType

    override fun toStorageValue(value: T): Any? = result.toStorageValue(value)

    override fun fromStorageValue(value: Any?): T = result.fromStorageValue(value)
}

/** An ordinary aggregate call. It becomes a window aggregate only after `.over { ... }`.
 *
 * @property distinct whether duplicate input values are removed before aggregation.
 */
class AggregateFunctionExpression<T> internal constructor(
    function: SqlFunction,
    arguments: List<SqlExpression<*>>,
    result: SqlExpression<T>,
    isNullable: Boolean = result.isNullable,
    val distinct: Boolean = false,
) : SqlFunctionExpression<T>(function, arguments, result, isNullable),
    WindowCapableExpression<T>,
    AggregateSqlExpression<T>

/** A ranking function that must be completed with `.over { ... }` before it is renderable. */
class WindowFunctionExpression<T> internal constructor(
    function: SqlFunction,
    result: SqlExpression<T>,
    /** A validated SQL integer grammar argument for functions such as `NTILE`. */
    val integerArgument: Int? = null,
) : SqlFunctionExpression<T>(function, emptyList(), result),
    WindowCapableExpression<T> {
    init {
        when (function) {
            SqlFunction.Ntile -> require(integerArgument != null) { "NTILE requires a positive bucket count" }

            SqlFunction.RowNumber,
            SqlFunction.Rank,
            SqlFunction.DenseRank,
            SqlFunction.PercentRank,
            SqlFunction.CumeDist,
            -> require(integerArgument == null) { "$function does not accept an integer argument" }

            else -> error(
                "Window function expression must use ROW_NUMBER, RANK, DENSE_RANK, PERCENT_RANK, CUME_DIST, or NTILE",
            )
        }
    }
}

/**
 * A value or navigation function that must be completed with `.over { ... }` before rendering.
 *
 * [offset] is retained as an integer AST field rather than a runtime SQL expression. This keeps
 * navigation offsets portable, injection-free, and rendered as SQL integer grammar. A non-null
 * [defaultValue] is a normal typed expression so its driver conversion follows [value].
 */
class ValueWindowFunctionExpression<T> internal constructor(
    function: SqlFunction,
    val value: SqlExpression<*>,
    result: SqlExpression<T>,
    val offset: Int? = null,
    val defaultValue: SqlExpression<*>? = null,
    /** Null when the SQL-standard option was omitted and database-default behavior is requested. */
    val nullTreatment: WindowNullTreatment? = null,
) : SqlFunctionExpression<T>(function, listOfNotNull(value, defaultValue), result),
    WindowCapableExpression<T> {
    init {
        when (function) {
            SqlFunction.Lag,
            SqlFunction.Lead,
            -> require(offset != null) { "$function requires a navigation offset" }

            SqlFunction.FirstValue,
            SqlFunction.LastValue,
            -> require(offset == null && defaultValue == null) { "$function does not accept navigation arguments" }

            SqlFunction.NthValue -> require(offset != null && defaultValue == null) {
                "$function requires a positive position"
            }

            else -> error("Value window function must use a supported value or navigation function")
        }
    }
}

/**
 * Attaches an explicit NULL-treatment semantic to a value or navigation window function.
 *
 * Dialect modules expose their supported `ignoreNulls()` and `respectNulls()` convenience
 * extensions. This construction primitive remains separate so the AST can also represent a
 * rejected option precisely during renderer capability validation.
 */
fun <T> ValueWindowFunctionExpression<T>.withWindowNullTreatment(
    treatment: WindowNullTreatment,
): ValueWindowFunctionExpression<T> = ValueWindowFunctionExpression(
    function,
    value,
    this,
    offset,
    defaultValue,
    treatment,
)

/** A function expression admitted by the portable generated-column subset. */
class GeneratedColumnFunctionExpression<T> internal constructor(
    function: SqlFunction,
    arguments: List<SqlExpression<*>>,
    result: SqlExpression<T>,
    isNullable: Boolean = result.isNullable,
) : SqlFunctionExpression<T>(function, arguments, result, isNullable),
    GeneratedColumnExpression<T>

/** A typed projection handle. Only an alias is stable for expression result access.
 *
 * @property expression projected expression.
 * @property name stable alias used to retrieve the result.
 */
class SqlExpressionAlias<T> internal constructor(val expression: SqlExpression<T>, val name: String) :
    SqlExpression<T> {
    init {
        require(name.isNotBlank()) { "SQL expression alias must not be blank" }
    }

    override val referencedColumns: List<Column<*>> get() = expression.referencedColumns
    override val type: ColumnType<*> get() = expression.type
    override val storageType: ColumnType<*> get() = expression.storageType
    override val isNullable: Boolean get() = expression.isNullable

    override fun toStorageValue(value: T): Any? = expression.toStorageValue(value)

    override fun fromStorageValue(value: Any?): T = expression.fromStorageValue(value)
}

/** Names an expression result while preserving its Kotlin type and result decoder. */
fun <T> SqlExpression<T>.alias(name: String): SqlExpressionAlias<T> = SqlExpressionAlias(this, name)

/**
 * A typed nullable view of any expression.
 *
 * This keeps the underlying SQL expression and driver codec unchanged while making an explicitly
 * nullable result handle available. It is used when SQL itself can synthesize a NULL, including
 * an outer join and a super-aggregate row from advanced grouping.
 *
 * @property expression expression exposed with nullable result type.
 */
class NullableSqlExpression<T> internal constructor(val expression: SqlExpression<T>) : SqlExpression<T?> {
    override val referencedColumns: List<Column<*>> get() = expression.referencedColumns
    override val type: ColumnType<*> get() = expression.type
    override val storageType: ColumnType<*> get() = expression.storageType
    override val isNullable: Boolean get() = true

    override fun toStorageValue(value: T?): Any? = if (value == null) null else expression.toStorageValue(value)

    override fun fromStorageValue(value: Any?): T? = if (value == null) null else expression.fromStorageValue(value)

    override fun toString(): String = "$expression?"
}

/**
 * Marks an arbitrary expression as nullable without changing its schema mapping or SQL rendering.
 *
 * More-specific column and relation `.optional` handles retain their existing types. This generic
 * form additionally covers grouping expressions such as `lower(email)`.
 */
val <T> SqlExpression<T>.optional: NullableSqlExpression<T>
    get() = NullableSqlExpression(this)

/**
 * Creates a vendor-function expression without exposing a raw SQL expression API.
 *
 * The function identifier is restricted to one SQL identifier. Arguments remain typed expression
 * nodes and [result] supplies the explicit result mapping. Normal applications should use the
 * built-in typed functions instead.
 */
fun <T> unsafeFunction(
    name: String,
    result: SqlExpression<T>,
    vararg arguments: SqlExpression<*>,
): SqlFunctionExpression<T> {
    require(SQL_IDENTIFIER.matches(name)) { "Unsafe SQL function name must be one identifier: $name" }
    return SqlFunctionExpression(SqlFunction.Unsafe(name), arguments.toList(), result)
}

/** Converts a string expression to lower case while preserving its nullability. */
fun <T : String?> lower(expression: SqlExpression<T>): SqlFunctionExpression<T> =
    SqlFunctionExpression(SqlFunction.Lower, listOf(expression), expression)

/** Converts a generated-column string expression to lower case. */
fun <T : String?> lower(expression: GeneratedColumnExpression<T>): GeneratedColumnFunctionExpression<T> =
    GeneratedColumnFunctionExpression(SqlFunction.Lower, listOf(expression), expression)

/** Converts a string expression to upper case while preserving its nullability. */
fun <T : String?> upper(expression: SqlExpression<T>): SqlFunctionExpression<T> =
    SqlFunctionExpression(SqlFunction.Upper, listOf(expression), expression)

/** Converts a generated-column string expression to upper case. */
fun <T : String?> upper(expression: GeneratedColumnExpression<T>): GeneratedColumnFunctionExpression<T> =
    GeneratedColumnFunctionExpression(SqlFunction.Upper, listOf(expression), expression)

/** Removes leading and trailing spaces while preserving the input nullability. */
fun <T : String?> trim(expression: SqlExpression<T>): SqlFunctionExpression<T> =
    SqlFunctionExpression(SqlFunction.Trim, listOf(expression), expression)

/** Removes leading and trailing spaces in a generated-column expression. */
fun <T : String?> trim(expression: GeneratedColumnExpression<T>): GeneratedColumnFunctionExpression<T> =
    GeneratedColumnFunctionExpression(SqlFunction.Trim, listOf(expression), expression)

/** Removes leading spaces while preserving the input nullability. */
fun <T : String?> ltrim(expression: SqlExpression<T>): SqlFunctionExpression<T> =
    SqlFunctionExpression(SqlFunction.LeftTrim, listOf(expression), expression)

/** Removes leading spaces in a generated-column expression. */
fun <T : String?> ltrim(expression: GeneratedColumnExpression<T>): GeneratedColumnFunctionExpression<T> =
    GeneratedColumnFunctionExpression(SqlFunction.LeftTrim, listOf(expression), expression)

/** Removes trailing spaces while preserving the input nullability. */
fun <T : String?> rtrim(expression: SqlExpression<T>): SqlFunctionExpression<T> =
    SqlFunctionExpression(SqlFunction.RightTrim, listOf(expression), expression)

/** Removes trailing spaces in a generated-column expression. */
fun <T : String?> rtrim(expression: GeneratedColumnExpression<T>): GeneratedColumnFunctionExpression<T> =
    GeneratedColumnFunctionExpression(SqlFunction.RightTrim, listOf(expression), expression)

/** Returns the character length of a non-null string expression. */
@JsName("lengthNonNull")
fun length(expression: SqlExpression<String>): SqlFunctionExpression<Long> =
    SqlFunctionExpression(SqlFunction.Length, listOf(expression), longExpression(nullable = false))

/** Returns the character length of a nullable string expression. */
@JsName("lengthNullable")
fun length(expression: SqlExpression<String?>): SqlFunctionExpression<Long?> =
    SqlFunctionExpression(SqlFunction.Length, listOf(expression), nullableLongExpression)

/** Returns the character length of a generated non-null string expression. */
@JsName("generatedLengthNonNull")
fun length(expression: GeneratedColumnExpression<String>): GeneratedColumnFunctionExpression<Long> =
    GeneratedColumnFunctionExpression(SqlFunction.Length, listOf(expression), longExpression(nullable = false))

/** Returns the character length of a generated nullable string expression. */
@JsName("generatedLengthNullable")
fun length(expression: GeneratedColumnExpression<String?>): GeneratedColumnFunctionExpression<Long?> =
    GeneratedColumnFunctionExpression(SqlFunction.Length, listOf(expression), nullableLongExpression)

/** Returns a nullable result when [left] and [right] compare equal, otherwise [left]. */
fun <T> nullIf(left: SqlExpression<T>, right: SqlExpression<T>): SqlFunctionExpression<T?> =
    SqlFunctionExpression(SqlFunction.NullIf, listOf(left, right), nullableExpression(left), isNullable = true)

/** Binds [right] as a typed parameter before applying SQL `NULLIF`. */
fun <T> nullIf(left: SqlExpression<T>, right: T): SqlFunctionExpression<T?> = nullIf(left, literal(right, left))

/**
 * Returns the SQL-standard one-based substring of [expression] from [start] through its end.
 *
 * The position is deliberately a bound parameter, not embedded SQL. The portable contract starts
 * at one; negative and zero expression values have the selected database's native behavior.
 */
fun <T : String?> substring(expression: SqlExpression<T>, start: Int): SqlFunctionExpression<T> {
    require(start >= 1) { "substring start must be one or greater" }
    return substring(expression, literal(start))
}

/** Returns at most [length] characters of the SQL-standard one-based substring of [expression]. */
fun <T : String?> substring(expression: SqlExpression<T>, start: Int, length: Int): SqlFunctionExpression<T> {
    require(start >= 1) { "substring start must be one or greater" }
    require(length >= 0) { "substring length cannot be negative" }
    return substring(expression, literal(start), literal(length))
}

/** Expression form of a one-based substring start position. */
fun <T : String?> substring(expression: SqlExpression<T>, start: SqlExpression<Int>): SqlFunctionExpression<T> =
    SqlFunctionExpression(SqlFunction.Substring, listOf(expression, start), expression)

/** Expression form of a one-based substring start position and character count. */
fun <T : String?> substring(
    expression: SqlExpression<T>,
    start: SqlExpression<Int>,
    length: SqlExpression<Int>,
): SqlFunctionExpression<T> = SqlFunctionExpression(
    SqlFunction.Substring,
    listOf(expression, start, length),
    expression,
)

/** Replaces every non-null [old] string expression in [expression] with non-null [new]. */
fun <T : String?> replace(
    expression: SqlExpression<T>,
    old: SqlExpression<String>,
    new: SqlExpression<String>,
): SqlFunctionExpression<T> = SqlFunctionExpression(SqlFunction.Replace, listOf(expression, old, new), expression)

/** Binds the replacement strings as parameters before applying SQL `REPLACE`. */
fun <T : String?> replace(expression: SqlExpression<T>, old: String, new: String): SqlFunctionExpression<T> =
    replace(expression, literal(old), literal(new))

/**
 * Concatenates non-null strings.
 *
 * The portable API excludes nullable operands because supported databases disagree on whether a
 * null operand propagates or becomes an empty string. Use [coalesce] explicitly when that policy
 * is desired.
 */
fun concat(
    first: SqlExpression<String>,
    second: SqlExpression<String>,
    vararg additional: SqlExpression<String>,
): SqlFunctionExpression<String> = SqlFunctionExpression(
    SqlFunction.Concat,
    listOf(first, second) + additional,
    stringExpression(nullable = false),
    isNullable = false,
)

/** Convenience overload that binds a separator while retaining CONCAT's non-null contract. */
fun concat(
    first: SqlExpression<String>,
    separator: String,
    last: SqlExpression<String>,
): SqlFunctionExpression<String> = concat(first, literal(separator), last)

/** Returns the first non-null result. A non-null fallback makes the result non-null. */
fun <T : Any> coalesce(
    first: SqlExpression<T?>,
    fallback: SqlExpression<T>,
    vararg additional: SqlExpression<T?>,
): SqlFunctionExpression<T> = SqlFunctionExpression(
    SqlFunction.Coalesce,
    listOf(first, fallback) + additional,
    nonNullExpression(fallback),
    isNullable = false,
)

/** Returns the first non-null generated value. A generated non-null fallback makes it non-null. */
fun <T : Any> coalesce(
    first: GeneratedColumnExpression<T?>,
    fallback: GeneratedColumnExpression<T>,
    vararg additional: GeneratedColumnExpression<T?>,
): GeneratedColumnFunctionExpression<T> = GeneratedColumnFunctionExpression(
    SqlFunction.Coalesce,
    listOf(first, fallback) + additional,
    nonNullExpression(fallback),
    isNullable = false,
)

/** Returns the first non-null result while retaining the common expression nullability. */
fun <T> coalesce(
    first: SqlExpression<T>,
    second: SqlExpression<T>,
    vararg additional: SqlExpression<T>,
): SqlFunctionExpression<T> = SqlFunctionExpression(
    SqlFunction.Coalesce,
    listOf(first, second) + additional,
    first,
)

/** Returns the first non-null generated value while retaining the common expression nullability. */
fun <T> coalesce(
    first: GeneratedColumnExpression<T>,
    second: GeneratedColumnExpression<T>,
    vararg additional: GeneratedColumnExpression<T>,
): GeneratedColumnFunctionExpression<T> = GeneratedColumnFunctionExpression(
    SqlFunction.Coalesce,
    listOf(first, second) + additional,
    first,
)

/** Absolute value for signed Kotlin numeric expressions, preserving nullability and numeric type. */
fun <T : Number?> abs(expression: SqlExpression<T>): SqlFunctionExpression<T> =
    SqlFunctionExpression(SqlFunction.Abs, listOf(expression), expression)

/** Absolute value for a generated signed Kotlin numeric expression. */
fun <T : Number?> abs(expression: GeneratedColumnExpression<T>): GeneratedColumnFunctionExpression<T> =
    GeneratedColumnFunctionExpression(SqlFunction.Abs, listOf(expression), expression)

/** Rounds a non-null numeric input to an implementation-defined numeric scale as Kotlin [Double]. */
@JsName("roundNonNull")
fun <T : Number> round(expression: SqlExpression<T>): SqlFunctionExpression<Double> = numericFunction(
    SqlFunction.Round,
    expression,
)

/** Rounds a nullable numeric input while preserving NULL propagation as Kotlin [Double]. */
@JsName("roundNullable")
fun <T : Number> round(expression: SqlExpression<T?>): SqlFunctionExpression<Double?> = nullableNumericFunction(
    SqlFunction.Round,
    expression,
)

/** Rounds a non-null numeric input to the bound decimal [digits] scale as Kotlin [Double]. */
@JsName("roundDigitsNonNull")
fun <T : Number> round(expression: SqlExpression<T>, digits: Int): SqlFunctionExpression<Double> = numericFunction(
    SqlFunction.Round,
    expression,
    literal(digits),
)

/** Rounds a nullable numeric input to the bound decimal [digits] scale as Kotlin [Double]. */
@JsName("roundDigitsNullable")
fun <T : Number> round(expression: SqlExpression<T?>, digits: Int): SqlFunctionExpression<Double?> =
    nullableNumericFunction(SqlFunction.Round, expression, literal(digits))

/** Returns the mathematical floor of a non-null numeric input as Kotlin [Double]. */
@JsName("floorNonNull")
fun <T : Number> floor(expression: SqlExpression<T>): SqlFunctionExpression<Double> = numericFunction(
    SqlFunction.Floor,
    expression,
)

/** Returns the mathematical floor of a nullable numeric input as Kotlin [Double]. */
@JsName("floorNullable")
fun <T : Number> floor(expression: SqlExpression<T?>): SqlFunctionExpression<Double?> = nullableNumericFunction(
    SqlFunction.Floor,
    expression,
)

/** Returns the mathematical ceiling of a non-null numeric input as Kotlin [Double]. */
@JsName("ceilNonNull")
fun <T : Number> ceil(expression: SqlExpression<T>): SqlFunctionExpression<Double> = numericFunction(
    SqlFunction.Ceiling,
    expression,
)

/** Returns the mathematical ceiling of a nullable numeric input as Kotlin [Double]. */
@JsName("ceilNullable")
fun <T : Number> ceil(expression: SqlExpression<T?>): SqlFunctionExpression<Double?> = nullableNumericFunction(
    SqlFunction.Ceiling,
    expression,
)

/**
 * Returns the square root as Kotlin [Double].
 *
 * The result is nullable even for a non-null input because SQLite and MySQL represent an
 * out-of-domain negative root as SQL NULL; other supported databases may reject that value.
 */
@JsName("sqrtNonNull")
fun <T : Number> sqrt(expression: SqlExpression<T>): SqlFunctionExpression<Double?> = nullableNumericFunction(
    SqlFunction.SquareRoot,
    expression,
)

/** Returns the nullable square root of a nullable numeric input as Kotlin [Double]. */
@JsName("sqrtNullable")
fun <T : Number> sqrt(expression: SqlExpression<T?>): SqlFunctionExpression<Double?> = nullableNumericFunction(
    SqlFunction.SquareRoot,
    expression,
)

/** Raises a non-null numeric [base] to a bound integral [exponent] as Kotlin [Double]. */
@JsName("powerNonNull")
fun <T : Number> power(base: SqlExpression<T>, exponent: Int): SqlFunctionExpression<Double> = numericFunction(
    SqlFunction.Power,
    base,
    literal(exponent),
)

/** Raises a nullable numeric [base] to a bound integral [exponent] as Kotlin [Double]. */
@JsName("powerNullable")
fun <T : Number> power(base: SqlExpression<T?>, exponent: Int): SqlFunctionExpression<Double?> =
    nullableNumericFunction(SqlFunction.Power, base, literal(exponent))

/**
 * Produces the database's current instant for ordinary SQL and DEFAULT expression contexts.
 *
 * [currentInstant] is the schema-oriented name for this same typed special expression.
 */
fun currentTimestamp(): SqlFunctionExpression<kotlin.time.Instant> = SqlFunctionExpression(
    SqlFunction.CurrentTimestamp,
    emptyList(),
    resultExpression(InstantColumnType),
    isNullable = false,
)

/** Counts all rows as a signed 64-bit value. */
fun count(): AggregateFunctionExpression<Long> = AggregateFunctionExpression(
    SqlFunction.Count,
    emptyList(),
    longExpression(nullable = false),
)

/** Counts non-null values of [expression] as a signed 64-bit value. */
fun count(expression: SqlExpression<*>): AggregateFunctionExpression<Long> =
    AggregateFunctionExpression(SqlFunction.Count, listOf(expression), longExpression(nullable = false))

/** Counts distinct non-null values of [expression], independently from SELECT DISTINCT. */
fun countDistinct(expression: SqlExpression<*>): AggregateFunctionExpression<Long> = AggregateFunctionExpression(
    SqlFunction.Count,
    listOf(expression),
    longExpression(nullable = false),
    distinct = true,
)

/**
 * Returns `0` when [expression] participates in the current grouping set and `1` when a native
 * grouping extension synthesizes its NULL placeholder. The logical result is a SQL integer, not
 * a Boolean, so the native function's semantics remain available to SELECT, HAVING, and ORDER BY.
 */
fun grouping(expression: SqlExpression<*>): SqlFunctionExpression<Int> = SqlFunctionExpression(
    SqlFunction.Grouping,
    listOf(expression),
    intExpression(nullable = false),
    isNullable = false,
)

/**
 * Aggregates built-in numeric types without permitting stringly or arbitrary expression inputs.
 * Exact integral SUM values are decoded as Long and reject values outside that range. AVG uses an
 * explicit Double result because Kagiroi core has no portable exact-decimal logical type.
 */
@Suppress("LongMethod")
private object StandardNumericAggregates {
    fun sumIntegral(expression: SqlExpression<*>): AggregateFunctionExpression<Long?> =
        AggregateFunctionExpression(SqlFunction.Sum, listOf(expression), nullableLongExpression)

    fun average(expression: SqlExpression<*>): AggregateFunctionExpression<Double?> =
        AggregateFunctionExpression(SqlFunction.Average, listOf(expression), nullableDoubleExpression)

    fun sumFloating(expression: SqlExpression<*>): AggregateFunctionExpression<Double?> =
        AggregateFunctionExpression(SqlFunction.Sum, listOf(expression), nullableDoubleExpression)
}

/** SUM for signed integral inputs. A result outside Kotlin Long is rejected, never rounded. */
@JsName("sumByte")
fun sum(expression: SqlExpression<Byte>): AggregateFunctionExpression<Long?> = StandardNumericAggregates.sumIntegral(
    expression,
)

/** Returns the nullable sum of signed byte input as `Long`. */
@JsName("sumNullableByte")
fun sum(expression: SqlExpression<Byte?>): AggregateFunctionExpression<Long?> = StandardNumericAggregates.sumIntegral(
    expression,
)

/** Returns the sum of signed short input as `Long`. */
@JsName("sumShort")
fun sum(expression: SqlExpression<Short>): AggregateFunctionExpression<Long?> = StandardNumericAggregates.sumIntegral(
    expression,
)

/** Returns the nullable sum of signed short input as `Long`. */
@JsName("sumNullableShort")
fun sum(expression: SqlExpression<Short?>): AggregateFunctionExpression<Long?> = StandardNumericAggregates.sumIntegral(
    expression,
)

/** Returns the sum of integer input as `Long`. */
@JsName("sumInt")
fun sum(expression: SqlExpression<Int>): AggregateFunctionExpression<Long?> = StandardNumericAggregates.sumIntegral(
    expression,
)

/** Returns the nullable sum of integer input as `Long`. */
@JsName("sumNullableInt")
fun sum(expression: SqlExpression<Int?>): AggregateFunctionExpression<Long?> = StandardNumericAggregates.sumIntegral(
    expression,
)

/** Returns the sum of long input as nullable `Long`. */
@JsName("sumLong")
fun sum(expression: SqlExpression<Long>): AggregateFunctionExpression<Long?> = StandardNumericAggregates.sumIntegral(
    expression,
)

/** Returns the nullable sum of nullable long input. */
@JsName("sumNullableLong")
fun sum(expression: SqlExpression<Long?>): AggregateFunctionExpression<Long?> = StandardNumericAggregates.sumIntegral(
    expression,
)

/** SUM for floating-point inputs. */
@JsName("sumFloat")
fun sum(expression: SqlExpression<Float>): AggregateFunctionExpression<Double?> = StandardNumericAggregates.sumFloating(
    expression,
)

/** Returns the nullable sum of floating-point input as `Double`. */
@JsName("sumNullableFloat")
fun sum(expression: SqlExpression<Float?>): AggregateFunctionExpression<Double?> =
    StandardNumericAggregates.sumFloating(
        expression,
    )

/** Returns the sum of double input. */
@JsName("sumDouble")
fun sum(expression: SqlExpression<Double>): AggregateFunctionExpression<Double?> =
    StandardNumericAggregates.sumFloating(
        expression,
    )

/** Returns the nullable sum of nullable double input. */
@JsName("sumNullableDouble")
fun sum(expression: SqlExpression<Double?>): AggregateFunctionExpression<Double?> =
    StandardNumericAggregates.sumFloating(
        expression,
    )

/** AVG is approximate for exact numeric inputs because core does not expose a portable decimal type. */
@JsName("avgByte")
fun avg(expression: SqlExpression<Byte>): AggregateFunctionExpression<Double?> = StandardNumericAggregates.average(
    expression,
)

/** Returns the average of nullable byte input as nullable `Double`. */
@JsName("avgNullableByte")
fun avg(expression: SqlExpression<Byte?>): AggregateFunctionExpression<Double?> = StandardNumericAggregates.average(
    expression,
)

/** Returns the average of short input as nullable `Double`. */
@JsName("avgShort")
fun avg(expression: SqlExpression<Short>): AggregateFunctionExpression<Double?> = StandardNumericAggregates.average(
    expression,
)

/** Returns the average of nullable short input as nullable `Double`. */
@JsName("avgNullableShort")
fun avg(expression: SqlExpression<Short?>): AggregateFunctionExpression<Double?> = StandardNumericAggregates.average(
    expression,
)

/** Returns the average of integer input as nullable `Double`. */
@JsName("avgInt")
fun avg(expression: SqlExpression<Int>): AggregateFunctionExpression<Double?> = StandardNumericAggregates.average(
    expression,
)

/** Returns the average of nullable integer input as nullable `Double`. */
@JsName("avgNullableInt")
fun avg(expression: SqlExpression<Int?>): AggregateFunctionExpression<Double?> = StandardNumericAggregates.average(
    expression,
)

/** Returns the average of long input as nullable `Double`. */
@JsName("avgLong")
fun avg(expression: SqlExpression<Long>): AggregateFunctionExpression<Double?> = StandardNumericAggregates.average(
    expression,
)

/** Returns the average of nullable long input as nullable `Double`. */
@JsName("avgNullableLong")
fun avg(expression: SqlExpression<Long?>): AggregateFunctionExpression<Double?> = StandardNumericAggregates.average(
    expression,
)

/** Returns the average of floating-point input as nullable `Double`. */
@JsName("avgFloat")
fun avg(expression: SqlExpression<Float>): AggregateFunctionExpression<Double?> = StandardNumericAggregates.average(
    expression,
)

/** Returns the average of nullable floating-point input as nullable `Double`. */
@JsName("avgNullableFloat")
fun avg(expression: SqlExpression<Float?>): AggregateFunctionExpression<Double?> = StandardNumericAggregates.average(
    expression,
)

/** Returns the average of double input as nullable `Double`. */
@JsName("avgDouble")
fun avg(expression: SqlExpression<Double>): AggregateFunctionExpression<Double?> = StandardNumericAggregates.average(
    expression,
)

/** Returns the average of nullable double input as nullable `Double`. */
@JsName("avgNullableDouble")
fun avg(expression: SqlExpression<Double?>): AggregateFunctionExpression<Double?> = StandardNumericAggregates.average(
    expression,
)

/** Minimum, which is nullable because an empty input set produces SQL NULL. */
fun <T> min(expression: SqlExpression<T>): AggregateFunctionExpression<T?> = AggregateFunctionExpression(
    SqlFunction.Minimum,
    listOf(expression),
    nullableExpression(expression),
    isNullable = true,
)

/** Maximum, which is nullable because an empty input set produces SQL NULL. */
fun <T> max(expression: SqlExpression<T>): AggregateFunctionExpression<T?> = AggregateFunctionExpression(
    SqlFunction.Maximum,
    listOf(expression),
    nullableExpression(expression),
    isNullable = true,
)

/** Numbers rows in a window from one. Complete this expression with `.over { ... }`. */
fun rowNumber(): WindowFunctionExpression<Long> =
    WindowFunctionExpression(SqlFunction.RowNumber, longExpression(nullable = false))

/** Ranks peer groups with gaps. Complete this expression with `.over { ... }`. */
fun rank(): WindowFunctionExpression<Long> =
    WindowFunctionExpression(SqlFunction.Rank, longExpression(nullable = false))

/** Ranks peer groups without gaps. Complete this expression with `.over { ... }`. */
fun denseRank(): WindowFunctionExpression<Long> =
    WindowFunctionExpression(SqlFunction.DenseRank, longExpression(nullable = false))

/** Returns the relative rank of the current row in its ordered window partition. */
fun percentRank(): WindowFunctionExpression<Double> =
    WindowFunctionExpression(SqlFunction.PercentRank, doubleExpression(nullable = false))

/** Returns the cumulative distribution of the current row in its ordered window partition. */
fun cumeDist(): WindowFunctionExpression<Double> =
    WindowFunctionExpression(SqlFunction.CumeDist, doubleExpression(nullable = false))

/** Assigns the current row to one of [bucketCount] ordered buckets in its partition. */
fun ntile(bucketCount: Int): WindowFunctionExpression<Long> {
    require(bucketCount > 0) { "NTILE bucket count must be positive" }
    return WindowFunctionExpression(SqlFunction.Ntile, longExpression(nullable = false), bucketCount)
}

/**
 * Returns the value from [offset] preceding rows, or NULL when that row is missing.
 * Complete this expression with `.over { ... }`.
 */
fun <T> lag(expression: SqlExpression<T>, offset: Int = 1): ValueWindowFunctionExpression<T?> =
    navigationWindowFunction(SqlFunction.Lag, expression, offset)

/**
 * Returns the value from [offset] preceding rows, or [default] when that row is missing.
 * Complete this expression with `.over { ... }`.
 *
 * The result remains nullable because a navigated input row may itself contain NULL.
 */
fun <T> lag(expression: SqlExpression<T>, offset: Int = 1, default: T & Any): ValueWindowFunctionExpression<T?> =
    navigationWindowFunction(
        SqlFunction.Lag,
        expression,
        offset,
        literal(default, expression),
    )

/**
 * Returns the value from [offset] following rows, or NULL when that row is missing.
 * Complete this expression with `.over { ... }`.
 */
fun <T> lead(expression: SqlExpression<T>, offset: Int = 1): ValueWindowFunctionExpression<T?> =
    navigationWindowFunction(SqlFunction.Lead, expression, offset)

/**
 * Returns the value from [offset] following rows, or [default] when that row is missing.
 * Complete this expression with `.over { ... }`.
 *
 * The result remains nullable because a navigated input row may itself contain NULL.
 */
fun <T> lead(expression: SqlExpression<T>, offset: Int = 1, default: T & Any): ValueWindowFunctionExpression<T?> =
    navigationWindowFunction(
        SqlFunction.Lead,
        expression,
        offset,
        literal(default, expression),
    )

/** Returns the first value in the current window frame. Complete this expression with `.over { ... }`. */
fun <T> firstValue(expression: SqlExpression<T>): ValueWindowFunctionExpression<T> =
    ValueWindowFunctionExpression(SqlFunction.FirstValue, expression, expression)

/** Returns the last value in the current window frame. Complete this expression with `.over { ... }`. */
fun <T> lastValue(expression: SqlExpression<T>): ValueWindowFunctionExpression<T> =
    ValueWindowFunctionExpression(SqlFunction.LastValue, expression, expression)

/**
 * Returns the value at one-based [position] in the current window frame, or SQL NULL when the
 * frame has fewer rows. The original expression mapping is retained while nullability is lifted.
 */
fun <T> nthValue(expression: SqlExpression<T>, position: Int): ValueWindowFunctionExpression<T?> {
    require(position > 0) { "NTH_VALUE position must be positive" }
    return ValueWindowFunctionExpression(
        SqlFunction.NthValue,
        expression,
        nullableExpression(expression),
        offset = position,
    )
}

private fun <T> navigationWindowFunction(
    function: SqlFunction,
    expression: SqlExpression<T>,
    offset: Int,
    default: SqlExpression<*>? = null,
): ValueWindowFunctionExpression<T?> {
    require(offset > 0) { "Window navigation offset must be positive" }
    return ValueWindowFunctionExpression(
        function,
        expression,
        nullableExpression(expression),
        offset,
        default,
    )
}

private class BasicSqlExpression<T>(
    override val type: ColumnType<*>,
    override val storageType: ColumnType<*>,
    override val isNullable: Boolean,
    private val encode: (T) -> Any?,
    private val decode: (Any?) -> T,
) : SqlExpression<T> {
    override val referencedColumns: List<Column<*>> = emptyList()

    override fun toStorageValue(value: T): Any? = encode(value)

    override fun fromStorageValue(value: Any?): T = decode(value)
}

private fun longExpression(nullable: Boolean): SqlExpression<Long> = BasicSqlExpression(
    LongColumnType,
    LongColumnType,
    nullable,
    encode = { it },
    decode = { value -> requireNotNull(value) as Long },
)

private fun stringExpression(nullable: Boolean): SqlExpression<String> = BasicSqlExpression(
    StringColumnType,
    StringColumnType,
    nullable,
    encode = { it },
    decode = { value -> requireNotNull(value) as String },
)

private fun booleanExpression(nullable: Boolean): SqlExpression<Boolean> = BasicSqlExpression(
    BooleanColumnType,
    BooleanColumnType,
    nullable,
    encode = { it },
    decode = { value -> requireNotNull(value) as Boolean },
)

private fun byteExpression(nullable: Boolean): SqlExpression<Byte> = BasicSqlExpression(
    ByteColumnType,
    ByteColumnType,
    nullable,
    encode = { it },
    decode = { value -> requireNotNull(value) as Byte },
)

private fun shortExpression(nullable: Boolean): SqlExpression<Short> = BasicSqlExpression(
    ShortColumnType,
    ShortColumnType,
    nullable,
    encode = { it },
    decode = { value -> requireNotNull(value) as Short },
)

private fun intExpression(nullable: Boolean): SqlExpression<Int> = BasicSqlExpression(
    IntColumnType,
    IntColumnType,
    nullable,
    encode = { it },
    decode = { value -> requireNotNull(value) as Int },
)

private fun floatExpression(nullable: Boolean): SqlExpression<Float> = BasicSqlExpression(
    FloatColumnType,
    FloatColumnType,
    nullable,
    encode = { it },
    decode = { value -> requireNotNull(value) as Float },
)

private fun doubleExpression(nullable: Boolean): SqlExpression<Double> = BasicSqlExpression(
    DoubleColumnType,
    DoubleColumnType,
    nullable,
    encode = { it },
    decode = { value -> requireNotNull(value) as Double },
)

private val nullableLongExpression: SqlExpression<Long?> = BasicSqlExpression(
    LongColumnType,
    LongColumnType,
    true,
    encode = { it },
    decode = { it as Long? },
)

private val nullableDoubleExpression: SqlExpression<Double?> = BasicSqlExpression(
    DoubleColumnType,
    DoubleColumnType,
    true,
    encode = { it },
    decode = { it as Double? },
)

private fun numericFunction(
    function: SqlFunction,
    expression: SqlExpression<*>,
    vararg additional: SqlExpression<*>,
): SqlFunctionExpression<Double> = SqlFunctionExpression(
    function,
    listOf(expression) + additional,
    doubleExpression(nullable = false),
    isNullable = false,
)

private fun nullableNumericFunction(
    function: SqlFunction,
    expression: SqlExpression<*>,
    vararg additional: SqlExpression<*>,
): SqlFunctionExpression<Double?> = SqlFunctionExpression(
    function,
    listOf(expression) + additional,
    nullableDoubleExpression,
    isNullable = true,
)

private fun <T> nullableExpression(expression: SqlExpression<T>): SqlExpression<T?> = BasicSqlExpression(
    expression.type,
    expression.storageType,
    true,
    encode = { value -> value?.let(expression::toStorageValue) },
    decode = { value -> value?.let(expression::fromStorageValue) },
)

private fun <T> nonNullExpression(expression: SqlExpression<T>): SqlExpression<T> = BasicSqlExpression(
    expression.type,
    expression.storageType,
    false,
    encode = expression::toStorageValue,
    decode = { value -> requireNotNull(value).let(expression::fromStorageValue) },
)

/** Reuses an expression's complete logical/storage mapping as a CAST target. */
fun <T> castTarget(template: SqlExpression<T>): SqlCastTarget<T> = SqlCastTarget(template)

/** Standard CAST target for Kotlin [String]. */
fun stringType(): SqlCastTarget<String> = standardCastTarget(StringColumnType)

/** Standard CAST target for Kotlin [Boolean]. */
fun booleanType(): SqlCastTarget<Boolean> = standardCastTarget(BooleanColumnType)

/** Standard CAST target for Kotlin [Byte]. */
fun byteType(): SqlCastTarget<Byte> = standardCastTarget(ByteColumnType)

/** Standard CAST target for Kotlin [Short]. */
fun shortType(): SqlCastTarget<Short> = standardCastTarget(ShortColumnType)

/** Standard CAST target for Kotlin [Int]. */
fun intType(): SqlCastTarget<Int> = standardCastTarget(IntColumnType)

/** Standard CAST target for Kotlin [Long]. */
fun longType(): SqlCastTarget<Long> = standardCastTarget(LongColumnType)

/** Standard CAST target for Kotlin [UByte]. */
fun ubyteType(): SqlCastTarget<UByte> = standardCastTarget(UByteColumnType)

/** Standard CAST target for Kotlin [UShort]. */
fun ushortType(): SqlCastTarget<UShort> = standardCastTarget(UShortColumnType)

/** Standard CAST target for Kotlin [UInt]. */
fun uintType(): SqlCastTarget<UInt> = standardCastTarget(UIntColumnType)

/** Standard CAST target for Kotlin [ULong]. */
fun ulongType(): SqlCastTarget<ULong> = standardCastTarget(ULongColumnType)

/** Standard CAST target for Kotlin [Float]. */
fun floatType(): SqlCastTarget<Float> = standardCastTarget(FloatColumnType)

/** Standard CAST target for Kotlin [Double]. */
fun doubleType(): SqlCastTarget<Double> = standardCastTarget(DoubleColumnType)

/** Standard CAST target for Kotlin [ByteArray]. */
fun bytesType(): SqlCastTarget<ByteArray> = standardCastTarget(ByteArrayColumnType)

/** Standard CAST target for Kotlin [kotlin.time.Instant]. */
fun instantType(): SqlCastTarget<kotlin.time.Instant> = standardCastTarget(InstantColumnType)

/** Standard CAST target for Kotlin [kotlin.time.Duration]. */
fun durationType(): SqlCastTarget<kotlin.time.Duration> = standardCastTarget(DurationColumnType)

/** Standard CAST target for Kotlin [kotlin.uuid.Uuid]. */
fun uuidType(): SqlCastTarget<kotlin.uuid.Uuid> = standardCastTarget(UuidColumnType)

/** CAST of a non-null expression retains a non-null Kotlin result type. */
@JsName("castNonNull")
fun <S : Any, T : Any> cast(expression: SqlExpression<S>, target: SqlCastTarget<T>): CastExpression<T> =
    CastExpression(expression, target, target.result, isNullable = false)

/** CAST of a nullable expression retains a nullable Kotlin result type. */
@JsName("castNullable")
fun <S : Any, T : Any> cast(expression: SqlExpression<S?>, target: SqlCastTarget<T>): CastExpression<T?> =
    CastExpression(expression, target, nullableExpression(target.result), isNullable = true)

/** Fluent non-null CAST form. */
@JsName("castToNonNull")
fun <S : Any, T : Any> SqlExpression<S>.castTo(target: SqlCastTarget<T>): CastExpression<T> = cast(this, target)

/** Fluent nullable CAST form. */
@JsName("castToNullable")
fun <S : Any, T : Any> SqlExpression<S?>.castTo(target: SqlCastTarget<T>): CastExpression<T?> = cast(this, target)

@Suppress("UNCHECKED_CAST") // The decoder receives exactly the target's logical Kotlin type.
private fun <T : Any> standardCastTarget(type: ColumnType<T>): SqlCastTarget<T> = SqlCastTarget(
    resultExpression(type),
)

/**
 * Creates a non-null result mapping for a typed expression with no input mapping to inherit.
 *
 * This is intended for closed built-in or dialect-extension AST builders. It is not a raw SQL
 * escape hatch: it has no SQL rendering form on its own.
 */
@Suppress("UNCHECKED_CAST") // The driver codec selected by [type] returns its matching Kotlin type.
fun <T : Any> resultExpression(type: ColumnType<T>): SqlExpression<T> = BasicSqlExpression(
    type,
    type,
    false,
    encode = { it },
    decode = { value -> requireNotNull(value) as T },
)

/** Allows a nullable CASE to use an ordinary non-null expression branch. */
@JsName("caseNullableExpressionWhenThen")
fun <T : Any> SearchedCaseBuilder<T?>.whenThen(condition: BooleanExpression, result: SqlExpression<T>) {
    whenThenCompatible(condition, result)
}

/** Allows a nullable CASE to use an ordinary non-null expression ELSE branch. */
@JsName("caseNullableExpressionElseValue")
fun <T : Any> SearchedCaseBuilder<T?>.elseValue(result: SqlExpression<T>) {
    elseValueCompatible(result)
}

/** Adds a string literal as this CASE branch's result. */
@JsName("caseStringWhenThen")
fun SearchedCaseBuilder<String>.whenThen(condition: BooleanExpression, result: String) = whenThen(
    condition,
    literal(result),
)

/** Uses a string literal as this CASE expression's ELSE result. */
@JsName("caseStringElseValue")
fun SearchedCaseBuilder<String>.elseValue(result: String) = elseValue(literal(result))

/** Adds a non-null string literal to a nullable CASE branch. */
@JsName("caseNullableStringWhenThen")
fun SearchedCaseBuilder<String?>.whenThen(condition: BooleanExpression, result: String) =
    whenThenCompatible(condition, literal(result))

/** Uses a non-null string literal as the ELSE result of a nullable CASE. */
@JsName("caseNullableStringElseValue")
fun SearchedCaseBuilder<String?>.elseValue(result: String) = elseValueCompatible(literal(result))

/** Adds a Boolean literal as this CASE branch's result. */
@JsName("caseBooleanWhenThen")
fun SearchedCaseBuilder<Boolean>.whenThen(condition: BooleanExpression, result: Boolean) = whenThen(
    condition,
    literal(result),
)

/** Uses a Boolean literal as this CASE expression's ELSE result. */
@JsName("caseBooleanElseValue")
fun SearchedCaseBuilder<Boolean>.elseValue(result: Boolean) = elseValue(literal(result))

/** Adds a Byte literal as this CASE branch's result. */
@JsName("caseByteWhenThen")
fun SearchedCaseBuilder<Byte>.whenThen(condition: BooleanExpression, result: Byte) = whenThen(
    condition,
    literal(result),
)

/** Uses a Byte literal as this CASE expression's ELSE result. */
@JsName("caseByteElseValue")
fun SearchedCaseBuilder<Byte>.elseValue(result: Byte) = elseValue(literal(result))

/** Adds a Short literal as this CASE branch's result. */
@JsName("caseShortWhenThen")
fun SearchedCaseBuilder<Short>.whenThen(condition: BooleanExpression, result: Short) = whenThen(
    condition,
    literal(result),
)

/** Uses a Short literal as this CASE expression's ELSE result. */
@JsName("caseShortElseValue")
fun SearchedCaseBuilder<Short>.elseValue(result: Short) = elseValue(literal(result))

/** Adds an Int literal as this CASE branch's result. */
@JsName("caseIntWhenThen")
fun SearchedCaseBuilder<Int>.whenThen(condition: BooleanExpression, result: Int) = whenThen(condition, literal(result))

/** Uses an Int literal as this CASE expression's ELSE result. */
@JsName("caseIntElseValue")
fun SearchedCaseBuilder<Int>.elseValue(result: Int) = elseValue(literal(result))

/** Adds a Long literal as this CASE branch's result. */
@JsName("caseLongWhenThen")
fun SearchedCaseBuilder<Long>.whenThen(condition: BooleanExpression, result: Long) = whenThen(
    condition,
    literal(result),
)

/** Uses a Long literal as this CASE expression's ELSE result. */
@JsName("caseLongElseValue")
fun SearchedCaseBuilder<Long>.elseValue(result: Long) = elseValue(literal(result))

/** Adds a Float literal as this CASE branch's result. */
@JsName("caseFloatWhenThen")
fun SearchedCaseBuilder<Float>.whenThen(condition: BooleanExpression, result: Float) = whenThen(
    condition,
    literal(result),
)

/** Uses a Float literal as this CASE expression's ELSE result. */
@JsName("caseFloatElseValue")
fun SearchedCaseBuilder<Float>.elseValue(result: Float) = elseValue(literal(result))

/** Adds a Double literal as this CASE branch's result. */
@JsName("caseDoubleWhenThen")
fun SearchedCaseBuilder<Double>.whenThen(condition: BooleanExpression, result: Double) = whenThen(
    condition,
    literal(result),
)

/** Uses a Double literal as this CASE expression's ELSE result. */
@JsName("caseDoubleElseValue")
fun SearchedCaseBuilder<Double>.elseValue(result: Double) = elseValue(literal(result))

private val SQL_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
