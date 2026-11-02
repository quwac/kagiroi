package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.case
import dev.quwac.kagiroi.schema.elseValue
import dev.quwac.kagiroi.schema.whenThen
import dev.quwac.kagiroi.sql.eq

private object ExpressionOrders : Table("expression_orders") {
    val id = long("id")
    val active = boolean("active")
}

val invalidCaseCondition = case<String> {
    whenThen(ExpressionOrders.id, "not_boolean")
    elseValue("other")
}

val invalidCaseResult = case<String> {
    whenThen(ExpressionOrders.active eq true, "yes")
    elseValue(123)
}
