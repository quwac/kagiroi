package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.BooleanExpression
import dev.quwac.kagiroi.schema.CaseExpression
import dev.quwac.kagiroi.schema.CastExpression
import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.schema.ExpressionComparison
import dev.quwac.kagiroi.schema.ExpressionNullComparison
import dev.quwac.kagiroi.schema.ExpressionValueComparison
import dev.quwac.kagiroi.schema.ForeignKeyAction
import dev.quwac.kagiroi.schema.ForeignKeyConstraint
import dev.quwac.kagiroi.schema.LikeExpressionComparison
import dev.quwac.kagiroi.schema.LikeValueComparison
import dev.quwac.kagiroi.schema.LogicalPredicate
import dev.quwac.kagiroi.schema.NullComparison
import dev.quwac.kagiroi.schema.PrimaryKeyConstraint
import dev.quwac.kagiroi.schema.SqlCastTarget
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.SqlFunction
import dev.quwac.kagiroi.schema.SqlFunctionExpression
import dev.quwac.kagiroi.schema.SqlLiteral
import dev.quwac.kagiroi.schema.UniqueConstraint
import dev.quwac.kagiroi.schema.ValueComparison
import kotlin.uuid.Uuid

/** Renders the common table-constraint grammar while leaving identifiers and literals dialect-owned. */
fun renderTableConstraints(
    statement: CreateTable,
    quoteIdentifier: (String) -> String,
    quoteTable: (dev.quwac.kagiroi.schema.Table) -> String,
    booleanLiteral: (Boolean) -> String,
    stringLiteral: (String) -> String,
    renderForeignKeyAction: (String, ForeignKeyAction) -> String,
    renderUniqueComment: (UniqueConstraint) -> String = { "" },
    renderFunction: (SqlFunction, List<String>) -> String = ::defaultFunction,
    renderCastTarget: (SqlCastTarget<*>) -> String = { target ->
        throw UnsupportedDdlOperation("CHECK CAST", "no CAST target mapping for ${target.type.name}")
    },
): List<String> = buildList {
    statement.primaryKey?.let { add(renderPrimaryKeyConstraint(it, quoteIdentifier)) }
    statement.uniqueConstraints.forEach { unique ->
        add(renderUniqueConstraint(unique, quoteIdentifier, renderUniqueComment(unique)))
    }
    statement.foreignKeys.forEach {
        add(
            renderForeignKeyConstraint(it, quoteIdentifier, quoteTable, renderForeignKeyAction),
        )
    }
    statement.checks.forEach { check ->
        add(
            buildString {
                check.name?.let { append("CONSTRAINT ").append(quoteIdentifier(it)).append(' ') }
                append("CHECK (")
                append(
                    renderCheckConstraintExpression(
                        check.expression,
                        quoteIdentifier,
                        booleanLiteral,
                        stringLiteral,
                        renderFunction,
                        renderCastTarget,
                    ),
                )
                append(')')
            },
        )
    }
}

/** Renders a primary-key constraint using the dialect's identifier quoting. */
fun renderPrimaryKeyConstraint(constraint: PrimaryKeyConstraint, quoteIdentifier: (String) -> String): String =
    buildString {
        constraint.name?.let { append("CONSTRAINT ").append(quoteIdentifier(it)).append(' ') }
        append("PRIMARY KEY (")
        append(constraint.columns.joinToString(", ") { quoteIdentifier(it.name) })
        append(')')
    }

/** Renders a UNIQUE constraint and appends dialect-specific comment syntax. */
fun renderUniqueConstraint(constraint: UniqueConstraint, quoteIdentifier: (String) -> String, comment: String): String =
    buildString {
        constraint.name?.let { append("CONSTRAINT ").append(quoteIdentifier(it)).append(' ') }
        append("UNIQUE (")
        append(constraint.columns.joinToString(", ") { quoteIdentifier(it.name) })
        append(')')
        append(comment)
    }

/** Renders a foreign-key constraint with dialect-provided identifier and action syntax. */
fun renderForeignKeyConstraint(
    constraint: ForeignKeyConstraint,
    quoteIdentifier: (String) -> String,
    quoteTable: (dev.quwac.kagiroi.schema.Table) -> String,
    renderForeignKeyAction: (String, ForeignKeyAction) -> String,
): String = buildString {
    constraint.name?.let { append("CONSTRAINT ").append(quoteIdentifier(it)).append(' ') }
    append("FOREIGN KEY (")
    append(constraint.sourceColumns.joinToString(", ") { quoteIdentifier(it.name) })
    append(") REFERENCES ")
    append(quoteTable(constraint.targetTable))
    append(" (")
    append(constraint.targetColumns.joinToString(", ") { quoteIdentifier(it.name) })
    append(')')
    constraint.onDelete?.let { append(renderForeignKeyAction("DELETE", it)) }
    constraint.onUpdate?.let { append(renderForeignKeyAction("UPDATE", it)) }
}

