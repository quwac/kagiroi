package dev.quwac.kagiroi.oracle

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
    fun mapsCorrelatedRelationSemanticsToOracleApply() {
        val latest = lateral("latest") {
            select(OracleLateralOrders.id)
                .from(OracleLateralOrders)
                .where(OracleLateralOrders.userId eq OracleLateralUsers.id)
        }
        val latestId = latest[OracleLateralOrders.id]

        assertEquals(
            "SELECT \"lateral_users\".\"id\", \"latest\".\"id\" FROM \"lateral_users\" CROSS APPLY " +
                "(SELECT \"lateral_orders\".\"id\" FROM \"lateral_orders\" WHERE \"lateral_orders\".\"user_id\" = " +
                "\"lateral_users\".\"id\") \"latest\"",
            SqlRenderer(OracleDialect).render(
                select(OracleLateralUsers.id, latestId).from(OracleLateralUsers).crossJoinLateral(latest),
            ).sql,
        )
        assertEquals(
            "SELECT \"lateral_users\".\"id\", \"latest\".\"id\" FROM \"lateral_users\" OUTER APPLY " +
                "(SELECT \"lateral_orders\".\"id\" FROM \"lateral_orders\" WHERE \"lateral_orders\".\"user_id\" = " +
                "\"lateral_users\".\"id\") \"latest\"",
            SqlRenderer(OracleDialect).render(
                select(OracleLateralUsers.id, latestId.optional)
                    .from(OracleLateralUsers)
                    .leftJoinLateral(latest),
            ).sql,
        )
    }
}

private object OracleLateralUsers : Table("lateral_users") {
    val id = long("id")
}

private object OracleLateralOrders : Table("lateral_orders") {
    val id = long("id")
    val userId = long("user_id")
}
