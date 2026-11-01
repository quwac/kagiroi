package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.BooleanExpression
import dev.quwac.kagiroi.schema.CaseExpression
import dev.quwac.kagiroi.schema.CastExpression
import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.schema.ColumnIndexKey
import dev.quwac.kagiroi.schema.ExpressionComparison
import dev.quwac.kagiroi.schema.ExpressionIndexKey
import dev.quwac.kagiroi.schema.ExpressionNullComparison
import dev.quwac.kagiroi.schema.ExpressionValueComparison
import dev.quwac.kagiroi.schema.GeneratedColumnBinaryExpression
import dev.quwac.kagiroi.schema.IndexKey
import dev.quwac.kagiroi.schema.LikeExpressionComparison
import dev.quwac.kagiroi.schema.LikeValueComparison
import dev.quwac.kagiroi.schema.LogicalPredicate
import dev.quwac.kagiroi.schema.NullComparison
import dev.quwac.kagiroi.schema.NumericBinaryExpression
import dev.quwac.kagiroi.schema.NumericBinaryOperator
import dev.quwac.kagiroi.schema.SchemaIndex
import dev.quwac.kagiroi.schema.SqlCastTarget
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.SqlFunction
import dev.quwac.kagiroi.schema.SqlFunctionExpression
import dev.quwac.kagiroi.schema.SqlLiteral
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.ValueComparison
import kotlin.uuid.Uuid

/**
 * Renders immutable [SchemaIndex] metadata without query parameters.
 *
 * Query rendering remains parameterized. Index definitions are schema metadata, so literals are
 * encoded through their typed expression mapping and rendered with dialect-owned literal syntax.
 */
fun renderSchemaIndex(
    index: SchemaIndex,
    quoteIdentifier: (String) -> String,
    quoteTable: (Table) -> String,
    quoteIndexName: (SchemaIndex) -> String,
    booleanLiteral: (Boolean) -> String,
    stringLiteral: (String) -> String,
    renderFunction: (SqlFunction, List<String>) -> String,
    renderCastTarget: (SqlCastTarget<*>) -> String,
    renderNumericBinary: (NumericBinaryOperator, String, String) -> String,
    supportsExpressionKeys: Boolean,
    supportsPredicate: Boolean,
    supportsIncludedColumns: Boolean,
    expressionKeyNeedsParentheses: Boolean,
): String = buildString {
    append("CREATE ")
    if (index.unique) append("UNIQUE ")
    append("INDEX ")
    append(quoteIndexName(index))
    append(" ON ")
    append(quoteTable(index.table))
    append(" (")
    append(
        index.keys.joinToString(", ") { key ->
            renderIndexKey(
                key,
                quoteIdentifier,
                booleanLiteral,
                stringLiteral,
                renderFunction,
                renderCastTarget,
                renderNumericBinary,
                supportsExpressionKeys,
                expressionKeyNeedsParentheses,
            )
        },
    )
    append(')')
    if (index.includedColumns.isNotEmpty()) {
        if (!supportsIncludedColumns) {
            throw UnsupportedDdlOperation(
                "index INCLUDE columns",
                "this dialect has no portable INCLUDE clause",
            )
        }
        append(" INCLUDE (")
        append(index.includedColumns.joinToString(", ") { column -> quoteIdentifier(column.name) })
        append(')')
    }
    index.predicate?.let { predicate ->
        if (!supportsPredicate) {
            throw UnsupportedDdlOperation(
                "partial or filtered index",
                "this dialect does not support a generic index predicate",
            )
        }
        append(" WHERE ")
        append(
            renderIndexPredicate(
                predicate,
                quoteIdentifier,
                booleanLiteral,
                stringLiteral,
                renderFunction,
                renderCastTarget,
                renderNumericBinary,
            ),
        )
    }
}

private fun renderIndexKey(
    key: IndexKey,
    quoteIdentifier: (String) -> String,
    booleanLiteral: (Boolean) -> String,
    stringLiteral: (String) -> String,
    renderFunction: (SqlFunction, List<String>) -> String,
    renderCastTarget: (SqlCastTarget<*>) -> String,
    renderNumericBinary: (NumericBinaryOperator, String, String) -> String,
    supportsExpressionKeys: Boolean,
    expressionKeyNeedsParentheses: Boolean,
): String {
    val rendered = when (key) {
        is ColumnIndexKey -> quoteIdentifier(key.column.name)

        is ExpressionIndexKey -> {
            if (!supportsExpressionKeys) {
                throw UnsupportedDdlOperation(
                    "expression index",
                    "this dialect requires a dialect-specific generated or computed-column design",
                )
            }
            val expression = renderIndexExpression(
                key.expression,
                quoteIdentifier,
                booleanLiteral,
                stringLiteral,
                renderFunction,
                renderCastTarget,
                renderNumericBinary,
            )
            if (expressionKeyNeedsParentheses) "($expression)" else expression
        }
    }
    return rendered + (key.direction?.let { " ${it.sql}" } ?: "")
}