/** Renders a typed CHECK expression using dialect-provided literal and function syntax. */
@Suppress("LongMethod")
fun renderCheckConstraintExpression(
    expression: BooleanExpression,
    quoteIdentifier: (String) -> String,
    booleanLiteral: (Boolean) -> String,
    stringLiteral: (String) -> String,
    renderFunction: (SqlFunction, List<String>) -> String,
    renderCastTarget: (SqlCastTarget<*>) -> String,
): String = when (expression) {
    is ValueComparison<*> -> {
        "${quoteIdentifier(expression.column.name)} ${expression.operator.sql} " +
            renderCheckLiteral(expression.column, expression.storageValue(), booleanLiteral, stringLiteral)
    }

    is ExpressionValueComparison<*> -> {
        "${renderCheckExpressionValue(
            expression.expression,
            quoteIdentifier,
            booleanLiteral,
            stringLiteral,
            renderFunction,
            renderCastTarget,
        )} " +
            "${expression.operator.sql} " +
            renderCheckLiteral(expression.expression, expression.storageValue(), booleanLiteral, stringLiteral)
    }

    is ExpressionComparison<*> ->
        "${renderCheckExpressionValue(
            expression.left,
            quoteIdentifier,
            booleanLiteral,
            stringLiteral,
            renderFunction,
            renderCastTarget,
        )} ${expression.operator.sql} " +
            renderCheckExpressionValue(
                expression.right,
                quoteIdentifier,
                booleanLiteral,
                stringLiteral,
                renderFunction,
                renderCastTarget,
            )

    is LikeValueComparison<*> ->
        "${renderCheckExpressionValue(
            expression.expression,
            quoteIdentifier,
            booleanLiteral,
            stringLiteral,
            renderFunction,
            renderCastTarget,
        )} ${expression.operator.sql} ${stringLiteral(expression.pattern)}"

    is LikeExpressionComparison<*> ->
        "${renderCheckExpressionValue(
            expression.expression,
            quoteIdentifier,
            booleanLiteral,
            stringLiteral,
            renderFunction,
            renderCastTarget,
        )} ${expression.operator.sql} " +
            renderCheckExpressionValue(
                expression.pattern,
                quoteIdentifier,
                booleanLiteral,
                stringLiteral,
                renderFunction,
                renderCastTarget,
            )

    is NullComparison -> "${quoteIdentifier(expression.column.name)} ${expression.operator.sql}"

    is ExpressionNullComparison ->
        "${renderCheckExpressionValue(
            expression.expression,
            quoteIdentifier,
            booleanLiteral,
            stringLiteral,
            renderFunction,
            renderCastTarget,
        )} ${expression.operator.sql}"

    is LogicalPredicate -> "(" + renderCheckConstraintExpression(
        expression.left,
        quoteIdentifier,
        booleanLiteral,
        stringLiteral,
        renderFunction,
        renderCastTarget,
    ) + " ${expression.operator.sql} " + renderCheckConstraintExpression(
        expression.right,
        quoteIdentifier,
        booleanLiteral,
        stringLiteral,
        renderFunction,
        renderCastTarget,
    ) + ")"

    else -> throw UnsupportedDdlOperation(
        "CHECK expression",
        "subqueries and query-only predicates are not allowed",
    )
}

@Suppress("LongMethod")
private fun renderCheckExpressionValue(
    expression: SqlExpression<*>,
    quoteIdentifier: (String) -> String,
    booleanLiteral: (Boolean) -> String,
    stringLiteral: (String) -> String,
    renderFunction: (SqlFunction, List<String>) -> String,
    renderCastTarget: (SqlCastTarget<*>) -> String,
): String = when (expression) {
    is Column<*> -> quoteIdentifier(expression.name)

    is SqlLiteral<*> -> renderCheckLiteral(
        expression,
        expression.toStorageValue(expression.value),
        booleanLiteral,
        stringLiteral,
    )

    is SqlFunctionExpression<*> -> {
        val arguments = expression.arguments.map {
            renderCheckExpressionValue(
                it,
                quoteIdentifier,
                booleanLiteral,
                stringLiteral,
                renderFunction,
                renderCastTarget,
            )
        }
        checkedCheckFunction(expression.function, arguments, renderFunction)
    }

    is CastExpression<*> -> "CAST(${renderCheckExpressionValue(
        expression.expression,
        quoteIdentifier,
        booleanLiteral,
        stringLiteral,
        renderFunction,
        renderCastTarget,
    )} AS ${renderCastTarget(expression.target)})"

    is CaseExpression<*> -> buildString {
        append("CASE")
        expression.branches.forEach { branch ->
            append(" WHEN ")
            append(
                renderCheckConstraintExpression(
                    branch.condition,
                    quoteIdentifier,
                    booleanLiteral,
                    stringLiteral,
                    renderFunction,
                    renderCastTarget,
                ),
            )
            append(" THEN ")
            append(
                renderCheckExpressionValue(
                    branch.result,
                    quoteIdentifier,
                    booleanLiteral,
                    stringLiteral,
                    renderFunction,
                    renderCastTarget,
                ),
            )
        }
        append(" ELSE ")
        append(
            renderCheckExpressionValue(
                expression.elseResult,
                quoteIdentifier,
                booleanLiteral,
                stringLiteral,
                renderFunction,
                renderCastTarget,
            ),
        )
        append(" END")
    }

    else -> throw UnsupportedDdlOperation("CHECK expression", "unsupported expression: $expression")
}

