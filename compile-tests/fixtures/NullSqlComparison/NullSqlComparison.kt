package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.gt
import dev.quwac.kagiroi.sql.gte
import dev.quwac.kagiroi.sql.lt
import dev.quwac.kagiroi.sql.lte
import dev.quwac.kagiroi.sql.neq

private object Accounts : Table("accounts") {
    val age = int("age").nullable()
}

val nullEquals = Accounts.age eq null
val nullNotEquals = Accounts.age neq null
val nullLessThan = Accounts.age lt null
val nullLessThanOrEquals = Accounts.age lte null
val nullGreaterThan = Accounts.age gt null
val nullGreaterThanOrEquals = Accounts.age gte null
