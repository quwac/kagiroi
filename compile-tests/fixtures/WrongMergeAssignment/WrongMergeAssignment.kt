package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.get
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sqlserver.mergeInto

private object Rows : Table("rows") {
    val id = long("id")
    val name = string("name")
}
private object Incoming : Table("incoming") {
    val id = long("id")
}

val source = Incoming.alias("s")
val invalid = mergeInto(Rows, source, Rows.id eq source[Incoming.id]) {
    whenMatchedUpdate { set(Rows.name, source[Incoming.id]) }
}
