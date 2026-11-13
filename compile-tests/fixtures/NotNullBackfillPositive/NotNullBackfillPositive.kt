package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.WritableColumn
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.notNullBackfill
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.values

object BackfillParents : Table("backfill_parents") {
    val id = long("id").primaryKey()
}
object BackfillChildren : Table("backfill_children") {
    val parentId: WritableColumn<Long> = long("parent_id").notNullBackfill {
        execute(insertInto(BackfillParents).values { set(BackfillParents.id, 7L) })
        execute(update(column.table) { set(column, 7L) })
    }
}
