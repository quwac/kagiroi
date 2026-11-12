package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.CaseExpression
import dev.quwac.kagiroi.schema.CastExpression
import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.schema.ExpressionComparison
import dev.quwac.kagiroi.schema.ExpressionNullComparison
import dev.quwac.kagiroi.schema.GeneratedColumnBinaryExpression
import dev.quwac.kagiroi.schema.GeneratedColumnExpression
import dev.quwac.kagiroi.schema.LogicalPredicate
import dev.quwac.kagiroi.schema.NullComparison
import dev.quwac.kagiroi.schema.SqlCastTarget
import dev.quwac.kagiroi.schema.SqlFunction
import dev.quwac.kagiroi.schema.SqlFunctionExpression
import dev.quwac.kagiroi.schema.ValueComparison

/** Renders the portable, value-free generated-column expression subset with dialect-owned quoting. */
fun renderGeneratedColumnExpression(
    expression: GeneratedColumnExpression<*>,
    quoteColumn: (String) -> String,
    renderFunction: (SqlFunction, List<String>) -> String = ::defaultFunction,
    renderCastTarget: (SqlCastTarget<*>) -> String = { target ->
        throw UnsupportedDdlOperation("generated column CAST", "no CAST target mapping for ${target.type.name}")
    },
): String = "(${renderExpression(expression, quoteColumn, renderFunction, renderCastTarget)})"

private fun renderExpression(
    expression: GeneratedColumnExpression<*>,
    quoteColumn: (String) -> String,
    renderFunction: (SqlFunction, List<String>) -> String,
    renderCastTarget: (SqlCastTarget<*>) -> String,
): String = when (expression) {
    is Column<*> -> quoteColumn(expression.name)

    is GeneratedColumnBinaryExpression<*> -> renderOperand(
        expression.left,
        quoteColumn,
        renderFunction,
        renderCastTarget,
    ) +
        " ${expression.operator.sql} " +
        renderOperand(expression.right, quoteColumn, renderFunction, renderCastTarget)

    is SqlFunctionExpression<*> -> {
        val arguments = expression.arguments.map { argument ->
            renderOperand(
                argument as? GeneratedColumnExpression<*>
                    ?: throw UnsupportedDdlOperation(
                        "generated column expression",
                        "function arguments must be expressions",
                    ),
                quoteColumn,
                renderFunction,
                renderCastTarget,
            )
        }
        checkedGeneratedFunction(expression.function, arguments, renderFunction)
    }

    is CastExpression<*> -> "CAST(${renderGeneratedExpression(
        expression.expression,
        quoteColumn,
        renderFunction,
        renderCastTarget,
    )} AS ${renderCastTarget(expression.target)})"

    is CaseExpression<*> -> buildString {
        append("CASE")
        expression.branches.forEach { branch ->
            append(" WHEN ")
            append(renderGeneratedPredicate(branch.condition, quoteColumn, renderFunction, renderCastTarget))
            append(" THEN ")
            append(renderGeneratedExpression(branch.result, quoteColumn, renderFunction, renderCastTarget))
        }
        append(" ELSE ")
        append(renderGeneratedExpression(expression.elseResult, quoteColumn, renderFunction, renderCastTarget))
        append(" END")
    }

    else -> throw UnsupportedDdlOperation("generated column expression", "unsupported expression: $expression")
}

private fun renderOperand(
    expression: GeneratedColumnExpression<*>,
    quoteColumn: (String) -> String,
    renderFunction: (SqlFunction, List<String>) -> String,
    renderCastTarget: (SqlCastTarget<*>) -> String,
): String = when (expression) {
    is Column<*> -> renderExpression(expression, quoteColumn, renderFunction, renderCastTarget)

    is GeneratedColumnBinaryExpression<*> -> "(${renderExpression(
        expression,
        quoteColumn,
        renderFunction,
        renderCastTarget,
    )})"

    is SqlFunctionExpression<*> -> renderExpression(expression, quoteColumn, renderFunction, renderCastTarget)

    is CastExpression<*> -> renderExpression(expression, quoteColumn, renderFunction, renderCastTarget)

    is CaseExpression<*> -> renderExpression(expression, quoteColumn, renderFunction, renderCastTarget)

    else -> throw UnsupportedDdlOperation("generated column expression", "unsupported expression: $expression")
}

private fun renderGeneratedExpression(
    expression: dev.quwac.kagiroi.schema.SqlExpression<*>,
    quoteColumn: (String) -> String,
    renderFunction: (SqlFunction, List<String>) -> String,
    renderCastTarget: (SqlCastTarget<*>) -> String,
): String = renderExpression(
    expression as? GeneratedColumnExpression<*>
        ?: throw UnsupportedDdlOperation("generated column expression", "expression must be value-free: $expression"),
    quoteColumn,
    renderFunction,
    renderCastTarget,
)

