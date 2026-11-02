package fixtures

import dev.quwac.kagiroi.schema.Table

private object Orders : Table("orders") {
    val quantity = int("quantity")

    init {
        checkConstraint { quantity }
    }
}
