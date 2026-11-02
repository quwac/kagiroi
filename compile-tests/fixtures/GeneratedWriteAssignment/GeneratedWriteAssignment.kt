package fixtures

import dev.quwac.kagiroi.schema.GeneratedColumnStorage
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.generated
import dev.quwac.kagiroi.schema.times
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.values

private object Orders : Table("orders") {
    val price = int("price")
    val quantity = int("quantity")
    val total = int("total").generated(price * quantity, GeneratedColumnStorage.Stored)
}

val invalidGeneratedInsert = insertInto(Orders).values {
    set(Orders.total, 10)
}

val invalidGeneratedUpdate = update(Orders) {
    set(Orders.total, 20)
}
