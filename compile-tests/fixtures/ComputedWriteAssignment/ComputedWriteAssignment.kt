package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.computed
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.values

private object Accounts : Table("accounts") {
    val calculatedScore = int("calculated_score").computed()
}

val invalidComputedInsert = insertInto(Accounts).values {
    set(Accounts.calculatedScore, 10)
}

val invalidComputedUpdate = update(Accounts) {
    set(Accounts.calculatedScore, 20)
}
