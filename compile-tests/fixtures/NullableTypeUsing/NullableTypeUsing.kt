package fixtures

import dev.quwac.kagiroi.schema.IntColumnType
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.cast
import dev.quwac.kagiroi.schema.longType
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.typeUsing

object NullableConverted : Table("nullable_converted") {
    val value = long("value").nullable().typeUsing(IntColumnType) { cast(it, longType()) }
}
