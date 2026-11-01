package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.asEnum
import dev.quwac.kagiroi.sql.eq

private enum class AccountStatus {
    ACTIVE,
    SUSPENDED,
}

private object Accounts : Table("accounts") {
    val status = int("status").asEnum(AccountStatus.entries) {
        AccountStatus.ACTIVE mapsTo 100
        AccountStatus.SUSPENDED mapsTo 200
    }
}

val invalidEnumComparison = Accounts.status eq 100
