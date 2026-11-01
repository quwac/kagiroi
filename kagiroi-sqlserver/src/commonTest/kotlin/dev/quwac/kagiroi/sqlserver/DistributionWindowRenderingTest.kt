package dev.quwac.kagiroi.sqlserver

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.cumeDist
import dev.quwac.kagiroi.schema.firstValue
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.percentRank
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.over
import dev.quwac.kagiroi.sql.select
import kotlin.test.Test
import kotlin.test.assertContains

class DistributionWindowRenderingTest {
    @Test
    fun rendersNativeDistributionWindowsAndIgnoreNulls() {
        val rendered = SqlRenderer(SqlServerDialect).render(
            select(
                percentRank().over { orderBy(SqlServerDistributionRows.score.asc()) },
                cumeDist().over { orderBy(SqlServerDistributionRows.score.asc()) },
                firstValue(SqlServerDistributionRows.score).ignoreNulls()
                    .over { orderBy(SqlServerDistributionRows.id.asc()) },
            ).from(SqlServerDistributionRows),
        ).sql

        assertContains(rendered, "PERCENT_RANK() OVER (ORDER BY [sqlserver_distribution_rows].[score] ASC)")
        assertContains(rendered, "CUME_DIST() OVER (ORDER BY [sqlserver_distribution_rows].[score] ASC)")
        assertContains(rendered, "FIRST_VALUE([sqlserver_distribution_rows].[score]) IGNORE NULLS OVER")
    }
}

private object SqlServerDistributionRows : Table("sqlserver_distribution_rows") {
    val id = int("id")
    val score = int("score").nullable()
}
