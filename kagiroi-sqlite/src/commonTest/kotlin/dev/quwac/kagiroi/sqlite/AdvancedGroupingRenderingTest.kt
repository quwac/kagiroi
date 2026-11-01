package dev.quwac.kagiroi.sqlite

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.optional
import dev.quwac.kagiroi.schema.sum
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.UnsupportedCapability
import dev.quwac.kagiroi.sql.cube
import dev.quwac.kagiroi.sql.grandTotal
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
    fun rejectsAdvancedGroupingWithoutNativeSqliteSupport() {
        val country = SqliteAdvancedGroupingRows.country.optional
        val total = sum(SqliteAdvancedGroupingRows.amount)
        val renderer = SqlRenderer(SqliteDialect)

        assertEquals(
            "grouping-rollup",
            assertFailsWith<UnsupportedCapability> {
                renderer.render(
                    select(country, total).from(SqliteAdvancedGroupingRows)
                        .groupBy(rollup(SqliteAdvancedGroupingRows.country)),
                )
            }.capabilityName,
        )
        assertEquals(
            "grouping-cube",
            assertFailsWith<UnsupportedCapability> {
                renderer.render(
                    select(country, total).from(SqliteAdvancedGroupingRows)
                        .groupBy(cube(SqliteAdvancedGroupingRows.country)),
                )
            }.capabilityName,
        )
        assertEquals(
            "grouping-sets",
            assertFailsWith<UnsupportedCapability> {
                renderer.render(
                    select(country, total).from(SqliteAdvancedGroupingRows).groupBy(
                        groupingSets(groupingSet(SqliteAdvancedGroupingRows.country), grandTotal()),
                    ),
                )
            }.capabilityName,
        )
    }
}

private object SqliteAdvancedGroupingRows : Table("advanced_grouping") {
    val country = string("country")
    val amount = int("amount")
}
