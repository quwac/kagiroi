package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.optional
import dev.quwac.kagiroi.sql.SqlRow
import dev.quwac.kagiroi.sql.cube
import dev.quwac.kagiroi.sql.grandTotal
import dev.quwac.kagiroi.sql.groupBy
import dev.quwac.kagiroi.sql.groupingSets
import dev.quwac.kagiroi.sql.rollup
import dev.quwac.kagiroi.sql.select

private object Sales : Table("sales") {
    val country = string("country")
}

fun invalidSyntheticNullRead(row: SqlRow) {
    val country: String = row[Sales.country.optional]
}

val invalidEmptyRollup = rollup()

val invalidEmptyCube = cube()

val invalidEmptyGroupingSets = groupingSets()

val validGrandTotalOnly = select(Sales.country.optional).from(Sales).groupBy(groupingSets(grandTotal()))
