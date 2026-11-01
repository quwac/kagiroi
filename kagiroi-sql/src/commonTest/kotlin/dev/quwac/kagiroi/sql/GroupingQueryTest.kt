package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.avg
import dev.quwac.kagiroi.schema.count
import dev.quwac.kagiroi.schema.lower
import dev.quwac.kagiroi.schema.max
import dev.quwac.kagiroi.schema.sum
import dev.quwac.kagiroi.schema.upper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class GroupingQueryTest {
    @Test
    fun keepsWhereGroupByHavingAndOrderByAsSeparateImmutableClauses() {
        val orderCount = count().alias("order_count")
        val totalAmount = sum(GroupingOrders.amount).alias("total_amount")
        val minimumTotal = totalAmount gt 20L
        val query = select(GroupingOrders.organizationId, orderCount, totalAmount)
            .from(GroupingOrders)
            .where(GroupingOrders.active eq true)
            .groupBy(GroupingOrders.organizationId)
            .having(minimumTotal)
            .orderBy(totalAmount.desc())

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query)

        assertEquals(listOf(GroupingOrders.organizationId), query.groupBy)
        assertSame(minimumTotal, query.having)
        assertEquals(
            "SELECT \"grouping_orders\".\"organization_id\", COUNT(*) AS \"order_count\", " +
                "SUM(\"grouping_orders\".\"amount\") AS \"total_amount\" FROM \"grouping_orders\" " +
                "WHERE \"grouping_orders\".\"active\" = ? GROUP BY \"grouping_orders\".\"organization_id\" " +
                "HAVING SUM(\"grouping_orders\".\"amount\") > ? " +
                "ORDER BY SUM(\"grouping_orders\".\"amount\") DESC",
            rendered.sql,
        )
        assertEquals(listOf(true, 20L), rendered.parameters.map { it.value })
    }

    @Test
    fun groupsScalarFunctionExpressionsAndDetectsNestedAggregateExpressions() {
        val normalized = lower(GroupingOrders.email)
        val highestName = upper(max(GroupingOrders.name)).alias("highest_name")
        val byEmail = select(normalized, count())
            .from(GroupingOrders)
            .groupBy(normalized)
        val nestedAggregate = select(GroupingOrders.organizationId, highestName)
            .from(GroupingOrders)
            .groupBy(GroupingOrders.organizationId)

        assertEquals(
            "SELECT LOWER(\"grouping_orders\".\"email\"), COUNT(*) FROM \"grouping_orders\" " +
                "GROUP BY LOWER(\"grouping_orders\".\"email\")",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(byEmail).sql,
        )
        assertEquals(
            "SELECT \"grouping_orders\".\"organization_id\", " +
                "UPPER(MAX(\"grouping_orders\".\"name\")) AS \"highest_name\" FROM \"grouping_orders\" " +
                "GROUP BY \"grouping_orders\".\"organization_id\"",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(nestedAggregate).sql,
        )
    }

    @Test
    fun supportsGroupedDerivedTablesTypedRowsAndHavingScalarSubqueries() {
        val source = select(GroupingOrders.organizationId, GroupingOrders.amount)
            .from(GroupingOrders)
            .asTable("source")
        val organizationId = source[GroupingOrders.organizationId]
        val total = sum(source[GroupingOrders.amount]).alias("total")
        val derivedQuery = select(organizationId, total)
            .from(source)
            .groupBy(organizationId)
        val minimumAverage = select(avg(GroupingLimits.amount)).from(GroupingLimits).scalar()
        val average = avg(GroupingOrders.amount).alias("average")
        val havingSubquery = select(GroupingOrders.organizationId, average)
            .from(GroupingOrders)
            .groupBy(GroupingOrders.organizationId)
            .having(average gt minimumAverage)

        assertEquals(
            "SELECT \"source\".\"organization_id\", SUM(\"source\".\"amount\") AS \"total\" FROM " +
                "(SELECT \"grouping_orders\".\"organization_id\", \"grouping_orders\".\"amount\" " +
                "FROM \"grouping_orders\") AS \"source\" GROUP BY \"source\".\"organization_id\"",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(derivedQuery).sql,
        )
        assertEquals(
            "SELECT \"grouping_orders\".\"organization_id\", AVG(\"grouping_orders\".\"amount\") AS \"average\" " +
                "FROM \"grouping_orders\" GROUP BY \"grouping_orders\".\"organization_id\" " +
                "HAVING AVG(\"grouping_orders\".\"amount\") > " +
                "(SELECT AVG(\"grouping_limits\".\"amount\") FROM \"grouping_limits\")",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(havingSubquery).sql,
        )
        val row = sqlRow(listOf(organizationId, total), listOf<Any?>(7, 30L))
        assertEquals(7, row[organizationId])
        assertEquals(30L, row[total])
    }

    @Test
    fun permitsGroupByWithoutAggregatesAndHavingWithoutGroupBy() {
        val groupOnly = select(GroupingOrders.organizationId)
            .from(GroupingOrders)
            .groupBy(GroupingOrders.organizationId)
        val aggregateOnly = select(count()).from(GroupingOrders).having(count() gt 1L)

        assertEquals(
            "SELECT \"grouping_orders\".\"organization_id\" FROM \"grouping_orders\" " +
                "GROUP BY \"grouping_orders\".\"organization_id\"",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(groupOnly).sql,
        )
        assertEquals(
            "SELECT COUNT(*) FROM \"grouping_orders\" HAVING COUNT(*) > ?",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(aggregateOnly).sql,
        )
    }

    @Test
    fun rejectsScalarSubqueriesAndAggregatesInGroupByAndUngroupedProjections() {
        val base = select(GroupingOrders.organizationId).from(GroupingOrders)

        assertFailsWith<IllegalArgumentException> {
            base.groupBy(select(GroupingLimits.amount).from(GroupingLimits).scalar())
        }
        assertFailsWith<IllegalArgumentException> {
            base.groupBy(count())
        }
        assertFailsWith<IllegalArgumentException> {
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
                select(GroupingOrders.organizationId, GroupingOrders.status, count())
                    .from(GroupingOrders)
                    .groupBy(GroupingOrders.organizationId),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
                base.groupBy(GroupingLimits.amount),
            )
        }
    }
}

private object GroupingOrders : Table("grouping_orders") {
    val organizationId = int("organization_id")
    val status = string("status")
    val amount = int("amount")
    val active = boolean("active")
    val email = string("email")
    val name = string("name")
}

private object GroupingLimits : Table("grouping_limits") {
    val amount = int("amount")
}