@Suppress("LongMethod")
private fun renderIndexExpression(
    expression: SqlExpression<*>,
    quoteIdentifier: (String) -> String,
    booleanLiteral: (Boolean) -> String,
    stringLiteral: (String) -> String,
    renderFunction: (SqlFunction, List<String>) -> String,
    renderCastTarget: (SqlCastTarget<*>) -> String,
    renderNumericBinary: (NumericBinaryOperator, String, String) -> String,
): String {
    fun renderNested(nested: SqlExpression<*>): String = renderIndexExpression(
        nested,
        quoteIdentifier,
        booleanLiteral,
        stringLiteral,
        renderFunction,
        renderCastTarget,
        renderNumericBinary,
    )

    return when (expression) {
        is Column<*> -> quoteIdentifier(expression.name)

        is SqlLiteral<*> -> {
            @Suppress("UNCHECKED_CAST")
            val literal = expression as SqlLiteral<Any?>
            renderIndexLiteral(
                expression,
                literal.toStorageValue(literal.value),
                booleanLiteral,
                stringLiteral,
            )
        }

        is SqlFunctionExpression<*> -> renderFunction(expression.function, expression.arguments.map(::renderNested))

        is NumericBinaryExpression<*> -> renderNumericBinary(
            expression.operator,
            renderNested(expression.left),
            renderNested(expression.right),
        )

        is GeneratedColumnBinaryExpression<*> ->
            "(${renderNested(expression.left)} ${expression.operator.sql} ${renderNested(expression.right)})"

        is CastExpression<*> -> "CAST(${renderNested(expression.expression)} AS ${renderCastTarget(expression.target)})"

        is CaseExpression<*> -> buildString {
            append("CASE")
            expression.branches.forEach { branch ->
                append(" WHEN ")
                append(
                    renderIndexPredicate(
                        branch.condition,
                        quoteIdentifier,
                        booleanLiteral,
                        stringLiteral,
                        renderFunction,
                        renderCastTarget,
                        renderNumericBinary,
                    ),
                )
                append(" THEN ")
                append(renderNested(branch.result))
            }
            append(" ELSE ")
            append(renderNested(expression.elseResult))
            append(" END")
        }

        else -> error("Index expression was not validated: $expression")
    }
}

@Suppress("LongMethod")
private fun renderIndexPredicate(
    predicate: BooleanExpression,
    quoteIdentifier: (String) -> String,
    booleanLiteral: (Boolean) -> String,
    stringLiteral: (String) -> String,
    renderFunction: (SqlFunction, List<String>) -> String,
    renderCastTarget: (SqlCastTarget<*>) -> String,
    renderNumericBinary: (NumericBinaryOperator, String, String) -> String,
): String = when (predicate) {
    is ValueComparison<*> -> "${quoteIdentifier(predicate.column.name)} ${predicate.operator.sql} " +
        renderIndexLiteral(predicate.column, predicate.storageValue(), booleanLiteral, stringLiteral)

    is ExpressionValueComparison<*> -> "${renderIndexExpression(
        predicate.expression,
        quoteIdentifier,
        booleanLiteral,
        stringLiteral,
        renderFunction,
        renderCastTarget,
        renderNumericBinary,
    )} ${predicate.operator.sql} " +
        renderIndexLiteral(predicate.expression, predicate.storageValue(), booleanLiteral, stringLiteral)

    is ExpressionComparison<*> -> "${renderIndexExpression(
        predicate.left,
        quoteIdentifier,
        booleanLiteral,
        stringLiteral,
        renderFunction,
        renderCastTarget,
        renderNumericBinary,
    )} ${predicate.operator.sql} ${renderIndexExpression(
        predicate.right,
        quoteIdentifier,
        booleanLiteral,
        stringLiteral,
        renderFunction,
        renderCastTarget,
        renderNumericBinary,
    )}"

    is LikeValueComparison<*> -> "${renderIndexExpression(
        predicate.expression,
        quoteIdentifier,
        booleanLiteral,
        stringLiteral,
        renderFunction,
        renderCastTarget,
        renderNumericBinary,
    )} ${predicate.operator.sql} ${stringLiteral(predicate.pattern)}"

    is LikeExpressionComparison<*> -> "${renderIndexExpression(
        predicate.expression,
        quoteIdentifier,
        booleanLiteral,
        stringLiteral,
        renderFunction,
        renderCastTarget,
        renderNumericBinary,
    )} ${predicate.operator.sql} ${renderIndexExpression(
        predicate.pattern,
        quoteIdentifier,
        booleanLiteral,
        stringLiteral,
        renderFunction,
        renderCastTarget,
        renderNumericBinary,
    )}"

    is NullComparison -> "${quoteIdentifier(predicate.column.name)} ${predicate.operator.sql}"

    is ExpressionNullComparison -> "${renderIndexExpression(
        predicate.expression,
        quoteIdentifier,
        booleanLiteral,
        stringLiteral,
        renderFunction,
        renderCastTarget,
        renderNumericBinary,
    )} ${predicate.operator.sql}"

    is LogicalPredicate -> "(${renderIndexPredicate(
        predicate.left,
        quoteIdentifier,
        booleanLiteral,
        stringLiteral,
        renderFunction,
        renderCastTarget,
        renderNumericBinary,
    )} ${predicate.operator.sql} ${renderIndexPredicate(
        predicate.right,
        quoteIdentifier,
        booleanLiteral,
        stringLiteral,
        renderFunction,
        renderCastTarget,
        renderNumericBinary,
    )})"

    else -> error("Index predicate was not validated: $predicate")
}

private fun renderIndexLiteral(
    expression: SqlExpression<*>,
    value: Any?,
    booleanLiteral: (Boolean) -> String,
    stringLiteral: (String) -> String,
): String = when (value) {
    null -> "NULL"
    is Boolean -> booleanLiteral(value)
    is Byte, is Short, is Int, is Long, is UByte, is UShort, is UInt, is ULong, is Float, is Double -> value.toString()
    is String, is Char, is Uuid -> stringLiteral(value.toString())
    is ByteArray -> throw UnsupportedDdlOperation("Index literal", "binary literal is dialect-specific for $expression")
    else -> stringLiteral(value.toString())
}
