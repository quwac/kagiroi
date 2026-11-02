package fixtures

import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.case
import dev.quwac.kagiroi.schema.cast
import dev.quwac.kagiroi.schema.countDistinct
import dev.quwac.kagiroi.schema.elseValue
import dev.quwac.kagiroi.schema.isNotNull
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.stringType
import dev.quwac.kagiroi.schema.sum
import dev.quwac.kagiroi.schema.whenThen
import dev.quwac.kagiroi.sql.SqlRow
import dev.quwac.kagiroi.sql.distinct
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.gte
import dev.quwac.kagiroi.sql.over
import dev.quwac.kagiroi.sql.select

private object ExpressionOrders : Table("expression_orders") {
    val id = long("id")
    val organizationId = long("organization_id")
    val nickname = string("nickname").nullable()
    val name = string("name")
    val amount = long("amount")
    val active = boolean("active")
}

fun validCastDistinctCase(highThreshold: Long): String {
    val idText: SqlExpression<String> = cast(ExpressionOrders.id, stringType())
    val nullableName: SqlExpression<String?> = cast(ExpressionOrders.nickname, stringType())
    val category = case<String?> {
        whenThen(ExpressionOrders.amount gte highThreshold, "high")
        whenThen(ExpressionOrders.nickname.isNotNull(), ExpressionOrders.nickname)
        elseValue(ExpressionOrders.name)
    }.alias("category")
    val completedCount = sum(
        case<Long> {
            whenThen(ExpressionOrders.active eq true, 1L)
            elseValue(0L)
        },
    )
    val windowCount = sum(
        case<Long> {
            whenThen(ExpressionOrders.active eq true, 1L)
            elseValue(0L)
        },
    ).over {
        partitionBy(ExpressionOrders.organizationId)
    }
    val distinctOrganizations = select(ExpressionOrders.organizationId).distinct().from(ExpressionOrders)
    val aggregate = select(countDistinct(ExpressionOrders.organizationId)).from(ExpressionOrders)
    val typedRow: (SqlRow) -> String = { row -> row[idText.alias("id_text")] }
    return "$nullableName$category$completedCount$windowCount$distinctOrganizations$aggregate$typedRow"
}