private fun renderGeneratedPredicate(
    predicate: dev.quwac.kagiroi.schema.BooleanExpression,
    quoteColumn: (String) -> String,
    renderFunction: (SqlFunction, List<String>) -> String,
    renderCastTarget: (SqlCastTarget<*>) -> String,
): String = when (predicate) {
    is ExpressionComparison<*> ->
        "${renderGeneratedExpression(predicate.left, quoteColumn, renderFunction, renderCastTarget)} " +
            "${predicate.operator.sql} " +
            renderGeneratedExpression(predicate.right, quoteColumn, renderFunction, renderCastTarget)

    is NullComparison -> "${quoteColumn(predicate.column.name)} ${predicate.operator.sql}"

    is ExpressionNullComparison ->
        "${renderGeneratedExpression(predicate.expression, quoteColumn, renderFunction, renderCastTarget)} " +
            predicate.operator.sql

    is LogicalPredicate -> "(${renderGeneratedPredicate(
        predicate.left,
        quoteColumn,
        renderFunction,
        renderCastTarget,
    )} ${predicate.operator.sql} ${renderGeneratedPredicate(
        predicate.right,
        quoteColumn,
        renderFunction,
        renderCastTarget,
    )})"

    is ValueComparison<*> -> throw UnsupportedDdlOperation(
        "generated column CASE",
        "value predicates are not part of the value-free generated-column subset",
    )

    else -> throw UnsupportedDdlOperation(
        "generated column CASE",
        "predicate is not part of the value-free generated-column subset",
    )
}

@Suppress("ThrowsCount") // A generated expression must reject every non-portable function explicitly.
private fun defaultFunction(function: SqlFunction, arguments: List<String>): String {
    val name = when (function) {
        SqlFunction.PostgresCurrentSetting -> throw UnsupportedDdlOperation("current_setting", "requires PostgreSQL")

        SqlFunction.Lower -> "LOWER"

        SqlFunction.Upper -> "UPPER"

        SqlFunction.Length -> "LENGTH"

        SqlFunction.Trim -> "TRIM"

        SqlFunction.LeftTrim -> "LTRIM"

        SqlFunction.RightTrim -> "RTRIM"

        SqlFunction.Substring,
        SqlFunction.Replace,
        SqlFunction.Concat,
        SqlFunction.NullIf,
        SqlFunction.Round,
        SqlFunction.Floor,
        SqlFunction.Ceiling,
        SqlFunction.SquareRoot,
        SqlFunction.Power,
        SqlFunction.CurrentDate,
        SqlFunction.CurrentTime,
        SqlFunction.CurrentTimestamp,
        SqlFunction.Year,
        SqlFunction.Month,
        SqlFunction.Day,
        SqlFunction.Hour,
        SqlFunction.Minute,
        SqlFunction.Second,
        -> throw UnsupportedDdlOperation(
            "generated column expression",
            "function is not part of the portable value-free generated-column subset",
        )

        SqlFunction.Coalesce -> "COALESCE"

        SqlFunction.Abs -> "ABS"

        SqlFunction.Count, SqlFunction.Sum, SqlFunction.Average, SqlFunction.Minimum, SqlFunction.Maximum,
        SqlFunction.Grouping,
        SqlFunction.RowNumber, SqlFunction.Rank, SqlFunction.DenseRank, SqlFunction.PercentRank, SqlFunction.CumeDist,
        SqlFunction.Lag, SqlFunction.Lead, SqlFunction.FirstValue, SqlFunction.LastValue,
        SqlFunction.Ntile, SqlFunction.NthValue,
        ->
            throw UnsupportedDdlOperation(
                "generated column expression",
                "aggregate or window functions are not allowed",
            )

        is SqlFunction.Unsafe -> throw UnsupportedDdlOperation(
            "generated column expression",
            "unsafe functions are not allowed",
        )
    }
    return "$name(${arguments.joinToString(", ")})"
}

private fun checkedGeneratedFunction(
    function: SqlFunction,
    arguments: List<String>,
    renderFunction: (SqlFunction, List<String>) -> String,
): String = when (function) {
    SqlFunction.Count, SqlFunction.Sum, SqlFunction.Average, SqlFunction.Minimum, SqlFunction.Maximum,
    SqlFunction.Grouping,
    SqlFunction.RowNumber, SqlFunction.Rank, SqlFunction.DenseRank, SqlFunction.PercentRank, SqlFunction.CumeDist,
    SqlFunction.Lag, SqlFunction.Lead, SqlFunction.FirstValue, SqlFunction.LastValue,
    SqlFunction.Ntile, SqlFunction.NthValue,
    ->
        throw UnsupportedDdlOperation("generated column expression", "aggregate or window functions are not allowed")

    is SqlFunction.Unsafe -> throw UnsupportedDdlOperation(
        "generated column expression",
        "unsafe functions are not allowed",
    )

    SqlFunction.Substring,
    SqlFunction.Replace,
    SqlFunction.Concat,
    SqlFunction.NullIf,
    SqlFunction.Round,
    SqlFunction.Floor,
    SqlFunction.Ceiling,
    SqlFunction.SquareRoot,
    SqlFunction.Power,
    SqlFunction.CurrentDate,
    SqlFunction.CurrentTime,
    SqlFunction.CurrentTimestamp,
    SqlFunction.Year,
    SqlFunction.Month,
    SqlFunction.Day,
    SqlFunction.Hour,
    SqlFunction.Minute,
    SqlFunction.Second,
    -> throw UnsupportedDdlOperation(
        "generated column expression",
        "function is not part of the portable value-free generated-column subset",
    )

    else -> renderFunction(function, arguments)
}
