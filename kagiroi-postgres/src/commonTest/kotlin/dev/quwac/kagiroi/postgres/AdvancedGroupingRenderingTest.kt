package dev.quwac.kagiroi.postgres

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
        val country = PostgresAdvancedGroupingRows.country.optional
        val city = PostgresAdvancedGroupingRows.city.optional
        val total = sum(PostgresAdvancedGroupingRows.amount)

        assertEquals(
            "SELECT \"advanced_grouping\".\"country\", \"advanced_grouping\".\"city\", " +
                "SUM(\"advanced_grouping\".\"amount\"), GROUPING(\"advanced_grouping\".\"country\") " +
                "FROM \"advanced_grouping\" GROUP BY ROLLUP(\"advanced_grouping\".\"country\", " +
                "(\"advanced_grouping\".\"city\", \"advanced_grouping\".\"product\"))",
            SqlRenderer(PostgresDialect).render(
                select(country, city, total, grouping(PostgresAdvancedGroupingRows.country))
                    .from(PostgresAdvancedGroupingRows)
                    .groupBy(
                        rollup(
                            PostgresAdvancedGroupingRows.country,
                            group(PostgresAdvancedGroupingRows.city, PostgresAdvancedGroupingRows.product),
                        ),
                    ),
            ).sql,
        )
        assertEquals(
            "SELECT \"advanced_grouping\".\"country\", \"advanced_grouping\".\"city\", " +
                "SUM(\"advanced_grouping\".\"amount\") FROM \"advanced_grouping\" " +
                "GROUP BY CUBE(\"advanced_grouping\".\"country\", \"advanced_grouping\".\"city\")",
            SqlRenderer(PostgresDialect).render(
                select(country, city, total).from(PostgresAdvancedGroupingRows)
                    .groupBy(cube(PostgresAdvancedGroupingRows.country, PostgresAdvancedGroupingRows.city)),
            ).sql,
        )
        assertEquals(
            "SELECT \"advanced_grouping\".\"country\", \"advanced_grouping\".\"city\", " +
                "SUM(\"advanced_grouping\".\"amount\") FROM \"advanced_grouping\" GROUP BY " +
                "GROUPING SETS ((\"advanced_grouping\".\"country\", \"advanced_grouping\".\"city\"), " +
                "(\"advanced_grouping\".\"country\"), ())",
            SqlRenderer(PostgresDialect).render(
                select(country, city, total).from(PostgresAdvancedGroupingRows).groupBy(
                    groupingSets(
                        groupingSet(PostgresAdvancedGroupingRows.country, PostgresAdvancedGroupingRows.city),
                        groupingSet(PostgresAdvancedGroupingRows.country),
                        grandTotal(),
                    ),
                ),
            ).sql,
        )
    }
}

private object PostgresAdvancedGroupingRows : Table("advanced_grouping") {
    val country = string("country")
    val city = string("city")
    val product = string("product")
    val amount = int("amount")
}
