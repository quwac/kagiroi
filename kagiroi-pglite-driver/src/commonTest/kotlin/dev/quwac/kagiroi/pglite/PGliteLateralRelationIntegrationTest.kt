package dev.quwac.kagiroi.pglite

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.count
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.crossJoinLateral
import dev.quwac.kagiroi.sql.desc
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.lateral
import dev.quwac.kagiroi.sql.leftJoinLateral
import dev.quwac.kagiroi.sql.limit
import dev.quwac.kagiroi.sql.optional
import dev.quwac.kagiroi.sql.orderBy
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.values
import dev.quwac.kagiroi.sql.where
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PGliteLateralRelationIntegrationTest {
    @Test
    fun executesCrossAndLeftLateralRelations() = runTest {
        withLateralFixture { database ->
            val latest = latestOrderRelation()
            val latestId = latest[PGliteLateralOrders.id]
            val latestAmount = latest[PGliteLateralOrders.amount]

            assertEquals(
                listOf(LatestOrder(1, 11, 200), LatestOrder(2, 20, 300)),
                database.select(PGliteLateralUsers.id, latestId, latestAmount)
                    .from(PGliteLateralUsers)
                    .crossJoinLateral(latest)
                    .orderBy(PGliteLateralUsers.id.asc())
                    .all()
                    .map { row -> LatestOrder(row[PGliteLateralUsers.id], row[latestId], row[latestAmount]) },
            )
            assertEquals(
                listOf(LatestOrder(1, 11, 200), LatestOrder(2, 20, 300), LatestOrder(3, null, null)),
                database.select(PGliteLateralUsers.id, latestId.optional, latestAmount.optional)
                    .from(PGliteLateralUsers)
                    .leftJoinLateral(latest)
                    .orderBy(PGliteLateralUsers.id.asc())
                    .all()
                    .map { row ->
                        LatestOrder(
                            row[PGliteLateralUsers.id],
                            row[latestId.optional],
                            row[latestAmount.optional],
                        )
                    },
            )
        }
    }

    @Test
    fun executesMultipleRowsFromOneLateralRelation() = runTest {
        withLateralFixture { database ->
            val matchingOrders = lateral("matching_orders") {
                select(PGliteLateralOrders.id)
                    .from(PGliteLateralOrders)
                    .where(PGliteLateralOrders.userId eq PGliteLateralUsers.id)
            }
            val matchingOrderId = matchingOrders[PGliteLateralOrders.id]

            assertEquals(
                listOf(10L, 11L, 20L),
                database.select(matchingOrderId)
                    .from(PGliteLateralUsers)
                    .crossJoinLateral(matchingOrders)
                    .orderBy(matchingOrderId.asc())
                    .all()
                    .map { row -> row[matchingOrderId] },
            )
        }
    }

    @Test
    fun executesAggregateLateralRelations() = runTest {
        withLateralFixture { database ->
            val count = count().alias("order_count")
            val stats = lateral("stats") {
                select(count)
                    .from(PGliteLateralOrders)
                    .where(PGliteLateralOrders.userId eq PGliteLateralUsers.id)
            }
            val orderCount = stats[count].optional

            assertEquals(
                listOf(1L to 2L, 2L to 1L, 3L to 0L),
                database.select(PGliteLateralUsers.id, orderCount)
                    .from(PGliteLateralUsers)
                    .leftJoinLateral(stats)
                    .orderBy(PGliteLateralUsers.id.asc())
                    .all()
                    .map { row -> row[PGliteLateralUsers.id] to row[orderCount] },
            )
        }
    }
}

private fun latestOrderRelation() = lateral("latest") {
    select(PGliteLateralOrders.id, PGliteLateralOrders.amount)
        .from(PGliteLateralOrders)
        .where(PGliteLateralOrders.userId eq PGliteLateralUsers.id)
        .orderBy(PGliteLateralOrders.createdAt.desc())
        .limit(1)
}

private suspend fun withLateralFixture(block: suspend (PGliteDatabase) -> Unit) {
    val database = PGliteDatabase.inMemory()
    try {
        database.execute(createTable(PGliteLateralUsers))
        database.execute(createTable(PGliteLateralOrders))
        insertFixture(database)
        block(database)
    } finally {
        database.close()
    }
}

private suspend fun insertFixture(database: PGliteDatabase) {
    listOf(1L, 2L, 3L).forEach { id ->
        database.insertInto(PGliteLateralUsers).values { set(PGliteLateralUsers.id, id) }.execute()
    }
    listOf(
        Triple(10L, 1L, 100L) to 1000L,
        Triple(11L, 1L, 200L) to 2000L,
        Triple(20L, 2L, 300L) to 1500L,
    ).forEach { (order, createdAt) ->
        database.insertInto(PGliteLateralOrders).values {
            set(PGliteLateralOrders.id, order.first)
            set(PGliteLateralOrders.userId, order.second)
            set(PGliteLateralOrders.amount, order.third)
            set(PGliteLateralOrders.createdAt, createdAt)
        }.execute()
    }
}

private data class LatestOrder(val userId: Long, val id: Long?, val amount: Long?)

private object PGliteLateralUsers : Table("pglite_lateral_users") {
    val id = long("id")
}

private object PGliteLateralOrders : Table("pglite_lateral_orders") {
    val id = long("id")
    val userId = long("user_id")
    val amount = long("amount")
    val createdAt = long("created_at")
}
