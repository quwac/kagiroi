package fixtures

import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.get
import dev.quwac.kagiroi.schema.lower
import dev.quwac.kagiroi.sql.asTable
import dev.quwac.kagiroi.sql.and
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.exists
import dev.quwac.kagiroi.sql.inSubquery
import dev.quwac.kagiroi.sql.notExists
import dev.quwac.kagiroi.sql.notInSubquery
import dev.quwac.kagiroi.sql.scalar
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.where

private object SubqueryUsers : Table("subquery_users") {
    val id = long("id")
    val name = string("name")
    val active = boolean("active")
}

private object SubqueryOrders : Table("subquery_orders") {
    val id = long("id")
    val userId = long("user_id")
    val amount = long("amount")
}

fun validSubqueries(): String {
    val latestAmount: SqlExpression<Long?> = select(SubqueryOrders.amount)
        .from(SubqueryOrders)
        .where(SubqueryOrders.userId eq SubqueryUsers.id)
        .scalar()
    val matchingOrderIds = select(SubqueryOrders.userId)
        .from(SubqueryOrders)
        .where(SubqueryOrders.amount eq 10L)
    val normalizedName = lower(SubqueryUsers.name).alias("normalized_name")
    val activeUsers = select(SubqueryUsers.id, normalizedName)
        .from(SubqueryUsers)
        .where(SubqueryUsers.active eq true)
        .asTable("active_users")
    val derivedId: SqlExpression<Long> = activeUsers[SubqueryUsers.id]
    val derivedName: SqlExpression<String> = activeUsers[normalizedName]
    val query = select(SubqueryUsers.id, latestAmount.alias("latest_amount"))
        .from(SubqueryUsers)
        .where(
            (SubqueryUsers.id.inSubquery(matchingOrderIds) and
                SubqueryUsers.id.notInSubquery(matchingOrderIds)) and
                (exists(matchingOrderIds) and notExists(matchingOrderIds)),
        )
    return query.toString() + derivedId + derivedName
}
