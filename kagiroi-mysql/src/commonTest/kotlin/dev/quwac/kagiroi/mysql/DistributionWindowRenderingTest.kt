package dev.quwac.kagiroi.mysql

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.WindowNullTreatment
import dev.quwac.kagiroi.schema.cumeDist
import dev.quwac.kagiroi.schema.firstValue
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.percentRank
import dev.quwac.kagiroi.schema.withWindowNullTreatment
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.UnsupportedCapability
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.over
import dev.quwac.kagiroi.sql.select
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class DistributionWindowRenderingTest {
    @Test
    fun rendersNativeDistributionWindowsAndOnlyRespectNulls() {
        val rendered = SqlRenderer(MySqlDialect).render(
            select(
                percentRank().over { orderBy(MySqlDistributionRows.score.asc()) },
                cumeDist().over { orderBy(MySqlDistributionRows.score.asc()) },
                firstValue(MySqlDistributionRows.score).respectNulls().over { orderBy(MySqlDistributionRows.id.asc()) },
            ).from(MySqlDistributionRows),
        ).sql

        assertContains(rendered, "PERCENT_RANK() OVER (ORDER BY `mysql_distribution_rows`.`score` ASC)")
        assertContains(rendered, "CUME_DIST() OVER (ORDER BY `mysql_distribution_rows`.`score` ASC)")
        assertContains(rendered, "FIRST_VALUE(`mysql_distribution_rows`.`score`) RESPECT NULLS OVER")
        assertFailsWith<UnsupportedCapability> {
            SqlRenderer(MySqlDialect).render(
                select(
                    firstValue(MySqlDistributionRows.score)
                        .withWindowNullTreatment(WindowNullTreatment.IgnoreNulls)
                        .over { orderBy(MySqlDistributionRows.id.asc()) },
                ).from(MySqlDistributionRows),
            )
        }
    }
}

private object MySqlDistributionRows : Table("mysql_distribution_rows") {
    val id = int("id")
    val score = int("score").nullable()
}
