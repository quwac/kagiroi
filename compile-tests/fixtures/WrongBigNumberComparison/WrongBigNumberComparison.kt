package fixtures

import dev.quwac.bignumber.BigInteger
import dev.quwac.kagiroi.quwac.bignumber.bigDecimal
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.eq

private object Accounts : Table("accounts") {
    val balance = bigDecimal("balance")
}

val invalidBigNumberComparison = Accounts.balance eq BigInteger.ONE
