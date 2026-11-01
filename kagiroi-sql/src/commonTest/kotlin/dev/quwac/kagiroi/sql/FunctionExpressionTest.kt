package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.abs
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.avg
import dev.quwac.kagiroi.schema.case
import dev.quwac.kagiroi.schema.ceil
import dev.quwac.kagiroi.schema.coalesce
import dev.quwac.kagiroi.schema.concat
import dev.quwac.kagiroi.schema.count
import dev.quwac.kagiroi.schema.currentTimestamp
import dev.quwac.kagiroi.schema.elseValue
import dev.quwac.kagiroi.schema.floor
import dev.quwac.kagiroi.schema.length
import dev.quwac.kagiroi.schema.literal
import dev.quwac.kagiroi.schema.lower
import dev.quwac.kagiroi.schema.ltrim
import dev.quwac.kagiroi.schema.max
import dev.quwac.kagiroi.schema.min
import dev.quwac.kagiroi.schema.notLike
import dev.quwac.kagiroi.schema.nullIf
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.power
import dev.quwac.kagiroi.schema.replace
import dev.quwac.kagiroi.schema.round
import dev.quwac.kagiroi.schema.rtrim
import dev.quwac.kagiroi.schema.sqrt
import dev.quwac.kagiroi.schema.substring
import dev.quwac.kagiroi.schema.sum
import dev.quwac.kagiroi.schema.trim
import dev.quwac.kagiroi.schema.upper
import dev.quwac.kagiroi.schema.whenThen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FunctionExpressionTest {
    @Test
    fun preservesFunctionTypesAndNullability() {
        val lowered: SqlExpression<String> = lower(FunctionUsers.email)
        val nullableLowered: SqlExpression<String?> = lower(FunctionUsers.nickname)
        val nameLength: SqlExpression<Long> = length(FunctionUsers.name)
        val nullableLength: SqlExpression<Long?> = length(FunctionUsers.nickname)
        val magnitude: SqlExpression<Int> = abs(FunctionUsers.amount)
        val total: SqlExpression<Long?> = sum(FunctionUsers.amount)
        val average: SqlExpression<Double?> = avg(FunctionUsers.amount)
        val smallest: SqlExpression<Int?> = min(FunctionUsers.amount)
        val largest: SqlExpression<Int?> = max(FunctionUsers.amount)
        val fallback: SqlExpression<String> = coalesce(FunctionUsers.nickname, FunctionUsers.name)

        assertEquals(false, lowered.isNullable)
        assertEquals(true, nullableLowered.isNullable)
        assertEquals(false, nameLength.isNullable)
        assertEquals(true, nullableLength.isNullable)
        assertEquals(false, magnitude.isNullable)
        assertEquals(true, total.isNullable)
        assertEquals(true, average.isNullable)
        assertEquals(true, smallest.isNullable)
        assertEquals(true, largest.isNullable)
        assertEquals(false, fallback.isNullable)
    }

    @Test
    fun rendersNestedFunctionsAliasesWhereOrderAndLiteralParameters() {
        val normalized = lower(FunctionUsers.email).alias("normalized_email")
        val displayName = coalesce(FunctionUsers.nickname, literal("anonymous")).alias("display_name")
        val query = select(FunctionUsers.id, normalized, displayName)
            .from(FunctionUsers)
            .where(lower(FunctionUsers.email) eq "ada@example.com")
            .orderBy(lower(FunctionUsers.name).asc())

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query)

        assertEquals(
            "SELECT \"function_users\".\"id\", LOWER(\"function_users\".\"email\") AS \"normalized_email\", " +
                "COALESCE(\"function_users\".\"nickname\", ?) AS \"display_name\" FROM \"function_users\" " +
                "WHERE LOWER(\"function_users\".\"email\") = ? ORDER BY LOWER(\"function_users\".\"name\") ASC",
            rendered.sql,
        )
        assertEquals(listOf("anonymous", "ada@example.com"), rendered.parameters.map { it.value })
        assertEquals("anonymous", rendered.parameters.first().value)
    }

    @Test
    fun rendersAggregateFunctionsWithTypedAliases() {
        val rowCount = count().alias("row_count")
        val nonNullNames = count(FunctionUsers.nickname).alias("non_null_names")
        val total = sum(FunctionUsers.amount).alias("total_amount")
        val average = avg(FunctionUsers.amount).alias("average_amount")
        val smallest = min(FunctionUsers.amount).alias("smallest_amount")
        val largest = max(FunctionUsers.amount).alias("largest_amount")

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
            select(rowCount, nonNullNames, total, average, smallest, largest).from(FunctionUsers),
        )

        assertEquals(
            "SELECT COUNT(*) AS \"row_count\", " +
                "COUNT(\"function_users\".\"nickname\") AS \"non_null_names\", " +
                "SUM(\"function_users\".\"amount\") AS \"total_amount\", " +
                "AVG(\"function_users\".\"amount\") AS \"average_amount\", " +
                "MIN(\"function_users\".\"amount\") AS \"smallest_amount\", " +
                "MAX(\"function_users\".\"amount\") AS \"largest_amount\" " +
                "FROM \"function_users\"",
            rendered.sql,
        )
    }

    @Test
    fun readsAliasedResultsThroughTheirTypedExpressionHandle() {
        val normalized = upper(trim(FunctionUsers.name)).alias("normalized_name")
        val row = sqlRow(listOf(normalized), listOf("ADA"))

        assertEquals("ADA", row[normalized])
        assertFailsWith<IllegalArgumentException> {
            row[lower(FunctionUsers.email).alias("missing")]
        }
    }

    @Test
    fun preservesExpandedFunctionTypesAndNullability() {
        val nullName: SqlExpression<String?> = nullIf(FunctionUsers.name, "anonymous")
        val leftTrimmed: SqlExpression<String?> = ltrim(FunctionUsers.nickname)
        val rightTrimmed: SqlExpression<String?> = rtrim(FunctionUsers.nickname)
        val prefix: SqlExpression<String> = substring(FunctionUsers.name, start = 1, length = 4)
        val replaced: SqlExpression<String> = replace(FunctionUsers.name, old = "a", new = "A")
        val fullName: SqlExpression<String> = concat(FunctionUsers.firstName, " ", FunctionUsers.lastName)
        val rounded: SqlExpression<Double> = round(FunctionUsers.amount, digits = 2)
        val floored: SqlExpression<Double> = floor(FunctionUsers.amount)
        val ceiling: SqlExpression<Double> = ceil(FunctionUsers.amount)
        val root: SqlExpression<Double?> = sqrt(FunctionUsers.amount)
        val squared: SqlExpression<Double> = power(FunctionUsers.amount, 2)
        val shortName: SqlExpression<String> = case<String> {
            whenThen(length(FunctionUsers.name) gt 20, substring(FunctionUsers.name, start = 1, length = 20))
            elseValue(FunctionUsers.name)
        }

        assertEquals(true, nullName.isNullable)
        assertEquals(true, leftTrimmed.isNullable)
        assertEquals(true, rightTrimmed.isNullable)
        assertEquals(false, prefix.isNullable)
        assertEquals(false, replaced.isNullable)
        assertEquals(false, fullName.isNullable)
        assertEquals(false, rounded.isNullable)
        assertEquals(false, floored.isNullable)
        assertEquals(false, ceiling.isNullable)
        assertEquals(true, root.isNullable)
        assertEquals(false, squared.isNullable)
        assertEquals(false, shortName.isNullable)
    }

    @Test
    fun rendersExpandedFunctionsLikeAndCompositionWithBoundValues() {
        val displayName = concat(FunctionUsers.firstName, " ", FunctionUsers.lastName).alias("display_name")
        val query = select(displayName).from(FunctionUsers)
            .where(FunctionUsers.name notLike "%bot%")
            .groupBy(displayName)

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query)

        assertEquals(
            "SELECT CONCAT(\"function_users\".\"first_name\", ?, \"function_users\".\"last_name\") AS " +
                "\"display_name\" FROM \"function_users\" WHERE \"function_users\".\"name\" NOT LIKE ? " +
                "GROUP BY CONCAT(\"function_users\".\"first_name\", ?, \"function_users\".\"last_name\")",
            rendered.sql,
        )
        assertEquals(listOf(" ", "%bot%", " "), rendered.parameters.map { it.value })
        assertEquals(
            "SELECT MAX(LENGTH(\"function_users\".\"name\")) AS \"longest_name\" FROM \"function_users\"",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
                select(max(length(FunctionUsers.name)).alias("longest_name")).from(FunctionUsers),
            ).sql,
        )
        assertEquals(
            "SELECT CURRENT_TIMESTAMP AS \"observed_at\"",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
                select(currentTimestamp().alias("observed_at")),
            ).sql,
        )
    }
}

private object FunctionUsers : Table("function_users") {
    val id = int("id")
    val email = string("email")
    val name = string("name")
    val nickname = string("nickname").nullable()
    val amount = int("amount")
    val firstName = string("first_name")
    val lastName = string("last_name")
}