private fun renderCheckLiteral(
    expression: SqlExpression<*>,
    value: Any?,
    booleanLiteral: (Boolean) -> String,
    stringLiteral: (String) -> String,
): String = when (value) {
    null -> throw UnsupportedDdlOperation("CHECK literal", "comparison literals must be non-null for $expression")

    is Boolean -> booleanLiteral(value)

    is Byte, is Short, is Int, is Long, is UByte, is UShort, is UInt, is ULong, is Float, is Double -> value.toString()

    is String, is Char, is Uuid -> stringLiteral(value.toString())

    is ByteArray -> throw UnsupportedDdlOperation(
        "CHECK literal",
        "binary literals are dialect-specific for $expression",
    )

    else -> stringLiteral(value.toString())
}

@Suppress("CyclomaticComplexMethod", "ThrowsCount")
// Exhaustive closed catalog; unsupported functions must fail explicitly.
private fun defaultFunction(function: SqlFunction, arguments: List<String>): String {
    val name = when (function) {
        SqlFunction.PostgresAnyEquals -> throw UnsupportedDdlOperation("array ANY", "requires PostgreSQL")

        SqlFunction.PostgresAtTimeZone -> throw UnsupportedDdlOperation("AT TIME ZONE", "requires PostgreSQL")

        SqlFunction.PostgresCurrentSetting -> throw UnsupportedDdlOperation("current_setting", "requires PostgreSQL")

        SqlFunction.Lower -> "LOWER"

        SqlFunction.Upper -> "UPPER"

        SqlFunction.Length -> "LENGTH"

        SqlFunction.Trim -> "TRIM"

        SqlFunction.LeftTrim -> "LTRIM"

        SqlFunction.RightTrim -> "RTRIM"

        SqlFunction.Substring -> "SUBSTRING"

        SqlFunction.Replace -> "REPLACE"

        SqlFunction.Concat -> "CONCAT"

        SqlFunction.Coalesce -> "COALESCE"

        SqlFunction.NullIf -> "NULLIF"

        SqlFunction.Abs -> "ABS"

        SqlFunction.Round -> "ROUND"

        SqlFunction.Floor -> "FLOOR"

        SqlFunction.Ceiling -> "CEIL"

        SqlFunction.SquareRoot -> "SQRT"

        SqlFunction.Power -> "POWER"

        SqlFunction.Year -> "YEAR"

        SqlFunction.Month -> "MONTH"

        SqlFunction.Day -> "DAY"

        SqlFunction.Hour -> "HOUR"

        SqlFunction.Minute -> "MINUTE"

        SqlFunction.Second -> "SECOND"

        SqlFunction.CurrentDate,
        SqlFunction.CurrentTime,
        SqlFunction.CurrentTimestamp,
        -> throw UnsupportedDdlOperation("CHECK expression", "current date/time functions are not portable in CHECK")

        SqlFunction.Count, SqlFunction.Sum, SqlFunction.Average, SqlFunction.Minimum, SqlFunction.Maximum,
        SqlFunction.Grouping,
        SqlFunction.RowNumber, SqlFunction.Rank, SqlFunction.DenseRank, SqlFunction.PercentRank, SqlFunction.CumeDist,
        SqlFunction.Lag, SqlFunction.Lead, SqlFunction.FirstValue, SqlFunction.LastValue,
        SqlFunction.Ntile, SqlFunction.NthValue,
        ->
            throw UnsupportedDdlOperation("CHECK expression", "aggregate or window functions are not allowed")

        is SqlFunction.Unsafe -> throw UnsupportedDdlOperation("CHECK expression", "unsafe functions are not allowed")
    }
    return "$name(${arguments.joinToString(", ")})"
}

private fun checkedCheckFunction(
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
        throw UnsupportedDdlOperation("CHECK expression", "aggregate or window functions are not allowed")

    is SqlFunction.Unsafe -> throw UnsupportedDdlOperation("CHECK expression", "unsafe functions are not allowed")

    SqlFunction.CurrentDate,
    SqlFunction.CurrentTime,
    SqlFunction.CurrentTimestamp,
    -> throw UnsupportedDdlOperation("CHECK expression", "current date/time functions are not portable in CHECK")

    else -> renderFunction(function, arguments)
}
