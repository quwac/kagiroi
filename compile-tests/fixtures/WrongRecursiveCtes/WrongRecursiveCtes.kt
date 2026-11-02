package fixtures

import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.literal
import dev.quwac.kagiroi.schema.nullIf
import dev.quwac.kagiroi.sql.recursiveCte
import dev.quwac.kagiroi.sql.select

val number = literal(1).alias("n")

val wrongRecursiveKotlinType = recursiveCte(name = "numbers", anchor = select(number)) { self ->
    select(literal(1L).alias("n")).from(self)
}

val wrongRecursiveNullability = recursiveCte(name = "nullable_numbers", anchor = select(number)) { self ->
    select(nullIf(self[number], 1).alias("n")).from(self)
}
