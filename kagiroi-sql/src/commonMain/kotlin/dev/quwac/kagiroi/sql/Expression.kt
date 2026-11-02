@file:Suppress("TooManyFunctions") // Keeps the complete typed expression comparison API together.

package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.BooleanExpression
import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.schema.ExpressionComparison
import dev.quwac.kagiroi.schema.ExpressionNullComparison
import dev.quwac.kagiroi.schema.ExpressionValueComparison
import dev.quwac.kagiroi.schema.NullSafeComparison
import dev.quwac.kagiroi.schema.NullSafeValueComparison
import dev.quwac.kagiroi.schema.SqlExpression

/** Backwards-compatible query name for the Boolean expression shared with schema CHECK constraints. */
typealias SqlPredicate = BooleanExpression

typealias ComparisonOperator = dev.quwac.kagiroi.schema.ComparisonOperator
typealias ValueComparison<T> = dev.quwac.kagiroi.schema.ValueComparison<T>
typealias NullOperator = dev.quwac.kagiroi.schema.NullOperator
typealias NullComparison = dev.quwac.kagiroi.schema.NullComparison
typealias ExpressionNullComparison = dev.quwac.kagiroi.schema.ExpressionNullComparison
typealias LogicalOperator = dev.quwac.kagiroi.schema.LogicalOperator
typealias LogicalPredicate = dev.quwac.kagiroi.schema.LogicalPredicate

infix fun <T> Column<T>.eq(value: T & Any): SqlPredicate = comparison(ComparisonOperator.EQUALS, value)

infix fun <T> Column<T>.neq(value: T & Any): SqlPredicate = comparison(ComparisonOperator.NOT_EQUALS, value)

infix fun <T> Column<T>.lt(value: T & Any): SqlPredicate = comparison(ComparisonOperator.LESS_THAN, value)

infix fun <T> Column<T>.lte(value: T & Any): SqlPredicate = comparison(ComparisonOperator.LESS_THAN_OR_EQUALS, value)

infix fun <T> Column<T>.gt(value: T & Any): SqlPredicate = comparison(ComparisonOperator.GREATER_THAN, value)

infix fun <T> Column<T>.gte(value: T & Any): SqlPredicate = comparison(ComparisonOperator.GREATER_THAN_OR_EQUALS, value)

/** Compares a typed function or arithmetic expression with a non-null Kotlin value. */
infix fun <T> SqlExpression<T>.eq(value: T & Any): SqlPredicate =
    ExpressionValueComparison(this, ComparisonOperator.EQUALS, value)

infix fun <T> SqlExpression<T>.neq(value: T & Any): SqlPredicate =
    ExpressionValueComparison(this, ComparisonOperator.NOT_EQUALS, value)

infix fun <T> SqlExpression<T>.lt(value: T & Any): SqlPredicate =
    ExpressionValueComparison(this, ComparisonOperator.LESS_THAN, value)

infix fun <T> SqlExpression<T>.lte(value: T & Any): SqlPredicate =
    ExpressionValueComparison(this, ComparisonOperator.LESS_THAN_OR_EQUALS, value)

infix fun <T> SqlExpression<T>.gt(value: T & Any): SqlPredicate =
    ExpressionValueComparison(this, ComparisonOperator.GREATER_THAN, value)

infix fun <T> SqlExpression<T>.gte(value: T & Any): SqlPredicate =
    ExpressionValueComparison(this, ComparisonOperator.GREATER_THAN_OR_EQUALS, value)

/** Compares expressions with exactly the same logical Kotlin type. */
infix fun <T> SqlExpression<T>.eq(other: SqlExpression<T>): SqlPredicate =
    ExpressionComparison(this, ComparisonOperator.EQUALS, other)

infix fun <T> SqlExpression<T>.neq(other: SqlExpression<T>): SqlPredicate =
    ExpressionComparison(this, ComparisonOperator.NOT_EQUALS, other)

infix fun <T> SqlExpression<T>.lt(other: SqlExpression<T>): SqlPredicate =
    ExpressionComparison(this, ComparisonOperator.LESS_THAN, other)

infix fun <T> SqlExpression<T>.lte(other: SqlExpression<T>): SqlPredicate =
    ExpressionComparison(this, ComparisonOperator.LESS_THAN_OR_EQUALS, other)

infix fun <T> SqlExpression<T>.gt(other: SqlExpression<T>): SqlPredicate =
    ExpressionComparison(this, ComparisonOperator.GREATER_THAN, other)

infix fun <T> SqlExpression<T>.gte(other: SqlExpression<T>): SqlPredicate =
    ExpressionComparison(this, ComparisonOperator.GREATER_THAN_OR_EQUALS, other)

