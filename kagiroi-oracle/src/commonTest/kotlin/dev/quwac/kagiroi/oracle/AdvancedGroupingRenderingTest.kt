package dev.quwac.kagiroi.oracle

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
    fun rendersNativeAdvancedGroupingAndGroupingFunction() {
        val country = OracleAdvancedGroupingRows.country.optional
        val city = OracleAdvancedGroupingRows.city.optional
        val total = sum(OracleAdvancedGroupingRows.amount)

        assertEquals(
            "SELECT \"advanced_grouping\".\"country\", \"advanced_grouping\".\"city\", " +
                "SUM(\"advanced_grouping\".\"amount\"), GROUPING(\"advanced_grouping\".\"country\") " +
                "FROM \"advanced_grouping\" GROUP BY ROLLUP(\"advanced_grouping\".\"country\", " +
                "(\"advanced_grouping\".\"city\", \"advanced_grouping\".\"product\"))",
            SqlRenderer(OracleDialect).render(
                select(country, city, total, grouping(OracleAdvancedGroupingRows.country))
                    .from(OracleAdvancedGroupingRows)
                    .groupBy(
                        rollup(
                            OracleAdvancedGroupingRows.country,
                            group(OracleAdvancedGroupingRows.city, OracleAdvancedGroupingRows.product),
                        ),
                    ),
            ).sql,
        )
        assertEquals(
            "SELECT \"advanced_grouping\".\"country\", \"advanced_grouping\".\"city\", " +
                "SUM(\"advanced_grouping\".\"amount\") FROM \"advanced_grouping\" " +
                "GROUP BY CUBE(\"advanced_grouping\".\"country\", \"advanced_grouping\".\"city\")",
            SqlRenderer(OracleDialect).render(
                select(country, city, total).from(OracleAdvancedGroupingRows)
                    .groupBy(cube(OracleAdvancedGroupingRows.country, OracleAdvancedGroupingRows.city)),
            ).sql,
        )
        assertEquals(
            "SELECT \"advanced_grouping\".\"country\", \"advanced_grouping\".\"city\", " +
                "SUM(\"advanced_grouping\".\"amount\") FROM \"advanced_grouping\" GROUP BY " +
                "GROUPING SETS ((\"advanced_grouping\".\"country\", \"advanced_grouping\".\"city\"), " +
                "(\"advanced_grouping\".\"country\"), ())",
            SqlRenderer(OracleDialect).render(
                select(country, city, total).from(OracleAdvancedGroupingRows).groupBy(
                    groupingSets(
                        groupingSet(OracleAdvancedGroupingRows.country, OracleAdvancedGroupingRows.city),
                        groupingSet(OracleAdvancedGroupingRows.country),
                        grandTotal(),
                    ),
                ),
            ).sql,
        )
    }
}

private object OracleAdvancedGroupingRows : Table("advanced_grouping") {
    val country = string("country")
    val city = string("city")
    val product = string("product")
    val amount = int("amount")
}
