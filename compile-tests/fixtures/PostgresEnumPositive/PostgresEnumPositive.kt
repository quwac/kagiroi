package fixtures

import dev.quwac.kagiroi.postgres.PostgresTypes
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.asEnum
import dev.quwac.kagiroi.sql.SqlDatabase
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.values

enum class Kind { In, Out }
val kindType = PostgresTypes.enum("punch_kind", listOf("in", "out"))
object Punches : Table("punches") {
    val kind = column("kind", kindType).asEnum(Kind.entries) {
        Kind.In mapsTo "in"
        Kind.Out mapsTo "out"
    }
}
val typed: SqlExpression<Kind> = Punches.kind
suspend fun write(database: SqlDatabase) {
    database.execute { createPostgresEnum(kindType.definition); createTable(Punches) }
    database.insertInto(Punches).values { set(Punches.kind, Kind.In) }.execute()
}

val oracleKind = dev.quwac.kagiroi.oracle.OracleTypes.enumDomain("punch_kind", linkedMapOf("In" to "in", "Out" to "out"))
object OraclePunches : Table("punches") {
    val kind = column("kind", oracleKind).asEnum(Kind.entries) {
        Kind.In mapsTo "in"
        Kind.Out mapsTo "out"
    }
}
suspend fun writeOracle(database: SqlDatabase) {
    database.execute { createOracleEnumDomain(oracleKind.enumDomain); createTable(OraclePunches) }
    database.insertInto(OraclePunches).values { set(OraclePunches.kind, Kind.In) }.execute()
}
val mysqlKind = dev.quwac.kagiroi.mysql.MySqlTypes.enum("in", "out")
val mariaKind = dev.quwac.kagiroi.mariadb.MariaDbTypes.enum("in", "out")
