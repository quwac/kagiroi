package dev.quwac.kagiroi.mysql

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
            select(MySqlLateralOrders.id)
                .from(MySqlLateralOrders)
                .where(MySqlLateralOrders.userId eq MySqlLateralUsers.id)
        }
        val latestId = latest[MySqlLateralOrders.id]

        assertEquals(
            "SELECT `lateral_users`.`id`, `latest`.`id` FROM `lateral_users` CROSS JOIN LATERAL " +
                "(SELECT `lateral_orders`.`id` FROM `lateral_orders` WHERE `lateral_orders`.`user_id` = " +
                "`lateral_users`.`id`) AS `latest`",
            SqlRenderer(MySqlDialect).render(
                select(MySqlLateralUsers.id, latestId).from(MySqlLateralUsers).crossJoinLateral(latest),
            ).sql,
        )
        assertEquals(
            "SELECT `lateral_users`.`id`, `latest`.`id` FROM `lateral_users` LEFT JOIN LATERAL " +
                "(SELECT `lateral_orders`.`id` FROM `lateral_orders` WHERE `lateral_orders`.`user_id` = " +
                "`lateral_users`.`id`) AS `latest` ON TRUE",
            SqlRenderer(MySqlDialect).render(
                select(MySqlLateralUsers.id, latestId.optional).from(MySqlLateralUsers).leftJoinLateral(latest),
            ).sql,
        )
    }
}

private object MySqlLateralUsers : Table("lateral_users") {
    val id = long("id")
}

private object MySqlLateralOrders : Table("lateral_orders") {
    val id = long("id")
    val userId = long("user_id")
}
