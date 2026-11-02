package fixtures

import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.get
import dev.quwac.kagiroi.sql.and
import dev.quwac.kagiroi.sql.crossJoinLateral
import dev.quwac.kagiroi.sql.desc
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.innerJoin
import dev.quwac.kagiroi.sql.lateral
import dev.quwac.kagiroi.sql.leftJoinLateral
import dev.quwac.kagiroi.sql.limit
import dev.quwac.kagiroi.sql.optional
import dev.quwac.kagiroi.sql.orderBy
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.where

private object LateralUsers : Table("lateral_users") {
    val id = long("id")
    val organizationId = long("organization_id")
}

private object LateralOrganizations : Table("lateral_organizations") {
    val id = long("id")
}

private object LateralOrders : Table("lateral_orders") {
    val id = long("id")
    val userId = long("user_id")
    val organizationId = long("organization_id")
    val amount = long("amount")
    val createdAt = long("created_at")
}

fun validLateralRelations(): String {
    val user = LateralUsers.alias("user")
    val organization = LateralOrganizations.alias("organization")
    val latest = lateral("latest") {
        select(LateralOrders.id, LateralOrders.amount)
            .from(LateralOrders)
            .where(
                (LateralOrders.userId eq user[LateralUsers.id]) and
                    (LateralOrders.organizationId eq organization[LateralOrganizations.id]),
            )
            .orderBy(LateralOrders.createdAt.desc())
            .limit(1)
    }
    val latestId: SqlExpression<Long> = latest[LateralOrders.id]
    val latestAmount: SqlExpression<Long> = latest[LateralOrders.amount]
    val optionalLatestId: SqlExpression<Long?> = latest[LateralOrders.id].optional
    val cross = select(user[LateralUsers.id], latestId, latestAmount)
        .from(user)
        .innerJoin(organization, on = user[LateralUsers.organizationId] eq organization[LateralOrganizations.id])
        .crossJoinLateral(latest)
    val left = select(user[LateralUsers.id], optionalLatestId)
        .from(user)
        .leftJoinLateral(latest)
    return cross.toString() + left
}
