package dev.quwac.kagiroi.schema

/** Declares a typed literal DEFAULT value for this writable column. */
fun <T> WritableColumn<T>.default(value: T): WritableColumn<T> = default(literal(value, this))

/** Declares a typed SQL DEFAULT expression for this writable column. */
fun <T> WritableColumn<T>.default(expression: SqlExpression<T>): WritableColumn<T> = withDefinition(
    defaultDefinition(
        ColumnDefault(
            expression.also(::requireDefaultExpression),
        ),
    ),
)

/** Declares a typed composed DEFAULT expression built with [defaultExpression]. */
fun <T> WritableColumn<T>.default(expression: DefaultExpression<T>): WritableColumn<T> =
    default(expression.asSqlExpression(this))

/** Builds typed DEFAULT metadata for ALTER TABLE without changing [column]. */
fun <T> columnDefault(column: Column<T>, value: T): ColumnDefault<T> = columnDefault(column, literal(value, column))

/** Builds typed DEFAULT metadata for ALTER TABLE without changing [column]. */
fun <T> columnDefault(column: Column<T>, expression: SqlExpression<T>): ColumnDefault<T> {
    require(!column.isIdentity) { "Identity column cannot also declare a DEFAULT expression: $column" }
    require(!column.isGenerated) { "Generated column cannot also declare a DEFAULT expression: $column" }
    require(expression.type === column.type && expression.storageType === column.storageType) {
        "DEFAULT expression must use the column's logical and storage mapping: $column"
    }
    requireDefaultExpression(expression)
    if (expression is SqlLiteral<*> && expression.value == null) {
        require(column.isNullable) { "Non-null column cannot use DEFAULT NULL: $column" }
    }
    return ColumnDefault(expression)
}

internal fun <T> WritableColumn<T>.defaultDefinition(default: ColumnDefault<T>): ColumnDefinition {
    require(definition.identity == null) { "Identity column cannot also declare a DEFAULT expression: $this" }
    require(
        definition.generatedExpression == null,
    ) { "Generated column cannot also declare a DEFAULT expression: $this" }
    require(definition.defaultExpression == null) { "Column already declares a DEFAULT expression: $this" }
    require(default.expression.type === definition.type && default.expression.storageType === definition.storageType) {
        "DEFAULT expression must use the column's logical and storage mapping: $this"
    }
    requireDefaultExpression(default.expression)
    if (default.expression is SqlLiteral<*> && default.expression.value == null) {
        require(definition.isNullable) { "Non-null column cannot use DEFAULT NULL: $this" }
    }
    return definition.copy(defaultExpression = default)
}

internal fun requireDefaultExpression(expression: SqlExpression<*>) {
    require(expression.referencedColumns.isEmpty()) { "DEFAULT expression cannot reference a column: $expression" }
    when (expression) {
        is SqlLiteral<*>,
        is SequenceNextValue,
        -> Unit

        is NumericBinaryExpression<*> -> {
            requireDefaultExpression(expression.left)
            requireDefaultExpression(expression.right)
        }

        is LegacyDefaultBinarySqlExpression<*> -> {
            requireDefaultExpression(expression.left)
            requireDefaultExpression(expression.right)
        }

        is CastExpression<*> -> requireDefaultExpression(expression.expression)

        is SqlFunctionExpression<*> -> {
            require(
                expression !is AggregateFunctionExpression<*>,
            ) { "Aggregate function cannot be a DEFAULT expression" }
            require(expression !is WindowFunctionExpression<*>) { "Window function cannot be a DEFAULT expression" }
            require(expression.function.isDefaultScalarFunction()) {
                "Function is not available as a generic DEFAULT expression: ${expression.function}"
            }
            expression.arguments.forEach(::requireDefaultExpression)
        }

        else -> throw IllegalArgumentException("Expression is not available as a DEFAULT expression: $expression")
    }
}

private fun SqlFunction.isDefaultScalarFunction(): Boolean = when (this) {
    SqlFunction.Lower,
    SqlFunction.Upper,
    SqlFunction.Length,
    SqlFunction.Trim,
    SqlFunction.LeftTrim,
    SqlFunction.RightTrim,
    SqlFunction.Substring,
    SqlFunction.Replace,
    SqlFunction.Concat,
    SqlFunction.Coalesce,
    SqlFunction.NullIf,
    SqlFunction.Abs,
    SqlFunction.Round,
    SqlFunction.Floor,
    SqlFunction.Ceiling,
    SqlFunction.SquareRoot,
    SqlFunction.Power,
    SqlFunction.CurrentDate,
    SqlFunction.CurrentTime,
    SqlFunction.CurrentTimestamp,
    -> true

    SqlFunction.Year,
    SqlFunction.Month,
    SqlFunction.Day,
    SqlFunction.Hour,
    SqlFunction.Minute,
    SqlFunction.Second,
    SqlFunction.Count,
    SqlFunction.Sum,
    SqlFunction.Average,
    SqlFunction.Minimum,
    SqlFunction.Maximum,
    SqlFunction.Grouping,
    SqlFunction.RowNumber,
    SqlFunction.Rank,
    SqlFunction.DenseRank,
    SqlFunction.PercentRank,
    SqlFunction.CumeDist,
    SqlFunction.Lag,
    SqlFunction.Lead,
    SqlFunction.FirstValue,
    SqlFunction.LastValue,
    SqlFunction.Ntile,
    SqlFunction.NthValue,
    is SqlFunction.Unsafe,
    -> false
}
