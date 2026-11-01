package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.default
import dev.quwac.kagiroi.schema.literal
import dev.quwac.kagiroi.schema.nextValue
import dev.quwac.kagiroi.schema.sequence

private object Rows : Table("rows") {
    private val numbers = sequence("row_numbers")
    val value = int("value").default(numbers.nextValue())
    val wrongLiteral = int("wrong_literal").default(literal("zero"))
}
