package dev.quwac.kagiroi.postgres

import dev.quwac.kagiroi.schema.BooleanColumnType
import dev.quwac.kagiroi.schema.BooleanExpression
import dev.quwac.kagiroi.schema.ComparisonOperator
import dev.quwac.kagiroi.schema.ExpressionValueComparison
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.SqlFunction
import dev.quwac.kagiroi.schema.SqlFunctionExpression
import dev.quwac.kagiroi.schema.castTarget
import dev.quwac.kagiroi.schema.castTo
import dev.quwac.kagiroi.schema.optional
import dev.quwac.kagiroi.schema.resultExpression
import kotlin.js.JsName
import kotlin.uuid.Uuid

/** A PostgreSQL array expression whose element type is retained for comparisons. */
class PostgresArrayExpression<T : Any> internal constructor(internal val expression: SqlExpression<*>)

/** Casts PostgreSQL array text to an array with the declared element type. */
@JsName("asPostgresArrayNonNull")
fun <T : Any> SqlExpression<String>.asPostgresArray(element: PostgresColumnType<T, *>): PostgresArrayExpression<T> =
    PostgresArrayExpression(castTo(castTarget(resultExpression(PostgresTypes.array(element)))))

/** Casts nullable PostgreSQL array text, preserving a missing array as SQL NULL. */
@JsName("asPostgresArrayNullable")
fun <T : Any> SqlExpression<String?>.asPostgresArray(element: PostgresColumnType<T, *>): PostgresArrayExpression<T> =
    PostgresArrayExpression(castTo(castTarget(resultExpression(PostgresTypes.array(element)))))

/** Tests native PostgreSQL = ANY; an empty array is false and a NULL array remains unknown. */
@JsName("eqAnyNonNull")
fun <T : Any> SqlExpression<T>.eqAny(array: PostgresArrayExpression<T>): BooleanExpression = arrayAnyEquals(this, array)

/** Tests native PostgreSQL = ANY for a nullable value without replacing SQL NULL with a value. */
@JsName("eqAnyNullable")
fun <T : Any> SqlExpression<T?>.eqAny(array: PostgresArrayExpression<T>): BooleanExpression = arrayAnyEquals(
    this,
    array,
)

/** Encodes non-null UUIDs using PostgreSQL's one-dimensional array text input format, including {}. */
fun uuidArrayText(values: Iterable<Uuid>): String = values.joinToString(
    ",",
    prefix = "{",
    postfix = "}",
) { it.toString() }

private fun arrayAnyEquals(value: SqlExpression<*>, array: PostgresArrayExpression<*>): BooleanExpression =
    ExpressionValueComparison(
        SqlFunctionExpression(
            SqlFunction.PostgresAnyEquals,
            listOf(value, array.expression),
            resultExpression(BooleanColumnType).optional,
        ),
        ComparisonOperator.EQUALS,
        true,
    )
