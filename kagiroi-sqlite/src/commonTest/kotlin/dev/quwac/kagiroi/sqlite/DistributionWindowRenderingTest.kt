package dev.quwac.kagiroi.sqlite

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.cumeDist
import dev.quwac.kagiroi.schema.percentRank
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.over
import dev.quwac.kagiroi.sql.select
import kotlin.test.Test
import kotlin.test.assertContains

class DistributionWindowRenderingTest {
    @Test
    fun rendersNativeDistributionWindows() {
        val rendered = SqlRenderer(SqliteDialect).render(
            select(
                percentRank().over { orderBy(SqliteDistributionRows.score.asc()) },
                cumeDist().over { orderBy(SqliteDistributionRows.score.asc()) },
            ).from(SqliteDistributionRows),
        ).sql

        assertContains(rendered, "PERCENT_RANK() OVER (ORDER BY \"sqlite_distribution_rows\".\"score\" ASC)")
        assertContains(rendered, "CUME_DIST() OVER (ORDER BY \"sqlite_distribution_rows\".\"score\" ASC)")
    }
}

private object SqliteDistributionRows : Table("sqlite_distribution_rows") {
    val score = int("score")
}
