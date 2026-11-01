package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.orderBy
import dev.quwac.kagiroi.sql.select

private object Accounts : Table("accounts") {
    val id = long("id")
}

val emptyOrderBy = select(Accounts.id)
    .from(Accounts)
    .orderBy()
