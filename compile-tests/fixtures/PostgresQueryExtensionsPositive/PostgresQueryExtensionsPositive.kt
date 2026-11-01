package fixtures

import dev.quwac.kagiroi.postgres.distinctOn
import dev.quwac.kagiroi.postgres.ilike
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.sql.and
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.from
import dev.quwac.kagiroi.sql.isDistinctFrom
import dev.quwac.kagiroi.sql.isNotDistinctFrom
import dev.quwac.kagiroi.sql.nullsLast
import dev.quwac.kagiroi.sql.orderBy
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.where

private object QueryUsers : Table("query_users") {
    val id = long("id")
    val name = string("name")
    val nickname = string("nickname").nullable()
}

private object QueryNames : Table("query_names") {
    val userId = long("user_id")
    val name = string("name")
}

val postgresDistinctOn = select(QueryUsers.id, QueryUsers.name)
    .from(QueryUsers)
    .distinctOn(QueryUsers.name)
    .orderBy(QueryUsers.name.asc().nullsLast(), QueryUsers.id.asc())

val postgresIlikeAndNullSafe = select(QueryUsers.id)
    .from(QueryUsers)
    .where(
        (QueryUsers.name ilike "ada%") and
            (QueryUsers.nickname isNotDistinctFrom "Ada") and
            (QueryUsers.name isDistinctFrom QueryUsers.nickname),
    )

val updateFrom = update(QueryUsers) {
    set(QueryUsers.name, QueryNames.name)
}
    .from(QueryNames)
    .where(QueryUsers.id eq QueryNames.userId)
