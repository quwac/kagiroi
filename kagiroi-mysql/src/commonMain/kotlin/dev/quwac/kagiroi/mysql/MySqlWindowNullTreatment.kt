package dev.quwac.kagiroi.mysql

import dev.quwac.kagiroi.schema.SqlFunction
import dev.quwac.kagiroi.schema.ValueWindowFunctionExpression
import dev.quwac.kagiroi.schema.WindowNullTreatment
import dev.quwac.kagiroi.schema.withWindowNullTreatment

/**
 * Requests MySQL's explicit native `RESPECT NULLS` option for a supported value window function.
 *
 * MySQL 8.4 parses but rejects `IGNORE NULLS`, so this module deliberately does not expose an
 * `ignoreNulls()` extension.
 */
fun <T> ValueWindowFunctionExpression<T>.respectNulls(): ValueWindowFunctionExpression<T> {
    require(function in MYSQL_NULL_TREATMENT_FUNCTIONS) {
        "MySQL RESPECT NULLS is only available on LAG, LEAD, FIRST_VALUE, LAST_VALUE, and NTH_VALUE"
    }
    return withWindowNullTreatment(WindowNullTreatment.RespectNulls)
}

private val MYSQL_NULL_TREATMENT_FUNCTIONS = setOf(
    SqlFunction.Lag,
    SqlFunction.Lead,
    SqlFunction.FirstValue,
    SqlFunction.LastValue,
    SqlFunction.NthValue,
)
