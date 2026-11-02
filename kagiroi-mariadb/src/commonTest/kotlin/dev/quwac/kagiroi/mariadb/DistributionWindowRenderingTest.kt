package dev.quwac.kagiroi.mariadb

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
        val rendered = SqlRenderer(MariaDbDialect).render(
            select(
                percentRank().over { orderBy(MariaDbDistributionRows.score.asc()) },
                cumeDist().over { orderBy(MariaDbDistributionRows.score.asc()) },
            ).from(MariaDbDistributionRows),
        ).sql

        assertContains(rendered, "PERCENT_RANK() OVER (ORDER BY `mariadb_distribution_rows`.`score` ASC)")
        assertContains(rendered, "CUME_DIST() OVER (ORDER BY `mariadb_distribution_rows`.`score` ASC)")
    }
}

private object MariaDbDistributionRows : Table("mariadb_distribution_rows") {
    val score = int("score")
}
