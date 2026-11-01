package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.lag

private object WindowOrders : Table("window_orders") {
    val amount = int("amount")
}

val wrongWindowNavigationDefault = lag(WindowOrders.amount, default = "zero")
