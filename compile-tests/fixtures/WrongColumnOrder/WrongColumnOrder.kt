package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.order

object Records : Table("records") {
    val id = int("id").order("first")
}
