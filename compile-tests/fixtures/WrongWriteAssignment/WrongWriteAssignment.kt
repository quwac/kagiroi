package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.values

private object Accounts : Table("accounts") {
    val id = long("id")
    val score = int("score").nullable()
}

val invalidInsert = insertInto(Accounts).values {
    set(Accounts.score, "high")
}

val invalidUpdate = update(Accounts) {
    set(Accounts.id, "not-a-long")
}