/** Allows a nullable foreign key to be compared with its non-null target expression. */
@Suppress("UNCHECKED_CAST") // The signature has already constrained both sides to the same T.
infix fun <T : Any> SqlExpression<T?>.eq(other: SqlExpression<T>): SqlPredicate =
    ExpressionComparison(this, ComparisonOperator.EQUALS, other as SqlExpression<T?>)

/** Allows a non-null target expression to be compared with its nullable foreign key. */
@Suppress("UNCHECKED_CAST") // The signature has already constrained both sides to the same T.
infix fun <T : Any> SqlExpression<T>.eq(other: SqlExpression<T?>): SqlPredicate =
    ExpressionComparison(this as SqlExpression<T?>, ComparisonOperator.EQUALS, other)

/** Allows a non-null expression to compare with a nullable scalar subquery or relation expression. */
@Suppress("UNCHECKED_CAST") // The signature has already constrained both sides to the same T.
infix fun <T : Any> SqlExpression<T>.gt(other: SqlExpression<T?>): SqlPredicate =
    ExpressionComparison(this as SqlExpression<T?>, ComparisonOperator.GREATER_THAN, other)

fun <T : Any> Column<T?>.isNull(): SqlPredicate = NullComparison(this, NullOperator.IS_NULL)

fun <T : Any> Column<T?>.isNotNull(): SqlPredicate = NullComparison(this, NullOperator.IS_NOT_NULL)

fun <T : Any> SqlExpression<T?>.isNull(): SqlPredicate = ExpressionNullComparison(this, NullOperator.IS_NULL)

fun <T : Any> SqlExpression<T?>.isNotNull(): SqlPredicate = ExpressionNullComparison(this, NullOperator.IS_NOT_NULL)

/** Compares two values, treating two SQL NULL values as equal. */
infix fun <T> SqlExpression<T>.isNotDistinctFrom(other: SqlExpression<T>): SqlPredicate =
    NullSafeComparison(this, distinct = false, other)

/** Compares this expression with a non-null value, treating two SQL NULL values as equal. */
infix fun <T> SqlExpression<T>.isNotDistinctFrom(value: T & Any): SqlPredicate =
    NullSafeValueComparison(this, distinct = false, value)

/** Allows a nullable expression to use null-safe equality with its non-null counterpart. */
@Suppress("UNCHECKED_CAST") // The signature has already constrained both sides to the same non-null T.
infix fun <T : Any> SqlExpression<T?>.isNotDistinctFrom(other: SqlExpression<T>): SqlPredicate =
    NullSafeComparison(this, distinct = false, other as SqlExpression<T?>)

/** Allows a non-null expression to use null-safe equality with its nullable counterpart. */
@Suppress("UNCHECKED_CAST") // The signature has already constrained both sides to the same non-null T.
infix fun <T : Any> SqlExpression<T>.isNotDistinctFrom(other: SqlExpression<T?>): SqlPredicate =
    NullSafeComparison(this as SqlExpression<T?>, distinct = false, other)

/** Compares two values, treating one SQL NULL and one non-NULL value as distinct. */
infix fun <T> SqlExpression<T>.isDistinctFrom(other: SqlExpression<T>): SqlPredicate =
    NullSafeComparison(this, distinct = true, other)

/** Compares this expression with a non-null value, treating SQL NULL as a distinct value. */
infix fun <T> SqlExpression<T>.isDistinctFrom(value: T & Any): SqlPredicate =
    NullSafeValueComparison(this, distinct = true, value)

/** Allows a nullable expression to use null-safe inequality with its non-null counterpart. */
@Suppress("UNCHECKED_CAST") // The signature has already constrained both sides to the same non-null T.
infix fun <T : Any> SqlExpression<T?>.isDistinctFrom(other: SqlExpression<T>): SqlPredicate =
    NullSafeComparison(this, distinct = true, other as SqlExpression<T?>)

/** Allows a non-null expression to use null-safe inequality with its nullable counterpart. */
@Suppress("UNCHECKED_CAST") // The signature has already constrained both sides to the same non-null T.
infix fun <T : Any> SqlExpression<T>.isDistinctFrom(other: SqlExpression<T?>): SqlPredicate =
    NullSafeComparison(this as SqlExpression<T?>, distinct = true, other)

infix fun SqlPredicate.and(other: SqlPredicate): SqlPredicate = LogicalPredicate(this, LogicalOperator.AND, other)

infix fun SqlPredicate.or(other: SqlPredicate): SqlPredicate = LogicalPredicate(this, LogicalOperator.OR, other)

private fun <T> Column<T>.comparison(operator: ComparisonOperator, value: T & Any): SqlPredicate =
    ValueComparison(this, operator, value)
