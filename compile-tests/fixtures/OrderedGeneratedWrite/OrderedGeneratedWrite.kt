package fixtures

import dev.quwac.kagiroi.schema.GeneratedColumnStorage
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.generated
import dev.quwac.kagiroi.schema.order
import dev.quwac.kagiroi.schema.times
import dev.quwac.kagiroi.sql.update

object Records : Table("records") {
    val value = int("value")
    val squared = int("squared").generated(value * value, GeneratedColumnStorage.Stored).order(100)
}

val invalid = update(Records) { set(Records.squared, 1) }
