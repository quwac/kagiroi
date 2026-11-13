package fixtures

import dev.quwac.kagiroi.schema.ComputedColumn
import dev.quwac.kagiroi.schema.ForeignKeyAction
import dev.quwac.kagiroi.schema.ForeignKeyColumn
import dev.quwac.kagiroi.schema.GeneratedColumn
import dev.quwac.kagiroi.schema.GeneratedColumnStorage
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.WritableColumn
import dev.quwac.kagiroi.schema.computed
import dev.quwac.kagiroi.schema.generated
import dev.quwac.kagiroi.schema.order
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.schema.times

object Parent : Table("parents") {
    val id: WritableColumn<Int> = int("id").order(-100).primaryKey()
}

object Child : Table("children") {
    val parentId: ForeignKeyColumn<Int> = foreignKey("parent_id", Parent.id)
        .order(-100).onDelete(ForeignKeyAction.Cascade)
    val value = int("value")
    val label: ComputedColumn<String> = string("label").computed().order(100)
    val squared: GeneratedColumn<Int> = int("squared")
        .generated(value * value, GeneratedColumnStorage.Stored).order(200)
}
