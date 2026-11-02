package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.literal
import dev.quwac.kagiroi.schema.plus
import dev.quwac.kagiroi.sql.asCte
import dev.quwac.kagiroi.sql.deleteFrom
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.from
import dev.quwac.kagiroi.sql.inSubquery
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.lt
import dev.quwac.kagiroi.sql.recursiveCte
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.where
import dev.quwac.kagiroi.sql.with

private object Users : Table("users") {
    val id = long("id")
    val email = string("email")
    val active = boolean("active")
}

private object UserArchive : Table("user_archive") {
    val id = long("id")
    val email = string("email")
}

fun typedWithDml(status: String): String {
    val targets = select(Users.id, Users.email)
        .from(Users)
        .where(Users.active eq true)
        .asCte("targets")
    val targetId = targets[Users.id]
    val targetEmail = targets[Users.email]
    val update = with(targets) {
        update(Users) { set(Users.email, status) }
            .where(Users.id.inSubquery(select(targetId).from(targets)))
    }
    val delete = with(targets) {
        deleteFrom(Users).where(Users.id.inSubquery(select(targetId).from(targets)))
    }
    val insert = with(targets) {
        insertInto(UserArchive, UserArchive.id, UserArchive.email)
            .from(select(targetId, targetEmail).from(targets))
    }

    val n = literal(1L).alias("n")
    val numbers = recursiveCte("numbers", select(n)) { self ->
        select(self[n] + 1L).from(self).where(self[n] lt 3L)
    }
    val recursiveDelete = with(numbers) {
        deleteFrom(Users).where(Users.id.inSubquery(select(numbers[n]).from(numbers)))
    }

    return "$update$delete$insert$recursiveDelete"
}
