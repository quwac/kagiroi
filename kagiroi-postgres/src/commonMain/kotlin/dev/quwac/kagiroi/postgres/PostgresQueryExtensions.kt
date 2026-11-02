package dev.quwac.kagiroi.postgres

import dev.quwac.kagiroi.schema.LikeExpressionComparison
import dev.quwac.kagiroi.schema.LikeOperator
import dev.quwac.kagiroi.schema.LikeValueComparison
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.sql.ExecutableSelectQuery
import dev.quwac.kagiroi.sql.SelectProjectionShape
import dev.quwac.kagiroi.sql.SelectQuery
import dev.quwac.kagiroi.sql.SelectQueryState
import dev.quwac.kagiroi.sql.SqlPredicate
import dev.quwac.kagiroi.sql.withDistinctOn

/** Compares this string expression with PostgreSQL's case-insensitive `ILIKE` operator. */
infix fun <T : String?> SqlExpression<T>.ilike(pattern: String): SqlPredicate =
    LikeValueComparison(this, LikeOperator.ILIKE, pattern)

/** Compares this string expression with PostgreSQL's negated case-insensitive `ILIKE` operator. */
infix fun <T : String?> SqlExpression<T>.notIlike(pattern: String): SqlPredicate =
    LikeValueComparison(this, LikeOperator.NOT_ILIKE, pattern)

/** Compares this string expression with a typed PostgreSQL `ILIKE` pattern expression. */
infix fun <T : String?> SqlExpression<T>.ilike(pattern: SqlExpression<String>): SqlPredicate =
    LikeExpressionComparison(this, LikeOperator.ILIKE, pattern)

/** Compares this string expression with a typed PostgreSQL `NOT ILIKE` pattern expression. */
infix fun <T : String?> SqlExpression<T>.notIlike(pattern: SqlExpression<String>): SqlPredicate =
    LikeExpressionComparison(this, LikeOperator.NOT_ILIKE, pattern)

/** Adds PostgreSQL `DISTINCT ON` while retaining this SELECT's projection and clause typestate. */
fun <S : SelectQueryState, T, P : SelectProjectionShape> SelectQuery<S, T, P>.distinctOn(
    expression: SqlExpression<*>,
    vararg additionalExpressions: SqlExpression<*>,
): SelectQuery<S, T, P> = withDistinctOn(expression, *additionalExpressions)

/** Adds PostgreSQL `DISTINCT ON` while retaining this execution target and clause typestate. */
fun <S : SelectQueryState, T, P : SelectProjectionShape> ExecutableSelectQuery<S, T, P>.distinctOn(
    expression: SqlExpression<*>,
    vararg additionalExpressions: SqlExpression<*>,
): ExecutableSelectQuery<S, T, P> = withDistinctOn(expression, *additionalExpressions)
