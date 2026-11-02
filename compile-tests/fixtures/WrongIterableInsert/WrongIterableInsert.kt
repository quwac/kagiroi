package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.values

private object Accounts : Table("accounts") {
    val id = long("id")
}

private val users: List<User> = listOf(User(1L))

val invalidInsert = insertInto(Accounts).values(users) { user ->
    set(Accounts.id, user.id)
}
