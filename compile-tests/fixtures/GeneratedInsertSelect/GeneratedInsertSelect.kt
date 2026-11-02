package fixtures

import dev.quwac.kagiroi.schema.GeneratedColumnStorage
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.generated
import dev.quwac.kagiroi.schema.times
import dev.quwac.kagiroi.sql.from
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.select

private object Orders : Table("orders") {
    val price = int("price")
    val quantity = int("quantity")
    val total = int("total").generated(price * quantity, GeneratedColumnStorage.Stored)
}

val invalidGeneratedInsertSelect = insertInto(Orders, Orders.total).from(select(Orders.price).from(Orders))
