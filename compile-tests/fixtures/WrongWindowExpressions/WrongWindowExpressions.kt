package fixtures

import dev.quwac.kagiroi.schema.GeneratedColumnStorage
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.generated
import dev.quwac.kagiroi.schema.lower
import dev.quwac.kagiroi.schema.rowNumber
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.between
import dev.quwac.kagiroi.sql.currentRow
import dev.quwac.kagiroi.sql.orderBy
import dev.quwac.kagiroi.sql.over
import dev.quwac.kagiroi.sql.unboundedPreceding

private object WindowOrders : Table("window_orders") {
    val id = long("id")
    val name = string("name")
    val generatedRank = long("generated_rank").generated(
        rowNumber().over { orderBy(id.asc()) },
        GeneratedColumnStorage.Stored,
    )
}

val invalidScalarWindow = lower(WindowOrders.name).over { }

val rangeOutsideWindowBuilder = range(between(unboundedPreceding, currentRow))
