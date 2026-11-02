package dev.quwac.kagiroi.pglite

import dev.quwac.kagiroi.postgres.hypotheticalCumeDist
import dev.quwac.kagiroi.postgres.hypotheticalDenseRank
import dev.quwac.kagiroi.postgres.hypotheticalPercentRank
import dev.quwac.kagiroi.postgres.hypotheticalRank
import dev.quwac.kagiroi.postgres.mode
import dev.quwac.kagiroi.postgres.percentileCont
import dev.quwac.kagiroi.postgres.percentileDisc
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.cumeDist
import dev.quwac.kagiroi.schema.get
import dev.quwac.kagiroi.schema.percentRank
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.orderBy
import dev.quwac.kagiroi.sql.over
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.values
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PGliteAdvancedAnalyticalIntegrationTest {
    @Test
    fun delegatesDistributionOrderedSetAndHypotheticalSetSemanticsToPostgres() = runTest {
        val database = PGliteDatabase.inMemory()
        try {
            database.execute(createTable(PGliteAnalyticalRows))
            insertFixture(database)
            database.assertWindowDistributionSemantics()
            database.assertOrderedSetSemantics()
            database.assertHypotheticalSetSemantics()
        } finally {
            database.close()
        }
    }
}

private suspend fun PGliteDatabase.assertWindowDistributionSemantics() {
    val percentRank = percentRank().over { orderBy(PGliteAnalyticalRows.score.asc()) }.alias("percent_rank")
    val cumeDist = cumeDist().over { orderBy(PGliteAnalyticalRows.score.asc()) }.alias("cume_dist")
    val rows = select(PGliteAnalyticalRows.id, percentRank, cumeDist)
        .from(PGliteAnalyticalRows)
        .orderBy(PGliteAnalyticalRows.id.asc())
        .all()

    assertEquals(listOf(0.0, 1.0 / 3.0, 1.0 / 3.0, 1.0), rows.map { it[percentRank] })
    assertEquals(listOf(0.25, 0.75, 0.75, 1.0), rows.map { it[cumeDist] })
}

private suspend fun PGliteDatabase.assertOrderedSetSemantics() {
    val continuous = percentileCont(0.5).withinGroup(PGliteAnalyticalRows.amount.asc()).alias("continuous")
    val discrete = percentileDisc(0.5).withinGroup(PGliteAnalyticalRows.amount.asc()).alias("discrete")
    val mode = mode().withinGroup(PGliteAnalyticalRows.score.asc()).alias("mode")
    val row = select(continuous, discrete, mode).from(PGliteAnalyticalRows).all().single()

    assertEquals(25.0, row[continuous])
    assertEquals(20.0, row[discrete])
    assertEquals(20.0, row[mode])
}

private suspend fun PGliteDatabase.assertHypotheticalSetSemantics() {
    val rank = hypotheticalRank(25.0).withinGroup(PGliteAnalyticalRows.amount.asc()).alias("rank")
    val denseRank = hypotheticalDenseRank(25.0).withinGroup(PGliteAnalyticalRows.amount.asc()).alias("dense_rank")
    val percentRank = hypotheticalPercentRank(25.0).withinGroup(PGliteAnalyticalRows.amount.asc()).alias("percent_rank")
    val cumeDist = hypotheticalCumeDist(25.0).withinGroup(PGliteAnalyticalRows.amount.asc()).alias("cume_dist")
    val row = select(rank, denseRank, percentRank, cumeDist).from(PGliteAnalyticalRows).all().single()

    assertEquals(3L, row[rank])
    assertEquals(3L, row[denseRank])
    assertEquals(0.5, row[percentRank])
    assertEquals(0.6, row[cumeDist])
}

private suspend fun insertFixture(database: PGliteDatabase) {
    listOf(
        Triple(10.0, 10.0, 1),
        Triple(20.0, 20.0, 2),
        Triple(20.0, 30.0, 3),
        Triple(40.0, 40.0, 4),
    ).forEach { (score, amount, id) ->
        database.insertInto(PGliteAnalyticalRows).values {
            set(PGliteAnalyticalRows.id, id)
            set(PGliteAnalyticalRows.score, score)
            set(PGliteAnalyticalRows.amount, amount)
        }.execute()
    }
}

private object PGliteAnalyticalRows : Table("pglite_advanced_analytical_rows") {
    val id = int("id")
    val score = double("score")
    val amount = double("amount")
}
