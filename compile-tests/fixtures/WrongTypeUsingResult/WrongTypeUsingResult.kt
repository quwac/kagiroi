package fixtures

import dev.quwac.kagiroi.kotlinx.datetime.localDateTime
import dev.quwac.kagiroi.schema.InstantColumnType
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.typeUsing

object WrongConverted : Table("wrong_converted") {
    val createdAt = localDateTime("created_at").typeUsing(InstantColumnType) { previous -> previous }
}
