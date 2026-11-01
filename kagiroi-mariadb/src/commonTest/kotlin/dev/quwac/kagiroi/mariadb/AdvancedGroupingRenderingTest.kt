package dev.quwac.kagiroi.mariadb

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.grouping
import dev.quwac.kagiroi.schema.optional
import dev.quwac.kagiroi.schema.sum
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.UnsupportedCapability
import dev.quwac.kagiroi.sql.cube
import dev.quwac.kagiroi.sql.desc
import dev.quwac.kagiroi.sql.groupBy
import dev.quwac.kagiroi.sql.orderBy
import dev.quwac.kagiroi.sql.rollup
import dev.quwac.kagiroi.sql.select
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdvancedGroupingRenderingTest {
    @Test
    fun rendersWithRollupAndRejectsUnsupportedAdvancedForms() {
        val country = MariaDbAdvancedGroupingRows.country.optional
        val total = sum(MariaDbAdvancedGroupingRows.amount)
        val renderer = SqlRenderer(MariaDbDialect)
        val query = select(country, total).from(MariaDbAdvancedGroupingRows)
            .groupBy(rollup(MariaDbAdvancedGroupingRows.country))

        assertEquals(
            "SELECT `advanced_grouping`.`country`, SUM(`advanced_grouping`.`amount`) " +
                "FROM `advanced_grouping` GROUP BY `advanced_grouping`.`country` WITH ROLLUP",
            renderer.render(query).sql,
        )
        assertEquals(
            "grouping-function",
            assertFailsWith<UnsupportedCapability> {
                renderer.render(
                    select(country, total, grouping(MariaDbAdvancedGroupingRows.country))
                        .from(MariaDbAdvancedGroupingRows)
                        .groupBy(rollup(MariaDbAdvancedGroupingRows.country)),
                )
            }.capabilityName,
        )
        assertEquals(
            "grouping-cube",
            assertFailsWith<UnsupportedCapability> {
                renderer.render(
                    select(country, total).from(MariaDbAdvancedGroupingRows)
                        .groupBy(cube(MariaDbAdvancedGroupingRows.country)),
                )
            }.capabilityName,
        )
        assertEquals(
            "grouping-order-by",
            assertFailsWith<UnsupportedCapability> {
                renderer.render(query.orderBy(country.desc()))
            }.capabilityName,
        )
    }
}

private object MariaDbAdvancedGroupingRows : Table("advanced_grouping") {
    val country = string("country")
    val amount = int("amount")
}
