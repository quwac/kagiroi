package dev.quwac.kagiroi.mysql

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.grouping
import dev.quwac.kagiroi.schema.optional
import dev.quwac.kagiroi.schema.sum
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.UnsupportedCapability
import dev.quwac.kagiroi.sql.cube
import dev.quwac.kagiroi.sql.groupBy
import dev.quwac.kagiroi.sql.groupingSet
import dev.quwac.kagiroi.sql.groupingSets
import dev.quwac.kagiroi.sql.rollup
import dev.quwac.kagiroi.sql.select
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdvancedGroupingRenderingTest {
    @Test
    fun rendersNativeRollupAndGroupingAndRejectsUnsupportedAdvancedForms() {
        val country = MySqlAdvancedGroupingRows.country.optional
        val total = sum(MySqlAdvancedGroupingRows.amount)
        val renderer = SqlRenderer(MySqlDialect)
        val query = select(country, total, grouping(MySqlAdvancedGroupingRows.country))
            .from(MySqlAdvancedGroupingRows)
            .groupBy(rollup(MySqlAdvancedGroupingRows.country))

        assertEquals(
            "SELECT `advanced_grouping`.`country`, SUM(`advanced_grouping`.`amount`), " +
                "GROUPING(`advanced_grouping`.`country`) FROM `advanced_grouping` " +
                "GROUP BY ROLLUP(`advanced_grouping`.`country`)",
            renderer.render(query).sql,
        )
        assertEquals(
            "grouping-cube",
            assertFailsWith<UnsupportedCapability> {
                renderer.render(
                    select(country, total).from(MySqlAdvancedGroupingRows)
                        .groupBy(cube(MySqlAdvancedGroupingRows.country)),
                )
            }.capabilityName,
        )
        assertEquals(
            "grouping-sets",
            assertFailsWith<UnsupportedCapability> {
                renderer.render(
                    select(country, total).from(MySqlAdvancedGroupingRows)
                        .groupBy(groupingSets(groupingSet(MySqlAdvancedGroupingRows.country))),
                )
            }.capabilityName,
        )
    }
}

private object MySqlAdvancedGroupingRows : Table("advanced_grouping") {
    val country = string("country")
    val amount = int("amount")
}
