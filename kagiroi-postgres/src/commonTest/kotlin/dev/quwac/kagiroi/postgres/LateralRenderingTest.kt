package dev.quwac.kagiroi.postgres

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.crossJoinLateral
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.lateral
import dev.quwac.kagiroi.sql.leftJoinLateral
import dev.quwac.kagiroi.sql.optional
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.where
import kotlin.test.Test
import kotlin.test.assertEquals

class LateralRenderingTest {
    @Test
    fun rendersNativeCrossAndLeftLateral() {
        val latest = lateral("latest") {
            select(PostgresLateralOrders.id)
                .from(PostgresLateralOrders)
                .where(PostgresLateralOrders.userId eq PostgresLateralUsers.id)
        }
        val latestId = latest[PostgresLateralOrders.id]

        assertEquals(
            "SELECT \"lateral_users\".\"id\", \"latest\".\"id\" FROM \"lateral_users\" " +
                "CROSS JOIN LATERAL (SELECT \"lateral_orders\".\"id\" FROM \"lateral_orders\" " +
                "WHERE \"lateral_orders\".\"user_id\" = \"lateral_users\".\"id\") AS \"latest\"",
            SqlRenderer(PostgresDialect).render(
                select(PostgresLateralUsers.id, latestId).from(PostgresLateralUsers).crossJoinLateral(latest),
            ).sql,
        )
        assertEquals(
            "SELECT \"lateral_users\".\"id\", \"latest\".\"id\" FROM \"lateral_users\" " +
                "LEFT JOIN LATERAL (SELECT \"lateral_orders\".\"id\" FROM \"lateral_orders\" " +
                "WHERE \"lateral_orders\".\"user_id\" = \"lateral_users\".\"id\") AS \"latest\" ON TRUE",
            SqlRenderer(PostgresDialect).render(
                select(PostgresLateralUsers.id, latestId.optional)
                    .from(PostgresLateralUsers)
                    .leftJoinLateral(latest),
            ).sql,
        )
    }
}

private object PostgresLateralUsers : Table("lateral_users") {
    val id = long("id")
}

private object PostgresLateralOrders : Table("lateral_orders") {
    val id = long("id")
    val userId = long("user_id")
}
