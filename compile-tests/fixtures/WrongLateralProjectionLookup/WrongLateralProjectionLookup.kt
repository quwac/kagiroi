package fixtures

import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.lateral
import dev.quwac.kagiroi.sql.select

private object LateralLookupUsers : Table("lateral_lookup_users") {
    val id = long("id")
}

private object LateralLookupOrders : Table("lateral_lookup_orders") {
    val id = long("id")
}

val invalidStringLateralProjectionLookup: SqlExpression<Long> = lateral("latest") {
    select(LateralLookupOrders.id).from(LateralLookupOrders)
}["id"]
