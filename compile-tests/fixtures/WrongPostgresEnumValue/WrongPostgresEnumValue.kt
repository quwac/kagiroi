package fixtures

import dev.quwac.kagiroi.postgres.PostgresTypes
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.asEnum
import dev.quwac.kagiroi.sql.SqlDatabase
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.values

enum class Kind { In }
object Punches : Table("punches") {
    val kind = column("kind", PostgresTypes.enum("punch_kind", listOf("in"))).asEnum(Kind.entries) {
        Kind.In mapsTo "in"
    }
}
fun wrong(database: SqlDatabase) = database.insertInto(Punches).values { set(Punches.kind, "in") }

val invalidOracleDomain = dev.quwac.kagiroi.oracle.OracleTypes.enumDomain("state", mapOf("In" to 1))
