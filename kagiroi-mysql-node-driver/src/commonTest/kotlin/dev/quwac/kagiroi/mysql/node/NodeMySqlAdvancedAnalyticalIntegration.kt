package dev.quwac.kagiroi.mysql.node

import dev.quwac.kagiroi.mysql.respectNulls
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.cumeDist
import dev.quwac.kagiroi.schema.lastValue
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.percentRank
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.between
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.currentRow
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.orderBy
import dev.quwac.kagiroi.sql.over
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.unboundedPreceding
import dev.quwac.kagiroi.sql.values
import kotlin.test.assertEquals

internal suspend fun verifyDistributionWindowSql(database: NodeMySqlDatabase) {
    try {
        database.execute(createTable(DistributionWindowRows))
        listOf(
            Pair(10, 1),
            Pair(20, 2),
            Pair(20, 3),
            Pair(40, 4),
        ).forEach { (score, id) ->
            database.insertInto(DistributionWindowRows).values {
                set(DistributionWindowRows.id, id)
                set(DistributionWindowRows.score, score)
            }.execute()
        }

        val percentRank = percentRank().over {
            orderBy(DistributionWindowRows.score.asc())
        }.alias("percent_rank")
        val cumeDist = cumeDist().over {
            orderBy(DistributionWindowRows.score.asc())
        }.alias("cume_dist")

        val rows = database.select(percentRank, cumeDist)
            .from(DistributionWindowRows)
            .orderBy(DistributionWindowRows.id.asc())
            .all()

        assertDistributionValues(
            expected = listOf(0.0, 1.0 / 3.0, 1.0 / 3.0, 1.0),
            actual = rows.map { it[percentRank] },
        )
        assertDistributionValues(
            expected = listOf(0.25, 0.75, 0.75, 1.0),
            actual = rows.map { it[cumeDist] },
        )
    } finally {
        database.executeRaw("DROP TABLE IF EXISTS kagiroi_distribution_window")
        database.close()
    }
}

internal suspend fun verifyMySqlExplicitNullTreatment(database: NodeMySqlDatabase) {
    try {
        database.execute(createTable(MySqlNullTreatmentRows))
        listOf(10, null, 20, null).forEachIndexed { index, value ->
            database.insertInto(MySqlNullTreatmentRows).values {
                set(MySqlNullTreatmentRows.id, index + 1)
                set(MySqlNullTreatmentRows.value, value)
            }.execute()
        }

        val lastRespectingNulls = lastValue(MySqlNullTreatmentRows.value)
            .respectNulls()
            .over {
                orderBy(MySqlNullTreatmentRows.id.asc())
                rows(between(unboundedPreceding, currentRow))
            }
            .alias("last_respecting_nulls")

        val rows = database.select(lastRespectingNulls)
            .from(MySqlNullTreatmentRows)
            .orderBy(MySqlNullTreatmentRows.id.asc())
            .all()

        assertEquals(listOf(10, null, 20, null), rows.map { it[lastRespectingNulls] })
    } finally {
        database.executeRaw("DROP TABLE IF EXISTS kagiroi_mysql_null_treatment")
        database.close()
    }
}

private object DistributionWindowRows : Table("kagiroi_distribution_window") {
    val id = int("id")
    val score = int("score")
}

private object MySqlNullTreatmentRows : Table("kagiroi_mysql_null_treatment") {
    val id = int("id")
    val value = int("value").nullable()
}

private fun assertDistributionValues(expected: List<Double>, actual: List<Double>) {
    assertEquals(expected.size, actual.size)
    expected.indices.forEach { index -> assertEquals(expected[index], actual[index], 0.000_000_001) }
}
