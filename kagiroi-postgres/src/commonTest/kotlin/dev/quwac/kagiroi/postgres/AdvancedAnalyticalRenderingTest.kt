package dev.quwac.kagiroi.postgres

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.WindowNullTreatment
import dev.quwac.kagiroi.schema.firstValue
import dev.quwac.kagiroi.schema.literal
import dev.quwac.kagiroi.schema.withWindowNullTreatment
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.UnsupportedCapability
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.nullsLast
import dev.quwac.kagiroi.sql.over
import dev.quwac.kagiroi.sql.select
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class AdvancedAnalyticalRenderingTest {
    @Test
    fun rendersNativeOrderedAndHypotheticalSetAggregates() {
        val rendered = SqlRenderer(PostgresDialect).render(
            select(
                percentileCont(0.5).withinGroup(PostgresAnalyticalRows.score.asc().nullsLast()),
                percentileDisc(0.5).withinGroup(PostgresAnalyticalRows.score.asc()),
                mode().withinGroup(PostgresAnalyticalRows.score.asc()),
                hypotheticalRank(25).withinGroup(PostgresAnalyticalRows.score.asc()),
                hypotheticalDenseRank(25).withinGroup(PostgresAnalyticalRows.score.asc()),
                hypotheticalPercentRank(25).withinGroup(PostgresAnalyticalRows.score.asc()),
                hypotheticalCumeDist(literal(25)).withinGroup(PostgresAnalyticalRows.score.asc()),
            ).from(PostgresAnalyticalRows),
        )

        assertContains(
            rendered.sql,
            "PERCENTILE_CONT($1) WITHIN GROUP (ORDER BY \"postgres_analytical_rows\".\"score\" ASC NULLS LAST)",
        )
        assertContains(rendered.sql, "PERCENTILE_DISC($2) WITHIN GROUP")
        assertContains(rendered.sql, "MODE() WITHIN GROUP")
        assertContains(rendered.sql, "RANK($3) WITHIN GROUP")
        assertContains(rendered.sql, "DENSE_RANK($4) WITHIN GROUP")
        assertContains(rendered.sql, "PERCENT_RANK($5) WITHIN GROUP")
        assertContains(rendered.sql, "CUME_DIST($6) WITHIN GROUP")
    }

    @Test
    fun rejectsPostgresNullTreatmentInsteadOfChangingDefaultSemantics() {
        assertFailsWith<UnsupportedCapability> {
            SqlRenderer(PostgresDialect).render(
                select(
                    firstValue(PostgresAnalyticalRows.score)
                        .withWindowNullTreatment(WindowNullTreatment.RespectNulls)
                        .over { orderBy(PostgresAnalyticalRows.id.asc()) },
                ).from(PostgresAnalyticalRows),
            )
        }
    }
}

private object PostgresAnalyticalRows : Table("postgres_analytical_rows") {
    val id = int("id")
    val score = int("score")
}
