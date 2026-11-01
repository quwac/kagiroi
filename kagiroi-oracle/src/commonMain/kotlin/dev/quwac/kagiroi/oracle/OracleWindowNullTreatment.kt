package dev.quwac.kagiroi.oracle

import dev.quwac.kagiroi.schema.SqlFunction
import dev.quwac.kagiroi.schema.ValueWindowFunctionExpression
import dev.quwac.kagiroi.schema.WindowNullTreatment
import dev.quwac.kagiroi.schema.withWindowNullTreatment

/** Requests Oracle's native `IGNORE NULLS` option for a supported value window function. */
fun <T> ValueWindowFunctionExpression<T>.ignoreNulls(): ValueWindowFunctionExpression<T> {
    require(function in ORACLE_NULL_TREATMENT_FUNCTIONS) {
        "Oracle IGNORE NULLS is only available on LAG, LEAD, FIRST_VALUE, LAST_VALUE, and NTH_VALUE"
    }
    return withWindowNullTreatment(WindowNullTreatment.IgnoreNulls)
}

/** Requests Oracle's native `RESPECT NULLS` option for a supported value window function. */
fun <T> ValueWindowFunctionExpression<T>.respectNulls(): ValueWindowFunctionExpression<T> {
    require(function in ORACLE_NULL_TREATMENT_FUNCTIONS) {
        "Oracle RESPECT NULLS is only available on LAG, LEAD, FIRST_VALUE, LAST_VALUE, and NTH_VALUE"
    }
    return withWindowNullTreatment(WindowNullTreatment.RespectNulls)
}

private val ORACLE_NULL_TREATMENT_FUNCTIONS = setOf(
    SqlFunction.Lag,
    SqlFunction.Lead,
    SqlFunction.FirstValue,
    SqlFunction.LastValue,
    SqlFunction.NthValue,
)
