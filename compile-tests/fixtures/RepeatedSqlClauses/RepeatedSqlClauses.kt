package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.limit
import dev.quwac.kagiroi.sql.offset
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.where

private object Accounts : Table("accounts") {
    val id = long("id")
}

val repeatedWhere = select(Accounts.id)
    .from(Accounts)
    .where(Accounts.id eq 1L)
    .where(Accounts.id eq 2L)

val repeatedLimit = select(Accounts.id)
    .from(Accounts)
    .limit(10)
    .limit(20)

val repeatedOffset = select(Accounts.id)
    .from(Accounts)
    .offset(10)
    .offset(20)
