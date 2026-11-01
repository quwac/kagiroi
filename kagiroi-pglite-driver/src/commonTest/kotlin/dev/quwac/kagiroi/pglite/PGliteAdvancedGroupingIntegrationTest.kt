package dev.quwac.kagiroi.pglite

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.grouping
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.optional
import dev.quwac.kagiroi.schema.sum
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.cube
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.grandTotal
import dev.quwac.kagiroi.sql.group
import dev.quwac.kagiroi.sql.groupBy
import dev.quwac.kagiroi.sql.groupingSet
import dev.quwac.kagiroi.sql.groupingSets
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.rollup
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.values
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PGliteAdvancedGroupingIntegrationTest {
    @Test
    fun executesNativeRollupCubeAndGroupingSetsAndDistinguishesSyntheticNulls() = runTest {
        val database = PGliteDatabase.inMemory()
        try {
            database.execute(createTable(PGliteAdvancedGroupingRows))
            insertFixture(database)
            database.assertRollup()
            database.assertCube()
            database.assertGroupingSets()
            database.assertCompositeRollup()
        } finally {
            database.close()
        }
    }
}

private suspend fun PGliteDatabase.assertRollup() {
    val country = PGliteAdvancedGroupingRows.country.optional
    val city = PGliteAdvancedGroupingRows.city.optional
    val total = sum(PGliteAdvancedGroupingRows.amount).alias("total")
    val countryGrouping = grouping(PGliteAdvancedGroupingRows.country).alias("country_grouping")
    val cityGrouping = grouping(PGliteAdvancedGroupingRows.city).alias("city_grouping")
    val rows = select(country, city, total, countryGrouping, cityGrouping)
        .from(PGliteAdvancedGroupingRows)
        .groupBy(rollup(PGliteAdvancedGroupingRows.country, PGliteAdvancedGroupingRows.city))
        .all()
        .map { row ->
            GroupedRow(
                country = row[country],
                city = row[city],
                total = row[total],
                countryGrouping = row[countryGrouping],
                cityGrouping = row[cityGrouping],
            )
        }

    assertEquals(
        setOf(
            GroupedRow("Japan", "Tokyo", 100L, 0, 0),
            GroupedRow("Japan", "Osaka", 200L, 0, 0),
            GroupedRow("USA", "NY", 300L, 0, 0),
            GroupedRow(null, "Other", 50L, 0, 0),
            GroupedRow("Japan", null, 300L, 0, 1),
            GroupedRow("USA", null, 300L, 0, 1),
            GroupedRow(null, null, 50L, 0, 1),
            GroupedRow(null, null, 650L, 1, 1),
        ),
        rows.toSet(),
    )
}

private suspend fun PGliteDatabase.assertCube() {
    val country = PGliteAdvancedGroupingRows.country.optional
    val city = PGliteAdvancedGroupingRows.city.optional
    val total = sum(PGliteAdvancedGroupingRows.amount).alias("total")
    val countryGrouping = grouping(PGliteAdvancedGroupingRows.country).alias("country_grouping")
    val cityGrouping = grouping(PGliteAdvancedGroupingRows.city).alias("city_grouping")
    val rows = select(country, city, total, countryGrouping, cityGrouping)
        .from(PGliteAdvancedGroupingRows)
        .groupBy(cube(PGliteAdvancedGroupingRows.country, PGliteAdvancedGroupingRows.city))
        .all()
    assertEquals(12, rows.size)
    assertTrue(
        rows.any { row ->
            row[countryGrouping] == 1 && row[cityGrouping] == 1 && row[total] == 650L
        },
    )

    val duplicateSetRows = select(country, total)
        .from(PGliteAdvancedGroupingRows)
        .groupBy(
            groupingSets(
                groupingSet(PGliteAdvancedGroupingRows.country),
                groupingSet(PGliteAdvancedGroupingRows.country),
            ),
        )
        .all()
    assertEquals(6, duplicateSetRows.size)
}

private suspend fun PGliteDatabase.assertGroupingSets() {
    val country = PGliteAdvancedGroupingRows.country.optional
    val city = PGliteAdvancedGroupingRows.city.optional
    val total = sum(PGliteAdvancedGroupingRows.amount).alias("total")
    val countryGrouping = grouping(PGliteAdvancedGroupingRows.country).alias("country_grouping")
    val cityGrouping = grouping(PGliteAdvancedGroupingRows.city).alias("city_grouping")
    val rows = select(country, city, total, countryGrouping, cityGrouping)
        .from(PGliteAdvancedGroupingRows)
        .groupBy(
            groupingSets(
                groupingSet(PGliteAdvancedGroupingRows.country, PGliteAdvancedGroupingRows.city),
                groupingSet(PGliteAdvancedGroupingRows.country),
                grandTotal(),
            ),
        )
        .all()
    assertEquals(8, rows.size)
    assertTrue(
        rows.any { row ->
            row[countryGrouping] == 1 && row[cityGrouping] == 1 && row[total] == 650L
        },
    )
}

private suspend fun PGliteDatabase.assertCompositeRollup() {
    val country = PGliteAdvancedGroupingRows.country.optional
    val city = PGliteAdvancedGroupingRows.city.optional
    val total = sum(PGliteAdvancedGroupingRows.amount).alias("total")
    val rows = select(country, city, total)
        .from(PGliteAdvancedGroupingRows)
        .groupBy(rollup(group(PGliteAdvancedGroupingRows.country, PGliteAdvancedGroupingRows.city)))
        .all()
    assertEquals(5, rows.size)
}

private suspend fun insertFixture(database: PGliteDatabase) {
    listOf(
        Triple("Japan", "Tokyo", 100),
        Triple("Japan", "Osaka", 200),
        Triple("USA", "NY", 300),
        Triple(null, "Other", 50),
    ).forEach { (country, city, amount) ->
        database.insertInto(PGliteAdvancedGroupingRows).values {
            set(PGliteAdvancedGroupingRows.country, country)
            set(PGliteAdvancedGroupingRows.city, city)
            set(PGliteAdvancedGroupingRows.amount, amount)
        }.execute()
    }
}

private data class GroupedRow(
    val country: String?,
    val city: String?,
    val total: Long?,
    val countryGrouping: Int,
    val cityGrouping: Int,
)

private object PGliteAdvancedGroupingRows : Table("pglite_advanced_grouping_rows") {
    val country = string("country").nullable()
    val city = string("city")
    val amount = int("amount")
}
