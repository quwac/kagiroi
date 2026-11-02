package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.limit
import dev.quwac.kagiroi.sql.offset
import dev.quwac.kagiroi.sql.orderBy
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.where

private object Accounts : Table("accounts") {
    val id = long("id")
}

val whereAfterOrderBy = select(Accounts.id)
    .from(Accounts)
    .orderBy(Accounts.id.asc())
    .where(Accounts.id eq 1L)

val orderByAfterLimit = select(Accounts.id)
    .from(Accounts)
    .limit(10)
    .orderBy(Accounts.id.asc())

val orderByAfterOffset = select(Accounts.id)
    .from(Accounts)
    .offset(10)
    .orderBy(Accounts.id.asc())

val limitAfterOffset = select(Accounts.id)
    .from(Accounts)
    .offset(10)
    .limit(20)
