package dev.quwac.kagiroi.sqlserver

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
    fun mapsCorrelatedRelationSemanticsToCrossAndOuterApply() {
        val latest = lateral("latest") {
            select(SqlServerLateralOrders.id)
                .from(SqlServerLateralOrders)
                .where(SqlServerLateralOrders.userId eq SqlServerLateralUsers.id)
        }
        val latestId = latest[SqlServerLateralOrders.id]

        assertEquals(
            "SELECT CONVERT(NVARCHAR(4000), [lateral_users].[id]), [latest].[id] FROM [lateral_users] " +
                "CROSS APPLY (SELECT CONVERT(NVARCHAR(4000), [lateral_orders].[id]) FROM [lateral_orders] " +
                "WHERE [lateral_orders].[user_id] = [lateral_users].[id]) AS [latest]",
            SqlRenderer(SqlServerDialect).render(
                select(SqlServerLateralUsers.id, latestId).from(SqlServerLateralUsers).crossJoinLateral(latest),
            ).sql,
        )
        assertEquals(
            "SELECT CONVERT(NVARCHAR(4000), [lateral_users].[id]), [latest].[id] FROM [lateral_users] " +
                "OUTER APPLY (SELECT CONVERT(NVARCHAR(4000), [lateral_orders].[id]) FROM [lateral_orders] " +
                "WHERE [lateral_orders].[user_id] = [lateral_users].[id]) AS [latest]",
            SqlRenderer(SqlServerDialect).render(
                select(SqlServerLateralUsers.id, latestId.optional)
                    .from(SqlServerLateralUsers)
                    .leftJoinLateral(latest),
            ).sql,
        )
    }
}

private object SqlServerLateralUsers : Table("lateral_users") {
    val id = long("id")
}

private object SqlServerLateralOrders : Table("lateral_orders") {
    val id = long("id")
    val userId = long("user_id")
}
