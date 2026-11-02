package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.SqlRow

private object Accounts : Table("accounts") {
    val id = long("id")
}

fun invalidRowMapping(row: SqlRow) {
    val id: String = row[Accounts.id]
}
