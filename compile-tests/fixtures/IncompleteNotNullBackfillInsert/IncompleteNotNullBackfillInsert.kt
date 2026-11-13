package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.notNullBackfill

object IncompleteNotNullBackfillInsert : Table("incomplete_backfill") {
    val value = long("value").notNullBackfill {
        execute(insertInto(column.table))
    }
}
