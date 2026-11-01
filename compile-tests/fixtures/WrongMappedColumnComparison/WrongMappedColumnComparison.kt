package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.transform
import dev.quwac.kagiroi.sql.eq

private data class AccountId(val value: String)

private object Accounts : Table("accounts") {
    val id = string("id").transform(
        fromStorage = ::AccountId,
        toStorage = { it.value },
    )
}

val invalidMappedComparison = Accounts.id eq "raw-account-id"
