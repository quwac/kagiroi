package dev.quwac.kagiroi.oracle

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.firstValue
import dev.quwac.kagiroi.schema.literal
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.over
import dev.quwac.kagiroi.sql.select
import kotlin.test.Test
import kotlin.test.assertContains

class AdvancedAnalyticalRenderingTest {
    @Test
    fun rendersNativeOrderedHypotheticalAndNullTreatmentSemantics() {
        val aggregateRendered = SqlRenderer(OracleDialect).render(
            select(
                percentileCont(0.5).withinGroup(OracleAnalyticalRows.score.asc()),
                percentileDisc(0.5).withinGroup(OracleAnalyticalRows.score.asc()),
                hypotheticalRank(25).withinGroup(OracleAnalyticalRows.score.asc()),
                hypotheticalDenseRank(25).withinGroup(OracleAnalyticalRows.score.asc()),
                hypotheticalPercentRank(25).withinGroup(OracleAnalyticalRows.score.asc()),
                hypotheticalCumeDist(literal(25)).withinGroup(OracleAnalyticalRows.score.asc()),
            ).from(OracleAnalyticalRows),
        )
        val windowRendered = SqlRenderer(OracleDialect).render(
            select(
                firstValue(OracleAnalyticalRows.score).ignoreNulls().over { orderBy(OracleAnalyticalRows.id.asc()) },
                firstValue(OracleAnalyticalRows.score).respectNulls().over { orderBy(OracleAnalyticalRows.id.asc()) },
            ).from(OracleAnalyticalRows),
        )

        assertContains(
            aggregateRendered.sql,
            "PERCENTILE_CONT(:p1) WITHIN GROUP (ORDER BY \"oracle_analytical_rows\".\"score\" ASC)",
        )
        assertContains(aggregateRendered.sql, "PERCENTILE_DISC(:p2) WITHIN GROUP")
        assertContains(aggregateRendered.sql, "RANK(:p3) WITHIN GROUP")
        assertContains(aggregateRendered.sql, "DENSE_RANK(:p4) WITHIN GROUP")
        assertContains(aggregateRendered.sql, "PERCENT_RANK(:p5) WITHIN GROUP")
        assertContains(aggregateRendered.sql, "CUME_DIST(:p6) WITHIN GROUP")
        assertContains(windowRendered.sql, "FIRST_VALUE(\"oracle_analytical_rows\".\"score\") IGNORE NULLS OVER")
        assertContains(windowRendered.sql, "FIRST_VALUE(\"oracle_analytical_rows\".\"score\") RESPECT NULLS OVER")
    }
}

private object OracleAnalyticalRows : Table("oracle_analytical_rows") {
    val id = int("id")
    val score = int("score").nullable()
}
