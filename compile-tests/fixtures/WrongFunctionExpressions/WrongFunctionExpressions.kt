package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.abs
import dev.quwac.kagiroi.schema.lower
import dev.quwac.kagiroi.schema.round
import dev.quwac.kagiroi.schema.sqrt
import dev.quwac.kagiroi.schema.substring
import dev.quwac.kagiroi.schema.sum

private object FunctionUsers : Table("function_users") {
    val name = string("name")
    val amount = int("amount")
}

val invalidLower = lower(FunctionUsers.amount)
val invalidAbs = abs(FunctionUsers.name)
val invalidSum = sum(FunctionUsers.name)
val invalidSubstring = substring(FunctionUsers.amount, start = 1)
val invalidSqrt = sqrt(FunctionUsers.name)
val invalidRound = round(FunctionUsers.name)
