package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.from
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.where

private object UpdateUsers : Table("update_users") {
    val id = long("id")
    val name = string("name")
}

private object UpdateNames : Table("update_names") {
    val userId = long("user_id")
    val name = string("name")
}

val fromAfterWhere = update(UpdateUsers) {
    set(UpdateUsers.name, UpdateNames.name)
}
    .where(UpdateUsers.id eq UpdateNames.userId)
    .from(UpdateNames)
