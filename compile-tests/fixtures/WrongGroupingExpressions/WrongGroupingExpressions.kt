package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.sum
import dev.quwac.kagiroi.sql.groupBy
import dev.quwac.kagiroi.sql.having
import dev.quwac.kagiroi.sql.select

private object GroupingOrders : Table("grouping_orders") {
    val organizationId = int("organization_id")
    val amount = int("amount")
}

val invalidHaving = select(GroupingOrders.organizationId)
    .from(GroupingOrders)
    .having(sum(GroupingOrders.amount))

val repeatedGroupBy = select(GroupingOrders.organizationId)
    .from(GroupingOrders)
    .groupBy(GroupingOrders.organizationId)
    .groupBy(GroupingOrders.organizationId)
