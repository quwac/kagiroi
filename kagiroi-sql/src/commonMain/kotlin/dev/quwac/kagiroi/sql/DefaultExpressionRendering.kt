package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.CastExpression
import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.schema.ColumnDefault
import dev.quwac.kagiroi.schema.LegacyDefaultBinarySqlExpression
import dev.quwac.kagiroi.schema.NumericBinaryExpression
import dev.quwac.kagiroi.schema.NumericBinaryOperator
import dev.quwac.kagiroi.schema.Sequence
import dev.quwac.kagiroi.schema.SequenceNextValue
import dev.quwac.kagiroi.schema.SqlCastTarget
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.SqlFunction
import dev.quwac.kagiroi.schema.SqlFunctionExpression
import dev.quwac.kagiroi.schema.SqlLiteral
import kotlin.uuid.Uuid

/** Renders a validated column DEFAULT expression without introducing DDL bind parameters. */
fun renderDefaultExpression(
    column: Column<*>,
    booleanLiteral: (Boolean) -> String,
    stringLiteral: (String) -> String,
    renderSequenceNextValue: (Sequence) -> String,
    renderCurrentInstant: () -> String,
    renderFunction: (SqlFunction, List<String>) -> String,
    renderCastTarget: (SqlCastTarget<*>) -> String,
    renderNumericBinary: (NumericBinaryOperator, String, String) -> String,
    parenthesizeFunctionOrCast: Boolean = false,
    supportsFunction: (SqlFunction) -> Boolean = { true },
): String {
    val default = requireNotNull(column.defaultExpression) { "Column has no DEFAULT expression: $column" }
    return renderDefaultExpression(
        column,
        default,
        booleanLiteral,
        stringLiteral,
        renderSequenceNextValue,
        renderCurrentInstant,
        renderFunction,
        renderCastTarget,
        renderNumericBinary,
        parenthesizeFunctionOrCast,
        supportsFunction,
    )
}

/** Renders independently snapshotted DEFAULT metadata for ALTER TABLE. */
fun renderDefaultExpression(
    column: Column<*>,
    default: ColumnDefault<*>,
    booleanLiteral: (Boolean) -> String,
    stringLiteral: (String) -> String,
    renderSequenceNextValue: (Sequence) -> String,
    renderCurrentInstant: () -> String,
    renderFunction: (SqlFunction, List<String>) -> String,
    renderCastTarget: (SqlCastTarget<*>) -> String,
    renderNumericBinary: (NumericBinaryOperator, String, String) -> String,
    parenthesizeFunctionOrCast: Boolean = false,
    supportsFunction: (SqlFunction) -> Boolean = { true },
): String {
    require(default.expression.type === column.type && default.expression.storageType === column.storageType) {
        "DEFAULT snapshot must use the column's logical and storage mapping: $column"
    }
    val expression = default.expression
    val rendered = renderExpression(
        expression,
        booleanLiteral,
        stringLiteral,
        renderSequenceNextValue,
        renderCurrentInstant,
        renderFunction,
        renderCastTarget,
        renderNumericBinary,
        supportsFunction,
    )
    return if (parenthesizeFunctionOrCast && expression.requiresOuterDefaultParentheses()) "($rendered)" else rendered
}

private fun renderExpression(
    expression: SqlExpression<*>,
    booleanLiteral: (Boolean) -> String,
    stringLiteral: (String) -> String,
    renderSequenceNextValue: (Sequence) -> String,
    renderCurrentInstant: () -> String,
    renderFunction: (SqlFunction, List<String>) -> String,
    renderCastTarget: (SqlCastTarget<*>) -> String,
    renderNumericBinary: (NumericBinaryOperator, String, String) -> String,
    supportsFunction: (SqlFunction) -> Boolean,
): String {
    fun renderNested(nested: SqlExpression<*>): String = renderExpression(
        nested,
        booleanLiteral,
        stringLiteral,
        renderSequenceNextValue,
        renderCurrentInstant,
        renderFunction,
        renderCastTarget,
        renderNumericBinary,
        supportsFunction,
    )

    return when (expression) {
        is SqlLiteral<*> -> renderDefaultLiteral(expression, booleanLiteral, stringLiteral)

        is SequenceNextValue -> renderSequenceNextValue(expression.sequence)

        is SqlFunctionExpression<*> -> {
            require(supportsFunction(expression.function)) {
                "Function is not supported in this dialect's DEFAULT expressions: ${expression.function}"
            }
            if (expression.function == SqlFunction.CurrentTimestamp) {
                require(expression.arguments.isEmpty()) { "currentTimestamp does not accept arguments" }
                renderCurrentInstant()
            } else {
                renderFunction(expression.function, expression.arguments.map(::renderNested))
            }
        }

        is NumericBinaryExpression<*> -> renderNumericBinary(
            expression.operator,
            renderNested(expression.left),
            renderNested(expression.right),
        )

        is LegacyDefaultBinarySqlExpression<*> ->
            "(${renderNested(expression.left)} ${expression.operator.sql} ${renderNested(expression.right)})"

        is CastExpression<*> -> "CAST(${renderNested(expression.expression)} AS ${renderCastTarget(expression.target)})"

        else -> error("Unsupported DEFAULT expression after validation: $expression")
    }
}

private fun SqlExpression<*>.requiresOuterDefaultParentheses(): Boolean = when (this) {
    is SqlFunctionExpression<*> -> function != SqlFunction.CurrentTimestamp
    is CastExpression<*> -> true
    else -> false
}

@Suppress("UNCHECKED_CAST") // SqlLiteral retains the mapping that created the DEFAULT metadata.
private fun renderDefaultLiteral(
    expression: SqlLiteral<*>,
    booleanLiteral: (Boolean) -> String,
    stringLiteral: (String) -> String,
): String = when (val storageValue = (expression as SqlLiteral<Any?>).toStorageValue(expression.value)) {
    null -> "NULL"

    is Boolean -> booleanLiteral(storageValue)

    is Byte,
    is Short,
    is Int,
    is Long,
    is UByte,
    is UShort,
    is UInt,
    is ULong,
    is Float,
    is Double,
    -> storageValue.toString()

    is String,
    is Char,
    is Uuid,
    -> stringLiteral(storageValue.toString())

    is ByteArray -> throw UnsupportedDdlOperation(
        "DEFAULT literal",
        "binary defaults are dialect-specific",
    )

    else -> stringLiteral(storageValue.toString())
}
