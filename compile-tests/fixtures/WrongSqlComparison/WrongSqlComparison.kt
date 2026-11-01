package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.sql.eq

private object Accounts : Table("accounts") {
    val id = long("id").primaryKey()
}

val invalidComparison = Accounts.id eq "not-a-long"
