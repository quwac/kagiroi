package fixtures

import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.avg
import dev.quwac.kagiroi.schema.count
import dev.quwac.kagiroi.schema.lower
import dev.quwac.kagiroi.schema.sum
import dev.quwac.kagiroi.sql.SqlRow
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.asTable
import dev.quwac.kagiroi.sql.desc
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.gt
import dev.quwac.kagiroi.sql.groupBy
import dev.quwac.kagiroi.sql.having
import dev.quwac.kagiroi.sql.orderBy
import dev.quwac.kagiroi.sql.scalar
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.where

private object GroupingOrders : Table("grouping_orders") {
    val organizationId = int("organization_id")
    val amount = int("amount")
    val active = boolean("active")
    val email = string("email")
}

private object GroupingLimits : Table("grouping_limits") {
    val amount = int("amount")
}

fun validGroupingExpressions(minimumAmount: Long): String {
    val total = sum(GroupingOrders.amount).alias("total")
    val orderCount = count().alias("order_count")
    val normalized: SqlExpression<String> = lower(GroupingOrders.email)
    val grouped = select(GroupingOrders.organizationId, orderCount, total)
        .from(GroupingOrders)
        .where(GroupingOrders.active eq true)
        .groupBy(GroupingOrders.organizationId)
        .having(total gt minimumAmount)
        .orderBy(total.desc())
    val normalizedGroups = select(normalized, count())
        .from(GroupingOrders)
        .groupBy(normalized)
        .orderBy(normalized.asc())
    val lowerBound = select(avg(GroupingLimits.amount)).from(GroupingLimits).scalar()
    val average = avg(GroupingOrders.amount).alias("average")
    val scalarHaving = select(GroupingOrders.organizationId, average)
        .from(GroupingOrders)
        .groupBy(GroupingOrders.organizationId)
        .having(average gt lowerBound)
    val source = select(GroupingOrders.organizationId, GroupingOrders.amount)
        .from(GroupingOrders)
        .asTable("source")
    val sourceOrganizationId = source[GroupingOrders.organizationId]
    val sourceTotal = sum(source[GroupingOrders.amount]).alias("source_total")
    val derived = select(sourceOrganizationId, sourceTotal)
        .from(source)
        .groupBy(sourceOrganizationId)
    val groupedRow: (SqlRow) -> Long? = { row -> row[total] }
    val derivedRow: (SqlRow) -> Long? = { row -> row[sourceTotal] }
    return grouped.toString() + normalizedGroups + scalarHaving + derived + groupedRow + derivedRow
}
