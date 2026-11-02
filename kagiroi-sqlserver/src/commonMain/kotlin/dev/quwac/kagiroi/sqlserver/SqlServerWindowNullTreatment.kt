package dev.quwac.kagiroi.sqlserver

import dev.quwac.kagiroi.schema.SqlFunction
import dev.quwac.kagiroi.schema.ValueWindowFunctionExpression
import dev.quwac.kagiroi.schema.WindowNullTreatment
import dev.quwac.kagiroi.schema.withWindowNullTreatment

/** Requests SQL Server 2022's native `IGNORE NULLS` option for a supported value window function. */
fun <T> ValueWindowFunctionExpression<T>.ignoreNulls(): ValueWindowFunctionExpression<T> {
    require(function in SQL_SERVER_NULL_TREATMENT_FUNCTIONS) {
        "SQL Server IGNORE NULLS is only available on LAG, LEAD, FIRST_VALUE, and LAST_VALUE"
    }
    return withWindowNullTreatment(WindowNullTreatment.IgnoreNulls)
}

/** Requests SQL Server 2022's native `RESPECT NULLS` option for a supported value window function. */
fun <T> ValueWindowFunctionExpression<T>.respectNulls(): ValueWindowFunctionExpression<T> {
    require(function in SQL_SERVER_NULL_TREATMENT_FUNCTIONS) {
        "SQL Server RESPECT NULLS is only available on LAG, LEAD, FIRST_VALUE, and LAST_VALUE"
    }
    return withWindowNullTreatment(WindowNullTreatment.RespectNulls)
}

private val SQL_SERVER_NULL_TREATMENT_FUNCTIONS = setOf(
    SqlFunction.Lag,
    SqlFunction.Lead,
    SqlFunction.FirstValue,
    SqlFunction.LastValue,
)
