package fixtures

import dev.quwac.kagiroi.postgres.kotlinx.datetime.atTimeZone
import dev.quwac.kagiroi.schema.Table

object WrongZone : Table("wrong_zone") {
    val value = int("value")
}

val invalid = WrongZone.value.atTimeZone("UTC")
