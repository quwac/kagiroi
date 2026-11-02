package dev.quwac.kagiroi.sqlserver

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.grouping
import dev.quwac.kagiroi.schema.optional
import dev.quwac.kagiroi.schema.sum
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.cube
import dev.quwac.kagiroi.sql.grandTotal
import dev.quwac.kagiroi.sql.group
import dev.quwac.kagiroi.sql.groupBy
import dev.quwac.kagiroi.sql.groupingSet
import dev.quwac.kagiroi.sql.groupingSets
import dev.quwac.kagiroi.sql.rollup
import dev.quwac.kagiroi.sql.select
import kotlin.test.Test
import kotlin.test.assertEquals

class AdvancedGroupingRenderingTest {
    @Test
    fun rendersIsoAdvancedGroupingAndGroupingFunction() {
        val country = SqlServerAdvancedGroupingRows.country.optional
        val city = SqlServerAdvancedGroupingRows.city.optional
        val total = sum(SqlServerAdvancedGroupingRows.amount)

        assertEquals(
            "SELECT [advanced_grouping].[country], [advanced_grouping].[city], " +
                "SUM([advanced_grouping].[amount]), GROUPING([advanced_grouping].[country]) " +
                "FROM [advanced_grouping] GROUP BY ROLLUP([advanced_grouping].[country], " +
                "([advanced_grouping].[city], [advanced_grouping].[product]))",
            SqlRenderer(SqlServerDialect).render(
                select(country, city, total, grouping(SqlServerAdvancedGroupingRows.country))
                    .from(SqlServerAdvancedGroupingRows)
                    .groupBy(
                        rollup(
                            SqlServerAdvancedGroupingRows.country,
                            group(SqlServerAdvancedGroupingRows.city, SqlServerAdvancedGroupingRows.product),
                        ),
                    ),
            ).sql,
        )
        assertEquals(
            "SELECT [advanced_grouping].[country], [advanced_grouping].[city], " +
                "SUM([advanced_grouping].[amount]) FROM [advanced_grouping] " +
                "GROUP BY CUBE([advanced_grouping].[country], [advanced_grouping].[city])",
            SqlRenderer(SqlServerDialect).render(
                select(country, city, total).from(SqlServerAdvancedGroupingRows)
                    .groupBy(cube(SqlServerAdvancedGroupingRows.country, SqlServerAdvancedGroupingRows.city)),
            ).sql,
        )
        assertEquals(
            "SELECT [advanced_grouping].[country], [advanced_grouping].[city], " +
                "SUM([advanced_grouping].[amount]) FROM [advanced_grouping] GROUP BY " +
                "GROUPING SETS (([advanced_grouping].[country], [advanced_grouping].[city]), " +
                "([advanced_grouping].[country]), ())",
            SqlRenderer(SqlServerDialect).render(
                select(country, city, total).from(SqlServerAdvancedGroupingRows).groupBy(
                    groupingSets(
                        groupingSet(SqlServerAdvancedGroupingRows.country, SqlServerAdvancedGroupingRows.city),
                        groupingSet(SqlServerAdvancedGroupingRows.country),
                        grandTotal(),
                    ),
                ),
            ).sql,
        )
    }
}

private object SqlServerAdvancedGroupingRows : Table("advanced_grouping") {
    val country = string("country")
    val city = string("city")
    val product = string("product")
    val amount = int("amount")
}
