package fixtures
import dev.quwac.kagiroi.schema.*
object WrongNullable : Table("wrong") {
    val value = long("value").nullable().typeUsing(IntColumnType) { previous ->
        val unsafe: SqlExpression<Int> = previous
        cast(unsafe, longType())
    }
}
