package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.sql.notNullBackfill
import dev.quwac.kagiroi.sql.update

object NullableNotNullBackfill : Table("nullable_backfill") {
    val value = long("value").nullable().notNullBackfill {
        execute(update(column.table) { set(column, 7L) })
    }
}
