package dev.quwac.kagiroi.sqlite

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.UnsupportedCapability
import dev.quwac.kagiroi.sql.crossJoinLateral
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.lateral
import dev.quwac.kagiroi.sql.leftJoinLateral
import dev.quwac.kagiroi.sql.optional
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.where
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LateralRenderingTest {
    @Test
    fun rejectsLateralDerivedRelationsUntilStableSqliteSupportsThem() {
        val latest = lateral("latest") {
            select(SqliteLateralOrders.id)
                .from(SqliteLateralOrders)
                .where(SqliteLateralOrders.userId eq SqliteLateralUsers.id)
        }
        val latestId = latest[SqliteLateralOrders.id]

        assertEquals(
            "lateral-cross",
            assertFailsWith<UnsupportedCapability> {
                SqlRenderer(SqliteDialect).render(
                    select(SqliteLateralUsers.id, latestId).from(SqliteLateralUsers).crossJoinLateral(latest),
                )
            }.capabilityName,
        )
        assertEquals(
            "lateral-left",
            assertFailsWith<UnsupportedCapability> {
                SqlRenderer(SqliteDialect).render(
                    select(SqliteLateralUsers.id, latestId.optional)
                        .from(SqliteLateralUsers)
                        .leftJoinLateral(latest),
                )
            }.capabilityName,
        )
    }
}

private object SqliteLateralUsers : Table("lateral_users") {
    val id = long("id")
}

private object SqliteLateralOrders : Table("lateral_orders") {
    val id = long("id")
    val userId = long("user_id")
}
