package fixtures

import dev.quwac.kagiroi.postgres.PostgresTypes
import dev.quwac.kagiroi.postgres.asPostgresArray
import dev.quwac.kagiroi.postgres.eqAny
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.literal

object Documents : Table("documents") {
    val id = uuid("id")
}

val invalid = Documents.id.eqAny(literal("{1,2}").asPostgresArray(PostgresTypes.integer))
