package fixtures

import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.lower
import dev.quwac.kagiroi.schema.sum
import dev.quwac.kagiroi.sql.asCte
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.groupBy
import dev.quwac.kagiroi.sql.gt
import dev.quwac.kagiroi.sql.having
import dev.quwac.kagiroi.sql.innerJoin
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.where
import dev.quwac.kagiroi.sql.with

private object CteUsers : Table("cte_users") {
    val id = long("id")
    val email = string("email")
    val active = boolean("active")
}

private object CteOrders : Table("cte_orders") {
    val id = long("id")
    val userId = long("user_id")
    val amount = int("amount")
}

fun validCommonTableExpressions(minimumId: Long, minimumTotal: Long): String {
    val normalizedEmail = lower(CteUsers.email).alias("normalized_email")
    val activeUsers = select(CteUsers.id, normalizedEmail)
        .from(CteUsers)
        .where(CteUsers.active eq true)
        .asCte("active_users")
    val activeUserId: SqlExpression<Long> = activeUsers[CteUsers.id]
    val activeUserEmail: SqlExpression<String> = activeUsers[normalizedEmail]
    val recentUsers = select(activeUserId, activeUserEmail)
        .from(activeUsers)
        .where(activeUserId gt minimumId)
        .asCte("recent_users")
    val recentUserId: SqlExpression<Long> = recentUsers[activeUserId]
    val total = sum(CteOrders.amount).alias("total")
    val totals = select(CteOrders.userId, total)
        .from(CteOrders)
        .groupBy(CteOrders.userId)
        .having(total gt minimumTotal)
        .asCte("totals")
    val totalUserId: SqlExpression<Long> = totals[CteOrders.userId]
    val totalAmount: SqlExpression<Long?> = totals[total]
    val query = with(activeUsers, recentUsers, totals) {
        select(recentUserId, totalAmount)
            .from(recentUsers)
            .innerJoin(totals, on = recentUserId eq totalUserId)
            .where(totalAmount gt minimumTotal)
    }
    return query.toString() + recentUserId + totalAmount
}
