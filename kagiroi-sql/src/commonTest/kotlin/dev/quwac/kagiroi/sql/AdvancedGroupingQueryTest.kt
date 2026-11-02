package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.count
import dev.quwac.kagiroi.schema.grouping
import dev.quwac.kagiroi.schema.lower
import dev.quwac.kagiroi.schema.optional
import dev.quwac.kagiroi.schema.rank
import dev.quwac.kagiroi.schema.sum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AdvancedGroupingQueryTest {
    @Test
    fun rendersRollupCubeGroupingSetsAndCompositeItemsWithoutRewriting() {
        val country = AdvancedSales.country.optional
        val city = AdvancedSales.city.optional
        val total = sum(AdvancedSales.amount).alias("total")
        val countryGrouping = grouping(AdvancedSales.country).alias("country_grouping")

        val rollup = select(AdvancedSales.organizationId, country, city, total, countryGrouping)
            .from(AdvancedSales)
            .groupBy(AdvancedSales.organizationId, rollup(AdvancedSales.country, AdvancedSales.city))
            .having(total gt 0L)
            .orderBy(country.desc())
        val cube = select(country, city, total)
            .from(AdvancedSales)
            .groupBy(cube(AdvancedSales.country, AdvancedSales.city))
        val sets = select(AdvancedSales.organizationId, country, city, total)
            .from(AdvancedSales)
            .groupBy(
                AdvancedSales.organizationId,
                groupingSets(
                    groupingSet(AdvancedSales.country, group(AdvancedSales.city, AdvancedSales.product)),
                    groupingSet(AdvancedSales.country),
                    grandTotal(),
                ),
            )

        assertEquals(
            "SELECT \"advanced_sales\".\"organization_id\", \"advanced_sales\".\"country\", " +
                "\"advanced_sales\".\"city\", SUM(\"advanced_sales\".\"amount\") AS \"total\", " +
                "GROUPING(\"advanced_sales\".\"country\") AS \"country_grouping\" FROM \"advanced_sales\" " +
                "GROUP BY \"advanced_sales\".\"organization_id\", ROLLUP(\"advanced_sales\".\"country\", " +
                "\"advanced_sales\".\"city\") HAVING SUM(\"advanced_sales\".\"amount\") > ? " +
                "ORDER BY \"advanced_sales\".\"country\" DESC",
            SqlRenderer(AdvancedGroupingDialect).render(rollup).sql,
        )
        assertEquals(
            "SELECT \"advanced_sales\".\"country\", \"advanced_sales\".\"city\", " +
                "SUM(\"advanced_sales\".\"amount\") AS \"total\" FROM \"advanced_sales\" " +
                "GROUP BY CUBE(\"advanced_sales\".\"country\", \"advanced_sales\".\"city\")",
            SqlRenderer(AdvancedGroupingDialect).render(cube).sql,
        )
        assertEquals(
            "SELECT \"advanced_sales\".\"organization_id\", \"advanced_sales\".\"country\", " +
                "\"advanced_sales\".\"city\", SUM(\"advanced_sales\".\"amount\") AS \"total\" " +
                "FROM \"advanced_sales\" GROUP BY \"advanced_sales\".\"organization_id\", " +
                "GROUPING SETS ((\"advanced_sales\".\"country\", (\"advanced_sales\".\"city\", " +
                "\"advanced_sales\".\"product\")), (\"advanced_sales\".\"country\"), ())",
            SqlRenderer(AdvancedGroupingDialect).render(sets).sql,
        )
    }

    @Test
    fun requiresNullableProjectionOnlyForSyntheticNullGroupingKeys() {
        val total = sum(AdvancedSales.amount)
        val unsafe = select(AdvancedSales.country, total)
            .from(AdvancedSales)
            .groupBy(rollup(AdvancedSales.country))
        val safe = select(AdvancedSales.country.optional, total)
            .from(AdvancedSales)
            .groupBy(rollup(AdvancedSales.country))
        val ordinary = select(AdvancedSales.organizationId, AdvancedSales.country.optional, total)
            .from(AdvancedSales)
            .groupBy(AdvancedSales.organizationId, rollup(AdvancedSales.country))

        assertFailsWith<IllegalArgumentException> { SqlRenderer(AdvancedGroupingDialect).render(unsafe) }
        SqlRenderer(AdvancedGroupingDialect).render(safe)
        SqlRenderer(AdvancedGroupingDialect).render(ordinary)
    }

    @Test
    fun syntheticNullHandleCannotBeReadThroughTheNonNullColumnHandle() {
        val country = AdvancedSales.country.optional
        val row = sqlRow(listOf(country), listOf(null))

        assertNull(row[country])
        assertFailsWith<IllegalArgumentException> { row[AdvancedSales.country] }
    }

    @Test
    fun preservesSyntheticNullabilityThroughDerivedTablesAndCtes() {
        val country = AdvancedSales.country.optional
        val total = sum(AdvancedSales.amount).alias("total")
        val summary = select(country, total)
            .from(AdvancedSales)
            .groupBy(rollup(AdvancedSales.country))
            .asTable("summary")
        val summaryCountry: SqlExpression<String?> = summary[country]
        val cte = select(country, total)
            .from(AdvancedSales)
            .groupBy(rollup(AdvancedSales.country))
            .asCte("summary_cte")
        val cteCountry: SqlExpression<String?> = cte[country]

        assertEquals(
            "SELECT \"summary\".\"country\" FROM (SELECT \"advanced_sales\".\"country\", " +
                "SUM(\"advanced_sales\".\"amount\") AS \"total\" FROM \"advanced_sales\" " +
                "GROUP BY ROLLUP(\"advanced_sales\".\"country\")) AS \"summary\"",
            SqlRenderer(AdvancedGroupingDialect).render(select(summaryCountry).from(summary)).sql,
        )
        assertEquals(
            "WITH \"summary_cte\" AS (SELECT \"advanced_sales\".\"country\", " +
                "SUM(\"advanced_sales\".\"amount\") AS \"total\" FROM \"advanced_sales\" " +
                "GROUP BY ROLLUP(\"advanced_sales\".\"country\")) SELECT \"summary_cte\".\"country\" " +
                "FROM \"summary_cte\"",
            SqlRenderer(AdvancedGroupingDialect).render(select(cteCountry).from(cte).with(cte)).sql,
        )
    }

    @Test
    fun groupingRequiresAnAdvancedGroupingKeyAndRejectsInvalidGroupingKeys() {
        val plain = select(grouping(AdvancedSales.country)).from(AdvancedSales).groupBy(AdvancedSales.country)
        val unrelated = select(AdvancedSales.country.optional, sum(AdvancedSales.amount))
            .from(AdvancedSales)
            .groupBy(rollup(OtherSales.country))
        val whereGrouping = select(AdvancedSales.country.optional, sum(AdvancedSales.amount))
            .from(AdvancedSales)
            .where(grouping(AdvancedSales.country) eq 0)
            .groupBy(rollup(AdvancedSales.country))
        val noGrouping = select(grouping(AdvancedSales.country)).from(AdvancedSales)

        assertFailsWith<IllegalArgumentException> { SqlRenderer(AdvancedGroupingDialect).render(plain) }
        assertFailsWith<IllegalArgumentException> { SqlRenderer(AdvancedGroupingDialect).render(unrelated) }
        assertFailsWith<IllegalArgumentException> { SqlRenderer(AdvancedGroupingDialect).render(whereGrouping) }
        assertFailsWith<IllegalArgumentException> { SqlRenderer(AdvancedGroupingDialect).render(noGrouping) }
        assertFailsWith<IllegalArgumentException> {
            select(AdvancedSales.country).from(AdvancedSales).groupBy(rollup(sum(AdvancedSales.amount)))
        }
        assertFailsWith<IllegalArgumentException> {
            select(AdvancedSales.country).from(AdvancedSales).groupBy(rollup(rank().over { }))
        }
    }

    @Test
    fun genericNullableViewKeepsExpressionIdentityForAdvancedGrouping() {
        val normalized = lower(AdvancedSales.country)
        val normalizedOptional = normalized.optional.alias("normalized_country")
        val query = select(normalizedOptional, count())
            .from(AdvancedSales)
            .groupBy(rollup(normalized))

        assertEquals(
            "SELECT LOWER(\"advanced_sales\".\"country\") AS \"normalized_country\", COUNT(*) " +
                "FROM \"advanced_sales\" GROUP BY ROLLUP(LOWER(\"advanced_sales\".\"country\"))",
            SqlRenderer(AdvancedGroupingDialect).render(query).sql,
        )
    }

    @Test
    fun permitsGroupingInHavingAndOrderingAndPreservesGroupedWindowExpressions() {
        val country = AdvancedSales.country.optional
        val total = sum(AdvancedSales.amount).alias("total")
        val groupedAway = grouping(AdvancedSales.country)
        val groupedRank = rank().over { orderBy(total.desc()) }.alias("group_rank")
        val query = select(country, total, groupedRank)
            .from(AdvancedSales)
            .groupBy(rollup(AdvancedSales.country))
            .having(groupedAway eq 0)
            .orderBy(groupedAway.asc())

        assertEquals(
            "SELECT \"advanced_sales\".\"country\", SUM(\"advanced_sales\".\"amount\") AS \"total\", " +
                "RANK() OVER (ORDER BY SUM(\"advanced_sales\".\"amount\") DESC) AS \"group_rank\" " +
                "FROM \"advanced_sales\" GROUP BY ROLLUP(\"advanced_sales\".\"country\") " +
                "HAVING GROUPING(\"advanced_sales\".\"country\") = ? " +
                "ORDER BY GROUPING(\"advanced_sales\".\"country\") ASC",
            SqlRenderer(AdvancedGroupingDialect).render(query).sql,
        )
    }
}

private object AdvancedGroupingDialect : SqlDialect {
    override fun quoteIdentifier(identifier: String): String = "\"${identifier.replace("\"", "\"\"")}\""

    override fun parameterMarker(position: Int): String = "?"

    override fun supportsGrouping(capability: SqlGroupingCapability): Boolean = true
}

private object AdvancedSales : Table("advanced_sales") {
    val organizationId = int("organization_id")
    val country = string("country")
    val city = string("city")
    val product = string("product")
    val amount = int("amount")
}

private object OtherSales : Table("other_sales") {
    val country = string("country")
}
