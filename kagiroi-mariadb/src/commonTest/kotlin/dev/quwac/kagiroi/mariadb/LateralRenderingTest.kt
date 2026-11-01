package dev.quwac.kagiroi.mariadb

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
    fun rejectsLateralDerivedRelationsInsteadOfEmulatingThem() {
        val latest = lateral("latest") {
            select(MariaDbLateralOrders.id)
                .from(MariaDbLateralOrders)
                .where(MariaDbLateralOrders.userId eq MariaDbLateralUsers.id)
        }
        val latestId = latest[MariaDbLateralOrders.id]

        assertEquals(
            "lateral-cross",
            assertFailsWith<UnsupportedCapability> {
                SqlRenderer(MariaDbDialect).render(
                    select(MariaDbLateralUsers.id, latestId).from(MariaDbLateralUsers).crossJoinLateral(latest),
                )
            }.capabilityName,
        )
        assertEquals(
            "lateral-left",
            assertFailsWith<UnsupportedCapability> {
                SqlRenderer(MariaDbDialect).render(
                    select(MariaDbLateralUsers.id, latestId.optional)
                        .from(MariaDbLateralUsers)
                        .leftJoinLateral(latest),
                )
            }.capabilityName,
        )
    }
}

private object MariaDbLateralUsers : Table("lateral_users") {
    val id = long("id")
}

private object MariaDbLateralOrders : Table("lateral_orders") {
    val id = long("id")
    val userId = long("user_id")
}
