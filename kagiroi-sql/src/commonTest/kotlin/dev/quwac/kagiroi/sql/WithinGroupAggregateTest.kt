package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.literal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WithinGroupAggregateTest {
    @Test
    fun rendersOrderedSetAggregatesWithAggregateLocalOrderingAndNullableResults() {
        val continuous = percentileContAggregate(0.5)
            .withinGroup(AnalyticalRows.score.asc())
            .alias("continuous")
        val discrete = percentileDiscAggregate(0.5)
            .withinGroup(AnalyticalRows.score.asc())
            .alias("discrete")
        val mode = modeOrderedSetAggregate()
            .withinGroup(AnalyticalRows.score.desc())
            .alias("mode")

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
            select(continuous, discrete, mode).from(AnalyticalRows),
        )

        assertEquals(
            "SELECT PERCENTILE_CONT(?) WITHIN GROUP (ORDER BY \"analytical_rows\".\"score\" ASC) AS \"continuous\", " +
                "PERCENTILE_DISC(?) WITHIN GROUP (ORDER BY \"analytical_rows\".\"score\" ASC) AS \"discrete\", " +
                "MODE() WITHIN GROUP (ORDER BY \"analytical_rows\".\"score\" DESC) AS \"mode\" " +
                "FROM \"analytical_rows\"",
            rendered.sql,
        )
        assertEquals(listOf(0.5, 0.5), rendered.parameters.map { it.value })
        assertEquals(20, sqlRow(listOf(discrete), listOf<Any?>(20))[discrete])
        assertFailsWith<IllegalArgumentException> { percentileContAggregate(-0.1) }
        assertFailsWith<IllegalArgumentException> { percentileDiscAggregate(Double.NaN) }
    }

    @Test
    fun rendersHypotheticalAggregatesSeparatelyFromWindowRanksAndValidatesPositions() {
        val rank = hypotheticalSetAggregate(
            HypotheticalSetAggregateKind.Rank,
            literal(25),
            result = hypotheticalRankResult(),
        ).withinGroup(AnalyticalRows.score.asc()).alias("hypothetical_rank")
        val cumeDist = hypotheticalSetAggregate(
            HypotheticalSetAggregateKind.CumeDist,
            25,
            hypotheticalDistributionResult(),
        ).withinGroup(AnalyticalRows.score.asc()).alias("hypothetical_cume_dist")

        val rendered = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(
            select(rank, cumeDist).from(AnalyticalRows),
        )

        assertEquals(
            "SELECT RANK(?) WITHIN GROUP (ORDER BY \"analytical_rows\".\"score\" ASC) AS \"hypothetical_rank\", " +
                "CUME_DIST(?) WITHIN GROUP (ORDER BY \"analytical_rows\".\"score\" ASC) " +
                "AS \"hypothetical_cume_dist\" FROM \"analytical_rows\"",
            rendered.sql,
        )
        assertEquals(listOf(25, 25), rendered.parameters.map { it.value })

        assertFailsWith<IllegalArgumentException> {
            hypotheticalSetAggregate(
                HypotheticalSetAggregateKind.Rank,
                literal(25),
                literal(30),
                result = hypotheticalRankResult(),
            ).withinGroup(AnalyticalRows.score.asc())
        }
        assertFailsWith<IllegalArgumentException> {
            hypotheticalSetAggregate(
                HypotheticalSetAggregateKind.Rank,
                literal("25"),
                result = hypotheticalRankResult(),
            ).withinGroup(AnalyticalRows.score.asc())
        }
    }

    @Test
    fun integratesWithinGroupResultsWithAggregateAndDerivedRelationValidation() {
        val median = percentileDiscAggregate(0.5).withinGroup(AnalyticalRows.score.asc()).alias("median")
        val grouped = select(AnalyticalRows.group, median)
            .from(AnalyticalRows)
            .groupBy(AnalyticalRows.group)
        val summary = grouped.asTable("analytical_summary")
        val summaryMedian = summary[median]

        assertEquals(
            "SELECT \"analytical_summary\".\"median\" FROM (SELECT \"analytical_rows\".\"group\", " +
                "PERCENTILE_DISC(?) WITHIN GROUP (ORDER BY \"analytical_rows\".\"score\" ASC) AS \"median\" " +
                "FROM \"analytical_rows\" GROUP BY \"analytical_rows\".\"group\") AS \"analytical_summary\"",
            SqlRenderer(DoubleQuotedQuestionMarkDialect).render(select(summaryMedian).from(summary)).sql,
        )
        assertFailsWith<IllegalArgumentException> {
            select(AnalyticalRows.score).from(AnalyticalRows).where(median gt 20)
        }
    }
}

private object AnalyticalRows : Table("analytical_rows") {
    val group = int("group")
    val score = int("score")
}
